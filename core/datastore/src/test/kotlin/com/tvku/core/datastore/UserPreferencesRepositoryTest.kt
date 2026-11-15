package com.tvku.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tvku.core.model.ThemePreference
import com.tvku.core.model.UserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repository() = UserPreferencesRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            tmp.newFile("prefs.preferences_pb").also { it.delete() }
        },
    )

    @Test
    fun `defaults before anything is stored`() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(UserPreferences(), repository().preferences.first())
    }

    @Test
    fun `onboarding completion and theme are persisted`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        repo.setOnboardingCompleted(true)
        repo.setTheme(ThemePreference.SYSTEM)
        val prefs = repo.preferences.first()
        assertTrue(prefs.onboardingCompleted)
        assertEquals(ThemePreference.SYSTEM, prefs.theme)
    }
}
