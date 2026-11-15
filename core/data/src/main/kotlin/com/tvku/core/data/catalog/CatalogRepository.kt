package com.tvku.core.data.catalog

import android.util.Log
import com.tvku.core.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogState(
    val channels: List<Channel> = emptyList(),
    val origin: Origin = Origin.NONE,
    val isRefreshing: Boolean = false,
    val lastError: Problem? = null,
    /** Short technical detail of the last failure, shown small in the UI to help support. */
    val lastErrorDetail: String? = null,
) {
    enum class Origin { NONE, CACHE, NETWORK }
    enum class Problem { OFFLINE_OR_SERVER, INVALID_CATALOG }
}

/**
 * Channel catalog: network first, last good copy as offline fallback.
 * A catalog that fails validation is never cached and never replaces a good one.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val fetcher: CatalogFetcher,
    private val cache: CatalogCache,
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var cacheLoaded = false

    fun channel(id: String): Channel? = _state.value.channels.firstOrNull { it.id == id }

    private fun describe(e: Throwable?): String {
        val parts = generateSequence(e) { it.cause }.take(3)
            .map { "${it.javaClass.simpleName}${it.message?.let { m -> ": " + m.take(120) } ?: ""}" }
        return parts.joinToString(" <- ").take(300)
    }

    private companion object {
        const val TAG = "TVKuCatalog"
    }

    suspend fun refresh() = mutex.withLock {
        if (!cacheLoaded) {
            cacheLoaded = true
            cache.read()?.let { text ->
                runCatching { CatalogParser.parse(text) }.getOrNull()?.let { channels ->
                    _state.update { it.copy(channels = channels, origin = CatalogState.Origin.CACHE) }
                }
            }
        }
        _state.update { it.copy(isRefreshing = true) }
        var failure: Exception? = null
        val problem = try {
            val text = fetcher.fetch()
            val channels = CatalogParser.parse(text)
            cache.write(text)
            _state.update { CatalogState(channels = channels, origin = CatalogState.Origin.NETWORK) }
            Log.i(TAG, "catalog loaded from network: ${channels.size} channels")
            null
        } catch (e: CatalogFormatException) {
            failure = e
            CatalogState.Problem.INVALID_CATALOG
        } catch (e: IOException) {
            failure = e
            CatalogState.Problem.OFFLINE_OR_SERVER
        } catch (e: SecurityException) {
            failure = e
            CatalogState.Problem.OFFLINE_OR_SERVER
        }
        if (problem != null) {
            val detail = describe(failure)
            Log.w(TAG, "catalog refresh failed: $detail", failure)
            _state.update { it.copy(isRefreshing = false, lastError = problem, lastErrorDetail = detail) }
        }
    }
}
