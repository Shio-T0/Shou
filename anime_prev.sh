#!/bin/bash
# Go to previous episode
STATE="$HOME/.config/anime/state"
source "$STATE"

pkill -f mpv 2>/dev/null
sleep 0.5

EPISODE=$((EPISODE > 1 ? EPISODE - 1 : 1))
sed -i "s/^EPISODE=.*/EPISODE=${EPISODE}/" "$STATE"

notify-send "⏮ Previous Episode" "${ANIME} — Episode ${EPISODE}" -u normal -t 2000

export ANI_CLI_PLAYER="mpv --fs"
printf "${SELECTION}\n" | ani-cli -e "${EPISODE}" "${ANIME}"
