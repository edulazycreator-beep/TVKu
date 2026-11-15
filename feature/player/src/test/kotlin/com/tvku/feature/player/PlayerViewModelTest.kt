package com.tvku.feature.player

import com.tvku.core.data.catalog.CatalogCache
import com.tvku.core.data.catalog.CatalogFetcher
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.player.embed.YouTubePlayerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun repo(): CatalogRepository = CatalogRepository(
        object : CatalogFetcher {
            override suspend fun fetch() = """
                {"schema":1,"channels":[
                  {"id":"kompastv-id","name":"KompasTV","country":"ID","category":"news","sources":[{"type":"youtube_live","youtube_channel_id":"UC5BMIWZe9isJXLZZWPWvBlg"}]},
                  {"id":"indosiar-id","name":"Indosiar","country":"ID","sources":[{"type":"official_link","url":"https://www.vidio.com/live/indosiar","platform":"Vidio"}]}
                ]}
            """.trimIndent()
        },
        object : CatalogCache { override suspend fun read(): String? = null; override suspend fun write(text: String) {} },
    ).also { it.refresh() }

    @Test
    fun `LIVE badge only while YouTube reports playing`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("kompastv-id")
        assertFalse(vm.state.value.showLiveBadge)
        vm.onStatus(YouTubePlayerStatus.BUFFERING)
        assertFalse(vm.state.value.showLiveBadge)
        vm.onStatus(YouTubePlayerStatus.PLAYING)
        assertTrue(vm.state.value.showLiveBadge)
        vm.onStatus(YouTubePlayerStatus.PAUSED)
        assertFalse(vm.state.value.showLiveBadge)
    }

    @Test
    fun `times out when playback never starts and retry resets`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("kompastv-id")
        vm.onStatus(YouTubePlayerStatus.READY)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertTrue(vm.state.value.timedOut)
        vm.retry()
        assertFalse(vm.state.value.timedOut)
        assertEquals(1, vm.state.value.attempt)
        vm.onStatus(YouTubePlayerStatus.PLAYING)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertFalse(vm.state.value.timedOut)
    }

    @Test
    fun `final player errors are kept and do not time out`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("kompastv-id")
        vm.onStatus(YouTubePlayerStatus.EMBED_NOT_ALLOWED)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertEquals(YouTubePlayerStatus.EMBED_NOT_ALLOWED, vm.state.value.status)
        assertFalse(vm.state.value.timedOut)
    }

    @Test
    fun `link-only or unknown channels are not playable in app`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("indosiar-id")
        assertTrue(vm.state.value.notFound)
        val vm2 = PlayerViewModel(repo())
        vm2.load("missing")
        assertTrue(vm2.state.value.notFound)
    }
}
