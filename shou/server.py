#!/usr/bin/env python3
"""Shou — phone-controlled AniList "Currently Watching" launcher.

A long-running Flask + SocketIO server that is the single brain for Shou:
  * fetches your AniList "Currently Watching" list (public username, no auth),
  * shows a live carousel UI in a browser kiosk (which it launches/focuses itself),
  * serves a touch-first phone web-remote (PWA) that mirrors the kiosk live,
  * launches episodes through ani-cli/mpv (fullscreen), with an anipy fallback,
  * optionally marks episodes watched back on AniList as you finish them.

Control endpoints are reachable from loopback without auth (the kiosk page) and require
a shared-secret token (?k=…) from any networked client (the phone web-remote).
"""

import base64
import hmac
import json
import math
import os
import difflib
import re
import shlex
import secrets
import signal
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import wave
from functools import wraps
from pathlib import Path
from urllib.parse import urljoin

import requests
from flask import (
    Flask,
    Response,
    abort,
    jsonify,
    render_template,
    request,
    send_from_directory,
)
from flask_socketio import SocketIO

# --------------------------------------------------------------------------- #
# Config
# --------------------------------------------------------------------------- #
CONFIG_DIR = Path.home() / ".config" / "shou"
CONFIG_FILE = CONFIG_DIR / "shou.conf"
FF_PROFILE = CONFIG_DIR / "ff-profile"
FF_PIDFILE = CONFIG_DIR / "shou-ff.pid"
ANILIST_URL = "https://graphql.anilist.co"


def load_config() -> dict:
    """Parse the shell-style KEY="value" shou.conf."""
    cfg = {"ANILIST_USER": "", "PORT": "4100", "QUALITY": "1080p", "REMOTE_TOKEN": "",
           "ANILIST_TOKEN": "", "WATCHED_PERCENT": "90"}
    if CONFIG_FILE.exists():
        for raw in CONFIG_FILE.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            cfg[key.strip()] = value.strip().strip('"').strip("'")
    return cfg


CONFIG = load_config()
PORT = int(CONFIG.get("PORT") or 4100)


def reload_config() -> None:
    """Re-read shou.conf (called on /open) so ANILIST_USER/QUALITY changes apply
    without restarting the daemon. PORT and the token stay fixed for the session."""
    global CONFIG
    fresh = load_config()
    fresh["REMOTE_TOKEN"] = TOKEN
    CONFIG = fresh


def ensure_token() -> str:
    """Return the remote shared secret, generating + persisting one on first run."""
    token = (CONFIG.get("REMOTE_TOKEN") or "").strip()
    if token:
        return token
    token = secrets.token_urlsafe(24)
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    with CONFIG_FILE.open("a") as fh:
        fh.write(f'\n# Auto-generated shared secret for the phone web-remote.\nREMOTE_TOKEN="{token}"\n')
    CONFIG["REMOTE_TOKEN"] = token
    return token


TOKEN = ensure_token()

# --------------------------------------------------------------------------- #
# Shared runtime state
# --------------------------------------------------------------------------- #
STATE = {
    "view": "loading",   # loading | grid | sequel | playing | empty | error | search | detail
    "list": "watching",  # which list/mode is shown: watching | planned | search
    "items": [],          # list of card dicts
    "cursor": 0,
    "sequel": None,       # {"finished": str, "sequel_title": str}
    "playing": None,      # {"title": str, "episode": int}
    "rating": None,       # series-complete rating prompt (see show_rating)
    "message": "",
}

# UI list modes -> AniList MediaListStatus + a human label for messages/empty states.
LIST_STATUS = {"watching": "CURRENT", "planned": "PLANNING"}
LIST_LABEL = {"watching": "Currently Watching", "planned": "Plan to Watch"}
STATE_LOCK = threading.Lock()

# "Search new" mode: browse all of AniList, focus a result, then set its list status.
# A single shared query string is the source of truth so the phone's on-screen keyboard
# and the kiosk's physical keyboard both edit the same text, kept in sync via broadcast.
SEARCH = {
    "query": "",       # current search text
    "genres": [],       # active genre filters (subset of GENRES)
    "results": [],      # list of result dicts (see build_result)
    "cursor": 0,        # which result is focused (vertical list)
    "detail": None,     # focused-anime detail dict (see build_detail) or None
    "seasons": [],      # the focused anime's franchise chain (detail dicts), chronological
    "seasonIdx": 0,     # which season in `seasons` is focused (== detail)
    "busy": False,      # a search / detail / status write is in flight
    "gen": 0,           # debounce generation — only the newest keystroke's query applies
    "page": 1,          # last AniList page fetched into results
    "hasMore": False,   # AniList reports a further page (infinite scroll)
    "loadingMore": False,  # a load-more append is in flight
}
SEASON_MAX = 16            # cap franchise-chain traversal (prequel/sequel hops)
# Relations that chain a franchise into ordered "seasons/parts".
SEASON_RELATIONS = {"PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY"}
SEARCH_MAX = 14            # results fetched per page (appended as you scroll)
SEARCH_LOAD_AHEAD = 5      # start loading the next page this many rows from the end
# The phone's filter grid is AniList's 18 non-adult genres (broad buckets) followed by a
# curated set of its most recognizable *tags* (finer themes), ordered famous -> niche.
# Genres filter via genre_in, tags via tag_in; both are AND, so picking several narrows.
GENRES = [
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance",
    "Sci-Fi", "Slice of Life", "Supernatural", "Mystery", "Horror",
    "Psychological", "Sports", "Mecha", "Music", "Ecchi", "Thriller",
    "Mahou Shoujo",
]
TAGS = [
    "Shounen", "Seinen", "Shoujo", "Josei",
    "Isekai", "Super Power", "Magic", "Mythology", "Superhero", "Cultivation",
    "Wuxia", "Henshin", "Kaiju",
    "School", "School Club", "College",
    "Martial Arts", "Swordplay", "Guns", "Espionage", "Battle Royale",
    "Military", "Police", "Mafia", "Yakuza", "Assassins", "Gangs", "Cult",
    "Historical", "Medieval", "Dystopian",
    "Space", "Space Opera", "Cyberpunk", "Steampunk", "Post-Apocalyptic",
    "Virtual World", "Time Loop",
    "Survival", "Death Game", "Revenge", "Gore", "War", "Crime", "Politics",
    "Coming of Age", "Found Family", "Memory Manipulation",
    "Demons", "Vampire", "Witch", "Ninja", "Samurai", "Gods", "Dragons",
    "Robots", "Aliens", "Zombie", "Werewolf", "Elf", "Maids", "Idol",
    "Detective", "Pirates", "Tsundere", "Yandere",
    "Love Triangle", "Female Harem", "Yuri", "Boys' Love",
    "Iyashikei", "Cute Girls Doing Cute Things", "Cute Boys Doing Cute Things",
    "Super Robot", "Real Robot", "Tokusatsu",
    "Video Games", "E-Sports", "Band", "Food", "Fashion",
    "Parody", "Slapstick", "Satire",
]
GENRES_SET = set(GENRES)
TAGS_SET = set(TAGS)
FILTERS = GENRES + TAGS          # full ordered list the phone offers
FILTERS_SET = GENRES_SET | TAGS_SET
SEARCH_DEBOUNCE = 0.28     # secs after the last keystroke before querying AniList
# AniList MediaListStatus values the phone can set, in display order. "REMOVE" deletes.
SEARCH_STATUSES = [
    ("CURRENT", "Watching"),
    ("PLANNING", "Plan to Watch"),
    ("COMPLETED", "Completed"),
    ("PAUSED", "Paused"),
    ("DROPPED", "Dropped"),
]
STATUS_LABELS = {  # short labels for status pills anywhere they're shown
    "CURRENT": "Watching", "PLANNING": "Planned", "COMPLETED": "Completed",
    "PAUSED": "Paused", "DROPPED": "Dropped", "REPEATING": "Rewatching",
}
FORMAT_LABELS = {
    "TV": "TV", "TV_SHORT": "TV Short", "MOVIE": "Movie", "OVA": "OVA",
    "ONA": "ONA", "SPECIAL": "Special", "MUSIC": "Music",
}

# Tracks the in-flight playback launch so we can detect "ani-cli found no source"
# and report it instead of leaving the UI stuck on the "playing" screen.
PLAYBACK = {"gen": 0, "proc": None}
# A finished series (final episode completed) that's awaiting its rating page. Set the
# moment completion is detected — while mpv may still be open — so that whatever closes
# the player (a clean EOF, or pressing Back at 90%) can pop it and show the rating.
PENDING_RATING = {"info": None}
ANI_LOG = CONFIG_DIR / "ani-cli-last.log"
# Unique cmdline marker on every mpv WE launch (an mpv IPC socket path). It lets us
# stop *only* Shou's player and never touch other mpv instances — e.g. mpvpaper
# live wallpapers. Also doubles as an IPC control socket for future use.
MPV_IPC = str(Path(os.environ.get("XDG_RUNTIME_DIR") or "/tmp") / "shou-mpv.sock")
# An mpv key binding (written to this conf) lets you "throw" what's playing to your
# phone with one keypress: `t` fires `script-message shou-throw`, which mpv surfaces on
# the JSON IPC as a client-message — the playback watcher already reads that socket, so
# it picks it up and hands the stream off to the phone. No global hotkey daemon needed.
MPV_INPUT_CONF = CONFIG_DIR / "mpv-input.conf"
MPV_THROW_KEY = "t"
# What ani-cli should run as its player: fullscreen mpv tagged with our marker + throw key.
ANI_CLI_PLAYER = f"mpv --fs --input-ipc-server={MPV_IPC} --input-conf={MPV_INPUT_CONF}"


def notify(title: str, body: str, *, urgency: str = "normal", timeout: int = 3000) -> None:
    """Best-effort desktop notification. Linux uses notify-send; macOS uses
    osascript. Never raises if the tool is missing — on a headless/minimal Linux
    box (no libnotify) or on macOS, a notification is a nicety, not a hard path,
    so its absence must not crash the playback / rating / error handlers."""
    try:
        if sys.platform == "darwin" and shutil.which("osascript"):
            # AppleScript has no urgency/timeout; quote-safe the two strings.
            t = title.replace('\\', '').replace('"', "'")
            b = body.replace('\\', '').replace('"', "'")
            subprocess.run(
                ["osascript", "-e", f'display notification "{b}" with title "{t}"'],
                check=False,
            )
        elif shutil.which("notify-send"):
            subprocess.run(
                ["notify-send", title, body, "-u", urgency, "-t", str(timeout)],
                check=False,
            )
    except OSError:
        pass
# How long ani-cli gets to actually start mpv before we call it a failed source.
LAUNCH_TIMEOUT = 30.0
# Backup scrapers (anipy_api providers) tried when ani-cli finds no source, in order.
# animekai is a genuinely different source; allanime is the same site ani-cli uses but
# via a different client + smarter result/episode matching (fixes wrong-entry failures).
FALLBACK_PROVIDERS = ["animekai", "allanime"]

# "Throw to phone": the stream currently on the PC, re-resolved for mobile playback and
# served to the phone through the proxy below. Only one cast is live at a time; `gen`
# invalidates a stale resolve if you throw again or throw back.
CAST = {
    "active": False,     # a throw is in progress or live
    "resolving": False,  # still scraping a mobile stream
    "url": "",           # raw upstream stream URL (proxied to the phone, never sent raw)
    "referer": "",       # Referer the CDN requires (applied by the proxy)
    "subtitle": "",      # external subtitle URL, if the source has one
    "kind": "",          # "hls" (.m3u8 -> needs hls.js) or "file" (direct mp4)
    "position": 0.0,     # seconds to resume at on the phone
    "title": "",
    "episode": 0,
    "error": "",
    "gen": 0,
}

# allanime API ani-cli searches (replicated here so we can pre-pick the right result
# instead of blindly taking ani-cli's first hit). Same endpoint/agent/query ani-cli uses.
ALLANIME_API = "https://api.allanime.day/api"
ALLANIME_REFERER = "https://allmanga.to"
ALLANIME_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) "
                  "Gecko/20100101 Firefox/121.0")
ALLANIME_SEARCH_GQL = (
    "query( $search: SearchInput $limit: Int $page: Int "
    "$translationType: VaildTranslationTypeEnumType "
    "$countryOrigin: VaildCountryOriginEnumType ) { shows( search: $search "
    "limit: $limit page: $page translationType: $translationType "
    "countryOrigin: $countryOrigin ) { edges { _id name availableEpisodes "
    "__typename } }}"
)

# Continue-watching history: episodes left mid-way get a resume point (episode +
# playback position) persisted here, so the phone can pick one and resume on the PC.
HISTORY_FILE = CONFIG_DIR / "history.json"
HISTORY_LOCK = threading.Lock()
HISTORY_MAX = 24            # cap stored resume points (newest kept)
RESUME_MIN_SECONDS = 30     # ignore trivially-short positions (skipped intros etc.)
RESUME_REWIND_SECONDS = 5   # resume a few seconds before you stopped, for context

