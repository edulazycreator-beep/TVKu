#!/usr/bin/env python3
"""Plans a stream validation run.

  filter : apply the owner's publication policy to discovery candidates
           python3 plan_validation.py filter --candidates review_candidates.csv \
               --policy publication_policy.json --out approved.csv --report policy_report.json
  shard  : keep streams that passed Tier A, order by priority, split into shards
           for the Tier B emulator jobs
           python3 plan_validation.py shard --approved approved.csv --tier-a tier_a.json \
               --shards 16 --per-shard 30 --out-dir shards

Every exclusion is recorded with its reason; nothing is silently dropped.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import sys
import urllib.parse

TIER_A_PASS = {"SUCCESS"}


def host_of(url: str) -> str:
    return (urllib.parse.urlsplit(url).hostname or "").lower()


def policy_decision(row: dict, policy: dict) -> str | None:
    """Returns None when the candidate may be validated, else the exclusion reason."""
    url = row["stream_url"]
    if not url.startswith("https://"):
        return "not https"
    host = host_of(url)
    if any(host == s or host.endswith("." + s) for s in policy["excluded_host_suffixes"]):
        return "excluded host (sanctions policy)"
    owners = row.get("owners", "")
    if any(p.lower() in owners.lower() for p in policy["excluded_owner_patterns"]):
        return "excluded owner (sanctions policy)"
    if any(re.search(p, row["channel_id"]) for p in policy["excluded_channel_id_patterns"]):
        return "excluded channel (sanctions policy)"
    lowered = url.lower()
    if any(re.search(rf"(?<![a-z0-9]){re.escape(m)}(?![a-z0-9])", lowered) for m in policy["excluded_url_markers"]):
        return "distribution-partner feed"
    kind = row["evidence_kind"]
    if kind in policy["approved_evidence_kinds"]:
        return None
    if kind == "brand_domain" and row["channel_id"] in policy["approved_brand_domain_channels"]:
        return None
    return f"evidence {kind} not approved"


def priority(row: dict, policy: dict) -> tuple:
    c = row["country"]
    cats = set(filter(None, row.get("categories", "").split(";")))
    if c in policy["priority_countries"]:
        tier = 0
    elif c in policy["secondary_countries"]:
        tier = 1
    elif "sports" in cats:
        tier = 2
    elif "news" in cats:
        tier = 3
    else:
        tier = 4
    return (tier, row.get("feed_is_main") != "True", row["country"], row["name"].lower(), row["stream_url"])


def cmd_filter(args) -> None:
    policy = json.loads(pathlib.Path(args.policy).read_text(encoding="utf-8"))
    rows = list(csv.DictReader(open(args.candidates, encoding="utf-8")))
    kept, reasons = [], {}
    for r in rows:
        why = policy_decision(r, policy)
        if why is None:
            kept.append(r)
        else:
            reasons.setdefault(why, []).append(f"{r['channel_id']}@{r['feed']}")
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["stream_url"])
        w.writeheader()
        w.writerows(kept)
    report = {"candidates": len(rows), "approved": len(kept),
              "excluded": {k: {"count": len(v), "examples": v[:15]} for k, v in sorted(reasons.items())}}
    pathlib.Path(args.report).write_text(json.dumps(report, indent=1), encoding="utf-8")
    summary = f"policy candidates={len(rows)} approved={len(kept)} " + " ".join(
        f"[{k}]={len(v)}" for k, v in sorted(reasons.items()))
    print(summary)
    print("::notice title=Policy::" + summary)


def cmd_shard(args) -> None:
    policy = json.loads(pathlib.Path(args.policy).read_text(encoding="utf-8"))
    rows = list(csv.DictReader(open(args.approved, encoding="utf-8")))
    tier_a = json.loads(pathlib.Path(args.tier_a).read_text(encoding="utf-8"))["results"]
    passed, seen = [], set()
    for r in sorted(rows, key=lambda r: priority(r, policy)):
        res = tier_a.get(r["stream_url"])
        if not res or res["outcome"] not in TIER_A_PASS or r["stream_url"] in seen:
            continue
        seen.add(r["stream_url"])
        passed.append({"url": r["stream_url"], "format": res.get("format") or "HLS"})
    cap = args.shards * args.per_shard
    selected = passed[:cap]
    out = pathlib.Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    n = max(1, min(args.shards, -(-len(selected) // args.per_shard))) if selected else 0
    shards = [selected[i::n] for i in range(n)] if n else []  # round-robin keeps priorities spread evenly
    for i, s in enumerate(shards):
        (out / f"streams-{i}.json").write_text(json.dumps(s), encoding="utf-8")
    matrix = {"shard": list(range(len(shards)))}
    (out / "matrix.json").write_text(json.dumps(matrix), encoding="utf-8")
    summary = f"tier B planned={len(selected)} of tier-A-passed={len(passed)} shards={len(shards)}"
    if len(passed) > cap:
        summary += f" (deferred {len(passed) - cap} lowest-priority streams to the next run)"
    print(summary)
    print("::notice title=Tier B plan::" + summary)


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("filter")
    f.add_argument("--candidates", required=True)
    f.add_argument("--policy", required=True)
    f.add_argument("--out", required=True)
    f.add_argument("--report", required=True)
    s = sub.add_parser("shard")
    s.add_argument("--approved", required=True)
    s.add_argument("--policy", required=True)
    s.add_argument("--tier-a", required=True)
    s.add_argument("--shards", type=int, default=16)
    s.add_argument("--per-shard", type=int, default=30)
    s.add_argument("--out-dir", required=True)
    args = ap.parse_args()
    {"filter": cmd_filter, "shard": cmd_shard}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
