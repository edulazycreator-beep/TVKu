package com.tvku.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.tvku.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts loading then reflects stored onboarding state`() = runTest(dispatcher) {
        val repo = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("m.preferences_pb") },
        )
        val vm = MainViewModel(repo)
        vm.uiState.test {
            var item = awaitItem()
            if (item == MainUiState.Loading) item = awaitItem()
            assertEquals(MainUiState.Ready(onboardingCompleted = false), item)
            repo.setOnboardingCompleted(true)
            assertEquals(MainUiState.Ready(onboardingCompleted = true), awaitItem())
        }
    }
}