# Series-complete rating: when the final episode finishes and mpv closes, the kiosk shows a
# cinematic rating page driven from the phone (◀ ▶ adjust, ● confirm). The score is on the
# user's AniList scale; the kiosk also draws the proportionate star equivalent.
SCORE_FORMATS = {  # AniList scoreFormat -> {min, max, step, default}
    "POINT_100":        {"min": 0, "max": 100, "step": 5,   "default": 70},
    "POINT_10_DECIMAL": {"min": 0, "max": 10,  "step": 0.5, "default": 7.0},
    "POINT_10":         {"min": 0, "max": 10,  "step": 1,   "default": 7},
    "POINT_5":          {"min": 0, "max": 5,   "step": 1,   "default": 4},
    "POINT_3":          {"min": 0, "max": 3,   "step": 1,   "default": 2},
}
FINISH_SOUND = CONFIG_DIR / "finish.wav"    # synthesized once; overridable via FINISH_SOUND

app = Flask(__name__)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")


# --------------------------------------------------------------------------- #
# Auth (loopback-exempt token gate)
# --------------------------------------------------------------------------- #
LOCAL_ADDRS = {"127.0.0.1", "::1", "::ffff:127.0.0.1"}


def authorized() -> bool:
    if (request.remote_addr or "") in LOCAL_ADDRS:
        return True
    supplied = request.args.get("k") or request.headers.get("X-Shou-Token", "")
    return bool(supplied) and hmac.compare_digest(supplied, TOKEN)


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not authorized():
            abort(403)
        return fn(*args, **kwargs)

    return wrapper


# --------------------------------------------------------------------------- #
# AniList
# --------------------------------------------------------------------------- #
LIST_QUERY = """
query ($name: String, $status: MediaListStatus) {
  MediaListCollection(userName: $name, type: ANIME, status_in: [$status]) {
    lists {
      entries {
        progress
        updatedAt
        media {
          id
          title { romaji english }
          episodes
          status
          nextAiringEpisode { episode }
          coverImage { extraLarge large color }
          bannerImage
          relations {
            edges {
              relationType
              node {
                id
                format
                type
                title { romaji english }
              }
            }
          }
        }
      }
    }
  }
}
"""


def _title(title_obj: dict) -> str:
    """Display title — prefer english, fall back to romaji."""
    if not title_obj:
        return "Unknown"
    return title_obj.get("english") or title_obj.get("romaji") or "Unknown"


def _search_title(title_obj: dict) -> str:
    """ani-cli search title — prefer romaji (allanime matches it best)."""
    if not title_obj:
        return "Unknown"
    return title_obj.get("romaji") or title_obj.get("english") or "Unknown"


def fetch_list(mode: str = "watching") -> list:
    """Return the user's entries for the given list mode (raw AniList entry dicts).

    mode is one of LIST_STATUS keys ("watching" -> CURRENT, "planned" -> PLANNING).
    """
    user = CONFIG.get("ANILIST_USER", "").strip()
    if not user or user == "CHANGE_ME":
        raise RuntimeError("ANILIST_USER is not set in ~/.config/shou/shou.conf")
    status = LIST_STATUS.get(mode, "CURRENT")

    resp = requests.post(
        ANILIST_URL,
        json={"query": LIST_QUERY, "variables": {"name": user, "status": status}},
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        timeout=15,
    )
    resp.raise_for_status()
    payload = resp.json()
    if "errors" in payload:
        raise RuntimeError(payload["errors"][0].get("message", "AniList error"))

    collection = payload["data"]["MediaListCollection"]
    if not collection:
        return []
    entries = []
    for lst in collection["lists"]:
        entries.extend(lst["entries"])
    # Most recently updated first — matches "what I'm actively watching".
    entries.sort(key=lambda e: e.get("updatedAt") or 0, reverse=True)
    return entries


def last_released_episode(media: dict) -> int | None:
    """Highest episode number that has actually aired/released."""
    airing = media.get("nextAiringEpisode")
    if airing and airing.get("episode"):
        return max(airing["episode"] - 1, 0)
    return media.get("episodes")


def find_sequel(media: dict) -> dict | None:
    """Return the sequel media node if AniList lists one, else None."""
    relations = (media.get("relations") or {}).get("edges") or []
    for edge in relations:
        if edge.get("relationType") == "SEQUEL":
            node = edge.get("node") or {}
            if node.get("type") == "ANIME":
                return node
    return None


def episode_decision(entry: dict) -> dict:
    """Decide what to do when this entry is selected.

    Returns one of:
      {"action": "play", "title": str, "episode": int}
      {"action": "sequel", "finished": str, "sequel_title": str}
    """
    media = entry["media"]
    progress = entry.get("progress") or 0
    title = _title(media["title"])           # for display
    search = _search_title(media["title"])   # for ani-cli
    nxt = progress + 1
    last = last_released_episode(media)

    # Still episodes left to watch (or we simply don't know the total) -> play next.
    if last is None or nxt <= last:
        return {"action": "play", "title": title, "search": search, "episode": nxt}

    # Caught up. Recommend a sequel if there is one.
    sequel = find_sequel(media)
    if sequel:
        return {
            "action": "sequel",
            "finished": title,
            "sequel_title": _title(sequel["title"]),
            "sequel_search": _search_title(sequel["title"]),
            "sequel_id": sequel.get("id"),
            "sequel_total": sequel.get("episodes"),  # usually None (not in relations query)
        }

    # Caught up, no sequel -> play the latest released episode.
    return {"action": "play", "title": title, "search": search, "episode": max(last, 1)}


def build_card(entry: dict) -> dict:
    """Flatten an AniList entry into what the UI needs."""
    media = entry["media"]
    progress = entry.get("progress") or 0
    total = media.get("episodes")
    last = last_released_episode(media)
    cover = media.get("coverImage") or {}
    if total:
        ep_text = f"Ep {progress} / {total}"
    elif last:
        ep_text = f"Ep {progress} · {last} aired"
    else:
        ep_text = f"Ep {progress}"
    return {
        "id": media["id"],
        "title": _title(media["title"]),
        "progress": progress,
        "total": total,
        "available": last,
        "episodeText": ep_text,
        "cover": cover.get("extraLarge") or cover.get("large") or "",
        "color": cover.get("color") or "#1f2233",
        "banner": media.get("bannerImage") or "",
        "caughtUp": bool(last and progress >= last),
    }


# --------------------------------------------------------------------------- #
# "Search new" — query all of AniList, focus a result, set its list status
# --------------------------------------------------------------------------- #
SEARCH_QUERY = """
query ($search: String, $perPage: Int, $page: Int, $genres: [String], $tags: [String], $sort: [MediaSort]) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage }
    media(search: $search, genre_in: $genres, tag_in: $tags, type: ANIME, sort: $sort, isAdult: false) {
      id
      title { romaji english }
      format
      episodes
      status
      seasonYear
      averageScore
      coverImage { extraLarge large color }
      bannerImage
      mediaListEntry { status }
    }
  }
}
"""

DETAIL_QUERY = """
query ($id: Int) {
  Media(id: $id, type: ANIME) {
    id
    title { romaji english }
    format
    episodes
    duration
    status
    seasonYear
    startDate { year month }
    genres
    averageScore
    description(asHtml: false)
    coverImage { extraLarge large color }
    bannerImage
    studios(isMain: true) { nodes { name } }
    mediaListEntry { id status score progress }
    relations { edges { relationType node { id type format } } }
  }
}
"""


def _public_post(query: str, variables: dict) -> dict:
    """GraphQL call that uses the token when present (so the viewer's per-anime
    mediaListEntry status comes back), and falls back to anonymous otherwise."""
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    token = anilist_token()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.post(ANILIST_URL, json={"query": query, "variables": variables},
                         headers=headers, timeout=15)
    resp.raise_for_status()
    payload = resp.json()
    if "errors" in payload:
        raise RuntimeError(payload["errors"][0].get("message", "AniList error"))
    return payload["data"]


def _fmt_label(fmt: str | None) -> str:
    return FORMAT_LABELS.get(fmt or "", (fmt or "").replace("_", " ").title())


def build_result(media: dict) -> dict:
    """Flatten an AniList search hit into the compact card the search list shows."""
    art = media.get("coverImage") or {}
    entry = media.get("mediaListEntry") or {}
    return {
        "id": media["id"],
        "title": _title(media["title"]),
        "search": _search_title(media["title"]),
        "format": _fmt_label(media.get("format")),
        "episodes": media.get("episodes"),
        "year": media.get("seasonYear"),
        "score": media.get("averageScore"),
        "cover": art.get("extraLarge") or art.get("large") or "",
        "color": art.get("color") or "#1f2233",
        "banner": media.get("bannerImage") or "",
        "listStatus": entry.get("status"),  # CURRENT/PLANNING/... or None (= not in lists)
    }


def build_detail(m: dict) -> dict:
    """Richer payload for the focused-anime detail view."""
    art = m.get("coverImage") or {}
    entry = m.get("mediaListEntry") or {}
    studios = [n.get("name") for n in ((m.get("studios") or {}).get("nodes") or []) if n.get("name")]
    desc = re.sub(r"<[^>]+>", "", m.get("description") or "").strip()
    return {
        "loading": False,
        "id": m["id"],
        "title": _title(m["title"]),
        "search": _search_title(m["title"]),
        "format": _fmt_label(m.get("format")),
        "episodes": m.get("episodes"),
        "duration": m.get("duration"),
        "year": m.get("seasonYear"),
        "genres": (m.get("genres") or [])[:4],
        "score": m.get("averageScore"),
        "studio": studios[0] if studios else "",
        "description": desc,
        "cover": art.get("extraLarge") or art.get("large") or "",
        "color": art.get("color") or "#1f2233",
        "banner": m.get("bannerImage") or "",
        "listStatus": entry.get("status"),
        "entryId": entry.get("id"),
        "progress": entry.get("progress") or 0,
    }


def _kick_search() -> None:
    """Bump the debounce generation and schedule a fresh AniList query for the
    current text + genre filters. Safe to call after any edit to SEARCH['query']
    or SEARCH['genres']."""
    with STATE_LOCK:
        SEARCH["gen"] += 1
        gen = SEARCH["gen"]
        SEARCH["busy"] = True
        query = SEARCH["query"]
        genres = list(SEARCH["genres"])
    broadcast()
    socketio.start_background_task(run_search, gen, query, genres)


def _search_variables(query: str, genres: list, page: int) -> dict:
    """Build the AniList query variables for one page of search/browse results.

    With text, sorts by relevance (SEARCH_MATCH). With no text it's a browse: the
    top-rated anime overall (SCORE_DESC), narrowed by any active genres."""
    variables = {"perPage": SEARCH_MAX, "page": page}
    q = query.strip()
    if q:
        variables["search"] = q
        variables["sort"] = ["SEARCH_MATCH"]
    else:
        variables["sort"] = ["SCORE_DESC"]
    # `genres` here is the active filter set — split into AniList genres vs tags.
    gs = [g for g in genres if g in GENRES_SET]
    ts = [g for g in genres if g in TAGS_SET]
    if gs:
        variables["genres"] = gs
    if ts:
        variables["tags"] = ts
    return variables


def run_search(gen: int, query: str, genres: list) -> None:
    """Debounced AniList query worker (page 1). Only the newest keystroke/filter applies.

    Always runs, so the results list is never empty just because nothing's been typed."""
    time.sleep(SEARCH_DEBOUNCE)
    with STATE_LOCK:
        if gen != SEARCH["gen"]:
            return  # superseded by a newer keystroke / filter toggle
    results = []
    has_more = False
    try:
        data = _public_post(SEARCH_QUERY, _search_variables(query, genres, 1))
        page = data.get("Page") or {}
        results = [build_result(m) for m in (page.get("media") or [])]
        has_more = bool((page.get("pageInfo") or {}).get("hasNextPage"))
    except Exception as exc:  # noqa: BLE001 - search failures shouldn't crash the loop
        print(f"[search] query {query.strip()!r} genres={genres} failed: {exc}", flush=True)
    with STATE_LOCK:
        if gen != SEARCH["gen"]:
            return
        SEARCH["results"] = results
        SEARCH["cursor"] = 0
        SEARCH["page"] = 1
        SEARCH["hasMore"] = has_more
        SEARCH["loadingMore"] = False
        SEARCH["busy"] = False
    broadcast()


def load_more(gen: int) -> None:
    """Append the next AniList page to the current results (infinite scroll)."""
    with STATE_LOCK:
        if gen != SEARCH["gen"] or not SEARCH["hasMore"]:
            SEARCH["loadingMore"] = False
            return
        query = SEARCH["query"]
        genres = list(SEARCH["genres"])
        next_page = SEARCH["page"] + 1
    new_results = []
    has_more = False
    try:
        data = _public_post(SEARCH_QUERY, _search_variables(query, genres, next_page))
        page = data.get("Page") or {}
        new_results = [build_result(m) for m in (page.get("media") or [])]
        has_more = bool((page.get("pageInfo") or {}).get("hasNextPage"))
    except Exception as exc:  # noqa: BLE001
        print(f"[search] load page {next_page} failed: {exc}", flush=True)
    with STATE_LOCK:
        if gen != SEARCH["gen"]:
            return  # query changed under us; discard this page
        seen = {r["id"] for r in SEARCH["results"]}
        SEARCH["results"].extend(r for r in new_results if r["id"] not in seen)
        SEARCH["page"] = next_page
        SEARCH["hasMore"] = has_more
        SEARCH["loadingMore"] = False
    broadcast()


