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
import javax.inject.Inject

data class HomeUiState(
    val watchInApp: List<Channel> = emptyList(),
    val officialPlatforms: List<Channel> = emptyList(),
    val isRefreshing: Boolean = true,
    val problem: CatalogState.Problem? = null,
    val problemDetail: String? = null,
    val showingCachedCopy: Boolean = false,
) {
    val isEmpty: Boolean get() = watchInApp.isEmpty() && officialPlatforms.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = catalog.state
        .map { s ->
            HomeUiState(
                watchInApp = s.channels.filter { it.playable != null },
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
}
