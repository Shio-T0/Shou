# 🎌 Shou [ MacOS ]

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-ff4a32.svg)](LICENSE.md)
[![Branch: macOS](https://img.shields.io/badge/Branch-macOS-1f1f24.svg?logo=apple&logoColor=white)](#requirements)
[![Also on Linux](https://img.shields.io/badge/also%20on-Linux-1f1f24.svg?logo=linux&logoColor=white)](../../tree/main)
[![Also on Windows](https://img.shields.io/badge/also%20on-Windows-1f1f24.svg?logo=windows&logoColor=white)](../../tree/windows)

 - On **Windows**? There's a **[`windows branch`](../../tree/windows)** with its own installer.
 - On **Linux**? There's the **[`main branch`](../../tree/main)** with its own installer.

> **📍 You're on the `macos` branch** — it installs deps with **Homebrew**, autostarts via a
> **launchd LaunchAgent**, uses macOS's built-in **Bonjour** for `*.local` (nothing to set
> up), and notifies through **`osascript`**. The app itself is identical to Linux.

**Watch your anime entirely from your phone.** Shou turns your Mac into a cinematic AniList
kiosk and your phone into the only remote you touch — browse, play, resume, rate, even add
new shows, all from the couch. Pick something and it auto-plays your next unwatched episode
in `mpv`; mid-episode you can even **throw it to your phone** and toss it back to the Mac
right where you left off.

> 🌐 **[Full tour, screenshots & how it works → the website](https://shio-t0.github.io/Shou/)**
> This README is just the install guide.

## Requirements

- **macOS** (Apple Silicon or Intel) with **[Homebrew](https://brew.sh)** — no Homebrew? the installer offers to set it up.
- A **public** AniList account — so Shou can read your lists without logging in.
- Phone + Mac **on the same network**.
- Installed via Homebrew if missing: `mpv`, `uv`, and a **Chromium-family or Firefox browser** (Safari can't do a clean `--kiosk`). `curl` and **Bonjour** already ship with macOS. Optional: `ani-cli` (a second source).

## Install

Clone it onto the Mac that'll do the watching, `cd` in, and run:

```bash
./install.sh
```

That's the whole command. It's **idempotent** (re-run it as often as your anxiety demands),
uses **Homebrew**, and **asks before every system change** — nothing happens without a `y`.
It `brew install`s the deps, builds a private `uv` virtualenv, asks for your **AniList
username** (public list, remember), and offers a **launchd** login-autostart agent. When
it's done it prints your phone URL:

```
http://<name>.local:4100/remote?k=<your-private-token>
```

> **First run trips the firewall.** macOS will ask whether the browser/`mpv` may accept
> incoming connections — allow it on your LAN. That's Apple being cautious, not Shou.
>
> **Updating later?** Re-run `./install.sh` — it backfills new config keys without clobbering
> your settings.

## Connect your phone

### Using the App
I **recomend** getting our **Android App** from its **[Release](https://github.com/Shio-T0/Shou/releases/tag/v1.3.0)** (easier to download), 
or if you wish to build it yourself, check its **[README.md](android/README.md)**.

Recently, an **iOS** version of the app, albeit with less features, was created. Check 
its **[README.md](../../tree/macos/ios/README.md)**, you'll need a **MacOS** to build it. 
> **But Actually**... with the ammout of features removed from the **iOS App** for it to remain free and considering the work it needs on your side to remain active, 
> *you might be better off with just the browser instead of the iOS app*.

### Using the browser
Still, if the app doesn't run on your device, or you just don't want it (which is unfortunate), you can
still use the browser on your phone.

1. Make sure the daemon is up — the LaunchAgent handles it at login, or run `./shou_daemon.sh`
   once. The server lives in the background; the kiosk only appears when you press **Open**.
2. Open that `…/remote?k=<token>` URL in your phone's browser; the dot top-right turns
   **green “live”** once it reaches the Mac. **Add to Home Screen** for a one-tap app.
3. Press **⏻ Open** — the kiosk fades in with your list. You're now watching anime with your
   thumbs. Congratulations.

> `<name>.local` not resolving? Use the Mac's LAN IP instead. The `?k=<token>` is your private
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
launchctl kickstart -k gui/$(id -u)/com.shou.daemon   # restart the login agent
launchctl unload ~/Library/LaunchAgents/com.shou.daemon.plist   # stop it
uv run --project shou python shou/server.py           # run the server directly (live logs)
./uninstall.sh                                        # remove the agent; optionally config + venv
```

`uninstall.sh` leaves your Homebrew packages alone (they're probably used elsewhere) and
tells you how to remove them by hand if you insist.

## License & disclaimer

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** — free to use, modify, and share for any
**noncommercial** purpose; don't sell it, and keep the credit if you fork it. A community
**Homebrew tap** is fine; the official repos aren't.

Shou hosts, stores, and distributes **no** copyrighted content — it's a thin controller
around `anipy` / `ani-cli` / `mpv` and the public **AniList** API. Any streams those tools
find come from third-party sites Shou neither runs nor is affiliated with; you alone are
responsible for how you use it. Please support creators through official services. Provided
"as is", without warranty — like most anime adaptations of an ongoing manga.
