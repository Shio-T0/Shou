#!/usr/bin/env bash
#
#   █████╗ ███╗   ██╗██╗███╗   ███╗███████╗██╗   ██╗██╗
#  ██╔══██╗████╗  ██║██║████╗ ████║██╔════╝██║   ██║██║
#  ███████║██╔██╗ ██║██║██╔████╔██║█████╗  ██║   ██║██║
#  ██╔══██║██║╚██╗██║██║██║╚██╔╝██║██╔══╝  ██║   ██║██║
#  ██║  ██║██║ ╚████║██║██║ ╚═╝ ██║███████╗╚██████╔╝██║
#  ╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝╚═╝     ╚═╝╚══════╝ ╚═════╝ ╚═╝
#
#  Phone-controlled AniList "Currently Watching" launcher — Arch Linux installer.
#  Idempotent: safe to re-run. It only touches your system with your confirmation.
#
set -euo pipefail

# --------------------------------------------------------------------------- #
#  Pretty output
# --------------------------------------------------------------------------- #
if [[ -t 1 ]]; then
  BOLD=$'\e[1m'; DIM=$'\e[2m'; RESET=$'\e[0m'
  RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'
  BLUE=$'\e[34m'; MAGENTA=$'\e[35m'; CYAN=$'\e[36m'
else
  BOLD=''; DIM=''; RESET=''; RED=''; GREEN=''; YELLOW=''; BLUE=''; MAGENTA=''; CYAN=''
fi

STEP=0
step()  { STEP=$((STEP + 1)); printf '\n%s%s▸ [%d] %s%s\n' "$BOLD" "$CYAN" "$STEP" "$1" "$RESET"; }
info()  { printf '   %s•%s %s\n' "$BLUE" "$RESET" "$1"; }
ok()    { printf '   %s✓%s %s\n' "$GREEN" "$RESET" "$1"; }
warn()  { printf '   %s!%s %s\n' "$YELLOW" "$RESET" "$1"; }
die()   { printf '\n%s✗ %s%s\n' "$RED" "$1" "$RESET" >&2; exit 1; }

ask_yes() {  # ask_yes "Question?"  -> returns 0 for yes (default yes)
  local reply
  printf '   %s?%s %s %s[Y/n]%s ' "$MAGENTA" "$RESET" "$1" "$DIM" "$RESET"
  read -r reply || true
  [[ -z "$reply" || "$reply" =~ ^[Yy] ]]
}

banner() {
  printf '%s%s' "$BOLD" "$MAGENTA"
  cat <<'ART'

   ┌──────────────────────────────────────────────┐
   │   🎌  A N I M E U I   —   I N S T A L L E R   │
   │   phone-controlled AniList watching, on Arch  │
   └──────────────────────────────────────────────┘
ART
  printf '%s' "$RESET"
}

# --------------------------------------------------------------------------- #
#  Paths
# --------------------------------------------------------------------------- #
REPO_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DIR="$REPO_DIR/animeui"
CONFIG_DIR="$HOME/.config/anime"
CONFIG_FILE="$CONFIG_DIR/animeui.conf"
DAEMON="$REPO_DIR/animeui_daemon.sh"

# Packages from the official repos and the AUR.
OFFICIAL_PKGS=(uv firefox mpv playerctl curl libnotify avahi nss-mdns librsvg)
AUR_PKGS=(ani-cli)

banner

# --------------------------------------------------------------------------- #
step "Pre-flight checks"
# --------------------------------------------------------------------------- #
[[ $EUID -eq 0 ]] && die "Run this as your normal user, not root (it uses sudo only where needed)."
command -v pacman >/dev/null 2>&1 || die "This installer targets Arch Linux (pacman not found)."
[[ -f "$APP_DIR/server.py" ]] || die "Can't find animeui/server.py next to this script. Run it from inside the repo."
ok "Arch Linux detected, repo at ${DIM}$REPO_DIR${RESET}"

if command -v hyprctl >/dev/null 2>&1 || [[ "${XDG_CURRENT_DESKTOP:-}" == *Hyprland* ]]; then
  ok "Hyprland detected — auto-focus / auto-fullscreen of the kiosk & player will work."
  HAS_HYPR=1
