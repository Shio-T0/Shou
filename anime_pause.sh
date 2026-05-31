#!/bin/bash
# Toggle play/pause on mpv
playerctl -p mpv play-pause 2>/dev/null &&
  notify-send "⏯ Anime" "Play/Pause toggled" -u low -t 1500 ||
  notify-send "⏯ Anime" "mpv not running" -u normal
