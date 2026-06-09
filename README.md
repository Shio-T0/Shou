# 🎌 Shou [ Linux ]

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-ff4a32.svg)](LICENSE.md)
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-1f1f24.svg?logo=linux&logoColor=white)](#requirements)
[![Also on MacOS](https://img.shields.io/badge/Also%20on-macOS-1f1f24.svg?logo=apple&logoColor=white)](../../tree/macos)
[![Also on Windows](https://img.shields.io/badge/Also%20on-Windows-1f1f24.svg?logo=windows&logoColor=white)](../../tree/windows)

 - On **Windows**? There's a **[`windows branch`](../../tree/windows)** with its own installer.
 - On **macOS**? There's a **[`macos branch`](../../tree/macos)** with its own installer.

**Watch your anime entirely from your phone.** Shou turns a PC into a cinematic AniList
kiosk and your phone into the only remote you touch — browse, play, resume, rate, even add
new shows, all from the couch. Pick something and it auto-plays your next unwatched episode
in `mpv`; mid-episode you can even **throw it to your phone** and toss it back to the PC
right where you left off.

> 🌐 **[Full tour, screenshots & how it works → the website](https://shio-t0.github.io/Shou/)**
> This README is just the install guide.

## Requirements

- **Linux** with `pacman` / `apt` / `dnf` / `zypper` / `xbps` / `apk` (no match? you install the handful of deps by hand).
- A **public** AniList account — so Shou can read your lists without logging in.
- Phone + PC **on the same network**.
- Auto-installed if missing: `mpv`, a browser, `curl`, `uv`. Optional extras: `ani-cli` (a second source), `libnotify` (notifications), `avahi` + `nss-mdns` (reach the PC as `<hostname>.local`).

## Install

Clone it onto the PC that'll do the watching, `cd` in, and run:

```bash
./install.sh
```

That's the whole command. It's **idempotent** (re-run it as often as your anxiety demands),
detects your package manager, and **asks before every system change** — nothing happens
without a `y`. It installs the deps, builds a private `uv` virtualenv (no system Python
pollution), asks for your **AniList username** (public list, remember), and offers mDNS +
login-autostart. When it's done it prints your phone URL:

```
http://<hostname>.local:4100/remote?k=<your-private-token>
```

> Updating later? Just re-run `./install.sh` — it backfills new config keys without
> clobbering your settings, so you never hand-edit a config to catch up.

## Connect your phone

### Using the App
I **recomend** getting our **Android App** from its **[Release](https://github.com/Shio-T0/Shou/releases/tag/v1.3.0)** (easier to download), 
or if you wish to build it yourself, check its **[README.md](android/README.md)**.

Recently, an **iOS** version of the app, albeit with less features, was created. Check 
its **[README.md](../../tree/macos/ios/README.md)** and go to the **[MacOS branch](../../tree/macos)**
since macos is needed for the iOS app instalation.

### Using the browser
Still, if the app doesn't run on your device, or you just don't want it (which is unfortunate), you can
still use the browser on your phone.

1. Make sure the daemon is up — autostart handles it, or run `./shou_daemon.sh` once. The
   server lives in the background; the kiosk only appears when you press **Open**.
2. Open that `…/remote?k=<token>` URL in your phone's browser; the dot top-right turns
   **green “live”** once it reaches the PC. **Add to Home Screen** for a one-tap app.
3. Press **⏻ Open** — the kiosk fades in on the PC with your list. You're now watching anime
   with your thumbs. Congratulations.

> `<hostname>.local` not resolving? Use the PC's LAN IP instead (`http://192.168.1.50:4100/…`)
> — DNS, the cause of *and* solution to all networking woes. The `?k=<token>` is your private
> key, so don't share the URL.

## Configuration

`~/.config/shou/shou.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + remote port
QUALITY="1080p"                # ani-cli quality (anipy picks best on its own)
WATCHED_PERCENT="90"           # auto-mark watched past this %
# REMOTE_TOKEN / ANILIST_TOKEN — managed for you; leave them out
```

`REMOTE_TOKEN` is generated on first launch. Run `./shou_auth.sh` to grant AniList write
access if you want Shou to **auto-mark episodes watched**. Changing user/quality just needs
an **Open** tap; changing port or tokens needs a daemon restart.

## Run / restart / uninstall

```bash
./shou_daemon.sh                                   # start (restart-on-crash wrapper)
pkill -f shou_daemon.sh; pkill -f shou/server.py   # stop
uv run --project shou python shou/server.py        # run the server directly (live logs)
./uninstall.sh                                     # remove autostart; optionally config + venv
```

## License & disclaimer

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** — free to use, modify, and share for any
**noncommercial** purpose; don't sell it, and keep the credit if you fork it.

Shou hosts, stores, and distributes **no** copyrighted content — it's a thin controller
around `anipy` / `ani-cli` / `mpv` and the public **AniList** API. Any streams those tools
find come from third-party sites Shou neither runs nor is affiliated with; you alone are
responsible for how you use it. Please support creators through official services. Provided
"as is", without warranty — like most anime adaptations of an ongoing manga.
