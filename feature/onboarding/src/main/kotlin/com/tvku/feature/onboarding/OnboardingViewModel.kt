package com.tvku.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvku.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    private val _finished = MutableStateFlow(false)
    /** Becomes true once completion is persisted; navigation waits for it. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    fun complete() {
        if (_finished.value) return
        viewModelScope.launch {
            preferences.setOnboardingCompleted(true)
            _finished.value = true
        }
    }
}
