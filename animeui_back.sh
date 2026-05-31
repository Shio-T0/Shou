#!/bin/bash
# Stop playback and return to the AnimeUI carousel. The server kills mpv and
# refocuses/re-fullscreens the kiosk itself.
PORT=4100
[ -f "$HOME/.config/anime/animeui.conf" ] && source "$HOME/.config/anime/animeui.conf"
curl -s -o /dev/null -X POST "http://127.0.0.1:${PORT}/back"
