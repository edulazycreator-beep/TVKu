package com.tvku.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tvku.core.model.ThemePreference
import com.tvku.core.model.UserPreferences
import com.tvku.core.model.VideoQualityPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Local, non-authoritative application preferences. Entitlements are never stored here. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:UserPreferencesStore private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            UserPreferences(
                onboardingCompleted = p[Keys.OnboardingCompleted] ?: false,
                theme = p[Keys.Theme].toEnum(ThemePreference.DARK),
                videoQuality = p[Keys.VideoQuality].toEnum(VideoQualityPreference.AUTO),
            )
        }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.OnboardingCompleted] = completed }
    }

    suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[Keys.Theme] = theme.name }
    }

    suspend fun setVideoQuality(quality: VideoQualityPreference) {
        dataStore.edit { it[Keys.VideoQuality] = quality.name }
    }

    private object Keys {
        val OnboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val Theme = stringPreferencesKey("theme")
        val VideoQuality = stringPreferencesKey("video_quality")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
