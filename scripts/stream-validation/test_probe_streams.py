import http.server
import threading
import unittest

import probe_streams as ps


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        routes = {
            "/master.m3u8": (200, b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"\nlow/index.m3u8\n'),
            "/low/index.m3u8": (200, b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"),
            "/low/seg1.ts": (206, b"\x47" * 1000),
            "/nosegs.m3u8": (200, b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n"),
            "/html": (200, b"<html>hello</html>"),
            "/dash.mpd": (200, b'<?xml version="1.0"?><MPD><Representation height="1080"/></MPD>'),
            "/geo.m3u8": (403, b""),
            "/gone.m3u8": (404, b""),
            "/redirect": (302, b""),
        }
        code, body = routes.get(self.path, (500, b""))
        self.send_response(code)
        if self.path == "/redirect":
            self.send_header("Location", "/master.m3u8")
        self.end_headers()
        self.wfile.write(body)


class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def run_probe(self, path):
        return ps.probe(self.base + path, allow_private=True, scheme="http")

    def test_valid_hls_with_segment(self):
        r = self.run_probe("/master.m3u8")
        self.assertEqual("SUCCESS", r.outcome, r.error)
        self.assertEqual(("HLS", 720), (r.format, r.max_height))
        self.assertEqual(["avc1", "mp4a"], r.codecs)

    def test_redirect_is_followed_and_rechecked(self):
        self.assertEqual("SUCCESS", self.run_probe("/redirect").outcome)

    def test_dash(self):
        r = self.run_probe("/dash.mpd")
        self.assertEqual(("SUCCESS", "DASH", 1080), (r.outcome, r.format, r.max_height))

    def test_failures_are_classified(self):
        self.assertEqual("GEO_BLOCKED", self.run_probe("/geo.m3u8").outcome)
        self.assertEqual("PERMANENT_FAILURE", self.run_probe("/gone.m3u8").outcome)
        self.assertEqual("PERMANENT_FAILURE", self.run_probe("/nosegs.m3u8").outcome)
        self.assertEqual("PERMANENT_FAILURE", self.run_probe("/html").outcome)
        self.assertEqual("TRANSIENT_FAILURE", self.run_probe("/unknown").outcome)

    def test_ssrf_guard_blocks_private_and_metadata_hosts(self):
        r = ps.probe(self.base + "/master.m3u8", scheme="http")  # loopback without allow_private
        self.assertEqual("UNSUPPORTED", r.outcome)
        self.assertIn("non-public", r.error)
        with self.assertRaises(ps.Blocked):
            ps.check_host("metadata.google.internal")

    def test_cleartext_rejected_in_production_mode(self):
        r = ps.probe("http://example.com/live.m3u8")
        self.assertEqual("UNSUPPORTED", r.outcome)


if __name__ == "__main__":
    unittest.main()
