#!/bin/bash
# Skip to next episode
STATE="$HOME/.config/shou/state"
source "$STATE"

# Unique marker so we only ever kill OUR mpv, never e.g. an mpvpaper wallpaper.
MPV_IPC="${XDG_RUNTIME_DIR:-/tmp}/shou-mpv.sock"

# Kill only the anime mpv (matched by the marker), not every mpv on the system.
pkill -f "$MPV_IPC" 2>/dev/null
sleep 0.5

EPISODE=$((EPISODE + 1))

# Save new episode
sed -i "s/^EPISODE=.*/EPISODE=${EPISODE}/" "$STATE"

notify-send "⏭ Next Episode" "${ANIME} — Episode ${EPISODE}" -u normal -t 2000

export ANI_CLI_PLAYER="mpv --fs --input-ipc-server=$MPV_IPC"
printf "${SELECTION}\n" | ani-cli -e "${EPISODE}" "${ANIME}"
