#!/usr/bin/env python3
"""Aggregates Tier B (emulator playback) results into verified-streams.json.

Input: RESULT lines written by StreamPlaybackValidationTest (one JSON object
per line, collected from logcat by run_tier_b.sh), the policy-approved
candidate list (channel metadata) and the Tier A report.

A stream is VERIFIED only when every measured criterion holds:
outcome SUCCESS, >= 60 s played, video frames rendered, audio decoded and a
successful reconnect. The checks are repeated here so that a harness bug can
never publish a stream that did not meet them.

    python3 aggregate_results.py --results results/ --approved approved.csv \
        --tier-a tier_a.json --out verified-streams.json --report validation-report.json
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import pathlib
import sys


def is_verified(r: dict) -> bool:
    return (
        r.get("outcome") == "SUCCESS"
        and float(r.get("played_seconds", 0)) >= 60
        and int(r.get("video_frames", 0)) > 0
        and r.get("audio_decoded") is True
        and r.get("reconnect_success") is True
    )


def read_results(path: pathlib.Path) -> dict[str, dict]:
    files = sorted(path.rglob("*.jsonl")) if path.is_dir() else [path]
    out: dict[str, dict] = {}
    for f in files:
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(r, dict) and isinstance(r.get("url"), str):
                out[r["url"]] = r
    return out


def aggregate(results: dict[str, dict], approved: list[dict], planned: list[str], now: str) -> tuple[list[dict], dict]:
    meta: dict[str, dict] = {}
    for row in approved:
        meta.setdefault(row["stream_url"], row)
    verified, counts, per_stream = [], {}, []
    for url in planned:
        r = results.get(url)
        outcome = "NOT_RUN" if r is None else ("SUCCESS" if is_verified(r) else
                                                (r.get("outcome") if r.get("outcome") != "SUCCESS" else "INCOMPLETE"))
        counts[outcome] = counts.get(outcome, 0) + 1
        m = meta.get(url, {})
        per_stream.append({"url": url, "channel_id": m.get("channel_id"), "feed": m.get("feed"), "country": m.get("country"),
                           "name": m.get("name"), "outcome": outcome, "measured": r})
        if outcome == "SUCCESS" and m:
            verified.append({
                "channel_id": m["channel_id"], "feed": m["feed"], "feed_name": m.get("feed_name") or "",
                "feed_is_main": m.get("feed_is_main") == "True", "name": m["name"], "country": m["country"],
                "categories": [c for c in m.get("categories", "").split(";") if c],
                "languages": [c for c in m.get("languages", "").split(";") if c],
                "website": m.get("website") or None, "evidence_kind": m["evidence_kind"], "evidence": m.get("evidence"),
                "url": url, "format": r.get("format", "HLS"), "height": r.get("height"),
                "resolution": r.get("resolution"), "played_seconds": r["played_seconds"],
                "first_frame_ms": r.get("first_frame_ms"), "verified_at": now,
            })
    report = {"validated_at": now, "planned": len(planned), "counts": counts, "verified": len(verified),
              "verified_channels": len({(v["channel_id"], v["feed"]) for v in verified}),
              "verified_by_country": _count(v["country"] for v in verified), "streams": per_stream}
    return verified, report


def _count(values) -> dict:
    out: dict = {}
    for v in values:
        out[v] = out.get(v, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: (-kv[1], kv[0])))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True, type=pathlib.Path)
    ap.add_argument("--approved", required=True)
    ap.add_argument("--planned", required=True, type=pathlib.Path, help="directory with streams-*.json shard files")
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--report", required=True, type=pathlib.Path)
    args = ap.parse_args()
    planned = [s["url"] for f in sorted(args.planned.glob("streams-*.json")) for s in json.loads(f.read_text())]
    approved = list(csv.DictReader(open(args.approved, encoding="utf-8")))
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    verified, report = aggregate(read_results(args.results), approved, planned, now)
    args.out.write_text(json.dumps({"schema": 1, "validated_at": now, "streams": verified}, ensure_ascii=False, indent=1), encoding="utf-8")
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    summary = (f"tier B planned={report['planned']} verified_streams={report['verified']} "
               f"verified_channels={report['verified_channels']} " + " ".join(f"{k}={v}" for k, v in sorted(report["counts"].items())))
    top = " ".join(f"{k}:{v}" for k, v in list(report["verified_by_country"].items())[:15])
    print(summary)
    print("by country: " + top)
    print("::notice title=Tier B::" + summary + "%0Aby country: " + top)


if __name__ == "__main__":
    sys.exit(main())
