"""Minimal, strict-enough M3U/M3U8 (IPTV extended) playlist parser.

Parses #EXTINF attributes (tvg-id, tvg-logo, group-title, http-user-agent,
http-referrer), #EXTVLCOPT options and the stream URL. Never executes or
evaluates anything from the playlist; every value is treated as plain text.
Standard library only.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, Iterator

_ATTR_RE = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')
_QUALITY_RE = re.compile(r"\((\d{3,4})p\)")
MAX_LINE = 8192


@dataclass
class PlaylistEntry:
    title: str
    url: str
    attributes: dict[str, str] = field(default_factory=dict)
    vlc_options: dict[str, str] = field(default_factory=dict)
    line_number: int = 0

    @property
    def tvg_id(self) -> str | None:
        return self.attributes.get("tvg-id") or None

    @property
    def channel_id(self) -> str | None:
        """iptv-org channel id without the feed suffix ("DWEnglish.de@SD" -> "DWEnglish.de")."""
        return self.tvg_id.split("@", 1)[0] if self.tvg_id else None

    @property
    def feed_id(self) -> str | None:
        return self.tvg_id.split("@", 1)[1] if self.tvg_id and "@" in self.tvg_id else None

    @property
    def advertised_height(self) -> int | None:
        """Resolution claimed in the title, e.g. "(720p)". A claim, not a measurement."""
        m = _QUALITY_RE.search(self.title)
        return int(m.group(1)) if m else None

    @property
    def required_headers(self) -> dict[str, str]:
        headers = {}
        ua = self.attributes.get("http-user-agent") or self.vlc_options.get("http-user-agent")
        ref = self.attributes.get("http-referrer") or self.vlc_options.get("http-referrer")
        if ua:
            headers["User-Agent"] = ua
        if ref:
            headers["Referer"] = ref
        return headers


class PlaylistError(ValueError):
    pass


def parse(lines: Iterable[str]) -> Iterator[PlaylistEntry]:
    """Yields entries in order. Malformed entries are skipped; a missing header raises."""
    pending: tuple[str, dict[str, str], int] | None = None
    vlc: dict[str, str] = {}
    saw_header = False
    for number, raw in enumerate(lines, start=1):
        line = raw.strip().lstrip("﻿")
        if not line:
            continue
        if len(line) > MAX_LINE:
            pending, vlc = None, {}
            continue
        if not saw_header:
            if not line.startswith("#EXTM3U"):
                raise PlaylistError("missing #EXTM3U header")
            saw_header = True
            continue
        if line.startswith("#EXTINF:"):
            head, _, title = line.partition(",")
            attrs = {k.lower(): v.strip() for k, v in _ATTR_RE.findall(head)}
            pending, vlc = (title.strip(), attrs, number), {}
        elif line.startswith("#EXTVLCOPT:"):
            key, _, value = line[len("#EXTVLCOPT:"):].partition("=")
            if key:
                vlc[key.strip().lower()] = value.strip()
        elif line.startswith("#"):
            continue
        else:
            if pending is not None:
                title, attrs, at = pending
                yield PlaylistEntry(title=title, url=line, attributes=attrs, vlc_options=dict(vlc), line_number=at)
            pending, vlc = None, {}
