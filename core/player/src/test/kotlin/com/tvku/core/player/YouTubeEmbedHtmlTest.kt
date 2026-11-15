package com.tvku.core.player.embed

import com.tvku.core.model.PlaybackSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeEmbedHtmlTest {
    @Test
    fun `identifies the client with a stable reverse-DNS origin`() {
        val html = YouTubeEmbedHtml.build("dQw4w9WgXcQ")
        assertTrue(html.contains("origin: 'https://com.tvku.app'"))
        assertTrue(html.contains("widget_referrer: 'https://com.tvku.app'"))
        assertTrue(html.contains("strict-origin-when-cross-origin"))
        assertFalse(html.contains("noreferrer"))
    }

    @Test
    fun `uses the official IFrame API and the requested video`() {
        val html = YouTubeEmbedHtml.build("dQw4w9WgXcQ", autoplay = false)
        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
        assertTrue(html.contains("videoId: 'dQw4w9WgXcQ'"))
        assertTrue(html.contains("autoplay: 0"))
        assertTrue(html.contains("playsinline: 1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects script injection through the video id`() {
        YouTubeEmbedHtml.build("abc');alert(1);//")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non https origins`() {
        YouTubeEmbedHtml.build("dQw4w9WgXcQ", origin = "http://com.tvku.app")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `playback source validates video ids too`() {
        PlaybackSource.YouTubeEmbed("not-a-valid-id")
    }
}
