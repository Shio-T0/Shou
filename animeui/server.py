#!/usr/bin/env python3
"""AnimeUI — phone-controlled AniList "Currently Watching" launcher.

A long-running Flask + SocketIO server that is the single brain for AnimeUI:
  * fetches your AniList "Currently Watching" list (public username, no auth),
  * shows a live carousel UI in a Firefox kiosk (which it launches/focuses itself),
  * serves a touch-first phone web-remote (PWA) that mirrors the kiosk live,
  * launches episodes through ani-cli/mpv (fullscreen) and writes the shared
    ~/.config/anime/state file so the legacy next/prev/pause scripts keep working.

Control endpoints are reachable from loopback without auth (kiosk page + KDE Connect
scripts) and require a shared-secret token (?k=…) from any networked client (the phone).
"""

import hmac
import json
import os
import shlex
import secrets
import subprocess
import threading
import time
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
CONFIG_DIR = Path.home() / ".config" / "anime"
CONFIG_FILE = CONFIG_DIR / "animeui.conf"
STATE_FILE = CONFIG_DIR / "state"
FF_PROFILE = CONFIG_DIR / "ff-profile"
FF_PIDFILE = CONFIG_DIR / "animeui-ff.pid"
ANILIST_URL = "https://graphql.anilist.co"


