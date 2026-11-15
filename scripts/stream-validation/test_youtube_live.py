import unittest
from urllib.parse import parse_qs, urlsplit

from youtube_live import EmbedCheckResult, QuotaExceeded, YouTubeApi, check_live

CID = "UC5BMIWZe9isJXLZZWPWvBlg"
UPLOADS = "UU5BMIWZe9isJXLZZWPWvBlg"


def video(vid, *, live=True, embeddable=True, start="2026-11-15T00:00:00Z", end=None, channel=CID, privacy="public", region=None):
    v = {
        "id": vid,
        "snippet": {"channelId": channel, "liveBroadcastContent": "live" if live else "none"},
        "status": {"embeddable": embeddable, "privacyStatus": privacy},
        "liveStreamingDetails": {"actualStartTime": start} if live else {},
        "contentDetails": {},
    }
    if end:
        v["liveStreamingDetails"]["actualEndTime"] = end
    if region:
        v["contentDetails"]["regionRestriction"] = region
    return v


class FakeApi:
    def __init__(self, videos, channel_found=True, channel_id=CID, status_overrides=None):
        self.videos = {v["id"]: v for v in videos}
        self.channel_found = channel_found
        self.channel_id = channel_id
        self.status_overrides = status_overrides or {}
        self.calls = []

    def __call__(self, url):
        parts = urlsplit(url)
        endpoint = parts.path.rsplit("/", 1)[-1]
        q = {k: v[0] for k, v in parse_qs(parts.query).items()}
        self.calls.append((endpoint, q))
        assert q["key"] == "test-key"
        if endpoint in self.status_overrides:
            return self.status_overrides[endpoint]
        if endpoint == "channels":
            if not self.channel_found:
                return 200, {"items": []}
            return 200, {"items": [{"id": self.channel_id, "contentDetails": {"relatedPlaylists": {"uploads": UPLOADS}}}]}
        if endpoint == "playlistItems":
            return 200, {"items": [{"contentDetails": {"videoId": i}} for i in self.videos]}
        if endpoint == "videos":
            ids = q["id"].split(",")
            return 200, {"items": [self.videos[i] for i in ids if i in self.videos]}
        raise AssertionError(endpoint)


def run(fake, ref="@kompastv", **kw):
    return check_live(YouTubeApi("test-key", fetch=fake), ref, **kw)


class CheckLiveTest(unittest.TestCase):
    def test_live_and_embeddable(self):
        fake = FakeApi([video("AAAAAAAAAAA"), video("BBBBBBBBBBB", live=False)])
        r = run(fake)
        self.assertEqual(("SUCCESS", CID, "AAAAAAAAAAA", True), (r.outcome, r.channel_id, r.video_id, r.embeddable))
        self.assertEqual("@kompastv", fake.calls[0][1]["forHandle"])
        self.assertNotIn("search", [c[0] for c in fake.calls])  # cheap path only

    def test_prefers_longest_running_live(self):
        r = run(FakeApi([video("NEWNEWNEWNE", start="2026-11-15T10:00:00Z"), video("OLDOLDOLDOL", start="2026-11-01T00:00:00Z")]))
        self.assertEqual("OLDOLDOLDOL", r.video_id)

    def test_no_live_is_transient(self):
        r = run(FakeApi([video("AAAAAAAAAAA", live=False), video("CCCCCCCCCCC", end="2026-11-15T01:00:00Z")]))
        self.assertEqual("TRANSIENT_FAILURE", r.outcome)
        self.assertIsNone(r.video_id)

    def test_embedding_disabled_is_unsupported(self):
        r = run(FakeApi([video("AAAAAAAAAAA", embeddable=False)]))
        self.assertEqual("UNSUPPORTED", r.outcome)
        self.assertFalse(r.embeddable)

    def test_other_channels_videos_are_ignored(self):
        r = run(FakeApi([video("AAAAAAAAAAA", channel="UCotherotherotherotherot")]))
        self.assertEqual("TRANSIENT_FAILURE", r.outcome)

    def test_region_restriction(self):
        r = run(FakeApi([video("AAAAAAAAAAA", region={"blocked": ["US"]})]), vantage="US")
        self.assertEqual("GEO_BLOCKED", r.outcome)
        r = run(FakeApi([video("AAAAAAAAAAA", region={"allowed": ["ID"]})]), vantage="US")
        self.assertEqual("GEO_BLOCKED", r.outcome)
        r = run(FakeApi([video("AAAAAAAAAAA", region={"allowed": ["ID"]})]), vantage="ID")
        self.assertEqual("SUCCESS", r.outcome)

    def test_unknown_channel_and_pinned_mismatch(self):
        self.assertEqual("PERMANENT_FAILURE", run(FakeApi([], channel_found=False)).outcome)
        r = run(FakeApi([video("AAAAAAAAAAA")]), pinned_channel_id="UCpinnedpinnedpinnedpinn")
        self.assertEqual("PERMANENT_FAILURE", r.outcome)
        self.assertIn("expected", r.error)

    def test_invalid_reference(self):
        self.assertEqual("PERMANENT_FAILURE", run(FakeApi([]), ref="kompastv").outcome)

    def test_server_error_is_transient(self):
        self.assertEqual("TRANSIENT_FAILURE", run(FakeApi([], status_overrides={"channels": (503, {})})).outcome)

    def test_quota_exceeded_aborts(self):
        body = {"error": {"errors": [{"reason": "quotaExceeded"}]}}
        with self.assertRaises(QuotaExceeded):
            run(FakeApi([], status_overrides={"channels": (403, body)}))

    def test_rpc_params_shape(self):
        p = EmbedCheckResult("SUCCESS", CID, "AAAAAAAAAAA", True, None, 200).rpc_params("s1", "runner", "US")
        self.assertEqual("EMBED_STATUS_CHECK", p["p_test_type"])
        self.assertEqual("AAAAAAAAAAA", p["p_embed_video_id"])


if __name__ == "__main__":
    unittest.main()
