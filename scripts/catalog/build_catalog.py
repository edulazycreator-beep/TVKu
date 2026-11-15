#!/usr/bin/env python3
"""Builds the interim public channel catalog (catalog-v1.json).

Until the Supabase backend is online, the app downloads this file (published
by .github/workflows/catalog.yml as a GitHub Release asset) and caches it.

Source kinds:
  stream        : HLS/DASH stream on the broadcaster's own server. Published ONLY
                  from verified-streams.json, i.e. streams that passed Tier B
                  playback validation (Stream validation workflow) and the
                  owner's publication policy. Never from raw discovery output.
  youtube_live  : broadcaster's official YouTube channel (owner-approved), played
                  with YouTube's official embedded player
  official_link : broadcaster's own platform page (Vidio, RCTI+, ...), opened
                  outside TVKu; never played in-app

With --check-links each official page is requested once and the HTTP result is
recorded (never invented). YouTube is not requested by this script.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

SCHEMA = 1
CHANNEL_ID = re.compile(r"^UC[A-Za-z0-9_-]{22}$")
SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
CATEGORIES = {"news", "sports", "entertainment", "movies", "music", "documentary", "kids", "education", "religious", "general"}
USER_AGENT = "TVKu-catalog-check/1 (+https://github.com/edulazycreator-beep/TVKu)"


class CatalogError(ValueError):
    pass


# iptv-org category -> TVKu category
CATEGORY_MAP = {
    "news": "news", "business": "news", "weather": "news", "legislative": "news",
    "sports": "sports", "outdoor": "sports", "auto": "sports",
    "movies": "movies", "series": "entertainment", "comedy": "entertainment", "entertainment": "entertainment",
    "family": "entertainment", "lifestyle": "entertainment", "cooking": "entertainment", "travel": "entertainment",
    "culture": "entertainment", "classic": "entertainment", "relax": "entertainment", "shop": "entertainment",
    "music": "music", "documentary": "documentary", "science": "documentary",
    "kids": "kids", "animation": "kids", "education": "education", "religious": "religious", "general": "general",
}
CATEGORY_RANK = ["news", "sports", "kids", "movies", "music", "documentary", "education", "religious", "entertainment", "general"]


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def stream_category(categories: list[str]) -> str:
    mapped = {CATEGORY_MAP[c] for c in categories if c in CATEGORY_MAP}
    return min(mapped, key=CATEGORY_RANK.index) if mapped else "general"


def add_verified_streams(channels: dict[str, dict], verified: dict) -> set[str]:
    """Adds streams that passed Tier B playback validation. Streams are never invented here.

    Returns the ids of regional (non-main) feeds, which are listed after main channels."""
    regional: set[str] = set()
    for v in verified.get("streams", []):
        if not v["url"].startswith("https://") or v.get("format") not in ("HLS", "DASH"):
            raise CatalogError(f"{v.get('channel_id')}: verified stream must be https HLS/DASH")
        name = v["name"] if v.get("feed_is_main", True) or not v.get("feed_name") else f"{v['name']} {v['feed_name']}"
        cid = f"{slug(name)}-{v['country'].lower()}"
        if not SLUG.match(cid):
            continue
        ch = channels.setdefault(cid, {
            "id": cid, "name": name, "country": v["country"], "category": stream_category(v.get("categories", [])),
            "languages": v.get("languages", []), "sources": [],
        })
        if not v.get("feed_is_main", True):
            regional.add(cid)
        if any(s.get("url") == v["url"] for s in ch["sources"]):
            continue
        src = {"type": "stream", "url": v["url"], "format": v["format"], "verified_at": v["verified_at"],
               "evidence": v["evidence_kind"]}
        if isinstance(v.get("height"), int) and 0 < v["height"] <= 8192:
            src["height"] = v["height"]
        # Streams go before YouTube/links: the app plays the first playable source.
        n_streams = sum(1 for s in ch["sources"] if s["type"] == "stream")
        ch["sources"].insert(n_streams, src)
    return regional


def check_link(url: str, timeout: float = 15.0) -> dict:
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return {"http_status": r.status, "ok": 200 <= r.status < 400}
    except urllib.error.HTTPError as e:
        return {"http_status": e.code, "ok": False}
    except Exception as e:  # noqa: BLE001 - DNS, TLS, timeout: recorded as a failure, not hidden
        return {"http_status": None, "ok": False, "error": type(e).__name__}


def build(curated: dict, now: dt.datetime, link_checker=None, verified: dict | None = None) -> dict:
    channels: dict[str, dict] = {}
    for s in curated["sources"]:
        if s.get("approved") is not True or s["kind"] not in ("youtube", "link"):
            continue
        cid = f"{s['key']}-{s['country'].lower()}"
        if not SLUG.match(cid):
            raise CatalogError(f"bad channel id {cid}")
        if s["category"] not in CATEGORIES:
            raise CatalogError(f"{cid}: unknown category {s['category']}")
        ch = channels.setdefault(cid, {
            "id": cid, "name": s["channel_name"], "country": s["country"], "category": s["category"],
            "languages": s.get("languages", []), "sources": [],
        })
        if s["kind"] == "youtube":
            if not CHANNEL_ID.match(s["channel_ref"]):
                raise CatalogError(f"{cid}: youtube source needs a UC channel id")
            ch["sources"].append({
                "type": "youtube_live", "youtube_channel_id": s["channel_ref"],
                "official_page": f"https://www.youtube.com/{s['handle']}" if s.get("handle") else None,
            })
        else:
            if not s["url"].startswith("https://"):
                raise CatalogError(f"{cid}: links must be https")
            src = {"type": "official_link", "url": s["url"], "platform": s["platform"]}
            if link_checker:
                src["last_check"] = {"checked_at": now.isoformat(), **link_checker(s["url"])}
            ch["sources"].append(src)

    regional = add_verified_streams(channels, verified) if verified else set()

    def rank(c: dict) -> int:
        types = {x["type"] for x in c["sources"]}
        return 0 if "stream" in types else 1 if "youtube_live" in types else 2

    ordered = sorted(channels.values(), key=lambda c: (
        rank(c), c["id"] in regional, CATEGORY_RANK.index(c["category"]) if c["category"] in CATEGORY_RANK else 99,
        c["name"].lower()))
    return {
        "schema": SCHEMA,
        "generated_at": now.isoformat(),
        "publisher": "TVKu",
        "notice": "Interim catalog. Streams come from broadcasters' own servers and passed TVKu's playback validation; "
                  "other channels are official YouTube live channels or links to broadcasters' own platforms. "
                  "TVKu does not host or re-stream any video. Broadcasters can request removal.",
        "channels": ordered,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--curated", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--check-links", action="store_true")
    ap.add_argument("--verified-streams", type=pathlib.Path, help="verified-streams.json from the Stream validation workflow")
    args = ap.parse_args()
    verified = None
    if args.verified_streams and args.verified_streams.exists():
        verified = json.loads(args.verified_streams.read_text(encoding="utf-8"))
    catalog = build(json.loads(args.curated.read_text(encoding="utf-8")),
                    dt.datetime.now(dt.timezone.utc).replace(microsecond=0),
                    check_link if args.check_links else None, verified)
    args.out.write_text(json.dumps(catalog, ensure_ascii=False, indent=1), encoding="utf-8")
    n_yt = sum(1 for c in catalog["channels"] for s in c["sources"] if s["type"] == "youtube_live")
    n_link = sum(1 for c in catalog["channels"] for s in c["sources"] if s["type"] == "official_link")
    n_stream = sum(1 for c in catalog["channels"] for s in c["sources"] if s["type"] == "stream")
    n_stream_ch = sum(1 for c in catalog["channels"] if any(s["type"] == "stream" for s in c["sources"]))
    failed = [(c["id"], s["last_check"]) for c in catalog["channels"] for s in c["sources"]
              if s.get("last_check") and not s["last_check"]["ok"]]
    summary = (f"channels={len(catalog['channels'])} verified_streams={n_stream} (channels {n_stream_ch}) "
               f"youtube_live={n_yt} official_link={n_link} link_check_failures={len(failed)}")
    print(summary)
    for cid, chk in failed:
        print(f"  link check failed: {cid} {chk}")
    print("::notice title=Catalog::" + summary + "".join(f"%0A{cid}: {chk}" for cid, chk in failed))


if __name__ == "__main__":
    sys.exit(main())