def _kick_more_if_needed() -> bool:
    """Schedule a load-more page if we're in search, there's more, and none is in flight.
    Caller must NOT hold STATE_LOCK. Returns True if a load was scheduled."""
    with STATE_LOCK:
        if STATE["view"] != "search" or not SEARCH["hasMore"] or SEARCH["loadingMore"]:
            return False
        SEARCH["loadingMore"] = True
        gen = SEARCH["gen"]
    socketio.start_background_task(load_more, gen)
    return True


def _focus_detail(target: dict) -> None:
    """Enter the detail view for a search result. Caller must hold STATE_LOCK."""
    STATE["view"] = "detail"
    SEARCH["busy"] = True
    SEARCH["seasons"] = []
    SEARCH["seasonIdx"] = 0
    SEARCH["detail"] = {  # lightweight placeholder until the full detail lands
        "loading": True, "id": target["id"], "title": target["title"],
        "format": target.get("format", ""), "episodes": target.get("episodes"),
        "year": target.get("year"), "score": target.get("score"),
        "cover": target["cover"], "color": target["color"],
        "banner": target["banner"], "listStatus": target.get("listStatus"),
        "genres": [], "studio": "", "description": "", "entryId": None,
    }


def _season_sort_key(m: dict):
    """Chronological-ish ordering for a franchise's seasons."""
    sd = m.get("startDate") or {}
    return (
        m.get("seasonYear") or sd.get("year") or 9999,
        sd.get("month") or 0,
        m.get("id") or 0,
    )


def collect_chain(focus_id: int, seed: dict | None) -> list:
    """Walk PREQUEL/SEQUEL/PARENT/SIDE_STORY links out from the focused anime to gather
    the whole franchise as ordered "seasons". Each node is fetched once (full detail).
    Returns chronologically-sorted media dicts. `seed` is the already-fetched focus."""
    fetched: dict[int, dict] = {}
    queue: list[int] = [int(focus_id)]
    if seed:
        fetched[int(focus_id)] = seed
        queue = [int(focus_id)]  # still visit so we read its relations below
    while queue and len(fetched) < SEASON_MAX:
        mid = queue.pop(0)
        media = fetched.get(mid)
        if media is None:
            try:
                media = _public_post(DETAIL_QUERY, {"id": mid}).get("Media")
            except Exception as exc:  # noqa: BLE001
                print(f"[season] fetch {mid} failed: {exc}", flush=True)
                continue
            if not media:
                continue
            fetched[mid] = media
        for edge in ((media.get("relations") or {}).get("edges") or []):
            if edge.get("relationType") not in SEASON_RELATIONS:
                continue
            node = edge.get("node") or {}
            nid = node.get("id")
            if (node.get("type") == "ANIME" and nid and nid not in fetched
                    and nid not in queue and len(fetched) + len(queue) < SEASON_MAX):
                queue.append(nid)
    return sorted(fetched.values(), key=_season_sort_key)


def load_detail(media_id: int) -> None:
    """Fetch the focused anime's detail (shown quickly), then assemble its franchise
    season chain so the kiosk/phone can flip between seasons."""
    media_id = int(media_id)
    focus = None
    try:
        focus = _public_post(DETAIL_QUERY, {"id": media_id}).get("Media")
    except Exception as exc:  # noqa: BLE001
        print(f"[search] detail {media_id} failed: {exc}", flush=True)
    # Show the focused season right away.
    with STATE_LOCK:
        cur = SEARCH["detail"]
        on_target = STATE["view"] == "detail" and cur and cur.get("id") == media_id
        if not on_target:
            return  # user moved on before the fetch landed
        if focus:
            SEARCH["detail"] = build_detail(focus)
        else:
            cur["loading"] = False
        SEARCH["busy"] = False
    broadcast()
    if not focus:
        return
    # Then build the rest of the franchise chain (more network hops) in the background.
    chain = collect_chain(media_id, focus)
    seasons = [build_detail(m) for m in chain]
    idx = next((i for i, s in enumerate(seasons) if s["id"] == media_id), 0)
    with STATE_LOCK:
        cur = SEARCH["detail"]
        if not (STATE["view"] == "detail" and cur and cur.get("id") == media_id):
            return  # focus changed during traversal — discard
        SEARCH["seasons"] = seasons
        SEARCH["seasonIdx"] = idx
        if seasons:
            SEARCH["detail"] = seasons[idx]
    broadcast()


def apply_status(media_id: int, to: str, entry_id) -> None:
    """Write the chosen list status back to AniList for the focused anime."""
    new_status = None
    new_entry_id = entry_id
    try:
        if to == "REMOVE":
            if entry_id:
                _anilist_post(
                    "mutation($id:Int){ DeleteMediaListEntry(id:$id){ deleted } }",
                    {"id": int(entry_id)})
            new_entry_id = None
        else:
            payload = _anilist_post(
                "mutation($mediaId:Int,$status:MediaListStatus){"
                " SaveMediaListEntry(mediaId:$mediaId, status:$status){ id status } }",
                {"mediaId": int(media_id), "status": to})
            saved = (payload.get("data") or {}).get("SaveMediaListEntry") or {}
            new_status = saved.get("status") or to
            new_entry_id = saved.get("id") or entry_id
        print(f"[status] set {media_id} -> {to}", flush=True)
    except Exception as exc:  # noqa: BLE001
        print(f"[status] {media_id} -> {to} failed: {exc}", flush=True)
        with STATE_LOCK:
            SEARCH["busy"] = False
        broadcast()
        return
    with STATE_LOCK:
        d = SEARCH["detail"]
        if d and d.get("id") == int(media_id):
            d["listStatus"] = new_status
            d["entryId"] = new_entry_id
            d["justSet"] = to  # lets the UIs play a confirmation flourish
        for r in SEARCH["results"]:
            if r.get("id") == int(media_id):
                r["listStatus"] = new_status
        for s in SEARCH["seasons"]:  # keep the carousel's copy in sync
            if s.get("id") == int(media_id):
                s["listStatus"] = new_status
                s["entryId"] = new_entry_id
        SEARCH["busy"] = False
    broadcast()


# --------------------------------------------------------------------------- #
# AniList write-back : mark episodes watched (needs an OAuth token)
# --------------------------------------------------------------------------- #
def anilist_token() -> str:
    return (CONFIG.get("ANILIST_TOKEN") or "").strip()