else
  warn "Hyprland not detected. AnimeUI still runs, but the window auto-focus/fullscreen"
  warn "tricks (hyprctl) won't apply on other compositors. Everything else works."
  HAS_HYPR=0
fi

# --------------------------------------------------------------------------- #
step "Installing system dependencies"
# --------------------------------------------------------------------------- #
info "Official repos: ${OFFICIAL_PKGS[*]}"
if ask_yes "Install/upgrade these with pacman?"; then
  sudo pacman -S --needed "${OFFICIAL_PKGS[@]}"
  ok "Official packages ready."
else
  warn "Skipped — make sure they're already installed."
fi

# ani-cli lives in the AUR; use an AUR helper if present.
info "AUR packages: ${AUR_PKGS[*]}"
if command -v ani-cli >/dev/null 2>&1; then
  ok "ani-cli already installed."
else
  AUR_HELPER=""
  for h in paru yay; do command -v "$h" >/dev/null 2>&1 && { AUR_HELPER="$h"; break; }; done
  if [[ -n "$AUR_HELPER" ]]; then
    if ask_yes "Install ${AUR_PKGS[*]} with $AUR_HELPER?"; then
      "$AUR_HELPER" -S --needed "${AUR_PKGS[@]}"
      ok "AUR packages ready."
    fi
  else
    warn "No AUR helper (paru/yay) found. Install ani-cli manually, e.g.:"
    printf '       %sgit clone https://aur.archlinux.org/ani-cli.git && cd ani-cli && makepkg -si%s\n' "$DIM" "$RESET"
  fi
fi

# --------------------------------------------------------------------------- #
step "Installing the Python environment (uv)"
# --------------------------------------------------------------------------- #
command -v uv >/dev/null 2>&1 || die "uv is required but missing (install it in the previous step)."
( cd "$APP_DIR" && uv sync )
ok "Python virtualenv synced from uv.lock."

# --------------------------------------------------------------------------- #
step "Making control scripts executable"
# --------------------------------------------------------------------------- #
chmod +x "$REPO_DIR"/animeui_*.sh "$REPO_DIR"/anime_*.sh 2>/dev/null || true
ok "AnimeUI + legacy anime scripts are executable."

# --------------------------------------------------------------------------- #
step "Configuration"
# --------------------------------------------------------------------------- #
mkdir -p "$CONFIG_DIR"
if [[ -f "$CONFIG_FILE" ]]; then
  ok "Existing config kept: ${DIM}$CONFIG_FILE${RESET}"
  CUR_USER="$(grep -E '^ANILIST_USER=' "$CONFIG_FILE" | head -1 | cut -d= -f2- | tr -d '\042\047' || true)"
  [[ -n "${CUR_USER:-}" && "$CUR_USER" != "CHANGE_ME" ]] && info "AniList user: ${BOLD}$CUR_USER${RESET}"
else
  printf '   %s?%s Your %spublic%s AniList username (Settings → make list public): ' \
    "$MAGENTA" "$RESET" "$BOLD" "$RESET"
  read -r ANILIST_USER || true
  [[ -z "${ANILIST_USER:-}" ]] && ANILIST_USER="CHANGE_ME"
  cat >"$CONFIG_FILE" <<EOF
# AnimeUI configuration
# Your AniList username — the list MUST be public (Settings → Profile → "public").
ANILIST_USER="$ANILIST_USER"
# Port the server / phone remote listens on.
PORT="4100"
# mpv playback quality passed to ani-cli.
QUALITY="1080p"
EOF
  # The server appends an auto-generated REMOTE_TOKEN on first launch.
  ok "Wrote ${DIM}$CONFIG_FILE${RESET}"
  [[ "$ANILIST_USER" == "CHANGE_ME" ]] && warn "Set ANILIST_USER later — it's a placeholder for now."
fi

# --------------------------------------------------------------------------- #
step "mDNS — reach the PC by name from your phone"
# --------------------------------------------------------------------------- #
if systemctl is-active --quiet avahi-daemon 2>/dev/null; then
  ok "avahi-daemon already running."
else
  if ask_yes "Enable avahi-daemon so the phone can use ${BOLD}$(hostname).local${RESET}?"; then
    sudo systemctl enable --now avahi-daemon
    ok "avahi-daemon enabled."
  else
    warn "Skipped — you'll have to use the PC's IP address from the phone."
  fi
