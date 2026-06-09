# 🎌 Shou [ Windows ]

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-ff4a32.svg)](LICENSE.md)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows%2010%2F11-1f1f24.svg?logo=windows&logoColor=white)](#requirements)
[![Linux version](https://img.shields.io/badge/also%20on-Linux-1f1f24.svg?logo=linux&logoColor=white)](../../tree/main)
[![Also on MacOS](https://img.shields.io/badge/Also%20on-macOS-1f1f24.svg?logo=apple&logoColor=white)](../../tree/macos)

 - On **macOS**? There's a **[`macos branch`](../../tree/macos)** with its own installer.
 - On **Linux**? There's the **[`main branch`](../../tree/main)** with its own installer.

> **📍 You're on the `windows` branch** — it installs deps with **winget**, sources episodes
> through **anipy** (pure Python, no Git Bash), drives `mpv` over a **JSON IPC named pipe**,
> and autostarts from a **Startup-folder shortcut**. The app itself is identical to Linux.

**Watch your anime entirely from your phone.** Shou turns a Windows PC into a cinematic
AniList kiosk and your phone into the only remote you touch — browse, play, resume, rate,
even add new shows, all from the couch. Pick something and it auto-plays your next unwatched
episode in `mpv`; mid-episode you can even **throw it to your phone** and toss it back to the
PC right where you left off.

> 🌐 **[Full tour, screenshots & how it works → the website](https://shio-t0.github.io/Shou/)**
> This README is just the install guide.

## Requirements

- **Windows 10 or 11** with **PowerShell**.
- A **public** AniList account — so Shou can read your lists without logging in.
- **[mpv](https://mpv.io)** on your `PATH` (or set `MPV_BIN` in `shou.conf`), and a browser for the kiosk (Edge ships with Windows; Firefox/Chrome work too).
- Phone + PC **on the same network**.
- `install.ps1` adds `uv` (and `mpv` if winget has it); Python deps come from `uv.lock`.

## Install

From inside the repo, in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

It's **idempotent** (safe to re-run) and **asks before each change**. It installs `uv` (+
`mpv`), syncs the Python env, creates `%USERPROFILE%\.config\shou\shou.conf` asking for your
**AniList username** (public list, remember), and optionally adds a **Startup-folder
shortcut** for login autostart. When it's done it prints your phone URL:

```
http://<pc-lan-ip>:4100/remote?k=<your-private-token>
```

> **mpv not found?** winget doesn't always carry it — grab it from [mpv.io](https://mpv.io)
> (or `scoop install mpv`) and either add it to `PATH` or set `MPV_BIN="C:\path\to\mpv.exe"`
> in `shou.conf`. Updating Shou later? Re-run `install.ps1`; it backfills new config keys
> without clobbering yours.

## Connect your phone

1. Make sure the daemon is up — the Startup shortcut handles it, or run `shou_daemon.ps1`
   once. The server lives in the background; the kiosk only appears when you press **Open**.
2. Open that `…/remote?k=<token>` URL in your phone's browser; the dot top-right turns
   **green “live”** once it reaches the PC. **Add to Home Screen** for a one-tap app.
3. Press **⏻ Open** — the kiosk fades in with your list. You're now watching anime with your
   thumbs. Congratulations.

> Use the PC's LAN IP if `<name>.local` doesn't resolve. The `?k=<token>` is your private
> key, so don't share the URL. Screen keeps dimming? There's a native
> **[Android app](android/README.md)** that won't sleep (and an iOS one on the
> [macos branch](../../tree/macos/ios)).

## Configuration

`%USERPROFILE%\.config\shou\shou.conf`:

```ini
ANILIST_USER="your_username"   # must be a PUBLIC list
PORT="4100"                    # server + remote port
WATCHED_PERCENT="90"           # auto-mark watched past this %
MPV_BIN=""                     # full path to mpv.exe (blank = use PATH)
BROWSER=""                     # full path to a kiosk browser (blank = auto-detect)
# REMOTE_TOKEN / ANILIST_TOKEN — managed for you; leave them out
```

`REMOTE_TOKEN` is generated on first launch. Run `.\shou_auth.ps1` to grant AniList write
access if you want Shou to **auto-mark episodes watched**. Changing user just needs an
**Open** tap; changing port/paths/tokens needs a daemon restart.

## Run / restart / uninstall

```powershell
powershell -ExecutionPolicy Bypass -File .\shou_daemon.ps1        # start (restart-on-crash wrapper)
Get-CimInstance Win32_Process | Where-Object {                    # stop daemon + server + mpv
  $_.CommandLine -match 'shou_daemon\.ps1|shou\\server\.py|shou-mpv'
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
uv run --project shou python shou\server.py                       # run the server directly (live logs)
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1          # remove Startup shortcut; optionally config + venv
```

`uninstall.ps1` leaves `uv`, `mpv`, and your browser installed.

## License & disclaimer

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** — free to use, modify, and share for any
**noncommercial** purpose; don't sell it, and keep the credit if you fork it.

Shou hosts, stores, and distributes **no** copyrighted content — it's a thin controller
around `anipy` / `mpv` and the public **AniList** API. Any streams those tools find come
from third-party sites Shou neither runs nor is affiliated with; you alone are responsible
for how you use it. Please support creators through official services. Provided "as is",
without warranty — like most anime adaptations of an ongoing manga.
