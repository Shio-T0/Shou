#!/bin/bash
# Show current anime status as notification
STATE="$HOME/.config/shou/state"

if [ ! -f "$STATE" ]; then
  notify-send "🎌 Anime Player" "Nothing set." -u normal
  exit 0
fi

source "$STATE"

MPV_STATUS=$(playerctl -p mpv status 2>/dev/null || echo "Stopped")

notify-send "🎌 Anime Status" \
  "${ANIME}
Episode: ${EPISODE}
Player: ${MPV_STATUS}" -u normal
