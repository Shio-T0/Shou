# 🎌 AnimeUI

Phone-controlled launcher for your AniList **Currently Watching** list, for Arch Linux.

A long-running Flask + SocketIO server is the single brain: it fetches your public
AniList list, shows a live carousel in a **Firefox kiosk** on the PC, and serves a
**touch-first phone web-remote (PWA)** that mirrors the kiosk over WebSocket. Selecting an
anime auto-plays the next unwatched episode through `ani-cli`/`mpv` (fullscreen) — and if
you're caught up, it recommends the sequel (press again to play) or plays the latest
released episode. If `ani-cli` finds no source, it falls back to the `anipy` scrapers.
You never need to touch the computer.

Two control surfaces, both just hit the server's HTTP endpoints:

- **Phone web-remote** at `/remote` — add it to your home screen for a one-tap icon.
- **KDE Connect** Run-Command buttons calling the thin `animeui_*.sh` loopback clients.

---

## Install

On any Arch Linux machine, from inside the repo:

```bash
./install.sh
```

The installer is **idempotent** (safe to re-run) and asks before each system change. It:

1. Checks you're on Arch and not root; detects Hyprland (for window auto-focus/fullscreen).
2. Installs deps via pacman — `uv firefox mpv playerctl curl libnotify avahi nss-mdns librsvg` — and `ani-cli` from the AUR (via `paru`/`yay`).
3. Syncs the Python env with `uv sync` (from the committed `uv.lock`).
4. Marks the control scripts executable.
5. Writes `~/.config/anime/animeui.conf`, prompting for your **public** AniList username.
6. Enables `avahi-daemon` + wires `nss-mdns` so the phone can reach `<hostname>.local`.
7. Adds a Hyprland `exec-once` autostart line for the daemon.
8. Optionally starts the server immediately.

When it finishes it prints your phone remote URL (`http://<hostname>.local:4100/remote?k=<token>`)
and the paths to register as KDE Connect buttons.

> **Not on Hyprland?** Everything still works except the `hyprctl` auto-focus/fullscreen
> of the kiosk/player. The installer warns and skips those bits.

### Manual steps the installer can't do for you

- **Make your AniList list public**: AniList → Settings → Profile → set list to public (no OAuth needed).
- **Add the phone home-screen icon**: open the printed `/remote?k=…` URL on your phone → browser menu → *Add to Home screen*.
  Over plain HTTP this is a one-tap shortcut that opens in the browser; for a chromeless
  app, serve it over HTTPS (e.g. `tailscale serve`).
- **Register KDE Connect buttons**: in the KDE Connect app → Run Command → add one per script
  (`animeui_open.sh`, `animeui_left.sh`, `animeui_right.sh`, `animeui_select.sh`, `animeui_back.sh`).
- If `<hostname>.local` won't resolve on the phone, use the PC's LAN IP instead — the
  remote's `start_url` is host-relative, so the home-screen icon keeps working either way.

### Configuration

`~/.config/anime/animeui.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + phone remote port
QUALITY="1080p"                # passed to ani-cli
WATCHED_PERCENT="90"           # mark watched once playback passes this %
# REMOTE_TOKEN="..."           # auto-generated on first launch — don't set by hand
# ANILIST_TOKEN="..."          # OAuth write token — set by ./animeui_auth.sh
```

Changing `ANILIST_USER`/`QUALITY` only needs an **Open** tap (config reloads); no restart.

### Auto-mark episodes watched on AniList (optional)

By default AnimeUI only *reads* your public list. To have it **tick episodes off on
AniList automatically** when you finish them, give it write access once:

```bash
./animeui_auth.sh
```

It walks you through creating an AniList API client
(`https://anilist.co/settings/developer`, redirect URL `https://anilist.co/api/v2/oauth/pin`),
approving it, and pasting the code; the resulting OAuth token is saved as
`ANILIST_TOKEN` in `animeui.conf` (gitignored, never in the repo). The installer also
offers this step. **Restart the daemon afterwards.**

Once enabled, when playback passes `WATCHED_PERCENT` (default 90%) — or mpv reaches a
clean end — AnimeUI sets that episode as your progress (only ever *increasing* it; it
won't undo progress on a rewatch or **⏮**), and flips the entry to *Completed* on the
final episode. Tokens last ~1 year; re-run `./animeui_auth.sh` when it expires.

## Uninstall

```bash
./uninstall.sh
```

Stops the server, removes the Hyprland autostart line (with a backup), and *optionally*
deletes your config and the virtualenv. It deliberately **leaves system packages,
the nsswitch.conf entry, and your KDE Connect buttons alone** — it prints how to remove
those by hand if you want to.

## Run it manually

```bash
./animeui_daemon.sh        # restart-on-crash wrapper (what autostart runs)
# or directly:
uv run --project animeui python animeui/server.py
```

The server prints the full `/remote?k=…` URL to `~/.config/anime/animeui.log` on startup.
