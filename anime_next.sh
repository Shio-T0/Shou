#!/bin/bash
# Skip to next episode
STATE="$HOME/.config/anime/state"
source "$STATE"

# Kill current mpv if running
pkill -f mpv 2>/dev/null
sleep 0.5

EPISODE=$((EPISODE + 1))

# Save new episode
sed -i "s/^EPISODE=.*/EPISODE=${EPISODE}/" "$STATE"

notify-send "⏭ Next Episode" "${ANIME} — Episode ${EPISODE}" -u normal -t 2000

export ANI_CLI_PLAYER="mpv --fs"
printf "${SELECTION}\n" | ani-cli -e "${EPISODE}" "${ANIME}"
