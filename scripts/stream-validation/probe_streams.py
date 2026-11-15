#!/usr/bin/env python3
"""Tier A stream probe: cheap, parallel checks before real playback (Tier B).

For each candidate stream:
  1. URL must be https (the app refuses cleartext).
  2. Host must resolve to public addresses only (SSRF guard: no loopback,
     private, link-local, multicast, reserved or cloud-metadata ranges),
     re-checked on every redirect; at most 3 redirects.
  3. Manifest must download (HTTP 200, <= 2 MiB) and look like HLS (#EXTM3U)
     or DASH (<MPD).
  4. HLS: one media playlist and its first segment (first 64 KiB) must download.
Outcomes map to public.health_outcome. Passing Tier A never means VERIFIED.

    python3 probe_streams.py --candidates review_candidates.csv --out tier_a.json
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import csv
import ipaddress
import json
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass, field

USER_AGENT = "TVKu-validator/1 (+https://github.com/edulazycreator-beep/TVKu)"
MAX_MANIFEST = 2 * 1024 * 1024
SEGMENT_BYTES = 64 * 1024
TIMEOUT = 12.0
MAX_REDIRECTS = 3
METADATA_HOSTS = {"metadata.google.internal", "metadata", "instance-data"}


class Blocked(Exception):
    """Destination refused by the SSRF guard."""


@dataclass
class ProbeResult:
    url: str
    outcome: str                      # SUCCESS | TRANSIENT_FAILURE | PERMANENT_FAILURE | GEO_BLOCKED | UNSUPPORTED
    http_status: int | None = None
    format: str | None = None         # HLS | DASH
    max_height: int | None = None
    codecs: list[str] = field(default_factory=list)
    error: str | None = None
    elapsed_ms: int = 0


def check_host(host: str, allow_private: bool = False) -> None:
    if not host or host.lower() in METADATA_HOSTS:
        raise Blocked(f"blocked host {host!r}")
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        raise urllib.error.URLError(f"dns: {e}") from e
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if allow_private:
            continue
        if (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved
                or ip.is_unspecified or str(ip) == "169.254.169.254"):
            raise Blocked(f"{host} resolves to non-public address {ip}")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # handled manually to re-check every hop
        return None


def fetch(url: str, max_bytes: int, *, range_bytes: int | None = None, allow_private: bool = False,
          scheme: str = "https", context: ssl.SSLContext | None = None) -> tuple[int, str, bytes]:
    opener = urllib.request.build_opener(_NoRedirect, urllib.request.HTTPSHandler(context=context))
    for _ in range(MAX_REDIRECTS + 1):
        parts = urllib.parse.urlsplit(url)
        if parts.scheme != scheme:
            raise Blocked(f"scheme {parts.scheme} not allowed")
        check_host(parts.hostname or "", allow_private)
        headers = {"User-Agent": USER_AGENT, "Accept": "*/*"}
        if range_bytes:
            headers["Range"] = f"bytes=0-{range_bytes - 1}"
        req = urllib.request.Request(url, headers=headers)
        try:
            with opener.open(req, timeout=TIMEOUT) as r:
                return r.status, r.geturl(), r.read(max_bytes + 1)
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                url = urllib.parse.urljoin(url, e.headers["Location"])
                continue
            raise
    raise Blocked("too many redirects")


def _hls_variants(text: str, base: str) -> tuple[list[str], int | None, list[str]]:
    """Returns (media playlist URLs, max height, codecs) from a master playlist."""
    urls, heights, codecs = [], [], set()
    lines = [l.strip() for l in text.splitlines()]
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF"):
            m = re.search(r"RESOLUTION=(\d+)x(\d+)", line)
            if m:
                heights.append(int(m.group(2)))
            c = re.search(r'CODECS="([^"]+)"', line)
            if c:
                codecs.update(x.strip().split(".")[0] for x in c.group(1).split(","))
            nxt = next((l for l in lines[i + 1:] if l and not l.startswith("#")), None)
            if nxt:
                urls.append(urllib.parse.urljoin(base, nxt))
    return urls, (max(heights) if heights else None), sorted(codecs)


def _first_segment(text: str, base: str) -> str | None:
    seg = next((l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")), None)
    return urllib.parse.urljoin(base, seg) if seg else None


def probe(url: str, *, allow_private: bool = False, scheme: str = "https",
          context: ssl.SSLContext | None = None) -> ProbeResult:
    t0 = time.monotonic()
    res = ProbeResult(url=url, outcome="PERMANENT_FAILURE")
    kw = dict(allow_private=allow_private, scheme=scheme, context=context)
    try:
        if urllib.parse.urlsplit(url).scheme != scheme:
            res.outcome, res.error = "UNSUPPORTED", "cleartext http is not allowed by the app"
            return res
        status, final, body = fetch(url, MAX_MANIFEST, **kw)
        res.http_status = status
        if len(body) > MAX_MANIFEST:
            res.error = "manifest too large"
            return res
        text = body.decode("utf-8", errors="replace").lstrip("﻿ \r\n\t")
        if text.startswith("#EXTM3U"):
            res.format = "HLS"
            variants, res.max_height, res.codecs = _hls_variants(text, final)
            media_url, media_text = final, text
            if variants:
                _, media_url, mb = fetch(variants[0], MAX_MANIFEST, **kw)
                media_text = mb.decode("utf-8", errors="replace")
                if not media_text.lstrip().startswith("#EXTM3U"):
                    res.error = "variant playlist is not HLS"
                    return res
            seg = _first_segment(media_text, media_url)
            if not seg:
                res.error = "no segments in media playlist"
                return res
            if seg.split("?")[0].endswith(".m3u8"):
                res.error = "nested playlist without segments"
                return res
            s_status, _, s_body = fetch(seg, SEGMENT_BYTES, range_bytes=SEGMENT_BYTES, **kw)
            if s_status not in (200, 206) or not s_body:
                res.error = f"segment HTTP {s_status}"
                return res
        elif "<MPD" in text[:2048]:
            res.format = "DASH"
            h = [int(x) for x in re.findall(r'height="(\d+)"', text)]
            res.max_height = max(h) if h else None
        else:
            res.error = "response is not an HLS or DASH manifest"
            return res
        res.outcome = "SUCCESS"
        return res
    except Blocked as e:
        res.outcome, res.error = "UNSUPPORTED", f"blocked: {e}"
    except urllib.error.HTTPError as e:
        res.http_status = e.code
        if e.code in (403, 451):
            res.outcome, res.error = "GEO_BLOCKED", f"HTTP {e.code} (forbidden from validator location)"
        elif e.code in (404, 410):
            res.outcome, res.error = "PERMANENT_FAILURE", f"HTTP {e.code}"
        else:
            res.outcome, res.error = "TRANSIENT_FAILURE", f"HTTP {e.code}"
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ssl.SSLError, OSError) as e:
        res.outcome, res.error = "TRANSIENT_FAILURE", f"{type(e).__name__}: {str(e)[:160]}"
    finally:
        res.elapsed_ms = int((time.monotonic() - t0) * 1000)
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidates", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", default="official_domain,government_domain,brand_domain")
    ap.add_argument("--workers", type=int, default=32)
    args = ap.parse_args()
    allowed = set(args.evidence.split(","))
    rows = [r for r in csv.DictReader(open(args.candidates, encoding="utf-8")) if r["evidence_kind"] in allowed]
    urls = list(dict.fromkeys(r["stream_url"] for r in rows))
    results: dict[str, dict] = {}
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for r in ex.map(probe, urls):
            results[r.url] = asdict(r)
    counts: dict[str, int] = {}
    for r in results.values():
        counts[r["outcome"]] = counts.get(r["outcome"], 0) + 1
    json.dump({"probed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "counts": counts, "results": results},
              open(args.out, "w"), indent=1)
    summary = f"tier A probed={len(urls)} " + " ".join(f"{k}={v}" for k, v in sorted(counts.items()))
    print(summary)
    print("::notice title=Tier A::" + summary)


if __name__ == "__main__":
    sys.exit(main())
