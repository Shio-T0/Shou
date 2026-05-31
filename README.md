# 🎌 Shou

**Watch your anime entirely from your phone.** Shou puts your AniList list on the big
screen and lets you browse and play episodes from a beautiful phone remote — you never
touch the computer.

A long-running Flask + SocketIO server is the single brain. It fetches your public
AniList list, shows a cinematic 3D-coverflow **kiosk** (a fullscreen Firefox window) on
the PC, and serves a touch-first **phone web-remote (PWA)** that mirrors the kiosk live
over WebSocket. Pick an anime and it auto-plays your next unwatched episode through
`ani-cli` → `mpv` (fullscreen); if you're caught up it recommends the sequel, or plays
the latest released episode. If `ani-cli` finds no source it falls back to the `anipy`
scrapers, and (optionally) it ticks episodes off on AniList as you finish them.

Built for **Arch Linux** + **Hyprland** (works on other setups, minus the window
auto-fullscreen niceties).

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
- [KDE Connect buttons (alternative to the web remote)](#kde-connect-buttons-alternative-to-the-web-remote)
- [Control reference (HTTP endpoints)](#control-reference-http-endpoints)
- [Troubleshooting](#troubleshooting)
- [Run / restart manually](#run--restart-manually)
- [Uninstall](#uninstall)
- [License](#license)
- [Disclaimer](#disclaimer)

---

## How it works

```
   PHONE                         PC (this repo)                      INTERNET
 ┌─────────┐   HTTP POST     ┌──────────────────────┐   GraphQL    ┌──────────┐
 │ web     │ ───────────────▶│  Flask + SocketIO    │ ────────────▶│ AniList  │
 │ remote  │   /open /select │  server.py (daemon)  │              │   API    │
 │ (PWA)   │◀─ ─ ─ ─ ─ ─ ─ ─ │                      │              └──────────┘
 └─────────┘   SocketIO      │  • Firefox kiosk     │   scrape     ┌──────────┐
       ▲       live state    │  • ani-cli → mpv     │ ────────────▶│ ani-cli /│
       │                     │  • anipy fallback    │              │ anipy    │
 ┌─────────┐   HTTP POST     └──────────┬───────────┘              └──────────┘
 │   KDE   │ ───────────────▶           │ launches
 │ Connect │   /open /left …            ▼
 └─────────┘                  ┌──────────────────┐
                              │  Firefox kiosk   │  ← the cinematic coverflow
                              │  + mpv fullscreen│     you see on the TV/monitor
                              └──────────────────┘
```

The server is the only moving part; the kiosk and the phone remote are both just **views**
of its live state, and every control (phone or KDE Connect) is a small HTTP POST to it.

## Requirements

- **Arch Linux** (the installer uses `pacman` + an AUR helper).
- A **public** AniList account (so the server can read your lists without logging in).
- **Hyprland** recommended (for auto-focus / auto-fullscreen of the kiosk and player).
  Other Wayland/X11 compositors work too — you just lose those window tricks.
- Your **phone and PC on the same network** (for the web remote).
- Installed automatically: `uv`, `firefox`, `mpv`, `playerctl`, `curl`, `libnotify`,
  `avahi`, `nss-mdns`, `librsvg`, and `ani-cli` (AUR). Python deps come from `uv.lock`.

## Install

From inside the repo on the target machine:

```bash
./install.sh
```

The installer is **idempotent** (safe to re-run) and asks before each system change. It:

1. Checks you're on Arch and not root; detects Hyprland.
2. Installs the system + AUR dependencies (skips anything already present).
3. Syncs the Python env (`uv sync`).
4. Marks the control scripts executable.
5. Writes `~/.config/shou/shou.conf`, prompting for your **public** AniList username.
6. Enables `avahi-daemon` + wires `nss-mdns` so the phone can reach `<hostname>.local`.
7. Adds a Hyprland `exec-once` autostart line for the daemon.
8. Optionally sets up AniList write access and starts the server.

When it finishes it prints your phone remote URL
(`http://<hostname>.local:4100/remote?k=<token>`) and the KDE Connect script paths.

> **Not on Hyprland?** Everything still works except the `hyprctl` auto-focus/fullscreen
> of the kiosk/player — the installer detects this and skips those bits.

## Configuration

`~/.config/shou/shou.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + phone remote port
QUALITY="1080p"                # passed to ani-cli
WATCHED_PERCENT="90"           # auto-mark watched once playback passes this %
# REMOTE_TOKEN="..."           # auto-generated on first launch — don't set by hand
# ANILIST_TOKEN="..."          # OAuth write token — set by ./shou_auth.sh
```

Changing `ANILIST_USER` / `QUALITY` only needs an **Open** tap (config reloads); no
restart. Changing `PORT`, `REMOTE_TOKEN`, or `ANILIST_TOKEN` needs a daemon restart.

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

## KDE Connect buttons (alternative to the web remote)

If you'd rather use [KDE Connect](https://kdeconnect.kde.org/)'s *Run Command* plugin
instead of (or alongside) the PWA, add one command per script — they're thin loopback
`curl` clients with no token needed:

| Command name | Script |
| --- | --- |
| Shou: Open | `…/ScriptsKDE/shou_open.sh` |
| Shou: ◀ Left | `…/ScriptsKDE/shou_left.sh` |
| Shou: ▶ Right | `…/ScriptsKDE/shou_right.sh` |
| Shou: Select | `…/ScriptsKDE/shou_select.sh` |
| Shou: Back | `…/ScriptsKDE/shou_back.sh` |

(The web remote covers everything including playback + list switching; KDE Connect covers
the core navigation.)

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

- **Phone can't reach the PC** — confirm same network; try the LAN IP instead of
  `<hostname>.local`; make sure `avahi-daemon` is running (`systemctl status avahi-daemon`).
- **Remote loads but says offline / not live** — the daemon isn't running; start it
  (see [below](#run--restart-manually)) or check `~/.config/shou/shou.log`.
- **“ANILIST_USER is not set”** — set it in `shou.conf` and make sure the list is
  **public** (AniList → Settings → Profile).
- **Kiosk shows the old design after an update** — the running Firefox caches the page.
  Close the kiosk window and tap **Open** to relaunch it fresh.
- **Nothing plays / “no playable source”** — the scrapers couldn't find that title;
  Shou already tried the backup. Try another entry; check `~/.config/shou/ani-cli-last.log`.
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

Stops the server, removes the Hyprland autostart line (with a backup), and *optionally*
deletes your config and the virtualenv. It deliberately **leaves system packages, the
`nsswitch.conf` entry, and your KDE Connect buttons alone** — it prints how to remove
those by hand if you want to.

## License

MIT — see [LICENSE](LICENSE).

## Disclaimer

Shou is a personal hobby project. It hosts, stores, and distributes **no** copyrighted
content. It is a thin controller that drives third-party programs (`ani-cli`, `anipy-cli`,
`mpv`) and the public **AniList** API. Any streams those tools locate come from
third-party sites that Shou neither operates nor is affiliated with — you alone are
responsible for how you use it and for complying with the laws of your jurisdiction.
Please support creators through official services (Crunchyroll, Netflix, HIDIVE, your
local licensors, Blu-ray, etc.). Provided "as is", without warranty (see [LICENSE](LICENSE)).
