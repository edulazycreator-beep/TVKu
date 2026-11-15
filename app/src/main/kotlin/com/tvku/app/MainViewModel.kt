package com.tvku.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvku.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val onboardingCompleted: Boolean) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = preferences.preferences
        .map { MainUiState.Ready(onboardingCompleted = it.onboardingCompleted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)
}