fi
# nss-mdns wiring so *.local actually resolves locally too.
if ! grep -qE '^\s*hosts:.*mdns' /etc/nsswitch.conf 2>/dev/null; then
  if ask_yes "Add 'mdns_minimal' to /etc/nsswitch.conf for .local resolution? (a backup is made)"; then
    sudo cp /etc/nsswitch.conf "/etc/nsswitch.conf.animeui.bak.$(date +%s)"
    sudo sed -i -E 's/^(hosts:\s*)(.*)$/\1mdns_minimal [NOTFOUND=return] \2/' /etc/nsswitch.conf
    ok "nsswitch.conf updated (backup saved)."
  fi
else
  ok "nss-mdns already configured in nsswitch.conf."
fi

# --------------------------------------------------------------------------- #
step "Autostart on login (Hyprland)"
# --------------------------------------------------------------------------- #
EXEC_LINE="exec-once = $DAEMON"
HYPR_TARGET=""
for f in "$HOME/.config/hypr/hyprland/execs.conf" "$HOME/.config/hypr/hyprland.conf"; do
  [[ -f "$f" ]] && { HYPR_TARGET="$f"; break; }
done
if [[ "$HAS_HYPR" -eq 1 && -n "$HYPR_TARGET" ]]; then
  if grep -qF "$DAEMON" "$HYPR_TARGET"; then
    ok "Autostart already present in ${DIM}$HYPR_TARGET${RESET}"
  elif ask_yes "Add the daemon to ${DIM}$HYPR_TARGET${RESET} so it starts on login?"; then
    printf '\n# AnimeUI server (auto-added by install.sh)\n%s\n' "$EXEC_LINE" >>"$HYPR_TARGET"
    ok "Added autostart line."
  fi
else
  warn "No Hyprland config found to edit. Start AnimeUI on login yourself with:"
  printf '       %s%s%s\n' "$DIM" "$DAEMON" "$RESET"
fi

# --------------------------------------------------------------------------- #
step "Start the server now?"
# --------------------------------------------------------------------------- #
if curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null; then
  ok "Server already answering on :4100."
elif ask_yes "Launch the AnimeUI daemon now?"; then
  setsid nohup "$DAEMON" >/dev/null 2>&1 &
  for _ in $(seq 1 20); do
    curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null && break
    sleep 0.5
  done
  curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null \
    && ok "Server is up." \
    || warn "Server didn't answer yet — check ~/.config/anime/animeui.log"
fi

# --------------------------------------------------------------------------- #
#  Done — next steps
# --------------------------------------------------------------------------- #
TOKEN="$(grep -E '^REMOTE_TOKEN=' "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\042\047' || true)"
HOST="$(hostname)"
printf '\n%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s  AnimeUI is installed. 🎌%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n\n' "$BOLD" "$GREEN" "$RESET"

printf '%sPhone web-remote:%s\n' "$BOLD" "$RESET"
if [[ -n "$TOKEN" ]]; then
  printf '   %shttp://%s.local:4100/remote?k=%s%s\n' "$CYAN" "$HOST" "$TOKEN" "$RESET"
  info "Open it on your phone, then 'Add to Home screen' for a one-tap icon."
else
  info "Start the server once; it generates a token and prints the full /remote URL"
  info "to ${DIM}~/.config/anime/animeui.log${RESET}. Then add that URL to your phone's home screen."
fi

printf '\n%sKDE Connect buttons%s (Run Command plugin → add these commands):\n' "$BOLD" "$RESET"
for s in open left right select back; do
  printf '   %sAnimeUI %-7s%s  %s%s/animeui_%s.sh%s\n' "$MAGENTA" "$s" "$RESET" "$DIM" "$REPO_DIR" "$s" "$RESET"
done

printf '\n%sTip:%s if %s.local%s doesn'\''t resolve on the phone, use the PC'\''s LAN IP instead.\n' \
  "$BOLD" "$RESET" "$DIM" "$RESET"
if [[ "${ANILIST_USER:-}" == "CHANGE_ME" ]]; then
  printf '\n%s⚠ Remember to set ANILIST_USER in %s%s\n' "$YELLOW" "$CONFIG_FILE" "$RESET"
fi
printf '\n'
