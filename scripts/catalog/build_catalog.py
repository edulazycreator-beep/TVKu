#!/usr/bin/env python3
"""Builds the interim public channel catalog (catalog-v1.json).

Until the Supabase backend is online, the app downloads this file (published
by .github/workflows/catalog.yml as a GitHub Release asset) and caches it.

Only entries the product owner approved are published, and only two source
kinds that need no in-app stream validation:
  youtube_live  : broadcaster's official YouTube channel, played with YouTube's
                  official embedded player (live state is reported by the player)
  official_link : broadcaster's own platform page (Vidio, RCTI+, ...), opened
                  outside TVKu; never played in-app
HLS/DASH streams are excluded here until the Media3 playback validator runs.

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


def check_link(url: str, timeout: float = 15.0) -> dict:
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return {"http_status": r.status, "ok": 200 <= r.status < 400}
    except urllib.error.HTTPError as e:
        return {"http_status": e.code, "ok": False}
    except Exception as e:  # noqa: BLE001 - DNS, TLS, timeout: recorded as a failure, not hidden
        return {"http_status": None, "ok": False, "error": type(e).__name__}


def build(curated: dict, now: dt.datetime, link_checker=None) -> dict:
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

    ordered = sorted(channels.values(), key=lambda c: (
        0 if any(x["type"] == "youtube_live" for x in c["sources"]) else 1, c["category"] != "news", c["name"].lower()))
    return {
        "schema": SCHEMA,
        "generated_at": now.isoformat(),
        "publisher": "TVKu",
        "notice": "Interim catalog. Channels are official YouTube live channels or links to broadcasters' own platforms. "
                  "TVKu does not host or re-stream any video.",
        "channels": ordered,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--curated", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--check-links", action="store_true")
    args = ap.parse_args()
    catalog = build(json.loads(args.curated.read_text(encoding="utf-8")),
                    dt.datetime.now(dt.timezone.utc).replace(microsecond=0),
                    check_link if args.check_links else None)
    args.out.write_text(json.dumps(catalog, ensure_ascii=False, indent=1), encoding="utf-8")
    n_yt = sum(1 for c in catalog["channels"] for s in c["sources"] if s["type"] == "youtube_live")
    n_link = sum(1 for c in catalog["channels"] for s in c["sources"] if s["type"] == "official_link")
    failed = [(c["id"], s["last_check"]) for c in catalog["channels"] for s in c["sources"]
              if s.get("last_check") and not s["last_check"]["ok"]]
    summary = f"channels={len(catalog['channels'])} youtube_live={n_yt} official_link={n_link} link_check_failures={len(failed)}"
    print(summary)
    for cid, chk in failed:
        print(f"  link check failed: {cid} {chk}")
    print("::notice title=Catalog::" + summary + "".join(f"%0A{cid}: {chk}" for cid, chk in failed))


if __name__ == "__main__":
    sys.exit(main())
