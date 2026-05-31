#!/bin/bash
# Open Shou: ask the (always-running) server to reset cleanly and show the kiosk.
# The server owns the kiosk window, playback and config reload now; this is a thin
# loopback client that also starts the daemon as a fallback if it isn't up yet.
SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
CONF="$HOME/.config/shou/shou.conf"

PORT=4100
[ -f "$CONF" ] && source "$CONF"

if ! curl -s -o /dev/null -X POST "http://127.0.0.1:${PORT}/open"; then
  # Server not running — start the daemon, wait for it, then open.
  notify-send "🎌 Shou" "Starting server…" -u low -t 1500
  nohup "$SCRIPT_DIR/shou_daemon.sh" >/dev/null 2>&1 &
  for _ in $(seq 1 30); do
    curl -s -o /dev/null "http://127.0.0.1:${PORT}/" && break
    sleep 0.5
  done
  curl -s -o /dev/null -X POST "http://127.0.0.1:${PORT}/open"
fi
