#!/usr/bin/env python3
"""Shou (Windows) — phone-controlled AniList "Currently Watching" launcher.

A long-running Flask + SocketIO server that is the single brain for Shou:
  * fetches your AniList "Currently Watching" list (public username, no auth),
  * shows a live carousel UI in a browser kiosk (which it launches itself),
  * serves a touch-first phone web-remote (PWA) that mirrors the kiosk live,
  * resolves + plays episodes through anipy + mpv (fullscreen),
  * optionally marks episodes watched back on AniList as you finish them.

This is the WINDOWS port. Compared to the Linux version it:
  * controls mpv over its JSON IPC named pipe instead of playerctl — so it needs
    no mpv-mpris / playerctl,
  * sources episodes purely through anipy (pure Python) instead of ani-cli (bash),
  * stops the player with taskkill + an IPC `quit` instead of pkill/signals,
  * opens a browser kiosk (Firefox / Edge / Chrome) with no compositor tricks.

Control endpoints are reachable from loopback without auth (the kiosk page) and require
a shared-secret token (?k=…) from any networked client (the phone web-remote).
"""

import difflib
import hmac
import json
import math
import os
import re
import secrets
import shutil
import struct
import subprocess
import threading
import time
import wave
from functools import wraps
from pathlib import Path

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

# Don't pop up a console window for the helper processes we spawn (mpv, taskkill).
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

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
           "ANILIST_TOKEN": "", "WATCHED_PERCENT": "90", "MPV_BIN": "", "BROWSER": "",
           "BASH_BIN": "", "ANI_CLI": ""}
    if CONFIG_FILE.exists():
        for raw in CONFIG_FILE.read_text(encoding="utf-8").splitlines():
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
    with CONFIG_FILE.open("a", encoding="utf-8") as fh:
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
    "busy": False,      # a search / detail / status write is in flight
    "gen": 0,           # debounce generation — only the newest keystroke's query applies
    "page": 1,          # last AniList page fetched into results
    "hasMore": False,   # AniList reports a further page (infinite scroll)
    "loadingMore": False,  # a load-more append is in flight
}
SEARCH_MAX = 14            # results fetched per page (appended as you scroll)
SEARCH_LOAD_AHEAD = 5      # start loading the next page this many rows from the end
# AniList's non-adult genre vocabulary, in the order the phone's filter grid shows them.
# Selecting several narrows results (AniList's genre_in is an AND filter).
GENRES = [
    "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy",
    "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological",
    "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
]
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

# Tracks the in-flight playback so we can supersede it and stop only OUR mpv.
PLAYBACK = {"gen": 0, "proc": None}
# A finished series (final episode completed) awaiting its rating page. Set the moment
# completion is detected — while mpv may still be open — so whatever closes the player
# (a clean EOF, or pressing Back at 90%) can pop it and show the rating.
PENDING_RATING = {"info": None}
# mpv's JSON IPC endpoint. On Windows this is a named pipe; we use it both to control
# playback (pause / seek / quit) and to watch progress for AniList mark-watched.
MPV_IPC = r"\\.\pipe\shou-mpv"
# Source scrapers (anipy_api providers) tried in order. allanime is the same site
# ani-cli uses; animekai is a genuinely different source as a backstop.
SOURCE_PROVIDERS = ["allanime", "animekai"]

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

# ani-cli is the primary source on Windows (a bash script run under Git Bash); anipy is the
# backup tried when ani-cli finds nothing. ani-cli needs bash + fzf on the system (the
# installer sets these up) and sends its mpv to the same IPC pipe, so pause/seek/progress/
# resume all keep working.
LAUNCH_TIMEOUT = 30.0                       # secs to wait for ani-cli's mpv to come up
ANI_LOG = CONFIG_DIR / "ani-cli-last.log"   # ani-cli's captured output, for diagnostics

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


