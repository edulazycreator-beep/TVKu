import unittest

from m3u import PlaylistError, parse

SAMPLE = """﻿#EXTM3U
#EXTINF:-1 tvg-id="DWEnglish.de@HD" tvg-logo="https://example.org/dw.png" group-title="News",DW English (1080p)
https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8

#EXTINF:-1 tvg-id="Some.us@SD" http-user-agent="Mozilla/5.0" group-title="General",Some TV (480p)
#EXTVLCOPT:http-referrer=https://some.example/
http://cdn.example.net/live.m3u8
#EXTINF:-1,Orphan without url
#EXTINF:-1 tvg-id="" group-title="Undefined",No Id
https://x.example/a.m3u8
"""


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.entries = list(parse(SAMPLE.splitlines()))

    def test_entries_and_attributes(self):
        self.assertEqual(3, len(self.entries))
        dw = self.entries[0]
        self.assertEqual("DW English (1080p)", dw.title)
        self.assertEqual("DWEnglish.de", dw.channel_id)
        self.assertEqual("HD", dw.feed_id)
        self.assertEqual(1080, dw.advertised_height)
        self.assertEqual("News", dw.attributes["group-title"])
        self.assertTrue(dw.url.startswith("https://dwamdstream102"))

    def test_headers_from_attributes_and_vlcopt(self):
        some = self.entries[1]
        self.assertEqual({"User-Agent": "Mozilla/5.0", "Referer": "https://some.example/"}, some.required_headers)

    def test_orphan_extinf_is_dropped_and_empty_id_is_none(self):
        last = self.entries[2]
        self.assertEqual("No Id", last.title)
        self.assertIsNone(last.channel_id)

    def test_header_required(self):
        with self.assertRaises(PlaylistError):
            list(parse(["#EXTINF:-1,x", "https://a/b.m3u8"]))

    def test_overlong_lines_are_skipped(self):
        lines = ["#EXTM3U", "#EXTINF:-1," + "x" * 9000, "https://a/b.m3u8", "#EXTINF:-1,ok", "https://a/c.m3u8"]
        self.assertEqual(["ok"], [e.title for e in parse(lines)])


if __name__ == "__main__":
    unittest.main()
