#!/bin/bash
# Select the highlighted anime: plays the next unwatched episode, shows a sequel
# card if you're caught up (press again to play it), or plays the latest episode.
PORT=4100
[ -f "$HOME/.config/shou/shou.conf" ] && source "$HOME/.config/shou/shou.conf"
curl -s -X POST "http://127.0.0.1:${PORT}/select" -o /dev/null