def _anilist_post(query: str, variables: dict) -> dict:
    """Authenticated GraphQL call. Returns the `data` dict (raises on transport error)."""
    resp = requests.post(
        ANILIST_URL,
        json={"query": query, "variables": variables},
        headers={
            "Authorization": f"Bearer {anilist_token()}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        timeout=15,
    )
    resp.raise_for_status()
    return resp.json()


def mark_watched(media_id: int, episode: int, total: int | None, display: str) -> None:
    """Set AniList progress for `media_id` to `episode` (never lowering it). Flips the
    entry to COMPLETED on the final episode, otherwise CURRENT. No-op without a token."""
    if not anilist_token() or not media_id:
        return
    try:
        cur = _anilist_post(
            "query($id:Int){ Media(id:$id){ mediaListEntry { progress } } }",
            {"id": int(media_id)},
        )
        entry = ((cur.get("data") or {}).get("Media") or {}).get("mediaListEntry") or {}
        current = entry.get("progress") or 0
    except Exception as exc:  # noqa: BLE001
        print(f"[anilist] progress lookup failed: {exc}", flush=True)
        current = 0
    if episode <= current:
        return  # already at/ahead of this episode — don't decrement on rewatch/prev
    status = "COMPLETED" if (total and episode >= total) else "CURRENT"
    try:
        _anilist_post(
            "mutation($mediaId:Int,$progress:Int,$status:MediaListStatus){"
            " SaveMediaListEntry(mediaId:$mediaId, progress:$progress, status:$status){ id progress status } }",
            {"mediaId": int(media_id), "progress": int(episode), "status": status},
        )
        print(f"[anilist] marked {display!r} progress={episode} status={status}", flush=True)
        notify("✓ AniList", f"{display} — Episode {episode} watched",
               urgency="low", timeout=2500)
    except Exception as exc:  # noqa: BLE001
        print(f"[anilist] mark_watched failed: {exc}", flush=True)


def _clear_active_playing(gen: int) -> None:
    """mpv for this play has exited — clear the now-stale 'playing' so the kiosk, the web
    remote, and the phone's lock-screen media controls / widget all stop showing it as
    live. Guarded by gen so a Next/Prev/Resume that already started a new play (bumping
    the generation) is never wiped by the old watcher winding down."""
    with STATE_LOCK:
        if PLAYBACK["gen"] != gen or STATE.get("playing") is None:
            return
        STATE["playing"] = None
    broadcast()


def watch_playback(gen: int, media_id: int, episode: int, total: int | None,
                   display: str, search: str = "", cover: str = "",
                   color: str = "", banner: str = "") -> None:
    """Track Shou's mpv over its IPC socket. Two jobs:

      * past the completion threshold (or a clean EOF), mark the episode watched on
        AniList — only if a token is configured;
      * if playback stops mid-way instead, record a resume point (episode + position)
        in the continue-watching history. This runs with or without a token.
    """
    if not media_id:
        return  # need a stable key to relaunch/dedupe
    has_token = bool(anilist_token())
    try:
        threshold = float(CONFIG.get("WATCHED_PERCENT") or 90)
    except ValueError:
        threshold = 90.0

    # Wait for mpv to create the socket (it may be a while behind a slow source).
    end = time.time() + 180
    while time.time() < end and PLAYBACK["gen"] == gen and not os.path.exists(MPV_IPC):
        time.sleep(1)
    if PLAYBACK["gen"] != gen:
        return  # superseded by a newer play
    if not os.path.exists(MPV_IPC):
        print(f"[progress] mpv IPC socket never appeared at {MPV_IPC} "
              f"(no playable source?) — {display!r} not tracked", flush=True)
        return

    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(5)
        sock.connect(MPV_IPC)
        sock.sendall(b'{"command":["observe_property",1,"percent-pos"]}\n')
        sock.sendall(b'{"command":["observe_property",2,"time-pos"]}\n')
        sock.sendall(b'{"command":["observe_property",3,"duration"]}\n')
        sock.sendall(b'{"command":["observe_property",4,"pause"]}\n')
    except OSError as exc:
        print(f"[progress] mpv IPC connect failed: {exc}", flush=True)
        return
    print(f"[progress] watching {display!r} ep {episode} (mark at {threshold:.0f}% or EOF"
          f"{'' if has_token else '; no token — resume only'})", flush=True)

    buf = b""
    completed = False  # crossed the threshold or hit a clean EOF
    last_pos = 0.0     # furthest percent seen
    last_time = 0.0    # furthest time-pos seen (seconds)
    duration = 0.0
    last_emit_sec = -1  # throttle live-position broadcasts to ~1/second

    is_final = bool(total) and episode >= total and bool(anilist_token())

    def _finish():
        """Mark watched once, the moment completion is detected (so a quick Back right
        after the threshold still counts). Kept idempotent via the `completed` flag.
        If this was the final episode, arm the rating now — before mpv is even closed —
        so closing it (clean EOF or Back at 90%) still surfaces the rating page."""
        forget_resume(media_id, episode)
        if has_token and PLAYBACK["gen"] == gen:
            mark_watched(media_id, episode, total, display)
        if is_final:
            mark_pending_rating({"media_id": media_id, "title": display,
                                 "cover": cover, "color": color, "banner": banner})
        broadcast()

    # Read until mpv closes (EOF / Back / Next / stopped) — NOT just until the threshold —
    # so we can tell the final episode actually played out and mpv is gone before rating.
    try:
        while PLAYBACK["gen"] == gen:
            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not chunk:
                break  # mpv exited / was killed
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if not line.strip():
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                ev = msg.get("event")
                if ev == "property-change":
                    name = msg.get("name")
                    data = msg.get("data")
                    if name == "percent-pos" and isinstance(data, (int, float)):
                        last_pos = max(last_pos, data)
                        if data >= threshold and not completed:
                            completed = True
                            _finish()
                    elif name == "time-pos" and isinstance(data, (int, float)):
                        last_time = max(last_time, data)
                        # Surface live position to the remotes' progress bar, once a
                        # second, only while this play is still the current one.
                        sec = int(data)
                        if sec != last_emit_sec and PLAYBACK["gen"] == gen:
                            last_emit_sec = sec
                            with STATE_LOCK:
                                p = STATE.get("playing")
                                if (p and p.get("media_id") == media_id
                                        and p.get("episode") == episode):
                                    p["position"] = round(data, 1)
                                    p["duration"] = round(duration, 1)
                                    emit = True
                                else:
                                    emit = False
                            if emit:
                                broadcast()
                    elif name == "duration" and isinstance(data, (int, float)):
                        duration = data
                    elif name == "pause" and isinstance(data, bool):
                        # Reflect real play/pause to the clients (so the phone's media
                        # controls show the right icon), only for the current episode.
                        with STATE_LOCK:
                            p = STATE.get("playing")
                            changed = bool(
                                p and p.get("media_id") == media_id
                                and p.get("episode") == episode
                                and p.get("paused") != data
                            )
                            if changed:
                                p["paused"] = data
                        if changed:
                            broadcast()
                elif ev == "end-file" and msg.get("reason") == "eof" and not completed:
                    completed = True
                    _finish()
                elif ev == "client-message":
                    # The mpv `t` binding fires `script-message shou-throw`.
                    args = msg.get("args") or []
                    if args and args[0] == "shou-throw":
                        start_throw()
    finally:
        try:
            sock.close()
        except OSError:
            pass

    if completed:
        # Already marked watched. If mpv closed on its own (not superseded by Back/Next/
        # Open), pop the awaiting-rating info and show the page. If the user closed mpv via
        # Back instead, the /back handler pops it first — the atomic pop ensures one winner.
        if PLAYBACK["gen"] == gen:
            info = _pop_pending_rating()
            if info:
                show_rating(info)
        _clear_active_playing(gen)
        return

    # Stopped before the threshold — remember where, so it can be resumed later.
    if last_time >= RESUME_MIN_SECONDS and last_pos < threshold:
        remember_resume({
            "media_id": media_id,
            "episode": episode,
            "title": display,
            "search": search or display,
            "total": total,
            "cover": cover,
            "color": color or "#1f2233",
            "banner": banner,
            "position": round(last_time, 1),
            "duration": round(duration, 1),
            "percent": round(last_pos, 1),
            "updated_at": int(time.time()),
        })
        broadcast()
    else:
        print(f"[progress] {display!r} ep {episode} not marked/saved — ended at "
              f"{last_pos:.0f}% / {int(last_time)}s", flush=True)
    _clear_active_playing(gen)


# --------------------------------------------------------------------------- #
# Kiosk window control (compositor-agnostic / any browser)
# --------------------------------------------------------------------------- #
def kiosk_pid() -> int | None:
    """Return the PID of the running browser kiosk, or None."""
    try:
        pid = int(FF_PIDFILE.read_text().strip())
    except (FileNotFoundError, ValueError):
        return None
    try:
        os.kill(pid, 0)
    except OSError:
        return None
    return pid


def _hypr_is_fullscreen(pid: int) -> bool:
    """True if the Hyprland window for `pid` is already fullscreen."""
    try:
        out = subprocess.run(["hyprctl", "clients", "-j"],
                             capture_output=True, text=True, timeout=3)
        for c in json.loads(out.stdout or "[]"):
            if c.get("pid") == pid:
                fs = c.get("fullscreen")
                return fs is True or (isinstance(fs, int) and fs >= 2)
    except Exception:  # noqa: BLE001
        pass
    return False


def _sway_is_fullscreen(pid: int) -> bool:
    """True if the Sway window for `pid` is already fullscreen."""
    try:
        out = subprocess.run(["swaymsg", "-t", "get_tree"],
                             capture_output=True, text=True, timeout=3)
        stack = [json.loads(out.stdout or "{}")]
        while stack:
            node = stack.pop()
            if node.get("pid") == pid and node.get("fullscreen_mode"):
                return True
            stack.extend(node.get("nodes") or [])
            stack.extend(node.get("floating_nodes") or [])
    except Exception:  # noqa: BLE001
        pass
    return False


def _x11_window_id(pid: int) -> str | None:
    """Decimal id of the real top-level X11 window owned by `pid` (via xdotool), or None.

    Browsers spawn tiny helper/utility windows (Firefox maps a 10x10 one early), so when
    `xprop` is around we pick the one whose `_NET_WM_WINDOW_TYPE` is NORMAL and skip the
    rest — returning None while only helpers exist, so a poll keeps waiting for the real
    kiosk window. Without xprop we fall back to the last match."""
    if not shutil.which("xdotool"):
        return None
    try:
        out = subprocess.run(["xdotool", "search", "--pid", str(pid)],
                             capture_output=True, text=True, timeout=3)
        ids = out.stdout.split()
    except Exception:  # noqa: BLE001
        return None
    if not ids:
        return None
    if not shutil.which("xprop"):
        return ids[-1]
    for wid in ids:
        try:
            t = subprocess.run(["xprop", "-id", wid, "_NET_WM_WINDOW_TYPE"],
                               capture_output=True, text=True, timeout=2).stdout
            if "_NET_WM_WINDOW_TYPE_NORMAL" in t:
                return wid
        except Exception:  # noqa: BLE001
            pass
    return None


def _bspwm_is_fullscreen(wid: str) -> bool:
    """True if bspwm already has node `wid` in its fullscreen state."""
    try:
        out = subprocess.run(["bspc", "query", "-T", "-n", wid],
                             capture_output=True, text=True, timeout=3)
        client = json.loads(out.stdout or "{}").get("client") or {}
        return client.get("state") == "fullscreen"
    except Exception:  # noqa: BLE001
        return False


def _x11_focus_fullscreen(pid: int) -> bool:
    """Raise + fullscreen the kiosk window on X11 — any EWMH window manager (bspwm, i3,
    openbox, XFCE, KDE/X11, …). Returns True if a tool handled it.

    bspwm tracks fullscreen as its own *node state*, and a bare EWMH property change (all
    `xdotool windowstate` does) doesn't flip it — only a real `_NET_WM_STATE` client
    message would, which xdotool can't send. So when `bspc` is present we drive bspwm
    natively; otherwise `wmctrl -b` (which DOES send the client message) is preferred over
    the weaker xdotool fallback. Each path is idempotent — it never knocks the kiosk back
    out of fullscreen, so Back/Open are safe to repeat."""
    if shutil.which("bspc"):
        wid = _x11_window_id(pid)
        if wid:
            if not _bspwm_is_fullscreen(wid):
                subprocess.run(["bspc", "node", wid, "-t", "fullscreen"], check=False)
            subprocess.run(["bspc", "node", wid, "-f"], check=False)  # focus/raise
            return True
    if shutil.which("wmctrl"):
        try:
            out = subprocess.run(["wmctrl", "-lp"],
                                 capture_output=True, text=True, timeout=3)
            for line in out.stdout.splitlines():
                # cols: window-id  desktop  pid  host  title
                parts = line.split(None, 4)
                if len(parts) >= 3 and parts[2] == str(pid):
                    wid = parts[0]
                    subprocess.run(["wmctrl", "-i", "-a", wid], check=False)
                    subprocess.run(["wmctrl", "-i", "-r", wid, "-b", "add,fullscreen"],
                                   check=False)
                    return True
        except Exception:  # noqa: BLE001
            pass
    wid = _x11_window_id(pid)
    if wid:
        subprocess.run(["xdotool", "windowactivate", "--sync", wid], check=False)
        subprocess.run(["xdotool", "windowstate", "--add", "FULLSCREEN", wid], check=False)
        return True
    return False


def focus_kiosk(pid: int) -> None:
    """Best-effort raise + fullscreen of the kiosk window. Supports Hyprland and Sway on
    Wayland, and any EWMH window manager on X11 (bspwm, i3, openbox, …) via wmctrl/xdotool;
    a no-op only when none of those tools are present — the browser was already opened with
    --kiosk, so it stays fullscreen on its own. This is purely a 'bring it back to front'
    nicety.

    On Wayland the fullscreen toggle is only applied when the window ISN'T already
    fullscreen — re-dispatching it on an already-fullscreen window would flip it back out
    (which is what made Back un-fullscreen the kiosk). On X11 we *add* the EWMH fullscreen
    state, which is idempotent, so no such guard is needed."""
    if os.environ.get("WAYLAND_DISPLAY") and shutil.which("hyprctl"):
        subprocess.run(["hyprctl", "dispatch", "focuswindow", f"pid:{pid}"], check=False)
        if not _hypr_is_fullscreen(pid):
            subprocess.run(["hyprctl", "dispatch", "fullscreenstate", "2", "-1"], check=False)
    elif os.environ.get("WAYLAND_DISPLAY") and shutil.which("swaymsg"):
        subprocess.run(["swaymsg", f"[pid={pid}]", "focus"], check=False)
        if not _sway_is_fullscreen(pid):
            subprocess.run(["swaymsg", "fullscreen", "enable"], check=False)
    else:
        _x11_focus_fullscreen(pid)


def _focus_kiosk_when_ready(pid: int) -> None:
    """A freshly-launched browser hasn't mapped its window yet, so a one-shot focus would
    miss it — bspwm in particular maps the kiosk floating until we flip its node state.
    Poll for the real window (skipping the early helper windows) and fullscreen it once it
    appears. X11 only; Wayland honors the browser's own --kiosk fullscreen at map time."""
    if os.environ.get("WAYLAND_DISPLAY"):
        return
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if kiosk_pid() != pid:          # browser exited or was replaced — stop
            return
        if _x11_window_id(pid):         # the real (NORMAL) window is up
            focus_kiosk(pid)
            return
        time.sleep(0.4)


# Browser candidates, in preference order. Each entry: (binaries, argv-builder, env).
# Firefox uses --profile; Chromium-family browsers use --user-data-dir.
_FIREFOX_BINS = ("firefox", "firefox-esr", "librewolf", "waterfox")
_CHROMIUM_BINS = ("chromium", "chromium-browser", "google-chrome-stable",
                  "google-chrome", "brave", "brave-browser", "vivaldi-stable", "vivaldi")


# macOS keeps browsers in .app bundles, not on PATH — probe the usual binaries.
_MAC_FIREFOX = ("/Applications/Firefox.app/Contents/MacOS/firefox",)
_MAC_CHROMIUM = (
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "/Applications/Vivaldi.app/Contents/MacOS/Vivaldi",
)


def _firefox_argv(b, profile, url):
    return ([b, "--kiosk", "--profile", profile, url], {"MOZ_NO_REMOTE": "1"})


def _chromium_argv(b, profile, url):
    return ([b, "--kiosk", "--no-first-run", f"--user-data-dir={profile}", url], {})


def _browser_command(url: str):
    """Return (argv, extra_env) for a fullscreen kiosk on the first browser found,
    or (None, None) if none is installed. Chromium-family browsers take --kiosk on
    macOS too, launched via the binary inside their .app bundle."""
    profile = str(FF_PROFILE)
    if sys.platform == "darwin":
        for b in _MAC_FIREFOX:
            if os.path.exists(b):
                return _firefox_argv(b, profile, url)
        for b in _MAC_CHROMIUM:
            if os.path.exists(b):
                return _chromium_argv(b, profile, url)
    for b in _FIREFOX_BINS:
        if shutil.which(b):
            return _firefox_argv(b, profile, url)
    for b in _CHROMIUM_BINS:
        if shutil.which(b):
            return _chromium_argv(b, profile, url)
    return (None, None)


def ensure_kiosk() -> None:
    """Make sure the fullscreen kiosk is showing; launch it if needed, else refocus."""
    pid = kiosk_pid()
    if pid:
        focus_kiosk(pid)
        return
    argv, extra_env = _browser_command(f"http://127.0.0.1:{PORT}/")
    if argv is None:
        print("[kiosk] no supported browser found (firefox/chromium/brave/…); "
              f"open http://127.0.0.1:{PORT}/ manually.", flush=True)
        return
    FF_PROFILE.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(
        argv,
        env=os.environ | extra_env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    FF_PIDFILE.write_text(str(proc.pid))
    # The window isn't mapped yet; fullscreen it once it appears (bspwm maps it floating).
    threading.Thread(target=_focus_kiosk_when_ready, args=(proc.pid,), daemon=True).start()


# --------------------------------------------------------------------------- #
# Playback / process control
# --------------------------------------------------------------------------- #
def kill_players() -> None:
    """Stop ONLY the players Shou started — never unrelated mpv instances such as
    mpvpaper live wallpapers. Our mpv carries a unique --input-ipc-server marker; the
    ani-cli launcher we tracked is killed by its process group (which also reaps the
    mpv it spawned); `ani-cli` by name is unambiguous and catches any stray instance."""
    # Reap the tracked ani-cli launcher + its whole session (its mpv child included).
    proc = PLAYBACK.get("proc")
    if proc is not None and proc.poll() is None:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            pass
    # ani-cli is an unambiguous name; safe to match broadly.
    subprocess.run(["pkill", "-f", "ani-cli"], check=False)
    # Our mpv only, identified by the unique socket-path marker (NOT bare "mpv").
    subprocess.run(["pkill", "-f", MPV_IPC], check=False)
    # Drop the IPC socket file. A hard-killed mpv can leave it behind, and a stale
    # file blocks the next mpv from binding its --input-ipc-server — which silently
    # disables pause/seek and the watch-progress watcher (ECONNREFUSED on connect).
    try:
        Path(MPV_IPC).unlink()
    except FileNotFoundError:
        pass
    except OSError:
        pass


def bump_play_gen() -> int:
    """Invalidate any in-flight playback monitor and return a fresh generation id."""
    with STATE_LOCK:
        PLAYBACK["gen"] += 1
        return PLAYBACK["gen"]


def mpv_running() -> bool:
    """True only if Shou's own mpv is up (matched by our unique marker), so a
    live-wallpaper mpv never counts as 'playing' for the launch monitor."""
    return subprocess.run(
        ["pgrep", "-f", MPV_IPC], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    ).returncode == 0


def mpv_ipc_command(command: list) -> None:
    """Send one JSON command to Shou's mpv over its IPC socket (best-effort, no reply).
    Used for pause / seek so the remote needs no playerctl or mpv-mpris plugin — mpv's
    own --input-ipc-server (set on every player we launch) is enough."""
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(2)
        sock.connect(MPV_IPC)
        sock.sendall(json.dumps({"command": command}).encode() + b"\n")
        sock.close()
    except OSError:
        pass


def _norm_title(s: str) -> str:
    """Lowercase + strip punctuation/space so 'Fate/Zero' ~ 'fate zero' for comparison."""
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def anicli_result_index(search_title: str, total: int | None) -> int | None:
    """Decide which of ani-cli's search results to play (1-based, for `ani-cli -S N`).

    Replicates ani-cli's allanime search so the ordering — and therefore the index we
    return — matches what `-S N` will pick. The result list ani-cli builds keeps only
    shows that have at least one sub episode, in the API's edge order.

    Selection: prefer a result whose name is (near-)identical to the search title — that
    reliably separates "Fate/Zero" from "Fate/Zero: Onegai...". Among such strong matches,
    AniList's episode count (`total`) breaks ties: an exact episode-count match wins, else
    the closest count, else the best name match. allanime episode counts drift by a few
    (recaps/specials), so they're a tie-breaker, not the primary signal.

    If no result is a strong title match we return None and let the caller keep ani-cli's
    default (-S 1): that first hit is usually the canonical show, whereas a loose title
    match mis-picks badly (allanime lists One Piece itself under the name "1P").
    """
    query = search_title.replace(" ", "+")
    payload = {
        "variables": {
            "search": {"allowAdult": False, "allowUnknown": False, "query": query},
            "limit": 40, "page": 1, "translationType": "sub", "countryOrigin": "ALL",
        },
        "query": ALLANIME_SEARCH_GQL,
    }
    try:
        resp = requests.post(
            ALLANIME_API, json=payload,
            headers={"Referer": ALLANIME_REFERER, "User-Agent": ALLANIME_AGENT},
            timeout=12,
        )
        resp.raise_for_status()
        edges = resp.json()["data"]["shows"]["edges"]
    except Exception as exc:  # noqa: BLE001 - any failure -> let ani-cli pick result 1
        print(f"[pick] allanime search failed for {search_title!r}: {exc}", flush=True)
        return None

    # Mirror ani-cli's filter: only shows with >=1 sub episode survive, in edge order.
    candidates = []  # (1-based index, name, sub_episode_count)
    for edge in edges:
        sub = ((edge.get("availableEpisodes") or {}).get("sub")) or 0
        if sub >= 1:
            candidates.append((len(candidates) + 1, edge.get("name") or "", sub))
    if not candidates:
        return None

    target = _norm_title(search_title)
    # Keep only near-identical titles; below this we don't trust the match (and the
    # canonical show sometimes has an odd name, where ani-cli's first result is better).
    STRONG = 0.85
    strong = [
        (idx, name, sub,
         difflib.SequenceMatcher(None, target, _norm_title(name)).ratio())
        for idx, name, sub in candidates
    ]
    strong = [c for c in strong if c[3] >= STRONG]
    if not strong:
        return None  # no confident title match -> let ani-cli take result 1

    # Rank strong matches: exact episode count first, then closest count, then best name.
    def rank(c):
        idx, name, sub, ratio = c
        if total:
            exact = 1 if sub == total else 0
            closeness = -abs(sub - total)
        else:
            exact, closeness = 0, 0
        return (exact, closeness, ratio, -idx)

    idx, name, sub, ratio = max(strong, key=rank)
    print(f"[pick] {search_title!r}: -> #{idx} {name!r} "
          f"(sub={sub}, total={total}, title={ratio:.2f}, "
          f"{len(strong)} strong of {len(candidates)})", flush=True)
    return idx


def play(search_title: str, episode: int, display_title: str | None = None,
         media_id: int | None = None, total: int | None = None,
         cover: str = "", color: str = "", banner: str = "",
         start: float = 0.0) -> None:
    """Launch ani-cli for the given title/episode, detached, auto-picking result 1.

    `search_title` (romaji) is what ani-cli searches; `display_title` (english) is what
    the UI/notification shows. A background monitor watches whether mpv actually starts
    and reports back to the UI if no playable source was found. When `media_id` is known
    a second watcher marks the episode watched once playback completes (if an AniList
    token is set) and records a resume point if you stop mid-episode. `start` (seconds)
    resumes playback from a saved position; cover/color/banner travel into the history.
    """
    display = display_title or search_title
    start = float(start or 0)
    _pop_pending_rating()  # starting new playback cancels any awaiting finale rating
    gen = bump_play_gen()
    kill_players()
    time.sleep(0.4)
    quality = CONFIG.get("QUALITY") or "1080p"
    # -S N picks the Nth search result non-interactively. ani-cli selects the anime
    # with fzf, which reads /dev/tty; running detached (no terminal) that aborts with
    # "inappropriate ioctl for device" on any multi-result search (e.g. ONE PIECE, which
    # returns the series plus dozens of films/specials), so nothing plays. Piping a "1"
    # to stdin does NOT help — the picker ignores stdin. --select-nth sets the index
    # directly and never invokes fzf. (Single-result searches skipped fzf already, which
    # is why some titles played while others silently failed.)
    # We pre-pick N by replaying ani-cli's own search and matching episode count + title,
    # so "Fate/Zero" beats "Fate/Zero: Onegai..." instead of grabbing whatever's first.
    nth = anicli_result_index(search_title, total) or 1
    cmd = (
        f"ani-cli -S {nth} -q {shlex.quote(quality)} "
        f"-e {episode} {shlex.quote(search_title)}"
    )
    print(f"[play] ani-cli search={search_title!r} episode={episode} "
          f"quality={quality!r} start={int(start)}s (showing as {display!r})", flush=True)
    # ani-cli output is captured so the monitor can read a failure reason.
    logf = ANI_LOG.open("w")
    # ANI_CLI_PLAYER is expanded unquoted into ani-cli's mpv branch, so this opens mpv
    # fullscreen — scoped to ani-cli only (no global mpv config edit). A non-zero start
    # adds --start=<sec> so a resumed episode opens at the saved position.
    player = ANI_CLI_PLAYER
    if start > 0:
        player = (f"mpv --fs --start={int(start)} "
                  f"--input-ipc-server={MPV_IPC} --input-conf={MPV_INPUT_CONF}")
    proc = subprocess.Popen(
        cmd,
        shell=True,
        stdin=subprocess.DEVNULL,
        stdout=logf,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        env=os.environ | {"ANI_CLI_PLAYER": player},
    )
    logf.close()
    with STATE_LOCK:
        PLAYBACK["proc"] = proc
        STATE["playing"] = {"title": display, "search": search_title, "episode": episode,
                            "media_id": media_id, "total": total,
                            "cover": cover, "color": color, "banner": banner}
    notify("🎌 Now Watching", f"{display} — Episode {episode}",
           urgency="normal", timeout=3000)
    socketio.start_background_task(monitor_playback, gen, search_title, episode, display, start)
    # Playback watcher: marks watched on AniList (if a token is set) and records a
    # resume point if stopped mid-way. Covers the ani-cli mpv and the fallback mpv.
    if media_id:
        socketio.start_background_task(watch_playback, gen, media_id, episode, total,
                                       display, search_title, cover, color, banner)


def resolve_fallback(search_title: str, episode: int):
    """Try the backup scrapers (anipy_api) for a playable stream of the requested
    episode. Returns (url, referrer, subtitle, provider, matched_name) or None."""
    try:
        from anipy_api.provider import get_provider, LanguageTypeEnum
    except Exception as exc:  # noqa: BLE001
        print(f"[fallback] anipy_api unavailable: {exc}", flush=True)
        return None

    for prov_name in FALLBACK_PROVIDERS:
        try:
            prov = get_provider(prov_name)
            if not prov:
                continue
            results = prov.get_search(search_title)
        except Exception as exc:  # noqa: BLE001
            print(f"[fallback] {prov_name} search failed: {exc}", flush=True)
            continue
        # Pick the first search result that actually has the requested episode.
        for anime in results[:6]:
            try:
                eps = prov.get_episodes(anime.identifier, LanguageTypeEnum.SUB)
                if episode not in eps:
                    continue
                streams = prov.get_video(anime.identifier, episode, LanguageTypeEnum.SUB)
            except Exception:  # noqa: BLE001
                continue
            if not streams:
                continue
            best = max(streams, key=lambda s: s.resolution or 0)
            print(f"[fallback] {prov_name} matched {anime.name!r} ep {episode} "
                  f"@ {best.resolution}p", flush=True)
            return best.url, best.referrer, best.subtitle, prov_name, anime.name
    return None


def fallback_play(gen: int, search_title: str, episode: int, display: str,
                  start: float = 0.0) -> bool:
    """Resolve a stream from a backup scraper and launch mpv directly. True on success."""
    resolved = resolve_fallback(search_title, episode)
    if not resolved:
        return False
    url, referrer, subtitle, prov_name, matched = resolved

    with STATE_LOCK:
        if PLAYBACK["gen"] != gen:
            return True  # superseded; treat as handled
    kill_players()
    time.sleep(0.3)
    cmd = ["mpv", "--fs", f"--input-ipc-server={MPV_IPC}",
           f"--input-conf={MPV_INPUT_CONF}",
           f"--force-media-title={display} — Episode {episode}"]
    if start and start > 0:
        cmd.append(f"--start={int(start)}")
    if referrer:
        cmd.append(f"--referrer={referrer}")
    if subtitle:
        cmd.append(f"--sub-file={subtitle}")
    cmd.append(url)
    subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
        env=os.environ,
    )
    # Give mpv a moment to come up.
    for _ in range(20):
        time.sleep(0.5)
        with STATE_LOCK:
            if PLAYBACK["gen"] != gen:
                return True
        if mpv_running():
            with STATE_LOCK:
                STATE["view"] = "playing"
                STATE["message"] = f"Playing {display} — Ep {episode}  ·  backup: {prov_name}"
            broadcast()
            notify("🎌 Backup Source",
                   f"{display} — Ep {episode} (via {prov_name})",
                   urgency="normal", timeout=3000)
            return True
    return False


def _failure_reason() -> str:
    """Best-effort hint pulled from ani-cli's captured output."""
    try:
        low = ANI_LOG.read_text(errors="ignore").lower()
    except OSError:
        return ""
    if "no results" in low or "not found" in low:
        return "no search results"
    if "out of range" in low or "no episode" in low:
        return "episode not available"
    return ""


def monitor_playback(gen: int, search_title: str, episode: int, display: str,
                     start: float = 0.0) -> None:
    """Watch for mpv to actually start. If ani-cli never launches it, try the backup
    scrapers; only if those fail too do we report 'no source' to the UI."""
    deadline = time.monotonic() + LAUNCH_TIMEOUT
    while time.monotonic() < deadline:
        with STATE_LOCK:
            superseded = PLAYBACK["gen"] != gen
            proc = PLAYBACK["proc"]
        if superseded:
            return
        if mpv_running():
            return  # success — playback started
        # ani-cli exited without launching mpv: give the window a brief grace, then fail.
        if proc is not None and proc.poll() is not None:
            for _ in range(8):
                time.sleep(0.5)
                with STATE_LOCK:
                    if PLAYBACK["gen"] != gen:
                        return
                if mpv_running():
                    return
            break
        time.sleep(1.0)

    with STATE_LOCK:
        if PLAYBACK["gen"] != gen:
            return
    if mpv_running():
        return

    # ani-cli found nothing — try the backup scrapers before giving up.
    print(f"[play] ani-cli found no source for {search_title!r} ep {episode}; "
          f"trying backups {FALLBACK_PROVIDERS}", flush=True)
    with STATE_LOCK:
        STATE["message"] = f"Searching backup sources for {display}…"
    broadcast()
    if fallback_play(gen, search_title, episode, display, start):
        return

    with STATE_LOCK:
        if PLAYBACK["gen"] != gen:
            return
    reason = _failure_reason()
    msg = f"No playable source for “{display}” (ep {episode})."
    if reason:
        msg += f" ({reason})"
    msg += " Press Back and try another."
    print(f"[play] FAILED — {msg}", flush=True)
    with STATE_LOCK:
        STATE["view"] = "error"
        STATE["message"] = msg
        STATE["playing"] = None
    broadcast()
    notify("🎌 Shou", f"⚠ {msg}", urgency="critical", timeout=6000)


# --------------------------------------------------------------------------- #
# Continue-watching history (resume points for episodes stopped mid-way)
# --------------------------------------------------------------------------- #
def _load_history() -> list:
    try:
        data = json.loads(HISTORY_FILE.read_text())
        return data if isinstance(data, list) else []
    except (FileNotFoundError, ValueError, OSError):
        return []


def _save_history(items: list) -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        HISTORY_FILE.write_text(json.dumps(items))
    except OSError as exc:  # noqa: BLE001
        print(f"[history] save failed: {exc}", flush=True)


def history_snapshot() -> list:
    """The current resume list (newest first) — broadcast to the UIs."""
    with HISTORY_LOCK:
        return _load_history()


def remember_resume(entry: dict) -> None:
    """Insert/replace a resume point keyed by (media_id, episode); newest first."""
    mid, ep = entry.get("media_id"), entry.get("episode")
    if not mid:
        return
    with HISTORY_LOCK:
        items = [e for e in _load_history()
                 if not (e.get("media_id") == mid and e.get("episode") == ep)]
        items.insert(0, entry)
        _save_history(items[:HISTORY_MAX])
    print(f"[history] saved resume {entry.get('title')!r} ep {ep} "
          f"@ {int(entry.get('position') or 0)}s", flush=True)


def forget_resume(media_id: int, episode: int) -> None:
    """Drop a resume point — called once an episode is finished/marked watched."""
    if not media_id:
        return
    with HISTORY_LOCK:
        before = _load_history()
        items = [e for e in before
                 if not (e.get("media_id") == media_id and e.get("episode") == episode)]
        if len(items) != len(before):
            _save_history(items)


# --------------------------------------------------------------------------- #
# Series-complete rating  (cinematic page + finish chime)
# --------------------------------------------------------------------------- #
_SCORE_FORMAT_CACHE = {"name": None, "fmt": None}


def fetch_score_format() -> str:
    """The user's AniList scoring scale (POINT_100/10/5/3…). Public, no token needed.
    Cached per username; falls back to POINT_10 on any error."""
    user = (CONFIG.get("ANILIST_USER") or "").strip()
    if not user:
        return "POINT_10"
    if _SCORE_FORMAT_CACHE["name"] == user and _SCORE_FORMAT_CACHE["fmt"]:
        return _SCORE_FORMAT_CACHE["fmt"]
    fmt = "POINT_10"
    try:
        resp = requests.post(
            ANILIST_URL,
            json={"query": "query($n:String){ User(name:$n){ mediaListOptions "
                           "{ scoreFormat } } }", "variables": {"n": user}},
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            timeout=15,
        )
        got = (((resp.json().get("data") or {}).get("User") or {})
               .get("mediaListOptions") or {}).get("scoreFormat")
        if got in SCORE_FORMATS:
            fmt = got
    except Exception as exc:  # noqa: BLE001
        print(f"[rating] scoreFormat lookup failed: {exc}", flush=True)
    _SCORE_FORMAT_CACHE.update(name=user, fmt=fmt)
    return fmt


def _stars(score: float, max_score: float) -> float:
    """Proportionate 0..5 star equivalent of a score on its native scale."""
    if not max_score:
        return 0.0
    return round(max(0.0, min(5.0, score / max_score * 5.0)), 3)


def _ensure_finish_sound() -> None:
    """Synthesize a short, warm completion chime (an ascending C-major arpeggio with a
    bell-ish timbre) once, into FINISH_SOUND. No audio assets shipped with the repo."""
    if FINISH_SOUND.exists():
        return
    sr = 44100
    notes = [(523.25, 0.00), (659.25, 0.12), (783.99, 0.24), (1046.50, 0.40)]
    dur = 2.0
    n = int(sr * dur)
    buf = [0.0] * n
    for freq, start in notes:
        s0 = int(start * sr)
        for i in range(s0, n):
            t = (i - s0) / sr
            env = math.exp(-2.6 * t)                       # smooth decay
            tone = (math.sin(2 * math.pi * freq * t)
                    + 0.45 * math.sin(2 * math.pi * 2 * freq * t) * math.exp(-5 * t))
            buf[i] += tone * env * 0.26
    peak = max(1e-9, max(abs(x) for x in buf))
    scale = min(1.0, 0.92 / peak)
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        with wave.open(str(FINISH_SOUND), "w") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(b"".join(
                struct.pack("<h", int(max(-1.0, min(1.0, x * scale)) * 32767)) for x in buf
            ))
    except (OSError, wave.Error) as exc:  # noqa: BLE001
        print(f"[rating] couldn't write finish chime: {exc}", flush=True)


def play_finish_sound() -> None:
    """Play the completion chime on the PC's speakers (best-effort, detached)."""
    custom = (CONFIG.get("FINISH_SOUND") or "").strip()
    path = custom or str(FINISH_SOUND)
    if not custom:
        _ensure_finish_sound()
    if not os.path.exists(path):
        return
    players = [
        ["mpv", "--no-config", "--no-video", "--force-window=no", "--really-quiet", path],
        ["afplay", path],                       # macOS native
        ["paplay", path],
        ["pw-play", path],
        ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path],
        ["aplay", "-q", path],
    ]
    for argv in players:
        if shutil.which(argv[0]):
            try:
                subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 start_new_session=True)
            except OSError:
                continue
            return


