#!/bin/bash
# Shou daemon: keeps the server running (restart-on-crash). Launched at login
# via a Hyprland `exec-once` so it inherits the Wayland/Hyprland session env it
# needs to spawn the firefox kiosk + mpv and to run hyprctl.
SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
APPDIR="$SCRIPT_DIR/shou"
LOG="$HOME/.config/shou/shou.log"
mkdir -p "$HOME/.config/shou"

PORT=4100
[ -f "$HOME/.config/shou/shou.conf" ] && source "$HOME/.config/shou/shou.conf"

# Don't start a second daemon if the server is already answering.
if curl -s -o /dev/null "http://127.0.0.1:${PORT}/"; then
  echo "[$(date)] Shou already running on :${PORT}; daemon exiting" >>"$LOG"
  exit 0
fi

while true; do
  echo "[$(date)] starting Shou server" >>"$LOG"
  uv run --project "$APPDIR" python "$APPDIR/server.py" >>"$LOG" 2>&1
  echo "[$(date)] server exited (code $?), restarting in 2s" >>"$LOG"
  sleep 2
done
