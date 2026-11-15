"""Checks an official YouTube channel for an embeddable live broadcast.

Uses only the official YouTube Data API v3 (no page scraping, no stream
extraction). Quota per check: channels.list (1) + playlistItems.list (1) +
videos.list (1) = 3 units; the optional search fallback costs 100 units and
is off by default.

The result maps 1:1 to public.record_stream_check(..., 'EMBED_STATUS_CHECK', ...).
"""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable

API = "https://www.googleapis.com/youtube/v3/"
CHANNEL_ID_RE = re.compile(r"^UC[A-Za-z0-9_-]{22}$")
HANDLE_RE = re.compile(r"^@[A-Za-z0-9._-]{3,100}$")
VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")

Fetch = Callable[[str], tuple[int, dict]]


class QuotaExceeded(RuntimeError):
    """Stop the run; do not record failures caused by our own quota."""


@dataclass
class EmbedCheckResult:
    outcome: str                      # SUCCESS | TRANSIENT_FAILURE | PERMANENT_FAILURE | GEO_BLOCKED | UNSUPPORTED
    channel_id: str | None = None
    video_id: str | None = None
    embeddable: bool | None = None
    error: str | None = None
    response_status: int | None = None

    def rpc_params(self, stream_id: str, runner_id: str, vantage: str | None) -> dict:
        return {
            "p_stream_id": stream_id,
            "p_test_type": "EMBED_STATUS_CHECK",
            "p_outcome": self.outcome,
            "p_response_status": self.response_status,
            "p_geo_vantage": vantage,
            "p_runner_id": runner_id,
            "p_error_message": self.error,
            "p_embed_channel_id": self.channel_id,
            "p_embed_video_id": self.video_id,
            "p_embeddable": self.embeddable,
        }


def http_fetch(url: str, timeout: float = 20.0) -> tuple[int, dict]:
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "TVKu-validator/1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read().decode("utf-8"))
        except Exception:  # noqa: BLE001
            body = {}
        return e.code, body


class YouTubeApi:
    def __init__(self, api_key: str, fetch: Fetch = http_fetch):
        if not api_key:
            raise ValueError("YOUTUBE_API_KEY is required")
        self._key = api_key
        self._fetch = fetch

    def get(self, endpoint: str, **params) -> tuple[int, dict]:
        params["key"] = self._key
        status, body = self._fetch(API + endpoint + "?" + urllib.parse.urlencode(params))
        if status == 403:
            reasons = {e.get("reason") for e in body.get("error", {}).get("errors", [])}
            if reasons & {"quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded"}:
                raise QuotaExceeded(", ".join(sorted(r for r in reasons if r)))
        return status, body


def _blocked_for(video: dict, vantage: str | None) -> bool:
    if not vantage:
        return False
    rr = video.get("contentDetails", {}).get("regionRestriction") or {}
    if vantage in rr.get("blocked", []):
        return True
    allowed = rr.get("allowed")
    return allowed is not None and vantage not in allowed


def check_live(api: YouTubeApi, channel_ref: str, pinned_channel_id: str | None = None,
               vantage: str | None = None, allow_search_fallback: bool = False) -> EmbedCheckResult:
    if CHANNEL_ID_RE.match(channel_ref):
        status, body = api.get("channels", part="id,contentDetails", id=channel_ref)
    elif HANDLE_RE.match(channel_ref):
        status, body = api.get("channels", part="id,contentDetails", forHandle=channel_ref)
    else:
        return EmbedCheckResult("PERMANENT_FAILURE", error=f"invalid channel reference {channel_ref!r}")
    if status >= 500:
        return EmbedCheckResult("TRANSIENT_FAILURE", error=f"channels.list HTTP {status}", response_status=status)
    if status != 200 or not body.get("items"):
        return EmbedCheckResult("PERMANENT_FAILURE", error=f"channel {channel_ref} not found", response_status=status)

    channel = body["items"][0]
    channel_id = channel["id"]
    if pinned_channel_id and channel_id != pinned_channel_id:
        return EmbedCheckResult("PERMANENT_FAILURE", channel_id=channel_id,
                                error=f"{channel_ref} now resolves to {channel_id}, expected {pinned_channel_id}")
    uploads = channel.get("contentDetails", {}).get("relatedPlaylists", {}).get("uploads")

    video_ids: list[str] = []
    if uploads:
        status, body = api.get("playlistItems", part="contentDetails", playlistId=uploads, maxResults=20)
        if status >= 500:
            return EmbedCheckResult("TRANSIENT_FAILURE", channel_id=channel_id, error=f"playlistItems.list HTTP {status}")
        video_ids = [i["contentDetails"]["videoId"] for i in body.get("items", []) if "contentDetails" in i]

    live = _live_videos(api, video_ids, channel_id)
    if not live and allow_search_fallback:
        status, body = api.get("search", part="id", channelId=channel_id, eventType="live", type="video", maxResults=5)
        live = _live_videos(api, [i["id"]["videoId"] for i in body.get("items", [])], channel_id)
    if not live:
        return EmbedCheckResult("TRANSIENT_FAILURE", channel_id=channel_id, error="no live broadcast at check time")

    # A 24/7 channel's main stream is the one that has been running longest.
    live.sort(key=lambda v: v["liveStreamingDetails"]["actualStartTime"])
    video = live[0]
    vid = video["id"]
    if not video.get("status", {}).get("embeddable", False):
        return EmbedCheckResult("UNSUPPORTED", channel_id=channel_id, video_id=vid, embeddable=False,
                                error="embedding disabled by channel owner")
    if video.get("status", {}).get("privacyStatus") != "public":
        return EmbedCheckResult("UNSUPPORTED", channel_id=channel_id, error="live broadcast is not public")
    if _blocked_for(video, vantage):
        return EmbedCheckResult("GEO_BLOCKED", channel_id=channel_id, video_id=vid, embeddable=True,
                                error=f"live video region-restricted for {vantage}")
    return EmbedCheckResult("SUCCESS", channel_id=channel_id, video_id=vid, embeddable=True, response_status=200)


def _live_videos(api: YouTubeApi, ids: list[str], channel_id: str) -> list[dict]:
    ids = [i for i in ids if VIDEO_ID_RE.match(i)]
    if not ids:
        return []
    status, body = api.get("videos", part="snippet,status,liveStreamingDetails,contentDetails", id=",".join(ids[:50]))
    if status != 200:
        return []
    return [
        v for v in body.get("items", [])
        if v.get("snippet", {}).get("liveBroadcastContent") == "live"
        and v.get("snippet", {}).get("channelId") == channel_id
        and v.get("liveStreamingDetails", {}).get("actualStartTime")
        and not v.get("liveStreamingDetails", {}).get("actualEndTime")
    ]