def notify(title: str, body: str) -> None:
    """Best-effort desktop notification. Always logs; a real toast is intentionally
    omitted to keep Shou dependency-free on Windows."""
    print(f"[notify] {title} — {body}", flush=True)


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
    """Search title — prefer romaji (allanime matches it best)."""
    if not title_obj:
        return "Unknown"
    return title_obj.get("romaji") or title_obj.get("english") or "Unknown"


def fetch_list(mode: str = "watching") -> list:
    """Return the user's entries for the given list mode (raw AniList entry dicts).

    mode is one of LIST_STATUS keys ("watching" -> CURRENT, "planned" -> PLANNING).
    """
    user = CONFIG.get("ANILIST_USER", "").strip()
    if not user or user == "CHANGE_ME":
        raise RuntimeError("ANILIST_USER is not set in shou.conf")
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
    search = _search_title(media["title"])   # for the scraper
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
query ($search: String, $perPage: Int, $page: Int, $genres: [String], $sort: [MediaSort]) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage }
    media(search: $search, genre_in: $genres, type: ANIME, sort: $sort, isAdult: false) {
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
    genres
    averageScore
    description(asHtml: false)
    coverImage { extraLarge large color }
    bannerImage
    studios(isMain: true) { nodes { name } }
    mediaListEntry { id status score progress }
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
    if genres:
        variables["genres"] = genres
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
    SEARCH["detail"] = {  # lightweight placeholder until the full detail lands
        "loading": True, "id": target["id"], "title": target["title"],
        "format": target.get("format", ""), "episodes": target.get("episodes"),
        "year": target.get("year"), "score": target.get("score"),
        "cover": target["cover"], "color": target["color"],
        "banner": target["banner"], "listStatus": target.get("listStatus"),
        "genres": [], "studio": "", "description": "", "entryId": None,
    }


def load_detail(media_id: int) -> None:
    """Fetch the richer detail for a focused result and broadcast it."""
    detail = None
    try:
        data = _public_post(DETAIL_QUERY, {"id": int(media_id)})
        detail = build_detail(data.get("Media") or {})
    except Exception as exc:  # noqa: BLE001
        print(f"[search] detail {media_id} failed: {exc}", flush=True)
    with STATE_LOCK:
        cur = SEARCH["detail"]
        if STATE["view"] == "detail" and cur and cur.get("id") == int(media_id):
            if detail:
                SEARCH["detail"] = detail
            else:
                cur["loading"] = False  # keep the lightweight placeholder
            SEARCH["busy"] = False
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
        notify("✓ AniList", f"{display} — Episode {episode} watched")
    except Exception as exc:  # noqa: BLE001
        print(f"[anilist] mark_watched failed: {exc}", flush=True)


def _open_ipc():
    """Open mpv's JSON IPC named pipe for read+write, or None if it isn't there yet."""
    try:
        return open(MPV_IPC, "r+b", buffering=0)
    except OSError:
        return None


def mpv_ipc_command(command: list) -> None:
    """Fire one JSON command at mpv over its IPC pipe (best-effort, no reply needed)."""
    pipe = _open_ipc()
    if pipe is None:
        return
    try:
        pipe.write(json.dumps({"command": command}).encode("utf-8") + b"\n")
    except OSError:
        pass
    finally:
        try:
            pipe.close()
        except OSError:
            pass


def watch_playback(gen: int, media_id: int, episode: int, total: int | None,
                   display: str, search: str = "", cover: str = "",
                   color: str = "", banner: str = "") -> None:
    """Track Shou's mpv over its IPC pipe. Two jobs:

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

    # Wait for mpv to create the pipe (it may lag behind a slow source).
    pipe = None
    end = time.time() + 180
    while time.time() < end and PLAYBACK["gen"] == gen:
        pipe = _open_ipc()
        if pipe is not None:
            break
        time.sleep(1)
    if pipe is None or PLAYBACK["gen"] != gen:
        if pipe is not None:
            pipe.close()
        return

    completed = False  # crossed the threshold or hit a clean EOF
    last_pos = 0.0     # furthest percent seen
    last_time = 0.0    # furthest time-pos seen (seconds)
    duration = 0.0
    is_final = bool(total) and episode >= total and bool(anilist_token())

    def _finish():
        """Mark watched once, the moment completion is detected (so a quick Back right
        after the threshold still counts). Idempotent via the `completed` flag. If this
        was the final episode, arm the rating now — before mpv is even closed — so closing
        it (clean EOF or Back at 90%) still surfaces the rating page."""
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
        pipe.write(b'{"command":["observe_property",1,"percent-pos"]}\n')
        pipe.write(b'{"command":["observe_property",2,"time-pos"]}\n')
        pipe.write(b'{"command":["observe_property",3,"duration"]}\n')
        while PLAYBACK["gen"] == gen:
            line = pipe.readline()  # returns b"" when mpv exits / pipe closes
            if not line:
                break  # mpv exited / was killed
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
                elif name == "duration" and isinstance(data, (int, float)):
                    duration = data
            elif ev == "end-file" and msg.get("reason") == "eof" and not completed:
                completed = True
                _finish()
    except OSError:
        pass
    finally:
        try:
            pipe.close()
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


# --------------------------------------------------------------------------- #
# Browser kiosk control
# --------------------------------------------------------------------------- #
def _pid_alive(pid: int) -> bool:
    """True if a process with this PID is currently running (via tasklist)."""
    try:
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            capture_output=True, text=True, creationflags=CREATE_NO_WINDOW,
        ).stdout
    except OSError:
        return False
    return str(pid) in out


def kiosk_pid() -> int | None:
    """Return the PID of the running browser kiosk, or None."""
    try:
        pid = int(FF_PIDFILE.read_text().strip())
    except (FileNotFoundError, ValueError):
        return None
    return pid if _pid_alive(pid) else None


def _find_browser() -> list | None:
    """Locate a kiosk-capable browser. Returns argv prefix (exe + kiosk flags) or None.
    Honors a BROWSER override in shou.conf (full path to firefox/edge/chrome .exe)."""
    pf = os.environ.get("ProgramFiles", r"C:\Program Files")
    pf86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
    local = os.environ.get("LOCALAPPDATA", "")

    override = (CONFIG.get("BROWSER") or "").strip()
    candidates = []
    if override:
        candidates.append(("custom", override))
    candidates += [
        ("firefox", rf"{pf}\Mozilla Firefox\firefox.exe"),
        ("firefox", rf"{pf86}\Mozilla Firefox\firefox.exe"),
        ("edge", rf"{pf86}\Microsoft\Edge\Application\msedge.exe"),
        ("edge", rf"{pf}\Microsoft\Edge\Application\msedge.exe"),
        ("chrome", rf"{pf}\Google\Chrome\Application\chrome.exe"),
        ("chrome", rf"{pf86}\Google\Chrome\Application\chrome.exe"),
        ("chrome", rf"{local}\Google\Chrome\Application\chrome.exe"),
    ]
    for kind, path in candidates:
        if not path or not os.path.exists(path):
            continue
        if kind == "firefox" or (kind == "custom" and "firefox" in path.lower()):
            return [path, "--kiosk", "--profile", str(FF_PROFILE)]
        if kind == "edge" or (kind == "custom" and "msedge" in path.lower()):
            return [path, "--kiosk", "--edge-kiosk-type=fullscreen",
                    "--no-first-run", f"--user-data-dir={FF_PROFILE}"]
        # chrome / generic chromium
        return [path, "--kiosk", "--no-first-run", f"--user-data-dir={FF_PROFILE}"]
    return None


def ensure_kiosk() -> None:
    """Make sure the fullscreen kiosk is showing; launch it if it isn't already up.
    (Windows has no compositor refocus trick — a live kiosk simply stays fullscreen.)"""
    if kiosk_pid():
        return
    prefix = _find_browser()
    if prefix is None:
        print("[kiosk] no Firefox/Edge/Chrome found — open "
              f"http://127.0.0.1:{PORT}/ manually.", flush=True)
        return
    FF_PROFILE.mkdir(parents=True, exist_ok=True)
    argv = prefix + [f"http://127.0.0.1:{PORT}/"]
    proc = subprocess.Popen(
        argv,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=CREATE_NO_WINDOW,
    )
    FF_PIDFILE.write_text(str(proc.pid))


# --------------------------------------------------------------------------- #
# Playback / process control
# --------------------------------------------------------------------------- #
def mpv_bin() -> str:
    """Path/name of the mpv executable (configurable; defaults to PATH lookup)."""
    return (CONFIG.get("MPV_BIN") or "").strip() or "mpv"


def kill_players() -> None:
    """Stop ONLY the mpv Shou launched — never unrelated mpv instances. We track our
    own mpv process, so we ask it to quit over IPC, then force-kill that PID tree."""
    mpv_ipc_command(["quit"])
    proc = PLAYBACK.get("proc")
    if proc is not None and proc.poll() is None:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=CREATE_NO_WINDOW, check=False,
        )


def bump_play_gen() -> int:
    """Invalidate any in-flight playback monitor and return a fresh generation id."""
    with STATE_LOCK:
        PLAYBACK["gen"] += 1
        return PLAYBACK["gen"]


def mpv_running() -> bool:
    """True only if Shou's own mpv is still up (we launched and track it directly)."""
    proc = PLAYBACK.get("proc")
    return proc is not None and proc.poll() is None


def mpv_ipc_alive() -> bool:
    """True if Shou's mpv IPC pipe is open — i.e. our mpv is up. Used to detect when the
    ani-cli backup (which launches its OWN mpv) has actually started playback."""
    pipe = _open_ipc()
    if pipe is None:
        return False
    try:
        pipe.close()
    except OSError:
        pass
    return True


def find_bash() -> str | None:
    """Locate Git Bash's bash.exe (NOT the System32 WSL launcher, which we must avoid).
    Honours a BASH_BIN override in shou.conf, then checks scoop's git + a system Git."""
    home = Path.home()
    candidates = [
        (CONFIG.get("BASH_BIN") or "").strip(),
        str(home / "scoop" / "apps" / "git" / "current" / "bin" / "bash.exe"),
        str(home / "scoop" / "apps" / "git" / "current" / "usr" / "bin" / "bash.exe"),
        rf"{os.environ.get('ProgramFiles', r'C:\Program Files')}\Git\bin\bash.exe",
        rf"{os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')}\Git\bin\bash.exe",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None


def anicli_script() -> Path:
    """Path to the ani-cli bash script (installer fetches it into the config dir)."""
    override = (CONFIG.get("ANI_CLI") or "").strip()
    return Path(override) if override else (CONFIG_DIR / "ani-cli")


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


def anicli_play(gen: int, search_title: str, episode: int, display: str,
                total: int | None = None, start: float = 0.0) -> bool:
    """Backup source: drive ani-cli (under Git Bash) to resolve + launch mpv on the same
    IPC pipe. Returns True if our mpv came up (or playback was superseded), else False."""
    bash = find_bash()
    script = anicli_script()
    if not bash:
        print("[anicli] Git Bash not found — ani-cli backup unavailable "
              "(install Git for Windows, or set BASH_BIN)", flush=True)
        return False
    if not script.exists():
        print(f"[anicli] script missing at {script} — re-run install.ps1 to fetch it",
              flush=True)
        return False

    quality = CONFIG.get("QUALITY") or "1080p"
    # Use a bare 'mpv' (on PATH via scoop) to dodge Windows path/space issues inside bash.
    # The IPC pipe path's backslashes pass through bash unprocessed, straight to mpv.
    player = f"mpv --fs --input-ipc-server={MPV_IPC}"
    if start and start > 0:
        player += f" --start={int(start)}"
    # -S N auto-picks the Nth search result so ani-cli never needs the fzf TTY menu. We
    # pre-pick N by replaying ani-cli's own search and matching episode count + title, so
    # "Fate/Zero" beats "Fate/Zero: Onegai..." instead of grabbing whatever's first.
    nth = anicli_result_index(search_title, total) or 1
    cmd = [bash, str(script).replace("\\", "/"),
           "-S", str(nth), "-q", quality, "-e", str(episode), search_title]
    print(f"[anicli] {search_title!r} ep {episode} via {bash}", flush=True)
    try:
        logf = open(ANI_LOG, "w", encoding="utf-8", errors="ignore")
    except OSError:
        logf = subprocess.DEVNULL
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=logf,
        stderr=subprocess.STDOUT,
        env=os.environ | {"ANI_CLI_PLAYER": player},
        creationflags=CREATE_NO_WINDOW,
    )
    if logf is not subprocess.DEVNULL:
        logf.close()
    with STATE_LOCK:
        PLAYBACK["proc"] = proc

    # ani-cli scrapes first, so wait for it to actually bring mpv up — or to give up.
    deadline = time.monotonic() + LAUNCH_TIMEOUT
    while time.monotonic() < deadline:
        if PLAYBACK["gen"] != gen:
            return True  # superseded by a newer play — treat as handled
        if mpv_ipc_alive():
            return True
        if proc.poll() is not None:
            # ani-cli exited without launching mpv; brief grace, then declare failure.
            for _ in range(6):
                time.sleep(0.5)
                if PLAYBACK["gen"] != gen:
                    return True
                if mpv_ipc_alive():
                    return True
            return False
        time.sleep(1.0)
    return mpv_ipc_alive()


def resolve_stream(search_title: str, episode: int):
    """Find a playable stream for the requested episode via anipy's scrapers.
    Returns (url, referrer, subtitle, provider, matched_name) or None."""
    try:
        from anipy_api.provider import get_provider, LanguageTypeEnum
    except Exception as exc:  # noqa: BLE001
        print(f"[source] anipy_api unavailable: {exc}", flush=True)
        return None

    for prov_name in SOURCE_PROVIDERS:
        try:
            prov = get_provider(prov_name)
            if not prov:
                continue
            results = prov.get_search(search_title)
        except Exception as exc:  # noqa: BLE001
            print(f"[source] {prov_name} search failed: {exc}", flush=True)
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
            print(f"[source] {prov_name} matched {anime.name!r} ep {episode} "
                  f"@ {best.resolution}p", flush=True)
            return best.url, best.referrer, best.subtitle, prov_name, anime.name
    return None


def _launch_mpv(url: str, display: str, episode: int,
                referrer: str | None, subtitle: str | None, start: float = 0.0):
    """Spawn fullscreen mpv on the resolved stream, tagged with our IPC pipe.
    A non-zero `start` (seconds) opens a resumed episode at the saved position."""
    cmd = [mpv_bin(), "--fs", f"--input-ipc-server={MPV_IPC}",
           f"--force-media-title={display} — Episode {episode}"]
    if start and start > 0:
        cmd.append(f"--start={int(start)}")
    if referrer:
        cmd.append(f"--referrer={referrer}")
    if subtitle:
        cmd.append(f"--sub-file={subtitle}")
    cmd.append(url)
    return subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=CREATE_NO_WINDOW,
    )


