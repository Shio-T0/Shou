#!/bin/bash
# Shou daemon: keeps the server running (restart-on-crash). Launched at login
# from your graphical session (a compositor `exec`/`exec-once`, or an XDG
# ~/.config/autostart entry) so it inherits the display env it needs to spawn
# the browser kiosk + mpv.
# Portable script dir — BSD/macOS readlink has no -f, so follow symlinks by hand.
SOURCE="${BASH_SOURCE[0]:-$0}"
while [ -h "$SOURCE" ]; do
  SDIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"; case "$SOURCE" in /*) ;; *) SOURCE="$SDIR/$SOURCE";; esac
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
APPDIR="$SCRIPT_DIR/shou"
LOG="$HOME/.config/shou/shou.log"
mkdir -p "$HOME/.config/shou"

PORT=4100
[ -f "$HOME/.config/shou/shou.conf" ] && source "$HOME/.config/shou/shou.conf"

# Resolve uv. Launched from a login session (bspwm's bspwmrc, .xinitrc, an i3/openbox
# autostart, …) PATH often doesn't yet include ~/.local/bin, so a bare `uv` fails with
# "command not found" and the server never starts. Fall back to the usual install dirs.
UV="$(command -v uv 2>/dev/null)"
if [ -z "$UV" ]; then
  for c in "$HOME/.local/bin/uv" "$HOME/.cargo/bin/uv" /usr/local/bin/uv /usr/bin/uv; do
    [ -x "$c" ] && { UV="$c"; break; }
  done
fi
if [ -z "$UV" ]; then
  echo "[$(date)] uv not found on PATH or in the usual dirs — install uv, then re-login" >>"$LOG"
  exit 1
fi

# Don't start a second daemon if the server is already answering.
if curl -s -o /dev/null "http://127.0.0.1:${PORT}/"; then
  echo "[$(date)] Shou already running on :${PORT}; daemon exiting" >>"$LOG"
  exit 0
fi

while true; do
  echo "[$(date)] starting Shou server" >>"$LOG"
  "$UV" run --project "$APPDIR" python "$APPDIR/server.py" >>"$LOG" 2>&1
  echo "[$(date)] server exited (code $?), restarting in 2s" >>"$LOG"
  sleep 2
done
