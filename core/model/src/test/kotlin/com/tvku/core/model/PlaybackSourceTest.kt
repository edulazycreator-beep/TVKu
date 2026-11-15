package com.tvku.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSourceTest {
    @Test
    fun `official link keeps url and platform`() {
        val link = PlaybackSource.ExternalLink("https://www.vidio.com/live/indosiar", "Vidio")
        assertEquals("Vidio", link.platformName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plain http links are refused`() {
        PlaybackSource.ExternalLink("http://www.vidio.com/live/indosiar", "Vidio")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non web schemes are refused`() {
        PlaybackSource.ExternalLink("intent://something#Intent;end", "Vidio")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `platform name is required`() {
        PlaybackSource.ExternalLink("https://www.vidio.com/live/indosiar", "")
    }
}
