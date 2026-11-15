package com.tvku.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.data.catalog.CatalogState
import com.tvku.core.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class CountrySection(val countryCode: String, val channels: List<Channel>)

data class HomeUiState(
    /** Channels TVKu plays itself, grouped by country (user's country and Indonesia first). */
    val countries: List<CountrySection> = emptyList(),
    /** Channels only available inside the broadcaster's own platform. */
    val officialPlatforms: List<Channel> = emptyList(),
    val isRefreshing: Boolean = true,
    val problem: CatalogState.Problem? = null,
    val problemDetail: String? = null,
    val showingCachedCopy: Boolean = false,
) {
    val isEmpty: Boolean get() = countries.isEmpty() && officialPlatforms.isEmpty()
    val playableCount: Int get() = countries.sumOf { it.channels.size }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val userCountry: String = Locale.getDefault().country.orEmpty()

    val uiState: StateFlow<HomeUiState> = catalog.state
        .map { s ->
            HomeUiState(
                countries = groupByCountry(s.channels.filter { it.playable != null }, userCountry),
                officialPlatforms = s.channels.filter { it.playable == null && it.officialLink != null },
                isRefreshing = s.isRefreshing,
                problem = s.lastError,
                problemDetail = s.lastErrorDetail,
                showingCachedCopy = s.origin == CatalogState.Origin.CACHE,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { catalog.refresh() }
    }

    companion object {
        /** Pinned countries first, then by number of channels, then by code. Channels keep catalog order. */
        fun groupByCountry(channels: List<Channel>, userCountry: String): List<CountrySection> {
            val pinned = listOf(userCountry, "ID").filter { it.isNotBlank() }.distinct()
            return channels.groupBy { it.countryCode }
                .map { (code, list) -> CountrySection(code, list) }
                .sortedWith(
                    compareBy<CountrySection> { pinned.indexOf(it.countryCode).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .thenByDescending { it.channels.size }
                        .thenBy { it.countryCode },
                )
        }
    }
}
