import datetime as dt
import json
import pathlib
import unittest

from build_catalog import CatalogError, build

NOW = dt.datetime(2026, 11, 15, tzinfo=dt.timezone.utc)
CURATED = pathlib.Path(__file__).resolve().parents[1] / "database-import" / "curated_sources.json"


def src(**kw):
    base = {"key": "x", "channel_name": "X", "country": "ID", "category": "news", "languages": ["ind"], "approved": True}
    base.update(kw)
    return base


class BuildCatalogTest(unittest.TestCase):
    def test_only_approved_youtube_and_links(self):
        cat = build({"sources": [
            src(key="a", kind="youtube", channel_ref="UC5BMIWZe9isJXLZZWPWvBlg"),
            src(key="b", kind="link", url="https://www.vidio.com/live/x", platform="Vidio"),
            src(key="c", kind="link", url="https://example.id/live", platform="Ex", approved=False),
            src(key="d", kind="hls", url="https://cdn.example.id/live.m3u8"),
        ]}, NOW)
        self.assertEqual(["a-id", "b-id"], [c["id"] for c in cat["channels"]])
        self.assertEqual("youtube_live", cat["channels"][0]["sources"][0]["type"])

    def test_rejects_handles_http_and_unknown_categories(self):
        with self.assertRaises(CatalogError):
            build({"sources": [src(kind="youtube", channel_ref="@kompastv")]}, NOW)
        with self.assertRaises(CatalogError):
            build({"sources": [src(kind="link", url="http://x.id", platform="X")]}, NOW)
        with self.assertRaises(CatalogError):
            build({"sources": [src(kind="link", url="https://x.id", platform="X", category="cooking")]}, NOW)

    def test_link_check_results_are_recorded_not_invented(self):
        cat = build({"sources": [src(kind="link", url="https://x.id/live", platform="X")]}, NOW,
                    link_checker=lambda url: {"http_status": 403, "ok": False})
        self.assertEqual({"checked_at": NOW.isoformat(), "http_status": 403, "ok": False},
                         cat["channels"][0]["sources"][0]["last_check"])
        cat = build({"sources": [src(kind="link", url="https://x.id/live", platform="X")]}, NOW)
        self.assertNotIn("last_check", cat["channels"][0]["sources"][0])

    def test_same_key_merges_sources_and_youtube_channels_sort_first(self):
        cat = build({"sources": [
            src(key="z", kind="link", url="https://z.id", platform="Z"),
            src(key="m", kind="youtube", channel_ref="UCzl0OrB3-ehunyotIQvK77A"),
            src(key="m", kind="link", url="https://m.id/live", platform="M"),
        ]}, NOW)
        self.assertEqual(["m-id", "z-id"], [c["id"] for c in cat["channels"]])
        self.assertEqual(2, len(cat["channels"][0]["sources"]))

    def test_real_curated_file_builds(self):
        cat = build(json.loads(CURATED.read_text(encoding="utf-8")), NOW)
        ids = {c["id"] for c in cat["channels"]}
        self.assertTrue({"kompastv-id", "metrotv-id", "inews-id", "cnnindonesia-id", "indosiar-id", "antv-id", "tvrisport-id"} <= ids)
        for c in cat["channels"]:
            for s in c["sources"]:
                self.assertIn(s["type"], ("youtube_live", "official_link"))


if __name__ == "__main__":
    unittest.main()