def mark_pending_rating(info: dict) -> None:
    """Record that a finished series is awaiting its rating (set at completion, while mpv
    may still be open). Whatever closes the player then pops it via _pop_pending_rating."""
    with STATE_LOCK:
        PENDING_RATING["info"] = info


def _pop_pending_rating() -> dict | None:
    """Atomically take the awaiting-rating info (None if already consumed/cleared)."""
    with STATE_LOCK:
        info = PENDING_RATING["info"]
        PENDING_RATING["info"] = None
    return info


def show_rating(info: dict) -> None:
    """Show the cinematic rating page for a just-finished series + play the chime."""
    if not info or not anilist_token():
        return
    fmt = fetch_score_format()
    spec = SCORE_FORMATS.get(fmt, SCORE_FORMATS["POINT_10"])
    score = spec["default"]
    with STATE_LOCK:
        STATE["view"] = "rating"
        STATE["playing"] = None
        STATE["rating"] = {
            "media_id": info["media_id"],
            "title": info["title"],
            "cover": info.get("cover", ""),
            "color": info.get("color") or "#1f2233",
            "banner": info.get("banner", ""),
            "format": fmt,
            "score": score,
            "min": spec["min"],
            "max": spec["max"],
            "step": spec["step"],
            "stars": _stars(score, spec["max"]),
            "submitting": False,
            "done": False,
        }
    print(f"[rating] {info['title']!r} complete — prompting ({fmt}, default {score})",
          flush=True)
    broadcast()
    play_finish_sound()


