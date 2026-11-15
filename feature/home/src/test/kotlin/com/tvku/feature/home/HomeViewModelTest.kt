package com.tvku.feature.home

import com.tvku.core.data.catalog.CatalogCache
import com.tvku.core.data.catalog.CatalogFetcher
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.data.catalog.CatalogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val catalog = """
        {"schema":1,"channels":[
          {"id":"kompastv-id","name":"KompasTV","country":"ID","category":"news","sources":[{"type":"youtube_live","youtube_channel_id":"UC5BMIWZe9isJXLZZWPWvBlg"}]},
          {"id":"indosiar-id","name":"Indosiar","country":"ID","category":"entertainment","sources":[{"type":"official_link","url":"https://www.vidio.com/live/indosiar","platform":"Vidio"}]}
        ]}
    """.trimIndent()

    private fun repo(fetch: () -> String) = CatalogRepository(
        object : CatalogFetcher { override suspend fun fetch() = fetch() },
        object : CatalogCache { override suspend fun read(): String? = null; override suspend fun write(text: String) {} },
    )

    @Test
    fun `splits in-app channels from official platform links`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo { catalog })
        backgroundScope.launch { vm.uiState.collect {} }
        val state = vm.uiState.first { !it.isRefreshing && !it.isEmpty }
        assertEquals(listOf("kompastv-id"), state.watchInApp.map { it.id })
        assertEquals(listOf("indosiar-id"), state.officialPlatforms.map { it.id })
    }

    @Test
    fun `offline with no saved list reports the problem`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo { throw IOException("offline") })
        backgroundScope.launch { vm.uiState.collect {} }
        val state = vm.uiState.first { it.problem != null }
        assertTrue(state.isEmpty)
        assertEquals(CatalogState.Problem.OFFLINE_OR_SERVER, state.problem)
    }
}
