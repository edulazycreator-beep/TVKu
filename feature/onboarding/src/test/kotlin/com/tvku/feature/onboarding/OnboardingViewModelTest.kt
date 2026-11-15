package com.tvku.feature.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tvku.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `complete persists onboarding and signals finish`() = runTest(dispatcher) {
        val repo = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("p.preferences_pb") },
        )
        val vm = OnboardingViewModel(repo)
        assertFalse(vm.finished.value)

        vm.complete()

        assertTrue(repo.preferences.first().onboardingCompleted)
        assertTrue(vm.finished.value)
    }
}