def play(search_title: str, episode: int, display_title: str | None = None,
         media_id: int | None = None, total: int | None = None,
         cover: str = "", color: str = "", banner: str = "",
         start: float = 0.0) -> None:
    """Resolve the title/episode and play it fullscreen. The slow scrape + launch run in
    a background task so the HTTP request returns immediately; the UI shows progress.

    `search_title` (romaji) is what the scraper searches; `display_title` (english) is
    what the UI/notification shows. When `media_id` is known a watcher marks the episode
    watched once playback completes (if an AniList token is set) and records a resume
    point if you stop mid-episode. `start` (seconds) resumes playback from a saved
    position; cover/color/banner travel into the history.
    """
    display = display_title or search_title
    start = float(start or 0)
    _pop_pending_rating()  # starting new playback cancels any awaiting finale rating
    gen = bump_play_gen()
    with STATE_LOCK:
        STATE["view"] = "playing"
        STATE["message"] = f"Searching for {display} — Ep {episode}…"
        STATE["playing"] = {"title": display, "search": search_title, "episode": episode,
                            "media_id": media_id, "total": total,
                            "cover": cover, "color": color, "banner": banner}
    broadcast()
    socketio.start_background_task(_play_task, gen, search_title, episode,
                                   display, media_id, total, cover, color, banner, start)


