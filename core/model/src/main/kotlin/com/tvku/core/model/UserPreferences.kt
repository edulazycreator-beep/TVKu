package com.tvku.core.model

enum class ThemePreference { SYSTEM, DARK, LIGHT }

enum class VideoQualityPreference { AUTO, DATA_SAVER, HIGHEST_AVAILABLE }

data class UserPreferences(
    val onboardingCompleted: Boolean = false,
    val theme: ThemePreference = ThemePreference.DARK,
    val videoQuality: VideoQualityPreference = VideoQualityPreference.AUTO,
)
