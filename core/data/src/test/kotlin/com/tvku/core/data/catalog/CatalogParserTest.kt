package com.tvku.core.data.catalog

import com.tvku.core.model.ChannelCategory
import com.tvku.core.model.ChannelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParserTest {
    @Test
    fun `parses youtube live and official link channels`() {
        val channels = CatalogParser.parse(SAMPLE)
        assertEquals(listOf("kompastv-id", "indosiar-id"), channels.map { it.id })
        assertEquals(ChannelSource.YouTubeLive("UC5BMIWZe9isJXLZZWPWvBlg"), channels[0].playable)
        assertEquals("Vidio", channels[1].officialLink?.platformName)
        assertEquals(ChannelCategory.ENTERTAINMENT, channels[1].category)
    }

    @Test
    fun `drops invalid sources and channels without valid sources`() {
        val text = """
            {"schema":1,"channels":[
              {"id":"bad-yt","name":"Bad","country":"ID","sources":[{"type":"youtube_live","youtube_channel_id":"@handle"}]},
              {"id":"bad-link","name":"Bad","country":"ID","sources":[{"type":"official_link","url":"http://x.id","platform":"Xtv"}]},
              {"id":"future","name":"Future","country":"ID","sources":[{"type":"hls","url":"https://x.id/a.m3u8"}]},
              {"id":"Bad Id","name":"X","country":"ID","sources":[{"type":"official_link","url":"https://x.id","platform":"Xtv"}]},
              {"id":"ok","name":"Ok","country":"ID","sources":[{"type":"hls"},{"type":"official_link","url":"https://x.id","platform":"Xtv"}]}
            ]}
        """.trimIndent()
        val channels = CatalogParser.parse(text)
        assertEquals(listOf("ok"), channels.map { it.id })
        assertEquals(1, channels[0].sources.size)
    }

    @Test
    fun `parses verified streams and rejects bad ones`() {
        val text = """
            {"schema":1,"channels":[
              {"id":"tvri-id","name":"TVRI","country":"ID","category":"general","sources":[
                {"type":"stream","url":"https://ott.example.go.id/live/master.m3u8","format":"HLS","height":720},
                {"type":"stream","url":"http://plain.example/x.m3u8","format":"HLS"},
                {"type":"stream","url":"https://x.example/x.m3u8","format":"RTMP"},
                {"type":"stream","url":"https://x.example/y.mpd","format":"DASH","height":99999}
              ]}
            ]}
        """.trimIndent()
        val channel = CatalogParser.parse(text).single()
        val stream = channel.streams.single()
        assertEquals(ChannelSource.Stream.Format.HLS, stream.format)
        assertEquals("HD", stream.qualityLabel)
        assertEquals(stream, channel.playable)
    }

    @Test(expected = CatalogFormatException::class)
    fun `rejects unknown schema`() {
        CatalogParser.parse("""{"schema":2,"channels":[]}""")
    }

    @Test(expected = CatalogFormatException::class)
    fun `rejects malformed json`() {
        CatalogParser.parse("<html>not json</html>")
    }

    @Test
    fun `ignores unknown fields`() {
        assertTrue(CatalogParser.parse("""{"schema":1,"extra":true,"channels":[]}""").isEmpty())
    }

    companion object {
        val SAMPLE = """
            {"schema":1,"generated_at":"2026-11-15T00:00:00+00:00","channels":[
              {"id":"kompastv-id","name":"KompasTV","country":"ID","category":"news","languages":["ind"],
               "sources":[{"type":"youtube_live","youtube_channel_id":"UC5BMIWZe9isJXLZZWPWvBlg","official_page":"https://www.youtube.com/@kompastv"}]},
              {"id":"indosiar-id","name":"Indosiar","country":"ID","category":"entertainment","languages":["ind"],
               "sources":[{"type":"official_link","url":"https://www.vidio.com/live/indosiar","platform":"Vidio","last_check":{"ok":true}}]}
            ]}
        """.trimIndent()
    }
}