def _start_watcher(gen, media_id, episode, total, display, search, cover, color, banner):
    """Start the playback watcher (mark-watched + resume capture) when a media_id is
    known. Runs with or without an AniList token; covers anipy and ani-cli mpv alike."""
    if media_id:
        socketio.start_background_task(watch_playback, gen, media_id, episode,
                                       total, display, search, cover, color, banner)


def _play_task(gen: int, search_title: str, episode: int, display: str,
               media_id: int | None, total: int | None, cover: str = "",
               color: str = "", banner: str = "", start: float = 0.0) -> None:
    """Background worker: stop the old player, resolve a stream, launch mpv, report.
    ani-cli is the primary source; anipy is the backup tried when ani-cli finds nothing."""
    kill_players()
    time.sleep(0.3)
    if PLAYBACK["gen"] != gen:
        return
    print(f"[play] resolving search={search_title!r} episode={episode} "
          f"start={int(start)}s", flush=True)

    # Primary: ani-cli (lets it launch mpv itself, on our IPC pipe).
    if anicli_play(gen, search_title, episode, display, total, start):
        with STATE_LOCK:
            if PLAYBACK["gen"] != gen:
                return
            STATE["view"] = "playing"
            STATE["message"] = f"Playing {display} — Ep {episode}  ·  ani-cli"
        broadcast()
        notify("🎌 Now Watching", f"{display} — Episode {episode} (ani-cli)")
        _start_watcher(gen, media_id, episode, total, display, search_title,
                       cover, color, banner)
        return

    # Backup: ani-cli found nothing (or isn't installed) — fall back to anipy's scrapers.
    if PLAYBACK["gen"] != gen:
        return
    print(f"[play] ani-cli found no source for {search_title!r} ep {episode}; "
          "trying anipy backup", flush=True)
    with STATE_LOCK:
        STATE["message"] = f"Searching anipy backup for {display}…"
    broadcast()
    kill_players()
    time.sleep(0.3)
    if PLAYBACK["gen"] != gen:
        return
    resolved = resolve_stream(search_title, episode)
    if PLAYBACK["gen"] != gen:
        return
    if resolved:
        url, referrer, subtitle, prov_name, _matched = resolved
        proc = _launch_mpv(url, display, episode, referrer, subtitle, start)
        with STATE_LOCK:
            PLAYBACK["proc"] = proc
            STATE["view"] = "playing"
            STATE["message"] = f"Playing {display} — Ep {episode}  ·  {prov_name}"
        broadcast()
        notify("🎌 Now Watching", f"{display} — Episode {episode}")
        _start_watcher(gen, media_id, episode, total, display, search_title,
                       cover, color, banner)
        return

    # Both sources failed.
    if PLAYBACK["gen"] != gen:
        return
    msg = (f"No playable source for “{display}” (ep {episode}). "
           "Press Back and try another.")
    print(f"[play] FAILED — {msg}", flush=True)
    with STATE_LOCK:
        STATE["view"] = "error"
        STATE["message"] = msg
        STATE["playing"] = None
    broadcast()
    notify("🎌 Shou", f"⚠ {msg}")


