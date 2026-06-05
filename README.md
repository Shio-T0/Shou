# 🎌 Shou (Windows)

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-ff4a32.svg)](LICENSE.md)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows%2010%2F11-1f1f24.svg?logo=windows&logoColor=white)](#requirements)
[![Linux version](https://img.shields.io/badge/also%20on-Linux-1f1f24.svg?logo=linux&logoColor=white)](../../tree/main)

**Watch your anime entirely from your phone.** Shou puts your AniList list on the big
screen and lets you browse and play episodes from a beautiful phone remote — you never
touch the computer.

> This is the **Windows** branch of Shou. For the original **Arch Linux + Hyprland**
> version (with `ani-cli`, `playerctl`/`mpv-mpris`, and a `pacman` installer), switch to
> the [`main`](../../tree/main) branch.

A long-running Flask + SocketIO server is the single brain. It fetches your public
AniList list, shows a cinematic 3D-coverflow **kiosk** (a fullscreen browser window) on
the PC, and serves a touch-first **phone web-remote (PWA)** that mirrors the kiosk live
over WebSocket. Pick an anime and it auto-plays your next unwatched episode through the
`anipy` scrapers → `mpv` (fullscreen); if you're caught up it recommends the sequel, or
plays the latest released episode. Optionally it ticks episodes off on AniList as you
finish them.

Built for **Windows 10/11**. The phone remote works from any phone on the same network.

---

## Contents

- [How it works](#how-it-works)
- [What's different from the Linux version](#whats-different-from-the-linux-version)
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
                live state   │  • anipy → mpv       │ ────────────▶│  anipy   │
                             │  • mpv JSON IPC pipe │              │ scrapers │
                             └──────────┬───────────┘              └──────────┘
                                        │ launches
                                        ▼
                              ┌──────────────────┐
                              │  browser kiosk   │  ← the cinematic coverflow
                              │  + mpv fullscreen│     you see on the TV/monitor
                              └──────────────────┘
```

The server is the only moving part; the kiosk and the phone remote are both just **views**
of its live state, and every control is a small HTTP POST to it. Playback control
(pause / ±30s seek) is sent to `mpv` over its **JSON IPC named pipe** — no extra player
plugins required.

## What's different from the Linux version

| | Linux (`main`) | Windows (this branch) |
| --- | --- | --- |
| Source | `ani-cli` (+ anipy fallback) | **anipy** (pure Python, no Git Bash needed) |
| Player control | `playerctl` + `mpv-mpris` | **mpv JSON IPC** named pipe |
| Installer | `install.sh` (`pacman`/AUR) | **`install.ps1`** (winget) |
| Daemon / auth | `*.sh` | **`*.ps1`** |
| Autostart | Hyprland `exec-once` | **Startup-folder shortcut** |
| `.local` discovery | `avahi` / `nss-mdns` | Windows mDNS (or just use the LAN IP) |

## Requirements

- **Windows 10 or 11** with **PowerShell**.
- A **public** AniList account (so the server can read your lists without logging in).
- **[mpv](https://mpv.io)** on your `PATH` (or set `MPV_BIN` in `shou.conf`).
- A **browser** for the kiosk: Firefox, Edge (ships with Windows), or Chrome.
- Your **phone and PC on the same network** (for the web remote).
- Installed by `install.ps1` (via winget, where possible): `uv`, and `mpv` if missing.
  Python deps come from `uv.lock` (`uv sync`).

## Install

From inside the repo, in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The installer is **idempotent** (safe to re-run) and asks before each change. It:

1. Checks you're in the repo and looks for `winget`.
2. Installs `uv` and (if missing) `mpv`; detects a kiosk browser.
3. Syncs the Python env (`uv sync`).
4. Creates/updates `%USERPROFILE%\.config\shou\shou.conf` — prompts for your **public**
   AniList username and **backfills any missing settings** with their defaults.
5. Optionally adds a **Startup-folder shortcut** so the daemon launches at login.
6. Optionally sets up AniList write access and starts the server.

When it finishes it prints your phone remote URL
(`http://<pc-lan-ip>:4100/remote?k=<token>`) to add to your phone's home screen.

> **mpv not found after install?** winget doesn't always carry mpv. Install it from
> [mpv.io](https://mpv.io) (or `scoop install mpv`) and either add it to your `PATH` or
> set `MPV_BIN="C:\path\to\mpv.exe"` in `shou.conf`.

## Configuration

`%USERPROFILE%\.config\shou\shou.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + phone remote port
WATCHED_PERCENT="90"           # auto-mark watched once playback passes this %
MPV_BIN=""                     # full path to mpv.exe (blank = use PATH)
BROWSER=""                     # full path to a kiosk browser .exe (blank = auto-detect)
# REMOTE_TOKEN="..."           # auto-generated on first launch — don't set by hand
# ANILIST_TOKEN="..."          # OAuth write token — set by .\shou_auth.ps1
```

`REMOTE_TOKEN` and `ANILIST_TOKEN` are managed for you (the server generates the first
on launch; `shou_auth.ps1` writes the second) — leave them out and let them appear.

Changing `ANILIST_USER` only needs an **Open** tap (config reloads); no restart. Changing
`PORT`, `MPV_BIN`, `BROWSER`, `REMOTE_TOKEN`, or `ANILIST_TOKEN` needs a daemon restart.

**After updating Shou**, just re-run `install.ps1` — it's idempotent and **adds any new
config keys** introduced by the update (with sensible defaults) without overwriting the
values you've set.

---

## Using Shou

### 1. Get it running

If you enabled autostart during install, the daemon launches on login — nothing to do.
Otherwise start it once:

```powershell
powershell -ExecutionPolicy Bypass -File .\shou_daemon.ps1
```

The server runs in the background; the kiosk window appears the first time you press
**Open** from your phone.

### 2. Set up your phone

1. On your phone's browser, open the remote URL the installer printed:
   `http://<pc-lan-ip>:4100/remote?k=<token>`
   (find it again anytime at the top of `%USERPROFILE%\.config\shou\shou.log`).
2. It should load the dark **Shou remote**. The little dot top-right turns **green
   “live”** when it's connected to the PC.
3. **Add it to your home screen** (browser menu → *Add to Home screen* / *Install*) so
   it's a one-tap icon from then on.

> The `?k=<token>` is required from the phone (loopback on the PC is exempt); it's your
> private key, so don't share the URL. If you'd rather use `<pc-name>.local` than the IP,
> that works only if Windows mDNS resolves it on your network — the LAN IP always works.

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
| **⤺ Back** | Stop playback and return to the carousel. |
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

While Shou is resolving a stream the kiosk shows a *“Searching…”* message; if no source
can be found it shows a clear error card instead of hanging — press **Back** and try
another title.

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

```powershell
powershell -ExecutionPolicy Bypass -File .\shou_auth.ps1
```

It walks you through creating an AniList API client
(`https://anilist.co/settings/developer`, redirect URL `https://anilist.co/api/v2/oauth/pin`),
approving it, and pasting the code. The resulting OAuth token is saved as `ANILIST_TOKEN`
in `shou.conf` (gitignored, never in the repo). The installer also offers this step.
**Restart the daemon afterwards.**

Once enabled, when playback passes `WATCHED_PERCENT` (default 90%) — or `mpv` reaches a
clean end — Shou sets that episode as your progress (only ever **increasing** it, so a
rewatch or **⏮** never lowers it) and flips the entry to **Completed** on the final
episode. Tokens last ~1 year; re-run `shou_auth.ps1` when it expires.

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
| `/pause` | Play/pause `mpv` (over IPC) |
| `/fwd` `/rew` | Seek ±30s (over IPC) |
| `/next` `/prev` | Previous / next episode |
| `/` | The kiosk page · `/remote?k=…` the phone PWA |

## Troubleshooting

- **Phone can't reach the PC** — confirm same network; use the PC's **LAN IP**. The first
  time the server runs, Windows Firewall may prompt to allow Python on private networks —
  **allow it**.
- **Remote loads but says offline / not live** — the daemon isn't running; start it
  (see [below](#run--restart-manually)) or check `%USERPROFILE%\.config\shou\shou.log`.
- **“ANILIST_USER is not set”** — set it in `shou.conf` and make sure the list is
  **public** (AniList → Settings → Profile).
- **Pause / ±30s seek do nothing** — those go to `mpv` over its IPC pipe; they only work
  while an episode launched by Shou is playing.
- **Nothing plays / “no playable source”** — the scrapers couldn't find that title. Make
  sure `mpv` is installed and on `PATH` (or `MPV_BIN` is set); try another entry; check
  `shou.log`.
- **No kiosk window appears** — install Firefox/Edge/Chrome, or point `BROWSER` in
  `shou.conf` at a browser `.exe`.
- **Changed a template / `server.py`** — restart the daemon (templates are cached in the
  running process). Editing only `static/*` just needs a kiosk reload.

## Run / restart manually

```powershell
# start (restart-on-crash wrapper — what autostart runs)
powershell -ExecutionPolicy Bypass -File .\shou_daemon.ps1

# stop the daemon + server + our mpv
Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -match 'shou_daemon\.ps1|shou\\server\.py|shou-mpv' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# run the server directly (no restart wrapper), e.g. to see logs live
uv run --project shou python shou\server.py
```

The server prints the full `/remote?k=…` URL to `%USERPROFILE%\.config\shou\shou.log`
on startup.

## Uninstall

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
```

Stops the server, removes the Startup shortcut, and *optionally* deletes your config and
the virtualenv. It deliberately **leaves `uv`, `mpv`, and your browser installed**.

## License

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** — free for any **noncommercial** use; no
selling or commercial use; redistributors must **keep the credit** to this repo (the
`Required Notice:` line in the [LICENSE](LICENSE.md)). Source-available rather than OSI "open
source." Want a commercial license? Ask.

## Disclaimer

Shou is a personal hobby project. It hosts, stores, and distributes **no** copyrighted
content. It is a thin controller that drives third-party programs (`anipy-cli`, `mpv`)
and the public **AniList** API. Any streams those tools locate come from third-party
sites that Shou neither operates nor is affiliated with — you alone are responsible for
how you use it and for complying with the laws of your jurisdiction. Please support
creators through official services (Crunchyroll, Netflix, HIDIVE, your local licensors,
Blu-ray, etc.). Provided "as is", without warranty (see [LICENSE](LICENSE.md)).