def _rating_adjust(steps: int) -> bool:
    """Nudge the pending rating by `steps` of its native step. True if it was handled
    (i.e. the rating page is up), so the caller skips the normal carousel move."""
    with STATE_LOCK:
        r = STATE.get("rating")
        if STATE["view"] != "rating" or not r or r.get("submitting") or r.get("done"):
            return STATE["view"] == "rating"  # swallow input while submitting/done
        step, lo, hi = r["step"], r["min"], r["max"]
        score = r["score"] + steps * step
        score = max(lo, min(hi, round(score / step) * step))
        score = round(score, 2)
        if abs(score - int(score)) < 1e-9:
            score = int(score)
        r["score"] = score
        r["stars"] = _stars(score, hi)
    broadcast()
    return True


def submit_rating() -> None:
    """Save the chosen score to AniList, flash a confirmation, then return to the grid."""
    with STATE_LOCK:
        r = STATE.get("rating")
        if not r or STATE["view"] != "rating" or r.get("submitting") or r.get("done"):
            return
        r["submitting"] = True
        media_id, score, title = r["media_id"], r["score"], r["title"]
    broadcast()

    ok = False
    try:
        _anilist_post(
            "mutation($id:Int,$s:Float){ SaveMediaListEntry(mediaId:$id, score:$s)"
            "{ id score } }",
            {"id": int(media_id), "s": float(score)},
        )
        ok = True
        print(f"[rating] saved {title!r} score={score}", flush=True)
        notify("★ AniList", f"{title} — rated {score}", urgency="low", timeout=2500)
    except Exception as exc:  # noqa: BLE001
        print(f"[rating] save failed: {exc}", flush=True)

    with STATE_LOCK:
        r = STATE.get("rating")
        if r:
            r["submitting"] = False
            r["done"] = True
            r["ok"] = ok
    broadcast()
    time.sleep(2.6)  # let the kiosk play the "rated" flourish
    with STATE_LOCK:
        STATE["rating"] = None
    refresh_list()  # back to the grid, now reflecting the new score/status


# --------------------------------------------------------------------------- #
# "Throw to phone" — hand the current stream off to the phone for mobile viewing
# --------------------------------------------------------------------------- #
def ensure_input_conf() -> None:
    """Write the mpv binding that throws to the phone (`t`). Idempotent."""
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        MPV_INPUT_CONF.write_text(
            f"# Auto-generated by Shou — press '{MPV_THROW_KEY}' to throw to your phone.\n"
            f"{MPV_THROW_KEY} script-message shou-throw\n"
        )
    except OSError as exc:
        print(f"[throw] could not write mpv input conf: {exc}", flush=True)


def _cast_proxy_path(url: str) -> str:
    """Opaque same-origin proxy path for an upstream URL (the phone never sees the raw CDN
    URL, and the proxy adds the Referer the CDN needs). The token is baked in so the rewritten
    HLS child URLs work even for native players that can't set request headers."""
    if not url:
        return ""
    enc = base64.urlsafe_b64encode(url.encode()).decode()
    return f"/cast/stream?u={enc}&k={TOKEN}"


def resolve_and_cast(gen: int, search: str, episode: int, title: str, position: float,
                     cover: str = "") -> None:
    """Re-scrape a mobile-playable stream for what's on the PC and arm the cast. mpv's own
    stream URL is bound to its session/headers, so we resolve a fresh one (with a Referer
    we can reuse in the proxy) via the same backup scrapers used for playback."""
    with STATE_LOCK:
        if CAST["gen"] != gen:
            return
        CAST.update(active=True, resolving=True, url="", kind="", error="",
                    position=float(position or 0), title=title, episode=episode)
    broadcast()
    resolved = resolve_fallback(search, episode)
    with STATE_LOCK:
        if CAST["gen"] != gen:
            return
        if not resolved:
            CAST.update(resolving=False, error="Couldn't find a phone-playable source.")
        else:
            url, referrer, subtitle, _prov, _matched = resolved
            kind = "hls" if url.split("?")[0].lower().endswith(".m3u8") else "file"
            CAST.update(resolving=False, error="", url=url, referer=referrer or "",
                        subtitle=subtitle or "", kind=kind)
    if not resolved:
        # Nothing castable — let the PC keep playing rather than sitting frozen.
        mpv_ipc_command(["show-text", "No phone-playable source - resuming", 3000])
        mpv_ipc_command(["set_property", "pause", False])
    else:
        mpv_ipc_command(["show-text", "Now watching on your phone  >>", 4000])
    broadcast()


def start_throw() -> bool:
    """Throw whatever's playing on the PC to the phone: pause mpv, kick a resolve. Returns
    False if nothing is playing."""
    with STATE_LOCK:
        p = STATE.get("playing")
        if not p:
            return False
        CAST["gen"] += 1
        gen = CAST["gen"]
        search = p.get("search") or p.get("title") or ""
        episode = p.get("episode") or 1
        title = p.get("title") or "Shou"
        position = p.get("position") or 0
        cover = p.get("cover", "")
    mpv_ipc_command(["set_property", "pause", True])  # hand off — freeze the PC player
    mpv_ipc_command(["show-text", "Sending to your phone...", 5000])
    socketio.start_background_task(resolve_and_cast, gen, search, episode, title, position, cover)
    return True


def stop_cast(resume_pc: bool = True, position: float | None = None) -> None:
    """Throw back: clear the cast and (optionally) resume the PC player — seeking it to
    where the phone left off so playback continues seamlessly on the big screen."""
    with STATE_LOCK:
        CAST.update(active=False, resolving=False, url="", error="")
        CAST["gen"] += 1
    if resume_pc:
        if position is not None and position > 0:
            mpv_ipc_command(["set_property", "time-pos", float(position)])
        mpv_ipc_command(["set_property", "pause", False])
        mpv_ipc_command(["show-text", ">> Resumed on this screen", 2500])
    broadcast()


