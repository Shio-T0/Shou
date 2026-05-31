#!/bin/bash
# Stop playback and return to the Shou carousel. The server kills mpv and
# refocuses/re-fullscreens the kiosk itself.
PORT=4100
[ -f "$HOME/.config/shou/shou.conf" ] && source "$HOME/.config/shou/shou.conf"
curl -s -o /dev/null -X POST "http://127.0.0.1:${PORT}/back"
