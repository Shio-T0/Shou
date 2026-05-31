#!/bin/bash
# Move the carousel selection one step right.
PORT=4100
[ -f "$HOME/.config/shou/shou.conf" ] && source "$HOME/.config/shou/shou.conf"
curl -s -X POST "http://127.0.0.1:${PORT}/right" -o /dev/null