# --------------------------------------------------------------------------- #
# State broadcast
# --------------------------------------------------------------------------- #
def broadcast() -> None:
    history = history_snapshot()  # read outside STATE_LOCK (own lock)
    with STATE_LOCK:
        in_search = STATE["list"] == "search" or STATE["view"] in ("search", "detail")
        snapshot = {
            "view": STATE["view"],
            "list": STATE["list"],
            "items": STATE["items"],
            "cursor": STATE["cursor"],
            "sequel": STATE["sequel"],
            "playing": STATE["playing"],
            "rating": STATE["rating"],
            "message": STATE["message"],
            "history": history,
            "search": {
                "query": SEARCH["query"],
                "genres": SEARCH["genres"],
                "allGenres": FILTERS,
                "genreList": GENRES,
                "tagList": TAGS,
                "results": SEARCH["results"],
                "cursor": SEARCH["cursor"],
                "hasMore": SEARCH["hasMore"],
                "detail": SEARCH["detail"],
                "seasons": [
                    {"id": s["id"], "title": s["title"], "year": s.get("year"),
                     "format": s.get("format"), "episodes": s.get("episodes")}
                    for s in SEARCH["seasons"]
                ],
                "seasonIdx": SEARCH["seasonIdx"],
                "busy": SEARCH["busy"],
                "statuses": SEARCH_STATUSES,
                "canWrite": bool(anilist_token()),
            } if in_search else None,
            "cast": {
                "active": CAST["active"],
                "resolving": CAST["resolving"],
                "kind": CAST["kind"],
                "src": _cast_proxy_path(CAST["url"]),
                "sub": _cast_proxy_path(CAST["subtitle"]),
                "position": CAST["position"],
                "title": CAST["title"],
                "episode": CAST["episode"],
                "error": CAST["error"],
            } if CAST["active"] else None,
        }
    socketio.emit("state", snapshot)


def refresh_list() -> None:
    """Pull the currently-selected AniList list and reset to the grid view."""
    with STATE_LOCK:
        mode = STATE["list"]
    label = LIST_LABEL.get(mode, "Currently Watching")
    try:
        entries = fetch_list(mode)
    except Exception as exc:  # noqa: BLE001 - surface any failure to the screen
        with STATE_LOCK:
            STATE.update(view="error", items=[], sequel=None, message=str(exc))
        broadcast()
        return

    cards = [build_card(e) for e in entries]
    _pop_pending_rating()  # a fresh grid (Open / list switch) clears any awaiting rating
    with STATE_LOCK:
        STATE["items"] = cards
        STATE["_entries"] = entries  # keep raw entries for decisions
        STATE["cursor"] = 0
        STATE["sequel"] = None
        STATE["rating"] = None
        STATE["view"] = "grid" if cards else "empty"
        STATE["message"] = "" if cards else f"Nothing in your {label} list."
    broadcast()


# --------------------------------------------------------------------------- #
# Display / PWA routes
# --------------------------------------------------------------------------- #
@app.route("/")
def index():
    return render_template("index.html")


@app.route("/remote")
@require_auth
def remote():
    hostname = socket.gethostname().split(".")[0] or "shou"
    return render_template(
        "remote.html",
        token=request.args.get("k", ""),
        server_ip=_lan_ip(),
        server_host=f"{hostname}.local",
        server_name=hostname,
        server_port=PORT,
    )


@app.route("/whoami")
def whoami():
    """Tiny, unauthenticated, CORS-open identity probe. The phone remote tries it
    on candidate hosts to re-find this server after a network change — it carries
    NO token, only enough to confirm 'a Shou server lives here' and report the
    current LAN IP / hostname so a saved remote can self-heal its address."""
    hostname = socket.gethostname().split(".")[0] or "shou"
    data = {
        "app": "shou",
        "name": hostname,
        "host": f"{hostname}.local",
        "ip": _lan_ip(),
        "port": PORT,
    }
    resp = Response(json.dumps(data), mimetype="application/json")
    resp.headers["Access-Control-Allow-Origin"] = "*"
    return resp


@app.route("/manifest.webmanifest")
def manifest():
    token = request.args.get("k", "")
    data = {
        "name": "Shou Remote",
        "short_name": "Shou",
        "description": "Phone remote for the Shou kiosk",
        "start_url": f"/remote?k={token}",
        "scope": "/",
        "display": "standalone",
        "orientation": "portrait",
        "background_color": "#0a0b12",
        "theme_color": "#0a0b12",
        "icons": [
            {"src": "/static/icon-192.png", "sizes": "192x192", "type": "image/png"},
            {"src": "/static/icon-512.png", "sizes": "512x512", "type": "image/png"},
            {"src": "/static/icon-512-maskable.png", "sizes": "512x512",
             "type": "image/png", "purpose": "maskable"},
        ],
    }
    return Response(json.dumps(data), mimetype="application/manifest+json")


@app.route("/sw.js")
def service_worker():
    return send_from_directory(
        app.static_folder, "sw.js", mimetype="application/javascript"
    )


# --------------------------------------------------------------------------- #
# Control endpoints
# --------------------------------------------------------------------------- #
@app.route("/open", methods=["POST"])
@require_auth
def open_ui():
    reload_config()
    bump_play_gen()
    kill_players()
    with STATE_LOCK:
        STATE["list"] = "watching"  # a clean Open always starts on the watching list
        STATE.update(view="loading", message="Loading your AniList…")
        SEARCH.update(query="", genres=[], results=[], cursor=0, detail=None, busy=False)
    broadcast()
    socketio.start_background_task(refresh_list)
    ensure_kiosk()
    return jsonify(ok=True)


@app.route("/list", methods=["POST"])
@require_auth
def switch_list():
    """Switch which AniList list the grid shows. ?to=watching|planned selects an
    explicit list; with no/unknown target it toggles between the two."""
    target = (request.args.get("to") or "").strip().lower()

    # "Search new" is its own mode: no list fetch, just an empty search box to type into.
    if target == "search":
        with STATE_LOCK:
            STATE["list"] = "search"
            STATE["view"] = "search"
            STATE["message"] = ""
            SEARCH.update(query="", genres=[], results=[], cursor=0, detail=None, busy=False)
        _kick_search()  # populate the browse list (top-rated) right away
        return jsonify(ok=True, list="search")

    with STATE_LOCK:
        if target not in LIST_STATUS:
            target = "planned" if STATE["list"] == "watching" else "watching"
        STATE["list"] = target
        STATE.update(view="loading",
                     message=f"Loading {LIST_LABEL[target]}…")
    broadcast()
    socketio.start_background_task(refresh_list)
    return jsonify(ok=True, list=target)


@app.route("/back", methods=["POST"])
@require_auth
def back():
    # Closing a finished finale (e.g. Back at 90%) should rate it, not skip to the grid.
    pending = _pop_pending_rating()
    bump_play_gen()
    kill_players()
    pid = kiosk_pid()
    if pending:
        show_rating(pending)
        if pid:
            focus_kiosk(pid)
        else:
            ensure_kiosk()
        return jsonify(ok=True, action="rate")

    # Search has its own back stack: detail -> results list -> exit to watching grid.
    with STATE_LOCK:
        view = STATE["view"]
    if view == "detail":
        with STATE_LOCK:
            STATE["view"] = "search"
            SEARCH["detail"] = None
            SEARCH["seasons"] = []
            SEARCH["seasonIdx"] = 0
        broadcast()
        if pid:
            focus_kiosk(pid)
        return jsonify(ok=True, action="search")
    if view == "search":
        with STATE_LOCK:
            STATE["list"] = "watching"
            STATE.update(view="loading", message="Loading…")
            SEARCH.update(query="", genres=[], results=[], cursor=0, detail=None, busy=False)
        broadcast()
        socketio.start_background_task(refresh_list)
        if pid:
            focus_kiosk(pid)
        else:
            ensure_kiosk()
        return jsonify(ok=True, action="exit-search")

    with STATE_LOCK:
        STATE["sequel"] = None
        STATE["playing"] = None
        STATE["rating"] = None
        STATE["view"] = "grid" if STATE["items"] else "empty"
    broadcast()
    if pid:
        focus_kiosk(pid)
    else:
        ensure_kiosk()
    return jsonify(ok=True)


@app.route("/left", methods=["POST"])
@require_auth
def left():
    if not _rating_adjust(-1):  # on the rating page, ◀ lowers the score
        _move(-1)
    return jsonify(ok=True)


@app.route("/right", methods=["POST"])
@require_auth
def right():
    if not _rating_adjust(1):   # on the rating page, ▶ raises the score
        _move(1)
    return jsonify(ok=True)


def _move(delta: int) -> None:
    near_end = False
    with STATE_LOCK:
        view = STATE["view"]
        if view == "search":
            n = len(SEARCH["results"])
            if not n:
                return
            # Clamp (don't wrap) so scrolling down approaches the end and pulls more.
            SEARCH["cursor"] = max(0, min(SEARCH["cursor"] + delta, n - 1))
            near_end = SEARCH["cursor"] >= n - SEARCH_LOAD_AHEAD
        elif view == "detail":
            # Up/down flips between the focused anime's seasons (vertical carousel).
            seasons = SEARCH["seasons"]
            if len(seasons) < 2:
                return
            SEARCH["seasonIdx"] = max(0, min(SEARCH["seasonIdx"] + delta, len(seasons) - 1))
            SEARCH["detail"] = seasons[SEARCH["seasonIdx"]]
        elif view == "grid" and STATE["items"]:
            n = len(STATE["items"])
            STATE["cursor"] = (STATE["cursor"] + delta) % n
        else:
            return
    broadcast()
    if near_end:
        _kick_more_if_needed()


@app.route("/select", methods=["POST"])
@require_auth
def select():
    with STATE_LOCK:
        view = STATE["view"]
        cursor = STATE["cursor"]
        entries = STATE.get("_entries", [])
        sequel = STATE["sequel"]

    # On the rating page: Select submits the chosen score to AniList.
    if view == "rating":
        socketio.start_background_task(submit_rating)
        return jsonify(ok=True, action="rate")

    # In search: Select focuses the highlighted result and loads its detail page.
    if view == "search":
        with STATE_LOCK:
            results = SEARCH["results"]
            cur = SEARCH["cursor"]
            target = results[cur] if 0 <= cur < len(results) else None
            if not target:
                return jsonify(ok=False, reason="no result")
            _focus_detail(target)
        broadcast()
        socketio.start_background_task(load_detail, target["id"])
        return jsonify(ok=True, action="detail")

    # On the sequel card: a second Select plays the sequel from episode 1.
    if view == "sequel" and sequel:
        play(sequel["sequel_search"], 1, sequel["sequel_title"],
             media_id=sequel.get("sequel_id"), total=sequel.get("sequel_total"))
        with STATE_LOCK:
            STATE["view"] = "playing"
            STATE["message"] = f"Playing sequel: {sequel['sequel_title']}"
        broadcast()
        return jsonify(ok=True, action="play_sequel")

    if view != "grid" or not entries or cursor >= len(entries):
        return jsonify(ok=False, reason="nothing selectable")

    media = entries[cursor]["media"]
    decision = episode_decision(entries[cursor])
    if decision["action"] == "play":
        art = media.get("coverImage") or {}
        play(decision["search"], decision["episode"], decision["title"],
             media_id=media.get("id"), total=media.get("episodes"),
             cover=art.get("extraLarge") or art.get("large") or "",
             color=art.get("color") or "#1f2233",
             banner=media.get("bannerImage") or "")
        with STATE_LOCK:
            STATE["view"] = "playing"
            STATE["message"] = f"Playing {decision['title']} — Ep {decision['episode']}"
        broadcast()
        return jsonify(ok=True, action="play")

    # sequel recommendation
    with STATE_LOCK:
        STATE["view"] = "sequel"
        STATE["sequel"] = {
            "finished": decision["finished"],
            "sequel_title": decision["sequel_title"],
            "sequel_search": decision["sequel_search"],
            "sequel_id": decision.get("sequel_id"),
            "sequel_total": decision.get("sequel_total"),
        }
    broadcast()
    return jsonify(ok=True, action="sequel")


@app.route("/resume", methods=["POST"])
@require_auth
def resume_play():
    """Resume a continue-watching entry on the PC, from its saved position.
    Identified by ?media_id=…&episode=… (the phone taps a card in its resume rail)."""
    try:
        media_id = int(request.args.get("media_id") or 0)
        episode = int(request.args.get("episode") or 0)
    except ValueError:
        return jsonify(ok=False, reason="bad params")

    entry = next((e for e in history_snapshot()
                  if e.get("media_id") == media_id and e.get("episode") == episode), None)
    if not entry:
        return jsonify(ok=False, reason="no such resume point")

    # Resume a few seconds early so you can re-orient where you left off.
    start = max(0.0, (entry.get("position") or 0) - RESUME_REWIND_SECONDS)
    play(entry.get("search") or entry.get("title"), episode, entry.get("title"),
         media_id=media_id, total=entry.get("total"),
         cover=entry.get("cover", ""), color=entry.get("color", ""),
         banner=entry.get("banner", ""), start=start)
    with STATE_LOCK:
        STATE["view"] = "playing"
        STATE["message"] = f"Resuming {entry.get('title')} — Ep {episode}"
    broadcast()
    return jsonify(ok=True, action="resume")