# --------------------------------------------------------------------------- #
# Continue-watching history (resume points for episodes stopped mid-way)
# --------------------------------------------------------------------------- #
def _load_history() -> list:
    try:
        data = json.loads(HISTORY_FILE.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (FileNotFoundError, ValueError, OSError):
        return []


def _save_history(items: list) -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        HISTORY_FILE.write_text(json.dumps(items), encoding="utf-8")
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
            env = math.exp(-2.6 * t)
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
    """Play the completion chime on the PC's speakers (best-effort, non-blocking).
    Prefers Windows' built-in winsound (no external player); falls back to mpv/ffplay."""
    custom = (CONFIG.get("FINISH_SOUND") or "").strip()
    path = custom or str(FINISH_SOUND)
    if not custom:
        _ensure_finish_sound()
    if not os.path.exists(path):
        return
    try:
        import winsound  # Windows stdlib — plays a WAV async with no dependencies
        winsound.PlaySound(path, winsound.SND_FILENAME | winsound.SND_ASYNC)
        return
    except Exception:  # noqa: BLE001 - not on Windows, or a non-WAV custom sound
        pass
    for argv in (
        ["mpv", "--no-config", "--no-video", "--force-window=no", "--really-quiet", path],
        ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path],
    ):
        if shutil.which(argv[0]):
            try:
                subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 creationflags=CREATE_NO_WINDOW)
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
        notify("★ AniList", f"{title} — rated {score}")
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
                "allGenres": GENRES,
                "results": SEARCH["results"],
                "cursor": SEARCH["cursor"],
                "hasMore": SEARCH["hasMore"],
                "detail": SEARCH["detail"],
                "busy": SEARCH["busy"],
                "statuses": SEARCH_STATUSES,
                "canWrite": bool(anilist_token()),
            } if in_search else None,
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
    return render_template("remote.html", token=request.args.get("k", ""))


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
    if pending:
        show_rating(pending)
        ensure_kiosk()
        return jsonify(ok=True, action="rate")

    # Search has its own back stack: detail -> results list -> exit to watching grid.
    with STATE_LOCK:
        view = STATE["view"]
    if view == "detail":
        with STATE_LOCK:
            STATE["view"] = "search"
            SEARCH["detail"] = None
        broadcast()
        return jsonify(ok=True, action="search")
    if view == "search":
        with STATE_LOCK:
            STATE["list"] = "watching"
            STATE.update(view="loading", message="Loading…")
            SEARCH.update(query="", genres=[], results=[], cursor=0, detail=None, busy=False)
        broadcast()
        socketio.start_background_task(refresh_list)
        ensure_kiosk()
        return jsonify(ok=True, action="exit-search")

    with STATE_LOCK:
        STATE["sequel"] = None
        STATE["playing"] = None
        STATE["rating"] = None
        STATE["view"] = "grid" if STATE["items"] else "empty"
    broadcast()
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
    if g not in GENRES:
        return jsonify(ok=False, reason="unknown genre")
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


@app.route("/fwd", methods=["POST"])
@require_auth
def seek_forward():
    mpv_ipc_command(["seek", 30, "relative"])
    return jsonify(ok=True)


@app.route("/rew", methods=["POST"])
@require_auth
def seek_backward():
    mpv_ipc_command(["seek", -30, "relative"])
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
    return jsonify(ok=True, episode=episode)


@socketio.on("connect")
def on_connect():
    if not authorized():
        return False  # reject unauthorized socket connections
    broadcast()
    return None


if __name__ == "__main__":
    local_url = f"http://127.0.0.1:{PORT}/remote?k={TOKEN}"
    print("Shou server listening on 0.0.0.0:%d" % PORT)
    print(f"  remote (local) : {local_url}")
    print(f"  remote (phone) : http://<this-pc-ip>:{PORT}/remote?k={TOKEN}")
    socketio.run(app, host="0.0.0.0", port=PORT, allow_unsafe_werkzeug=True)
