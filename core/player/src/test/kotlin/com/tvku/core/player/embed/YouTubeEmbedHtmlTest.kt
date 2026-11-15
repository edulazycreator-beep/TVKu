package com.tvku.core.player.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeEmbedHtmlTest {
    private val html = YouTubeEmbedHtml.liveChannel("UC5BMIWZe9isJXLZZWPWvBlg")

    @Test
    fun `embeds the channel live stream with the official player`() {
        assertTrue(html.contains("https://www.youtube.com/embed/live_stream?channel=UC5BMIWZe9isJXLZZWPWvBlg"))
        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
        assertTrue(html.contains("enablejsapi=1"))
        assertTrue(html.contains("playsinline=1"))
    }

    @Test
    fun `identifies the client with a stable reverse-DNS origin`() {
        assertTrue(html.contains("origin=https://com.tvku.app"))
        assertTrue(html.contains("widget_referrer=https://com.tvku.app"))
        assertTrue(html.contains("strict-origin-when-cross-origin"))
        assertFalse(html.contains("noreferrer"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects script injection through the channel id`() {
        YouTubeEmbedHtml.liveChannel("UC');alert(1);//aaaaaaaaaa")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non https origins`() {
        YouTubeEmbedHtml.liveChannel("UC5BMIWZe9isJXLZZWPWvBlg", origin = "http://com.tvku.app")
    }

    @Test
    fun `maps IFrame API events to honest statuses`() {
        assertEquals(YouTubePlayerStatus.PLAYING, YouTubePlayerStatus.from("state", 1))
        assertEquals(YouTubePlayerStatus.BUFFERING, YouTubePlayerStatus.from("state", 3))
        assertEquals(YouTubePlayerStatus.ENDED, YouTubePlayerStatus.from("state", 0))
        assertEquals(YouTubePlayerStatus.READY, YouTubePlayerStatus.from("state", -1))
        assertEquals(YouTubePlayerStatus.NOT_AVAILABLE, YouTubePlayerStatus.from("error", 100))
        assertEquals(YouTubePlayerStatus.EMBED_NOT_ALLOWED, YouTubePlayerStatus.from("error", 150))
        assertEquals(YouTubePlayerStatus.CLIENT_IDENTITY_ERROR, YouTubePlayerStatus.from("error", 153))
        assertEquals(YouTubePlayerStatus.ERROR, YouTubePlayerStatus.from("error", 5))
        assertEquals(YouTubePlayerStatus.ERROR, YouTubePlayerStatus.from("bogus", 1))
        assertTrue(YouTubePlayerStatus.EMBED_NOT_ALLOWED.isFinal)
        assertFalse(YouTubePlayerStatus.BUFFERING.isFinal)
    }
}
