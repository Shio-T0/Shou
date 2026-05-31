#!/usr/bin/env bash
#
#  AnimeUI — AniList write access setup (one-time).
#  Obtains an OAuth access token via AniList's authorization-code "PIN" flow and
#  stores it as ANILIST_TOKEN in ~/.config/anime/animeui.conf, enabling AnimeUI to
#  mark episodes watched automatically. Re-run anytime the token expires (~1 year).
#
set -euo pipefail

if [[ -t 1 ]]; then B=$'\e[1m'; D=$'\e[2m'; R=$'\e[0m'; C=$'\e[36m'; Y=$'\e[33m'; G=$'\e[32m'
else B=''; D=''; R=''; C=''; Y=''; G=''; fi

CONF="$HOME/.config/anime/animeui.conf"
REDIRECT="https://anilist.co/api/v2/oauth/pin"
mkdir -p "$HOME/.config/anime"

printf '%s🎌 AniList write access — AnimeUI%s\n\n' "$B" "$R"
printf '%s1.%s Open %shttps://anilist.co/settings/developer%s and create a client:\n' "$B" "$R" "$C" "$R"
printf '      Name: %sAnimeUI%s   ·   Redirect URL: %s%s%s\n\n' "$B" "$R" "$B" "$REDIRECT" "$R"

read -rp "   AniList Client ID: " CID
read -rsp "   AniList Client Secret: " CSECRET; echo
[[ -z "${CID:-}" || -z "${CSECRET:-}" ]] && { printf '%s✗ Client ID and Secret are required.%s\n' "$Y" "$R"; exit 1; }

AUTH_URL="https://anilist.co/api/v2/oauth/authorize?client_id=${CID}&redirect_uri=${REDIRECT}&response_type=code"
printf '\n%s2.%s Open this URL, log in, click %sAuthorise%s, then copy the code it shows:\n' "$B" "$R" "$B" "$R"
printf '   %s%s%s\n' "$C" "$AUTH_URL" "$R"
command -v xdg-open >/dev/null 2>&1 && xdg-open "$AUTH_URL" >/dev/null 2>&1 || true
echo
read -rp "   Paste the code (PIN): " CODE
[[ -z "${CODE:-}" ]] && { printf '%s✗ No code entered.%s\n' "$Y" "$R"; exit 1; }

# Build the JSON body via python so special characters can't break it.
BODY="$(CID="$CID" CSECRET="$CSECRET" REDIRECT="$REDIRECT" CODE="$CODE" python3 -c \
  'import os,json;print(json.dumps({"grant_type":"authorization_code","client_id":os.environ["CID"],"client_secret":os.environ["CSECRET"],"redirect_uri":os.environ["REDIRECT"],"code":os.environ["CODE"]}))')"

RESP="$(curl -s -X POST https://anilist.co/api/v2/oauth/token \
  -H 'Content-Type: application/json' -H 'Accept: application/json' -d "$BODY")"

TOKEN="$(printf '%s' "$RESP" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("access_token",""))
except Exception: print("")')"

if [[ -z "$TOKEN" ]]; then
  printf '\n%s✗ Could not get a token. AniList replied:%s\n%s\n' "$Y" "$R" "$RESP"
  exit 1
fi

if grep -q '^ANILIST_TOKEN=' "$CONF" 2>/dev/null; then
  # Replace existing line (| delimiter — tokens contain no pipes).
  tmp="$(mktemp)"; sed "s|^ANILIST_TOKEN=.*|ANILIST_TOKEN=\"$TOKEN\"|" "$CONF" >"$tmp" && mv "$tmp" "$CONF"
else
  printf '\n# AniList OAuth token (write access — auto-mark episodes watched).\nANILIST_TOKEN="%s"\n' "$TOKEN" >>"$CONF"
fi
chmod 600 "$CONF" 2>/dev/null || true

printf '\n%s✓ Saved ANILIST_TOKEN to %s%s\n' "$G" "$CONF" "$R"
printf '  Restart the daemon to enable auto-progress:\n'
printf '  %spkill -f animeui_daemon.sh; pkill -f animeui/server.py; setsid nohup %s/animeui_daemon.sh >/dev/null 2>&1 &%s\n' \
  "$D" "$(cd "$(dirname "$(readlink -f "$0")")" && pwd)" "$R"
