#!/usr/bin/env python3
"""Discovery pass over iptv-org data. Produces leads for human licensing review.

This script NEVER marks anything as approved or verified. It classifies each
playlist entry and writes:
  * discovery_summary.json  - counts by reason
  * review_candidates.csv   - entries whose stream host belongs to the
                              broadcaster's own website domain (strongest
                              automatic signal of an official source)

Exclusion reasons (never imported):
  blocklisted         channel is on iptv-org's blocklist (DMCA / NSFW)
  nsfw                channel flagged NSFW
  closed              channel has a closure date
  needs_headers       requires spoofed User-Agent / Referer
  tokenized           URL carries auth/session tokens or expiry signatures
  redirector          URL goes through a third-party redirect service
  unsupported_scheme  not HTTP(S) (rtmp, rtsp, srt, mms): not playable by the app
  no_channel_id       entry not linked to a known channel
  unknown_channel     tvg-id not in the iptv-org channel database

Usage:
  python3 discover_iptv_org.py --data DIR --out DIR
where DIR contains iptv_gh-pages_index.m3u and the database_master_data_*.csv files.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import pathlib
import re
import sys
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "playlist-parser"))
from m3u import parse  # noqa: E402

try:
    import tldextract
    _EXTRACT = tldextract.TLDExtract(suffix_list_urls=())  # bundled snapshot, no network
except ImportError:  # pragma: no cover
    sys.exit("pip install tldextract")

TOKEN_PARAMS = re.compile(
    r"^(token|tok|auth|hdnts|hdnea|hmac|signature|sig|st|e|expires|exp|wmsauthsign|key|session|sid|acl|policy|jwt|t)$",
    re.I,
)
TOKEN_PATH = re.compile(r"(token=|hdnts=|wmsAuthSign|/exp=|/hmac=|Signature=)", re.I)
REDIRECTORS = {"jmp2.uk", "bit.ly", "tinyurl.com", "is.gd"}
# iptv-org uses "UK"; TVKu stores ISO 3166-1 alpha-2.
COUNTRY_FIXES = {"UK": "GB"}


def registrable(host: str) -> str:
    ext = _EXTRACT(host)
    return ext.top_domain_under_public_suffix if hasattr(ext, "top_domain_under_public_suffix") else ext.registered_domain


def load_csv(path: pathlib.Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def classify(entry, channels, blocklist) -> tuple[str, dict]:
    cid = entry.channel_id
    if not cid:
        return "no_channel_id", {}
    if cid in blocklist:
        return "blocklisted", {}
    ch = channels.get(cid)
    if ch is None:
        return "unknown_channel", {}
    if ch.get("is_nsfw", "").upper() == "TRUE":
        return "nsfw", {}
    if ch.get("closed"):
        return "closed", {}
    if entry.required_headers:
        return "needs_headers", {}
    parts = urlsplit(entry.url)
    if parts.scheme not in ("http", "https"):
        return "unsupported_scheme", {}
    host = (parts.hostname or "").lower()
    if not host:
        return "invalid_url", {}
    if registrable(host) in REDIRECTORS:
        return "redirector", {}
    if any(TOKEN_PARAMS.match(k) for k in parse_qs(parts.query)) or TOKEN_PATH.search(entry.url):
        return "tokenized", {}

    stream_domain = registrable(host)
    website = ch.get("website", "")
    site_domain = registrable(urlsplit(website).hostname or "") if website else ""
    info = {"stream_domain": stream_domain, "site_domain": site_domain, "scheme": parts.scheme}
    if site_domain and stream_domain == site_domain:
        return "candidate_official_domain", info
    return "needs_manual_evidence", info


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    channels = {c["id"]: c for c in load_csv(args.data / "database_master_data_channels.csv")}
    blocklist = {b["channel"] for b in load_csv(args.data / "database_master_data_blocklist.csv")}
    with (args.data / "iptv_gh-pages_index.m3u").open(encoding="utf-8", errors="replace") as f:
        entries = list(parse(f))

    reasons = collections.Counter()
    schemes = collections.Counter()
    seen_urls: set[str] = set()
    duplicates = 0
    candidates = []
    for e in entries:
        if e.url in seen_urls:
            duplicates += 1
            continue
        seen_urls.add(e.url)
        schemes[urlsplit(e.url).scheme] += 1
        reason, info = classify(e, channels, blocklist)
        reasons[reason] += 1
        if reason == "candidate_official_domain":
            ch = channels[e.channel_id]
            candidates.append({
                "channel_id": e.channel_id,
                "feed": e.feed_id or "",
                "name": ch["name"],
                "country": COUNTRY_FIXES.get(ch["country"], ch["country"]),
                "categories": ch["categories"],
                "network": ch.get("network", ""),
                "owners": ch.get("owners", ""),
                "website": ch.get("website", ""),
                "stream_url": e.url,
                "scheme": info["scheme"],
                "advertised_height": e.advertised_height or "",
                "evidence": f"stream host is on the broadcaster's own domain ({info['stream_domain']})",
            })

    args.out.mkdir(parents=True, exist_ok=True)
    candidates.sort(key=lambda c: (c["country"], c["name"]))
    with (args.out / "review_candidates.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(candidates[0].keys()) if candidates else ["channel_id"])
        w.writeheader()
        w.writerows(candidates)

    by_country = collections.Counter(c["country"] for c in candidates)
    https_candidates = sum(1 for c in candidates if c["scheme"] == "https")
    summary = {
        "source": "iptv-org (index.m3u + database), fetched from raw.githubusercontent.com",
        "playlist_entries": len(entries),
        "duplicate_urls": duplicates,
        "unique_urls": len(seen_urls),
        "url_schemes": dict(schemes),
        "classification": dict(reasons.most_common()),
        "candidates_official_domain": len(candidates),
        "candidates_https": https_candidates,
        "candidate_channels": len({c["channel_id"] for c in candidates}),
        "candidate_countries": len(by_country),
        "top_candidate_countries": dict(by_country.most_common(25)),
        "note": "No entry is approved or verified by this script. Candidates still require licensing review and playback validation.",
    }
    (args.out / "discovery_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
