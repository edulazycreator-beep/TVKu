package com.tvku.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTest {
    private fun channel(vararg sources: ChannelSource) =
        Channel("x-id", "X", "ID", ChannelCategory.NEWS, listOf("ind"), sources.toList())

    @Test
    fun `playable and link are picked by type`() {
        val yt = ChannelSource.YouTubeLive("UC5BMIWZe9isJXLZZWPWvBlg")
        val link = ChannelSource.OfficialLink("https://www.vidio.com/live/indosiar", "Vidio")
        assertEquals(yt, channel(link, yt).playable)
        assertEquals(link, channel(link, yt).officialLink)
        assertNull(channel(link).playable)
    }

    @Test
    fun `youtube live page points at the official channel`() {
        assertEquals(
            "https://www.youtube.com/channel/UC5BMIWZe9isJXLZZWPWvBlg/live",
            ChannelSource.YouTubeLive("UC5BMIWZe9isJXLZZWPWvBlg").livePageUrl,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `youtube channel id is validated`() {
        ChannelSource.YouTubeLive("@kompastv")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plain http links are refused`() {
        ChannelSource.OfficialLink("http://www.vidio.com/live/indosiar", "Vidio")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non web schemes are refused`() {
        ChannelSource.OfficialLink("intent://something#Intent;end", "Vidio")
    }

    @Test
    fun `unknown category falls back to general`() {
        assertEquals(ChannelCategory.GENERAL, ChannelCategory.fromWire("cooking"))
        assertEquals(ChannelCategory.SPORTS, ChannelCategory.fromWire("sports"))
    }
}
