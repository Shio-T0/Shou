#!/bin/bash
# Move the carousel selection one step left.
PORT=4100
[ -f "$HOME/.config/anime/animeui.conf" ] && source "$HOME/.config/anime/animeui.conf"
curl -s -X POST "http://127.0.0.1:${PORT}/left" -o /dev/null
