# 🎌 Shou

**Watch your anime entirely from your phone.** Shou puts your AniList list on the big
screen and lets you browse and play episodes from a beautiful phone remote — you never
touch the computer.

A long-running Flask + SocketIO server is the single brain. It fetches your public
AniList list, shows a cinematic 3D-coverflow **kiosk** (a fullscreen browser window) on
the PC, and serves a touch-first **phone web-remote (PWA)** that mirrors the kiosk live
over WebSocket. Pick an anime and it auto-plays your next unwatched episode through the
`anipy` scrapers → `mpv` (fullscreen) — or through `ani-cli` if you have it installed; if
you're caught up it recommends the sequel, or plays the latest released episode. Optionally
it ticks episodes off on AniList as you finish them.

Runs on **most Linux distros** (Arch, Debian/Ubuntu, Fedora, openSUSE, Void, Alpine, …)
and any desktop/compositor — it needs only `mpv`, a browser, `curl`, and `uv`. Playback
control reaches mpv over its own IPC socket, so there's **no `playerctl`/`mpv-mpris`
requirement**. On **Hyprland** or **Sway** the kiosk also auto-raises/fullscreens; elsewhere
the browser's own `--kiosk` handles fullscreen.

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Install](#install)
- [Configuration](#configuration)
- [Using Shou](#using-shou)
  - [1. Get it running](#1-get-it-running)
  - [2. Set up your phone](#2-set-up-your-phone)
  - [3. The remote at a glance](#3-the-remote-at-a-glance)
  - [4. Watch something](#4-watch-something)
  - [5. While an episode is playing](#5-while-an-episode-is-playing)
  - [6. Watching vs Plan to Watch](#6-watching-vs-plan-to-watch)
- [Auto-mark episodes watched on AniList](#auto-mark-episodes-watched-on-anilist)
- [Control reference (HTTP endpoints)](#control-reference-http-endpoints)
- [Troubleshooting](#troubleshooting)
- [Run / restart manually](#run--restart-manually)
- [Uninstall](#uninstall)
- [License](#license)
- [Disclaimer](#disclaimer)

---

## How it works

```
   PHONE (web remote)            PC (this repo)                      INTERNET
 ┌─────────┐   HTTP POST     ┌──────────────────────┐   GraphQL    ┌──────────┐
 │ web     │ ───────────────▶│  Flask + SocketIO    │ ────────────▶│ AniList  │
 │ remote  │   /open /select │  server.py (daemon)  │              │   API    │
 │ (PWA)   │◀─ ─ ─ ─ ─ ─ ─ ─ │                      │              └──────────┘
 └─────────┘   SocketIO      │  • browser kiosk     │   scrape     ┌──────────┐
                live state   │  • anipy → mpv       │ ────────────▶│ anipy /  │
                             │  • ani-cli (if any)  │              │ ani-cli  │
                             └──────────┬───────────┘              └──────────┘
                                        │ launches
                                        ▼
                              ┌──────────────────┐
                              │  browser kiosk   │  ← the cinematic coverflow
                              │  + mpv fullscreen│     you see on the TV/monitor
                              └──────────────────┘
```

The server is the only moving part; the kiosk and the phone remote are both just **views**
of its live state, and every control is a small HTTP POST to it. Pause / ±30s seek are
sent straight to `mpv` over its JSON IPC socket — no extra player plugins needed.

## Requirements

- **Linux** with one of these package managers: `pacman` (Arch), `apt` (Debian/Ubuntu),
  `dnf` (Fedora), `zypper` (openSUSE), `xbps` (Void), or `apk` (Alpine). No supported
  package manager? It still installs — you just install the few system deps yourself.
- A **public** AniList account (so the server can read your lists without logging in).
- Your **phone and PC on the same network** (for the web remote).
- **Hard dependencies** (installed automatically where possible): `mpv`, a **browser**
  (Firefox / Chromium / Brave / …), `curl`, and `uv`. Python deps come from `uv.lock`.
- **Optional**: `ani-cli` for an extra source (without it the bundled `anipy` scrapers are
  used — works fine); `libnotify` for desktop notifications; `avahi` + `nss-mdns` to reach
  the PC as `<hostname>.local` (otherwise just use its LAN IP).
- **Hyprland** or **Sway** add a kiosk auto-raise/fullscreen nicety; on any other
  compositor/DE the browser's `--kiosk` still goes fullscreen on its own.

## Install

From inside the repo on the target machine:

```bash
./install.sh
```

The installer is **idempotent** (safe to re-run), **detects your distro's package
manager**, and asks before each system change. It:

1. Checks you're not root; detects your package manager and compositor.
2. Installs the dependencies (skips anything already present), mapping package names per
   distro and falling back to the official `uv` installer where `uv` isn't packaged.
3. Syncs the Python env (`uv sync`).
4. Marks the control scripts executable.
5. Creates/updates `~/.config/shou/shou.conf` — prompts for your **public** AniList
   username and **backfills any missing settings** with their defaults.
6. Optionally enables `avahi` + wires `nss-mdns` so the phone can reach `<hostname>.local`.
7. Adds login autostart — a Hyprland `exec-once` if Hyprland is detected, otherwise an
   XDG `~/.config/autostart/shou.desktop` entry (honored by GNOME/KDE/XFCE/…).
8. Optionally sets up AniList write access and starts the server.

When it finishes it prints your phone remote URL
(`http://<hostname>.local:4100/remote?k=<token>`) to add to your phone's home screen.

> **On Sway** (or another wlroots compositor without an XDG-autostart daemon), add
> `exec ~/path/to/shou_daemon.sh` to your config instead — the installer prints the exact
> line. Everything else is identical across distros.

## Configuration

`~/.config/shou/shou.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + phone remote port
QUALITY="1080p"                # passed to ani-cli when it's used (anipy picks best)
WATCHED_PERCENT="90"           # auto-mark watched once playback passes this %
# REMOTE_TOKEN="..."           # auto-generated on first launch — don't set by hand
# ANILIST_TOKEN="..."          # OAuth write token — set by ./shou_auth.sh
```

`REMOTE_TOKEN` and `ANILIST_TOKEN` are managed for you (the server generates the first
on launch; `./shou_auth.sh` writes the second) — leave them out and let them appear.

Changing `ANILIST_USER` / `QUALITY` only needs an **Open** tap (config reloads); no
restart. Changing `PORT`, `REMOTE_TOKEN`, or `ANILIST_TOKEN` needs a daemon restart.

**After updating Shou**, just re-run `./install.sh` — it's idempotent and **adds any new
config keys** introduced by the update (with sensible defaults) without overwriting the
values you've set, so you never have to hand-edit `shou.conf` to catch up.

---

## Using Shou

### 1. Get it running

If you enabled autostart during install, the daemon launches on login — nothing to do.
Otherwise start it once:

```bash
./shou_daemon.sh        # or just log out and back in
```

The server runs in the background; the kiosk window appears the first time you press
**Open** from your phone.

### 2. Set up your phone

1. On your phone's browser, open the remote URL the installer printed:
   `http://<hostname>.local:4100/remote?k=<token>`
   (find it again anytime at the top of `~/.config/shou/shou.log`).
2. It should load the dark **Shou remote**. The little dot top-right turns **green
   “live”** when it's connected to the PC.
3. **Add it to your home screen** (browser menu → *Add to Home screen* / *Install*) so
   it's a one-tap icon from then on.

> If `<hostname>.local` doesn't resolve on the phone, use the PC's LAN IP instead, e.g.
> `http://192.168.1.50:4100/remote?k=<token>` — the installed icon keeps working either
> way. The `?k=<token>` is required from the phone (loopback on the PC is exempt); it's
> your private key, so don't share the URL.

### 3. The remote at a glance

```
┌───────────────────────────────┐
│ 朱 SHOU            ● live   │   ← brand + connection status
│ ┌───────────────────────────┐ │
│ │  cover   NOW WATCHING  3/7│ │   ← live mirror of the kiosk:
│ │  ▥▥▥▥    F R I E R E N    │ │     what's highlighted, episode,
│ │          EP 12 / 28       │ │     and a progress bar
│ └───────────────────────────┘ │
│ [ Watching │ Plan to Watch ]  │   ← list switcher
│   ◀      SELECT       ▶        │   ← browse + choose
│   ⏻ Open          ⤺ Back      │   ← open the UI / stop & return
│  ─────── Transport ───────    │
│   ⏮       ⏯        ⏭          │   ← prev ep · play/pause · next ep
│   « 30s            30s »       │   ← seek back / forward 30s
└───────────────────────────────┘
```

| Button | What it does |
| --- | --- |
| **⏻ Open** | (Re)opens Shou: stops anything playing, refreshes your list, shows the kiosk fullscreen. Your “home” button. |
| **◀ / ▶** | Move the highlight left/right through the coverflow. |
| **SELECT** | Choose the highlighted anime (see [Watch something](#4-watch-something)). |
| **⤺ Back** | Stop playback and return to the carousel (re-fullscreens the kiosk). |
| **Watching / Plan to Watch** | Switch which AniList list the carousel shows. |
| **⏮ / ⏭** | Jump to the previous / next **episode** (relaunches the player). |
| **⏯** | Play / pause the current episode. |
| **« 30s / 30s »** | Seek 30 seconds back / forward. |

The hero panel at the top always **mirrors the kiosk live**, so the phone confirms every
action even if you're across the room from the screen.

### 4. Watch something

1. Tap **⏻ Open**. The kiosk fades in on the PC with your *Currently Watching* list as a
   3D coverflow.
2. Tap **◀ / ▶** to bring the anime you want to the centre.
3. Tap **SELECT**. Shou:
   - **plays your next unwatched episode** fullscreen (progress + 1), or
   - if you're **caught up and a sequel exists**, shows a *“✓ All caught up — recommended
     sequel”* card. Tap **SELECT** again to start the sequel from episode 1, or
   - if you're caught up with **no sequel**, plays the latest released episode.
4. When you're done, tap **⤺ Back** to return to the carousel.

If a source can't be found, Shou automatically tries the backup (`anipy`) scrapers;
if everything fails it shows a clear error card on the kiosk instead of hanging — press
**Back** and try another title.

### 5. While an episode is playing

The video is fullscreen `mpv`. From the phone:

- **⏯** pause/resume,
- **« 30s / 30s »** to skip the recap or rewind,
- **⏮ / ⏭** to switch episodes,
- **⤺ Back** to stop and return to the Shou carousel.

### 6. Watching vs Plan to Watch

Tap **Plan to Watch** to switch the carousel to your AniList *Planning* list (tap
**Watching** to switch back). Selecting a planned anime plays **episode 1**. The active
list is shown both on the remote and as a label on the kiosk. **Open** always returns you
to the *Watching* list.

---

## Auto-mark episodes watched on AniList

By default Shou only **reads** your public list. To have it **tick episodes off on
AniList automatically** when you finish them, grant write access once:

```bash
./shou_auth.sh
```

It walks you through creating an AniList API client
(`https://anilist.co/settings/developer`, redirect URL `https://anilist.co/api/v2/oauth/pin`),
approving it, and pasting the code. The resulting OAuth token is saved as `ANILIST_TOKEN`
in `shou.conf` (gitignored, never in the repo). The installer also offers this step.
**Restart the daemon afterwards.**

Once enabled, when playback passes `WATCHED_PERCENT` (default 90%) — or `mpv` reaches a
clean end — Shou sets that episode as your progress (only ever **increasing** it, so a
rewatch or **⏮** never lowers it) and flips the entry to **Completed** on the final
episode. Tokens last ~1 year; re-run `./shou_auth.sh` when it expires.

## Control reference (HTTP endpoints)

All control endpoints are `POST`, JSON responses. From the PC (`127.0.0.1`) no token is
needed; from any other host append `?k=<REMOTE_TOKEN>`.

| Endpoint | Action |
| --- | --- |
| `/open` | Reload config, refresh list, show kiosk (resets to *Watching*) |
| `/left` `/right` | Move carousel selection |
| `/select` | Play next ep / show or play sequel / play latest |
| `/back` | Stop playback, return to carousel |
| `/list?to=watching\|planned` | Switch list (omit `to` to toggle) |
| `/pause` | Play/pause `mpv` |
| `/fwd` `/rew` | Seek ±30s |
| `/next` `/prev` | Previous / next episode |
| `/` | The kiosk page · `/remote?k=…` the phone PWA |

## Troubleshooting

- **Phone can't reach the PC** — confirm same network; use the **LAN IP** instead of
  `<hostname>.local` (it always works); if you want `.local`, make sure `avahi-daemon` is
  running (`systemctl status avahi-daemon`).
- **Remote loads but says offline / not live** — the daemon isn't running; start it
  (see [below](#run--restart-manually)) or check `~/.config/shou/shou.log`.
- **“ANILIST_USER is not set”** — set it in `shou.conf` and make sure the list is
  **public** (AniList → Settings → Profile).
- **No kiosk window appears** — install a browser (`firefox`, `chromium`, `brave`, …);
  Shou auto-detects the first one it finds.
- **Pause / ±30s seek do nothing** — those go to `mpv` over its IPC socket; they only
  work while an episode launched by Shou is playing.
- **Kiosk shows the old design after an update** — the running browser caches the page.
  Close the kiosk window and tap **Open** to relaunch it fresh.
- **Nothing plays / “no playable source”** — the scrapers couldn't find that title. Make
  sure `mpv` is installed; try another entry; check `~/.config/shou/ani-cli-last.log`.
- **Changed a template / `server.py`** — restart the daemon (templates are cached in the
  running process). Editing only `static/*` just needs a kiosk reload.

## Run / restart manually

```bash
# start (foreground-ish, restart-on-crash wrapper — what autostart runs)
./shou_daemon.sh

# restart the daemon (kill the daemon first so it doesn't respawn the old server)
pkill -f shou_daemon.sh; pkill -f shou/server.py
setsid nohup ~/Projects/ScriptsKDE/shou_daemon.sh >/dev/null 2>&1 &

# run the server directly (no restart wrapper), e.g. to see logs live
uv run --project shou python shou/server.py
```

The server prints the full `/remote?k=…` URL to `~/.config/shou/shou.log` on startup.

## Uninstall

```bash
./uninstall.sh
```

Stops the server, removes the autostart entry (the Hyprland line — with a backup — and/or
the XDG `shou.desktop`), and *optionally* deletes your config and the virtualenv. It
deliberately **leaves system packages and the `nsswitch.conf` entry alone** — it prints
how to remove those by hand if you want to.

## License

MIT — see [LICENSE](LICENSE).

## Disclaimer

Shou is a personal hobby project. It hosts, stores, and distributes **no** copyrighted
content. It is a thin controller that drives third-party programs (`anipy-cli`, `ani-cli`,
`mpv`) and the public **AniList** API. Any streams those tools locate come from
third-party sites that Shou neither operates nor is affiliated with — you alone are
responsible for how you use it and for complying with the laws of your jurisdiction.
Please support creators through official services (Crunchyroll, Netflix, HIDIVE, your
local licensors, Blu-ray, etc.). Provided "as is", without warranty (see [LICENSE](LICENSE)).
