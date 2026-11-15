package com.tvku.feature.player

import com.tvku.core.data.catalog.CatalogCache
import com.tvku.core.data.catalog.CatalogFetcher
import com.tvku.core.data.catalog.CatalogRepository
import com.tvku.core.model.ChannelSource
import com.tvku.core.player.embed.YouTubePlayerStatus
import com.tvku.core.player.media.MediaPlaybackStatus
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
                  {"id":"tvri-id","name":"TVRI","country":"ID","sources":[
                    {"type":"stream","url":"https://a.example.go.id/live.m3u8","format":"HLS","height":720},
                    {"type":"stream","url":"https://b.example.go.id/live.m3u8","format":"HLS","height":576}]},
                  {"id":"indosiar-id","name":"Indosiar","country":"ID","sources":[{"type":"official_link","url":"https://www.vidio.com/live/indosiar","platform":"Vidio"}]}
                ]}
            """.trimIndent()
        },
        object : CatalogCache { override suspend fun read(): String? = null; override suspend fun write(text: String) {} },
    ).also { it.refresh() }

    @Test
    fun `LIVE badge only while frames are playing`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("tvri-id")
        assertFalse(vm.state.value.showLiveBadge)
        vm.onMediaStatus(MediaPlaybackStatus.CONNECTING, null)
        assertFalse(vm.state.value.showLiveBadge)
        vm.onMediaStatus(MediaPlaybackStatus.PLAYING, 720)
        assertTrue(vm.state.value.showLiveBadge)
        assertEquals(720, vm.state.value.measuredHeightPx)
        vm.onMediaStatus(MediaPlaybackStatus.BUFFERING, null)
        assertFalse(vm.state.value.showLiveBadge)
        assertEquals(720, vm.state.value.measuredHeightPx)
    }

    @Test
    fun `verified streams are preferred over youtube and fall back only within the same channel`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("tvri-id")
        assertEquals("https://a.example.go.id/live.m3u8", (vm.state.value.source as ChannelSource.Stream).url)
        vm.onMediaStatus(MediaPlaybackStatus.OFFLINE, null)
        assertEquals("https://b.example.go.id/live.m3u8", (vm.state.value.source as ChannelSource.Stream).url)
        assertFalse(vm.state.value.failed)
        vm.onMediaStatus(MediaPlaybackStatus.OFFLINE, null)
        assertTrue(vm.state.value.failed)
        assertEquals(R.string.player_status_offline, vm.state.value.statusText)
    }

    @Test
    fun `youtube channel times out when playback never starts and retry resets`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("kompastv-id")
        vm.onYouTubeStatus(YouTubePlayerStatus.READY)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertTrue(vm.state.value.timedOut)
        vm.retry()
        assertFalse(vm.state.value.timedOut)
        assertEquals(1, vm.state.value.attempt)
        vm.onYouTubeStatus(YouTubePlayerStatus.PLAYING)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertFalse(vm.state.value.timedOut)
    }

    @Test
    fun `final errors are kept and do not time out`() = runTest(dispatcher) {
        val vm = PlayerViewModel(repo())
        vm.load("kompastv-id")
        vm.onYouTubeStatus(YouTubePlayerStatus.EMBED_NOT_ALLOWED)
        advanceTimeBy(PlayerViewModel.START_TIMEOUT_MS + 1)
        assertEquals(YouTubePlayerStatus.EMBED_NOT_ALLOWED, vm.state.value.youtube)
        assertFalse(vm.state.value.timedOut)
        assertTrue(vm.state.value.failed)
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
