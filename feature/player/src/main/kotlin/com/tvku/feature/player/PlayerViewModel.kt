package com.tvku.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.model.Channel
import com.tvku.core.player.embed.YouTubePlayerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val channel: Channel? = null,
    val status: YouTubePlayerStatus = YouTubePlayerStatus.CONNECTING,
    /** True when playback never started within [PlayerViewModel.START_TIMEOUT_MS]. */
    val timedOut: Boolean = false,
    /** Incremented to force a fresh player instance on retry. */
    val attempt: Int = 0,
    val notFound: Boolean = false,
) {
    /** LIVE is shown only while YouTube reports that frames are playing. */
    val showLiveBadge: Boolean get() = status == YouTubePlayerStatus.PLAYING
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var timeout: Job? = null

    fun load(channelId: String) {
        if (_state.value.channel?.id == channelId) return
        val channel = catalog.channel(channelId)
        _state.value = PlayerUiState(channel = channel, notFound = channel?.playable == null)
        if (channel?.playable != null) armTimeout()
    }

    fun onStatus(status: YouTubePlayerStatus) {
        _state.update { it.copy(status = status, timedOut = it.timedOut && status != YouTubePlayerStatus.PLAYING) }
        if (status == YouTubePlayerStatus.PLAYING || status.isFinal) timeout?.cancel()
    }

    fun retry() {
        _state.update { it.copy(status = YouTubePlayerStatus.CONNECTING, timedOut = false, attempt = it.attempt + 1) }
        armTimeout()
    }

    private fun armTimeout() {
        timeout?.cancel()
        timeout = viewModelScope.launch {
            delay(START_TIMEOUT_MS)
            _state.update { if (it.status != YouTubePlayerStatus.PLAYING && !it.status.isFinal) it.copy(timedOut = true) else it }
        }
    }

    companion object {
        const val START_TIMEOUT_MS = 30_000L
    }
}