def load_config() -> dict:
    """Parse the shell-style KEY="value" animeui.conf."""
    cfg = {"ANILIST_USER": "", "PORT": "4100", "QUALITY": "1080p", "REMOTE_TOKEN": ""}
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
    """Re-read animeui.conf (called on /open) so ANILIST_USER/QUALITY changes apply
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
    "items": [],          # list of card dicts
    "cursor": 0,
    "sequel": None,       # {"finished": str, "sequel_title": str}
    "playing": None,      # {"title": str, "episode": int}
    "message": "",
}
STATE_LOCK = threading.Lock()

# Tracks the in-flight playback launch so we can detect "ani-cli found no source"
# and report it instead of leaving the UI stuck on the "playing" screen.
PLAYBACK = {"gen": 0, "proc": None}
ANI_LOG = CONFIG_DIR / "ani-cli-last.log"
# How long ani-cli gets to actually start mpv before we call it a failed source.
LAUNCH_TIMEOUT = 30.0
# Backup scrapers (anipy_api providers) tried when ani-cli finds no source, in order.
# animekai is a genuinely different source; allanime is the same site ani-cli uses but
# via a different client + smarter result/episode matching (fixes wrong-entry failures).
FALLBACK_PROVIDERS = ["animekai", "allanime"]

app = Flask(__name__)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")


# --------------------------------------------------------------------------- #
# Auth (loopback-exempt token gate)
# --------------------------------------------------------------------------- #
LOCAL_ADDRS = {"127.0.0.1", "::1", "::ffff:127.0.0.1"}


def authorized() -> bool:
    if (request.remote_addr or "") in LOCAL_ADDRS:
        return True
    supplied = request.args.get("k") or request.headers.get("X-AnimeUI-Token", "")
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
WATCHING_QUERY = """
query ($name: String) {
  MediaListCollection(userName: $name, type: ANIME, status_in: [CURRENT]) {
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


def fetch_watching() -> list:
    """Return the user's Currently Watching entries (raw AniList entry dicts)."""
    user = CONFIG.get("ANILIST_USER", "").strip()
    if not user or user == "CHANGE_ME":
        raise RuntimeError("ANILIST_USER is not set in ~/.config/anime/animeui.conf")

    resp = requests.post(
        ANILIST_URL,
        json={"query": WATCHING_QUERY, "variables": {"name": user}},
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
# Kiosk window control (Hyprland / Firefox)
# --------------------------------------------------------------------------- #
def kiosk_pid() -> int | None:
    """Return the PID of the running Firefox kiosk, or None."""
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
    """Raise + force-fullscreen the kiosk window (explicit, non-toggling state)."""
    subprocess.run(["hyprctl", "dispatch", "focuswindow", f"pid:{pid}"], check=False)
    subprocess.run(["hyprctl", "dispatch", "fullscreenstate", "2", "-1"], check=False)


def ensure_kiosk() -> None:
    """Make sure the fullscreen kiosk is showing; launch it if needed, else refocus."""
    pid = kiosk_pid()
    if pid:
        focus_kiosk(pid)
        return
    FF_PROFILE.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(
        ["firefox", "--kiosk", "--profile", str(FF_PROFILE),
         f"http://127.0.0.1:{PORT}/"],
        env=os.environ | {"MOZ_NO_REMOTE": "1"},
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    FF_PIDFILE.write_text(str(proc.pid))


# --------------------------------------------------------------------------- #
# Playback / process control
# --------------------------------------------------------------------------- #
def kill_players() -> None:
    for name in ("mpv", "ani-cli"):
        subprocess.run(["pkill", "-f", name], check=False)


def write_state_file(title: str, episode: int) -> None:
    """Mirror state into ~/.config/anime/state for the legacy next/prev/pause scripts."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(
        f'ANIME="{title}"\nEPISODE={episode}\nSELECTION=1\n'
    )


def bump_play_gen() -> int:
    """Invalidate any in-flight playback monitor and return a fresh generation id."""
    with STATE_LOCK:
        PLAYBACK["gen"] += 1
        return PLAYBACK["gen"]


def mpv_running() -> bool:
    return subprocess.run(
        ["pgrep", "-x", "mpv"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    ).returncode == 0


def play(search_title: str, episode: int, display_title: str | None = None) -> None:
    """Launch ani-cli for the given title/episode, detached, auto-picking result 1.

    `search_title` (romaji) is what ani-cli searches; `display_title` (english) is what
    the UI/notification shows. A background monitor watches whether mpv actually starts
    and reports back to the UI if no playable source was found.
    """
    display = display_title or search_title
    gen = bump_play_gen()
    kill_players()
    time.sleep(0.4)
    write_state_file(search_title, episode)
    quality = CONFIG.get("QUALITY") or "1080p"
    cmd = (
        f"printf '1\\n' | ani-cli -q {shlex.quote(quality)} "
        f"-e {episode} {shlex.quote(search_title)}"
    )
    print(f"[play] ani-cli search={search_title!r} episode={episode} "
          f"quality={quality!r} (showing as {display!r})", flush=True)
    # ani-cli output is captured so the monitor can read a failure reason.
    logf = ANI_LOG.open("w")
    # ANI_CLI_PLAYER is expanded unquoted into ani-cli's mpv branch, so this
    # opens mpv fullscreen — scoped to ani-cli only (no global mpv config edit).
    proc = subprocess.Popen(
        cmd,
        shell=True,
        stdin=subprocess.DEVNULL,
        stdout=logf,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        env=os.environ | {"ANI_CLI_PLAYER": "mpv --fs"},
    )
    logf.close()
    with STATE_LOCK:
        PLAYBACK["proc"] = proc
        STATE["playing"] = {"title": display, "search": search_title, "episode": episode}
    subprocess.run(
        ["notify-send", "🎌 Now Watching", f"{display} — Episode {episode}",
         "-u", "normal", "-t", "3000"],
        check=False,
    )
    socketio.start_background_task(monitor_playback, gen, search_title, episode, display)


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


def fallback_play(gen: int, search_title: str, episode: int, display: str) -> bool:
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
    cmd = ["mpv", "--fs", f"--force-media-title={display} — Episode {episode}"]
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


def monitor_playback(gen: int, search_title: str, episode: int, display: str) -> None:
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
    if fallback_play(gen, search_title, episode, display):
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
        ["notify-send", "🎌 AnimeUI", f"⚠ {msg}", "-u", "critical", "-t", "6000"],
        check=False,
    )


# --------------------------------------------------------------------------- #
# State broadcast
# --------------------------------------------------------------------------- #
def broadcast() -> None:
    with STATE_LOCK:
        snapshot = {
            "view": STATE["view"],
            "items": STATE["items"],
            "cursor": STATE["cursor"],
            "sequel": STATE["sequel"],
            "playing": STATE["playing"],
            "message": STATE["message"],
        }
    socketio.emit("state", snapshot)


def refresh_list() -> None:
    """Pull the watching list from AniList and reset to the grid view."""
    try:
        entries = fetch_watching()
    except Exception as exc:  # noqa: BLE001 - surface any failure to the screen
        with STATE_LOCK:
            STATE.update(view="error", items=[], sequel=None, message=str(exc))
        broadcast()
        return

    cards = [build_card(e) for e in entries]
    with STATE_LOCK:
        STATE["items"] = cards
        STATE["_entries"] = entries  # keep raw entries for decisions
        STATE["cursor"] = 0
        STATE["sequel"] = None
        STATE["view"] = "grid" if cards else "empty"
        STATE["message"] = "" if cards else "Nothing in your Currently Watching list."
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
        "name": "AnimeUI Remote",
        "short_name": "AnimeUI",
        "description": "Phone remote for the AnimeUI kiosk",
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
        STATE.update(view="loading", message="Loading your AniList…")
    broadcast()
    socketio.start_background_task(refresh_list)
    ensure_kiosk()
    return jsonify(ok=True)


@app.route("/back", methods=["POST"])
@require_auth
def back():
    bump_play_gen()
    kill_players()
    with STATE_LOCK:
        STATE["sequel"] = None
        STATE["playing"] = None
        STATE["view"] = "grid" if STATE["items"] else "empty"
    broadcast()
    pid = kiosk_pid()
    if pid:
        focus_kiosk(pid)
    else:
        ensure_kiosk()
    return jsonify(ok=True)


@app.route("/left", methods=["POST"])
@require_auth
def left():
    _move(-1)
    return jsonify(ok=True)


@app.route("/right", methods=["POST"])
@require_auth
def right():
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

    # On the sequel card: a second Select plays the sequel from episode 1.
    if view == "sequel" and sequel:
        play(sequel["sequel_search"], 1, sequel["sequel_title"])
        with STATE_LOCK:
            STATE["view"] = "playing"
            STATE["message"] = f"Playing sequel: {sequel['sequel_title']}"
        broadcast()
        return jsonify(ok=True, action="play_sequel")

    if view != "grid" or not entries or cursor >= len(entries):
        return jsonify(ok=False, reason="nothing selectable")

    decision = episode_decision(entries[cursor])
    if decision["action"] == "play":
        play(decision["search"], decision["episode"], decision["title"])
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
        }
    broadcast()
    return jsonify(ok=True, action="sequel")


@app.route("/pause", methods=["POST"])
@require_auth
def pause():
    subprocess.run(["playerctl", "-p", "mpv", "play-pause"], check=False)
    return jsonify(ok=True)


@app.route("/fwd", methods=["POST"])
@require_auth
def seek_forward():
    subprocess.run(["playerctl", "-p", "mpv", "position", "30+"], check=False)
    return jsonify(ok=True)


@app.route("/rew", methods=["POST"])
@require_auth
def seek_backward():
    subprocess.run(["playerctl", "-p", "mpv", "position", "30-"], check=False)
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
    play(playing.get("search") or playing["title"], episode, playing["title"])
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
    print("AnimeUI server listening on 0.0.0.0:%d" % PORT)
    print(f"  remote (local) : {local_url}")
    print(f"  remote (phone) : {lan_url}")
    socketio.run(app, host="0.0.0.0", port=PORT, allow_unsafe_werkzeug=True)
