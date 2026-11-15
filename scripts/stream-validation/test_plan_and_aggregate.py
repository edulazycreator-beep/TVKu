import json
import pathlib
import unittest

from aggregate_results import aggregate, is_verified
from plan_validation import policy_decision, priority

POLICY = json.loads((pathlib.Path(__file__).parent / "publication_policy.json").read_text(encoding="utf-8"))


def row(**kw):
    base = {"channel_id": "TVRI.id", "feed": "Aceh", "feed_name": "Aceh", "feed_is_main": "False", "name": "TVRI",
            "country": "ID", "categories": "general", "owners": "", "languages": "ind", "website": "https://tvri.go.id",
            "evidence_kind": "official_domain", "evidence": "x",
            "stream_url": "https://ott-balancer.tvri.go.id/live/eds/Aceh/hls/Aceh.m3u8"}
    base.update(kw)
    return base


class PolicyTest(unittest.TestCase):
    def test_official_domain_is_approved(self):
        self.assertIsNone(policy_decision(row(), POLICY))

    def test_cleartext_is_excluded(self):
        self.assertEqual("not https", policy_decision(row(stream_url="http://x.tvri.go.id/a.m3u8"), POLICY))

    def test_brand_domain_needs_explicit_approval(self):
        self.assertIsNone(policy_decision(row(channel_id="RodjaTV.id", evidence_kind="brand_domain",
                                              stream_url="https://rodjatv.com/rodjatv/live.m3u8"), POLICY))
        self.assertIn("not approved", policy_decision(row(channel_id="Other.us", evidence_kind="brand_domain"), POLICY))

    def test_sanctions_and_distribution_feeds(self):
        self.assertIn("sanctions", policy_decision(row(stream_url="https://live.presstv.ir/hls/a.m3u8"), POLICY))
        self.assertIn("sanctions", policy_decision(row(channel_id="MRTVNews.mm"), POLICY))
        self.assertIn("sanctions", policy_decision(row(owners="Gazprom Media"), POLICY))
        self.assertEqual("distribution-partner feed", policy_decision(
            row(stream_url="https://lotus.stingray.com/manifest/x/samsungtvplus/master.m3u8"), POLICY))
        # A marker inside a longer word is not a match ("televizio" is not "vizio").
        self.assertIsNone(policy_decision(row(stream_url="https://stream.vasarhelyitelevizio.hu/stream/stream.m3u8"), POLICY))

    def test_indonesia_first_then_region_then_sports(self):
        rows = [row(country="US", categories="news"), row(country="FR", categories="sports"),
                row(country="MY"), row(country="ID")]
        ordered = sorted(rows, key=lambda r: priority(r, POLICY))
        self.assertEqual(["ID", "MY", "FR", "US"], [r["country"] for r in ordered])


GOOD = {"url": "https://a/x.m3u8", "outcome": "SUCCESS", "played_seconds": 60.0, "video_frames": 1500,
        "audio_decoded": True, "reconnect_success": True, "height": 720, "resolution": "1280x720", "format": "HLS"}


class AggregateTest(unittest.TestCase):
    def test_every_criterion_is_rechecked(self):
        self.assertTrue(is_verified(GOOD))
        for bad in ({"played_seconds": 59.0}, {"video_frames": 0}, {"audio_decoded": False},
                    {"reconnect_success": False}, {"outcome": "GEO_BLOCKED"}):
            self.assertFalse(is_verified({**GOOD, **bad}), bad)

    def test_only_verified_streams_are_published_and_missing_results_are_reported(self):
        approved = [row(stream_url="https://a/x.m3u8"), row(stream_url="https://b/y.m3u8", feed="Bali"),
                    row(stream_url="https://c/z.m3u8", feed="Jambi")]
        results = {"https://a/x.m3u8": GOOD,
                   "https://b/y.m3u8": {**GOOD, "url": "https://b/y.m3u8", "reconnect_success": False}}
        verified, report = aggregate(results, approved, ["https://a/x.m3u8", "https://b/y.m3u8", "https://c/z.m3u8"], "now")
        self.assertEqual(["https://a/x.m3u8"], [v["url"] for v in verified])
        self.assertEqual(720, verified[0]["height"])
        self.assertEqual({"SUCCESS": 1, "INCOMPLETE": 1, "NOT_RUN": 1}, report["counts"])


if __name__ == "__main__":
    unittest.main()
