#!/usr/bin/env python3
"""Builds backend/supabase/seed/candidates_discovered.sql.

Inputs
  --candidates  review_candidates.csv from scripts/stream-discovery
  --curated     curated_sources.json (official YouTube channels, manual leads)
  --languages   iptv-org database_master_data_languages.csv (names for new codes)

Every channel is inserted with is_active = false and every stream with the
table defaults (DISCOVERED / UNREVIEWED), so nothing becomes visible in the
app. Re-running is safe (upserts on external ids / stream URL).
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

import pycountry

CATEGORY_MAP = {
    "news": "news", "business": "news", "legislative": "news", "weather": "news",
    "sports": "sports", "outdoor": "sports",
    "movies": "movies", "music": "music",
    "documentary": "documentary", "science": "documentary",
    "kids": "kids", "animation": "kids", "family": "kids",
    "education": "education", "religious": "religious", "general": "general",
    "entertainment": "entertainment", "comedy": "entertainment", "series": "entertainment",
    "lifestyle": "entertainment", "cooking": "entertainment", "travel": "entertainment",
    "culture": "entertainment", "classic": "entertainment", "auto": "entertainment",
    "relax": "entertainment", "shop": "entertainment",
}
PRIORITY = ["news", "sports", "kids", "education", "religious", "documentary", "music", "movies", "entertainment", "general"]
COUNTRIES = {c.alpha_2 for c in pycountry.countries} | {"XK"}


def q(v) -> str:
    if v is None or v == "":
        return "null"
    return "'" + str(v).replace("'", "''") + "'"


def slugify(value: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s)[:120].strip("-") or "channel"


def category(cats: str) -> str:
    mapped = {CATEGORY_MAP[c] for c in cats.split(";") if c in CATEGORY_MAP}
    return next((c for c in PRIORITY if c in mapped), "general")


def stream_format(url: str) -> str:
    path = url.split("?", 1)[0].lower()
    if path.endswith(".m3u8") or "/hls/" in path:
        return "HLS"
    if path.endswith(".mpd") or "/dash/" in path:
        return "DASH"
    return "UNKNOWN"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidates", required=True, type=pathlib.Path)
    ap.add_argument("--curated", required=True, type=pathlib.Path)
    ap.add_argument("--languages", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    lang_names = {r["code"]: r["name"] for r in csv.DictReader(args.languages.open(encoding="utf-8"))}
    channels: dict[tuple[str, str], dict] = {}
    streams: list[dict] = []
    skipped = 0

    for r in csv.DictReader(args.candidates.open(encoding="utf-8")):
        if r["country"] not in COUNTRIES:
            skipped += 1
            continue
        # Regional / variant feeds (e.g. TVRI Aceh) carry different programming: own channel.
        regional = r.get("feed_is_main") == "False" and r.get("feed") and r.get("feed_name")
        ext = f"{r['channel_id']}@{r['feed']}" if regional else r["channel_id"]
        name = f"{r['name']} {r['feed_name']}" if regional else r["name"]
        key = ("iptv-org", ext)
        channels.setdefault(key, {
            "name": name[:200], "slug": slugify(ext), "country": r["country"],
            "category": category(r["categories"]),
            "languages": [c for c in r["languages"].split(";") if re.fullmatch(r"[a-z]{3}", c)],
        })
        streams.append({
            "key": key, "url": r["stream_url"], "format": stream_format(r["stream_url"]),
            "source_name": f"iptv-org discovery ({r['evidence_kind']})",
            "source_url": r["website"] or "https://github.com/iptv-org/iptv",
            "embed_ref": None, "notes": r["evidence"],
        })

    for s in json.loads(args.curated.read_text(encoding="utf-8"))["sources"]:
        key = ("tvku-curated", s["key"])
        channels.setdefault(key, {
            "name": s["channel_name"], "slug": slugify(s["key"] + "-" + s["country"]), "country": s["country"],
            "category": s["category"], "languages": s.get("languages", []),
        })
        if s["kind"] == "youtube":
            ref = s["channel_ref"]
            url = f"https://www.youtube.com/{ref}" if ref.startswith("@") else f"https://www.youtube.com/channel/{ref}"
            streams.append({"key": key, "url": url, "format": "YOUTUBE_EMBED", "embed_ref": ref,
                            "source_name": "Official YouTube channel" + ("" if s.get("handle_confirmed") else " (handle unconfirmed)"),
                            "source_url": url, "notes": s["evidence"]})
        elif s["kind"] == "link":
            streams.append({"key": key, "url": s["url"], "format": "EXTERNAL_LINK", "embed_ref": None,
                            "platform": s["platform"], "source_name": f"Official platform: {s['platform']}",
                            "source_url": s["url"], "notes": s["evidence"]})
        else:
            streams.append({"key": key, "url": s["url"], "format": stream_format(s["url"]), "embed_ref": None,
                            "source_name": "Curated lead", "source_url": None, "notes": s["evidence"]})

    # Unique slugs.
    seen: dict[str, int] = {}
    for c in channels.values():
        base = c["slug"]
        n = seen.get(base, 0)
        seen[base] = n + 1
        if n:
            c["slug"] = f"{base}-{n + 1}"[:120]

    used_langs = sorted({l for c in channels.values() for l in c["languages"]})
    out = [
        "-- Generated by scripts/database-import/build_candidate_seed.py. Do not edit by hand.",
        "-- Discovery leads only: channels inactive, streams DISCOVERED / UNREVIEWED. Nothing here is published.",
        "begin;",
        "insert into public.languages (code, name) values",
        ",\n".join(f"  ({q(l)}, {q(lang_names.get(l, l))})" for l in used_langs),
        "on conflict (code) do nothing;",
        "",
        "insert into public.channels (channel_name, slug, country_code, category_id, is_active, external_source, external_id)",
        "select v.name, v.slug, v.country, c.id, false, v.src, v.ext from (values",
        ",\n".join(f"  ({q(c['name'])}, {q(c['slug'])}, {q(c['country'])}, {q(c['category'])}, {q(k[0])}, {q(k[1])})"
                   for k, c in channels.items()),
        ") as v(name, slug, country, category, src, ext)",
        "left join public.categories c on c.slug = v.category",
        "on conflict (external_source, external_id) do update set channel_name = excluded.channel_name;",
        "",
        "insert into public.channel_languages (channel_id, language_code)",
        "select ch.id, v.lang from (values",
        ",\n".join(f"  ({q(k[0])}, {q(k[1])}, {q(l)})" for k, c in channels.items() for l in c["languages"]),
        ") as v(src, ext, lang)",
        "join public.channels ch on ch.external_source = v.src and ch.external_id = v.ext",
        "on conflict do nothing;",
        "",
        "insert into public.streams (channel_id, stream_url, stream_format, source_name, source_url, embed_provider, embed_channel_ref, external_platform)",
        "select ch.id, v.url, v.fmt::public.stream_format, v.source_name, v.source_url, v.provider, v.ref, v.platform from (values",
        ",\n".join(
            f"  ({q(s['key'][0])}, {q(s['key'][1])}, {q(s['url'])}, {q(s['format'])}, {q(s['source_name'][:200])}, "
            f"{q(s['source_url'])}, {q('youtube' if s['embed_ref'] else None)}, {q(s['embed_ref'])}, {q(s.get('platform'))})"
            for s in streams),
        ") as v(src, ext, url, fmt, source_name, source_url, provider, ref, platform)",
        "join public.channels ch on ch.external_source = v.src and ch.external_id = v.ext",
        "on conflict do nothing;",
        "commit;",
        "",
    ]
    args.out.write_text("\n".join(out), encoding="utf-8")
    print(f"channels={len(channels)} streams={len(streams)} languages={len(used_langs)} skipped_unknown_country={skipped} -> {args.out}")


if __name__ == "__main__":
    main()
