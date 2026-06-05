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

import hmac
import json
import math
import os
import shlex
import secrets
import signal
import shutil
import socket
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
    "view": "loading",   # loading | grid | sequel | playing | empty | error
    "list": "watching",  # which AniList list is shown: watching | planned
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
# What ani-cli should run as its player: fullscreen mpv tagged with our marker.
ANI_CLI_PLAYER = f"mpv --fs --input-ipc-server={MPV_IPC}"
# How long ani-cli gets to actually start mpv before we call it a failed source.
LAUNCH_TIMEOUT = 30.0
# Backup scrapers (anipy_api providers) tried when ani-cli finds no source, in order.
# animekai is a genuinely different source; allanime is the same site ani-cli uses but
# via a different client + smarter result/episode matching (fixes wrong-entry failures).
FALLBACK_PROVIDERS = ["animekai", "allanime"]

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
        subprocess.run(
            ["notify-send", "✓ AniList", f"{display} — Episode {episode} watched",
             "-u", "low", "-t", "2500"],
            check=False,
        )
    except Exception as exc:  # noqa: BLE001
        print(f"[anilist] mark_watched failed: {exc}", flush=True)


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
                    elif name == "duration" and isinstance(data, (int, float)):
                        duration = data
                elif ev == "end-file" and msg.get("reason") == "eof" and not completed:
                    completed = True
                    _finish()
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


def focus_kiosk(pid: int) -> None:
    """Best-effort raise + fullscreen of the kiosk window. Supports Hyprland and Sway;
    a no-op on other compositors/DEs — the browser was already opened with --kiosk, so
    it stays fullscreen on its own. This is purely a 'bring it back to front' nicety."""
    if shutil.which("hyprctl"):
        subprocess.run(["hyprctl", "dispatch", "focuswindow", f"pid:{pid}"], check=False)
        subprocess.run(["hyprctl", "dispatch", "fullscreenstate", "2", "-1"], check=False)
    elif shutil.which("swaymsg"):
        subprocess.run(["swaymsg", f"[pid={pid}]", "focus"], check=False)
        subprocess.run(["swaymsg", "fullscreen", "enable"], check=False)


# Browser candidates, in preference order. Each entry: (binaries, argv-builder, env).
# Firefox uses --profile; Chromium-family browsers use --user-data-dir.
_FIREFOX_BINS = ("firefox", "firefox-esr", "librewolf", "waterfox")
_CHROMIUM_BINS = ("chromium", "chromium-browser", "google-chrome-stable",
                  "google-chrome", "brave", "brave-browser", "vivaldi-stable", "vivaldi")


def _browser_command(url: str):
    """Return (argv, extra_env) for a fullscreen kiosk on the first browser found,
    or (None, None) if none is installed."""
    profile = str(FF_PROFILE)
    for b in _FIREFOX_BINS:
        if shutil.which(b):
            return ([b, "--kiosk", "--profile", profile, url], {"MOZ_NO_REMOTE": "1"})
    for b in _CHROMIUM_BINS:
        if shutil.which(b):
            return ([b, "--kiosk", "--no-first-run", f"--user-data-dir={profile}", url], {})
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
    # -S 1 picks the first search result non-interactively. ani-cli selects the anime
    # with fzf, which reads /dev/tty; running detached (no terminal) that aborts with
    # "inappropriate ioctl for device" on any multi-result search (e.g. ONE PIECE, which
    # returns the series plus dozens of films/specials), so nothing plays. Piping a "1"
    # to stdin does NOT help — the picker ignores stdin. --select-nth sets the index
    # directly and never invokes fzf. (Single-result searches skipped fzf already, which
    # is why some titles played while others silently failed.)
    cmd = (
        f"ani-cli -S 1 -q {shlex.quote(quality)} "
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
        player = f"mpv --fs --start={int(start)} --input-ipc-server={MPV_IPC}"
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
    subprocess.run(
        ["notify-send", "🎌 Now Watching", f"{display} — Episode {episode}",
         "-u", "normal", "-t", "3000"],
        check=False,
    )
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
            subprocess.run(
                ["notify-send", "🎌 Backup Source",
                 f"{display} — Ep {episode} (via {prov_name})", "-u", "normal", "-t", "3000"],
                check=False,
            )
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
    subprocess.run(
        ["notify-send", "🎌 Shou", f"⚠ {msg}", "-u", "critical", "-t", "6000"],
        check=False,
    )


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
        subprocess.run(
            ["notify-send", "★ AniList", f"{title} — rated {score}", "-u", "low", "-t", "2500"],
            check=False,
        )
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
    with STATE_LOCK:
        if STATE["view"] != "grid" or not STATE["items"]:
            return
        n = len(STATE["items"])
        STATE["cursor"] = (STATE["cursor"] + delta) % n
    broadcast()


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
    with STATE_LOCK:
        STATE["view"] = "playing"
        STATE["message"] = f"Playing {playing['title']} — Ep {episode}"
    broadcast()
    return jsonify(ok=True, episode=episode)


@socketio.on("connect")
def on_connect():
    if not authorized():
        return False  # reject unauthorized socket connections
    broadcast()
    return None


if __name__ == "__main__":
    local_url = f"http://127.0.0.1:{PORT}/remote?k={TOKEN}"
    lan_url = f"http://shio-t0.local:{PORT}/remote?k={TOKEN}"
    print("Shou server listening on 0.0.0.0:%d" % PORT)
    print(f"  remote (local) : {local_url}")
    print(f"  remote (phone) : {lan_url}")
    socketio.run(app, host="0.0.0.0", port=PORT, allow_unsafe_werkzeug=True)
