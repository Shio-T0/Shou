#!/bin/bash
# Usage: anime_set.sh "Anime Name" <episode> <selection_number>
# selection_number = which result to pick from ani-cli search (usually 1)
STATE="$HOME/.config/anime/state"
mkdir -p "$HOME/.config/anime"

ANIME="${1:-Cowboy Bebop}"
EPISODE="${2:-1}"
SELECTION="${3:-1}"

cat >"$STATE" <<STATEEOF
ANIME="${ANIME}"
EPISODE=${EPISODE}
SELECTION=${SELECTION}
STATEEOF

notify-send "🎌 Anime Set" "${ANIME} — starting at episode ${EPISODE}" -u normal