@app.route("/forget", methods=["POST"])
@require_auth
def forget_play():
    """Remove a single continue-watching entry (the phone taps its × button).
    Identified by ?media_id=…&episode=…."""
    try:
        media_id = int(request.args.get("media_id") or 0)
        episode = int(request.args.get("episode") or 0)
    except ValueError:
        return jsonify(ok=False, reason="bad params")

    forget_resume(media_id, episode)
    broadcast()
    return jsonify(ok=True, action="forget")


# --------------------------------------------------------------------------- #
# "Search new" endpoints — the query is edited one character at a time so the
# phone keyboard and the kiosk's physical keyboard both drive the same text.
# --------------------------------------------------------------------------- #
@app.route("/search/key", methods=["POST"])
@require_auth
def search_key():
    """Append one typed character to the shared search query."""
    c = (request.args.get("c") or "")[:1]
    if not c or not c.isprintable():
        return jsonify(ok=False, reason="bad char")
    with STATE_LOCK:
        STATE["view"] = "search"
        STATE["list"] = "search"
        SEARCH["detail"] = None
        SEARCH["query"] = (SEARCH["query"] + c)[:80]
    _kick_search()
    return jsonify(ok=True)


@app.route("/search/back", methods=["POST"])
@require_auth
def search_backspace():
    """Delete the last character of the shared search query."""
    with STATE_LOCK:
        STATE["view"] = "search"
        SEARCH["detail"] = None
        SEARCH["query"] = SEARCH["query"][:-1]
    _kick_search()
    return jsonify(ok=True)


@app.route("/search/clear", methods=["POST"])
@require_auth
def search_clear():
    """Wipe the search query and results."""
    with STATE_LOCK:
        STATE["view"] = "search"
        SEARCH.update(query="", results=[], cursor=0, detail=None)
    _kick_search()
    return jsonify(ok=True)


@app.route("/search/genre", methods=["POST"])
@require_auth
def search_genre():
    """Toggle one genre filter on/off, then re-run the search/browse."""
    g = (request.args.get("g") or "").strip()
    if g not in FILTERS_SET:
        return jsonify(ok=False, reason="unknown filter")
    with STATE_LOCK:
        STATE["view"] = "search"
        STATE["list"] = "search"
        SEARCH["detail"] = None
        if g in SEARCH["genres"]:
            SEARCH["genres"].remove(g)
        else:
            SEARCH["genres"].append(g)
    _kick_search()
    return jsonify(ok=True)


@app.route("/search/genres/clear", methods=["POST"])
@require_auth
def search_genres_clear():
    """Drop all active genre filters and re-run."""
    with STATE_LOCK:
        STATE["view"] = "search"
        SEARCH["detail"] = None
        SEARCH["genres"] = []
    _kick_search()
    return jsonify(ok=True)


@app.route("/search/more", methods=["POST"])
@require_auth
def search_more():
    """Append the next page of results, if any (infinite scroll from the phone)."""
    loading = _kick_more_if_needed()
    return jsonify(ok=True, loading=loading)


@app.route("/search/pick", methods=["POST"])
@require_auth
def search_pick():
    """Focus a result by index (the phone taps a result card) and open its detail."""
    try:
        i = int(request.args.get("i") or -1)
    except ValueError:
        return jsonify(ok=False, reason="bad index")
    with STATE_LOCK:
        results = SEARCH["results"]
        if not (0 <= i < len(results)):
            return jsonify(ok=False, reason="out of range")
        SEARCH["cursor"] = i
        target = results[i]
        _focus_detail(target)
    broadcast()
    socketio.start_background_task(load_detail, target["id"])
    return jsonify(ok=True, action="detail")


@app.route("/status", methods=["POST"])
@require_auth
def set_status():
    """Set (or remove) the AniList list status of the focused-detail anime."""
    if not anilist_token():
        return jsonify(ok=False, reason="no token")
    to = (request.args.get("to") or "").strip().upper()
    valid = {s for s, _ in SEARCH_STATUSES} | {"REMOVE"}
    if to not in valid:
        return jsonify(ok=False, reason="bad status")
    with STATE_LOCK:
        detail = SEARCH["detail"]
        media_id = detail.get("id") if detail else None
        entry_id = detail.get("entryId") if detail else None
        if not media_id:
            return jsonify(ok=False, reason="no anime")
        SEARCH["busy"] = True
    broadcast()
    socketio.start_background_task(apply_status, int(media_id), to, entry_id)
    return jsonify(ok=True, action="status")


@app.route("/pause", methods=["POST"])
@require_auth
def pause():
    mpv_ipc_command(["cycle", "pause"])
    return jsonify(ok=True)


def _seek_seconds() -> int:
    """Seek step in seconds from ?s=… (default 30), clamped to a sane range."""
    try:
        s = int(request.args.get("s", 30))
    except (TypeError, ValueError):
        s = 30
    return max(1, min(s, 600))


@app.route("/fwd", methods=["POST"])
@require_auth
def seek_forward():
    mpv_ipc_command(["seek", _seek_seconds(), "relative"])
    return jsonify(ok=True)


@app.route("/rew", methods=["POST"])
@require_auth
def seek_backward():
    mpv_ipc_command(["seek", -_seek_seconds(), "relative"])
    return jsonify(ok=True)


@app.route("/next", methods=["POST"])
@require_auth
def next_episode():
    return _step_episode(1)


@app.route("/prev", methods=["POST"])
@require_auth
def prev_episode():
    return _step_episode(-1)


def _step_episode(delta: int):
    with STATE_LOCK:
        playing = STATE.get("playing")
    if not playing:
        return jsonify(ok=False, reason="nothing playing")
    episode = max(1, playing["episode"] + delta)
    play(playing.get("search") or playing["title"], episode, playing["title"],
         media_id=playing.get("media_id"), total=playing.get("total"),
         cover=playing.get("cover", ""), color=playing.get("color", ""),
         banner=playing.get("banner", ""))
    with STATE_LOCK:
        STATE["view"] = "playing"
        STATE["message"] = f"Playing {playing['title']} — Ep {episode}"
    broadcast()
    return jsonify(ok=True, episode=episode)


@app.route("/volume", methods=["POST"])
@require_auth
def volume():
    """Nudge mpv's volume (the phone's hardware volume rocker, forwarded by the native
    app, lands here). ?d=up|down steps by ?s=… (default 5); ?d=mute toggles mute."""
    d = (request.args.get("d") or "up").strip().lower()
    if d == "mute":
        mpv_ipc_command(["cycle", "mute"])
        return jsonify(ok=True, action="mute")
    try:
        step = int(request.args.get("s", 5))
    except (TypeError, ValueError):
        step = 5
    step = max(1, min(step, 50))
    mpv_ipc_command(["add", "volume", -step if d == "down" else step])
    return jsonify(ok=True, action=d)


@app.route("/airing")
@require_auth
def airing():
    """Compact feed of your Watching list: for each show, how far you've watched
    (progress) vs how many episodes have actually aired (available). The native app
    polls this to notify you when a show you're watching gets a new episode. Returns
    no titles you aren't tracking and never the token — it's already token-gated."""
    try:
        entries = fetch_list("watching")
    except Exception as exc:  # noqa: BLE001 - report empty rather than 500 the worker
        return jsonify(shows=[], error=str(exc))
    shows = []
    for e in entries:
        media = e.get("media") or {}
        shows.append({
            "id": media.get("id"),
            "title": _title(media.get("title")),
            "progress": e.get("progress") or 0,
            "available": last_released_episode(media) or 0,
        })
    return jsonify(shows=shows)


@app.route("/throw", methods=["POST"])
@require_auth
def throw():
    """Throw what's playing on the PC to the phone (also bound to mpv's `t` key)."""
    if not start_throw():
        return jsonify(ok=False, reason="nothing playing")
    return jsonify(ok=True)


@app.route("/cast/clear", methods=["POST"])
@require_auth
def cast_clear():
    """Throw back: stop casting to the phone and resume on the PC at ?pos= seconds."""
    try:
        pos = float(request.args.get("pos", 0) or 0)
    except (TypeError, ValueError):
        pos = 0.0
    stop_cast(resume_pc=True, position=pos)
    return jsonify(ok=True)


# Streamed media is large; allow a generous read window for slow CDNs.
CAST_CHUNK = 64 * 1024
_M3U8_ATTR_URI = re.compile(r'URI="([^"]+)"')


@app.route("/cast/stream")
@require_auth
def cast_stream():
    """Same-origin proxy for the thrown stream. The phone can't replay a scraped CDN URL
    directly — it needs the Referer the CDN requires and would hit CORS — so we fetch it
    here (adding that Referer) and re-serve it. For HLS we rewrite every playlist URI to
    point back through this proxy; for a plain file we pass Range through so seeking works."""
    raw = request.args.get("u", "")
    try:
        url = base64.urlsafe_b64decode(raw.encode()).decode()
    except Exception:
        abort(400)
    if not url.lower().startswith(("http://", "https://")):
        abort(400)

    headers = {"User-Agent": ALLANIME_AGENT}
    if CAST.get("referer"):
        headers["Referer"] = CAST["referer"]
    rng = request.headers.get("Range")
    if rng:
        headers["Range"] = rng

    try:
        upstream = requests.get(url, headers=headers, stream=True, timeout=20)
    except requests.RequestException as exc:
        print(f"[cast] upstream fetch failed: {exc}", flush=True)
        abort(502)

    ctype = upstream.headers.get("Content-Type", "")
    is_m3u8 = url.split("?")[0].lower().endswith(".m3u8") or "mpegurl" in ctype.lower()

    if is_m3u8:
        text = upstream.content.decode("utf-8", "replace")

        def rewrite(line: str) -> str:
            s = line.strip()
            if not s:
                return line
            if s.startswith("#"):
                # Rewrite any URI="..." attribute (keys, media, maps).
                return _M3U8_ATTR_URI.sub(
                    lambda m: 'URI="%s"' % _cast_proxy_path(urljoin(url, m.group(1))), line)
            return _cast_proxy_path(urljoin(url, s))  # a segment / sub-playlist line

        body = "\n".join(rewrite(ln) for ln in text.splitlines())
        resp = Response(body, content_type="application/vnd.apple.mpegurl")
        resp.headers["Access-Control-Allow-Origin"] = "*"
        return resp

    def pump():
        try:
            for chunk in upstream.iter_content(CAST_CHUNK):
                if chunk:
                    yield chunk
        finally:
            upstream.close()

    resp = Response(pump(), status=upstream.status_code,
                    content_type=ctype or "application/octet-stream")
    for h in ("Content-Length", "Content-Range", "Accept-Ranges"):
        if h in upstream.headers:
            resp.headers[h] = upstream.headers[h]
    resp.headers["Access-Control-Allow-Origin"] = "*"
    return resp


@socketio.on("connect")
def on_connect():
    if not authorized():
        return False  # reject unauthorized socket connections
    broadcast()
    return None


def _lan_ip() -> str:
    """Best-effort primary LAN IPv4 — the local address used to reach the network.
    No packets are actually sent; connect() just selects the outgoing interface."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def start_mdns():
    """Advertise this server over mDNS as `_shou._tcp` so the phone app can find
    host+port without typing an IP. The token is NEVER broadcast — it stays secret;
    the app still asks the user for it. Returns a cleanup callable (a no-op if
    zeroconf isn't installed, so the server runs fine either way)."""
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except Exception:
        print("  mDNS    : zeroconf not installed — auto-discovery off", flush=True)
        return lambda: None
    try:
        ip = _lan_ip()
        hostname = socket.gethostname().split(".")[0] or "shou"
        info = ServiceInfo(
            "_shou._tcp.local.",
            f"Shou ({hostname})._shou._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=PORT,
            properties={"path": "/remote", "app": "shou"},
            server=f"{hostname}.local.",
        )
        zc = Zeroconf()
        zc.register_service(info)
        print(f"  mDNS    : advertising _shou._tcp at {ip}:{PORT}", flush=True)

        def _close():
            try:
                zc.unregister_service(info)
            finally:
                zc.close()
        return _close
    except Exception as exc:  # pragma: no cover - best effort
        print(f"  mDNS    : could not advertise ({exc})", flush=True)
        return lambda: None


if __name__ == "__main__":
    ensure_input_conf()  # mpv `t` -> throw to phone
    local_url = f"http://127.0.0.1:{PORT}/remote?k={TOKEN}"
    lan_url = f"http://shio-t0.local:{PORT}/remote?k={TOKEN}"
    print("Shou server listening on 0.0.0.0:%d" % PORT)
    print(f"  remote (local) : {local_url}")
    print(f"  remote (phone) : {lan_url}")
    mdns_close = start_mdns()
    try:
        socketio.run(app, host="0.0.0.0", port=PORT, allow_unsafe_werkzeug=True)
    finally:
        mdns_close()
