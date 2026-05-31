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

# Portable hostname — `hostname` (inetutils) isn't installed on a default Arch box.
get_hostname() {
  local h="${HOSTNAME:-}"
  [[ -z "$h" && -r /etc/hostname ]] && h="$(< /etc/hostname)"
  [[ -z "$h" && -r /proc/sys/kernel/hostname ]] && h="$(< /proc/sys/kernel/hostname)"
  [[ -z "$h" ]] && h="$(uname -n 2>/dev/null || echo localhost)"
  printf '%s' "$h"
}

partial_upgrade_hint() {
  printf '\n%s%s   pacman couldn'\''t install a dependency.%s\n' "$BOLD" "$YELLOW" "$RESET"
  printf '   If you saw %s"breaks dependency"%s or %s"unable to satisfy dependency"%s, your\n' "$BOLD" "$RESET" "$BOLD" "$RESET"
  printf '   system is in a %spartial-upgrade%s state (the package DB is newer than your\n' "$BOLD" "$RESET"
  printf '   installed packages, e.g. python-uv pins an older uv). Fix it with a FULL\n'
  printf '   upgrade so paired packages move together, then re-run this installer:\n\n'
  printf '       %ssudo pacman -Syu%s\n' "$BOLD$CYAN" "$RESET"
  printf '       %s./install.sh%s\n\n' "$BOLD$CYAN" "$RESET"
  printf '   %s(Never "pacman -S <one pkg>" on a not-fully-upgraded system — it'\''s unsupported on Arch.)%s\n' "$DIM" "$RESET"
}

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
   │   朱   S H O U   ·   I N S T A L L E R         │
   │   your anime, controlled from your phone       │
   └──────────────────────────────────────────────┘
ART
  printf '%s' "$RESET"
}

# --------------------------------------------------------------------------- #
#  Paths
# --------------------------------------------------------------------------- #
REPO_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DIR="$REPO_DIR/shou"
CONFIG_DIR="$HOME/.config/shou"
CONFIG_FILE="$CONFIG_DIR/shou.conf"
DAEMON="$REPO_DIR/shou_daemon.sh"

# Packages from the official repos and the AUR.
OFFICIAL_PKGS=(uv firefox mpv playerctl curl libnotify avahi nss-mdns librsvg)
AUR_PKGS=(ani-cli)

banner

# --------------------------------------------------------------------------- #
step "Pre-flight checks"
# --------------------------------------------------------------------------- #
[[ $EUID -eq 0 ]] && die "Run this as your normal user, not root (it uses sudo only where needed)."
command -v pacman >/dev/null 2>&1 || die "This installer targets Arch Linux (pacman not found)."
[[ -f "$APP_DIR/server.py" ]] || die "Can't find shou/server.py next to this script. Run it from inside the repo."
ok "Arch Linux detected, repo at ${DIM}$REPO_DIR${RESET}"

if command -v hyprctl >/dev/null 2>&1 || [[ "${XDG_CURRENT_DESKTOP:-}" == *Hyprland* ]]; then
  ok "Hyprland detected — auto-focus / auto-fullscreen of the kiosk & player will work."
  HAS_HYPR=1
else
  warn "Hyprland not detected. Shou still runs, but the window auto-focus/fullscreen"
  warn "tricks (hyprctl) won't apply on other compositors. Everything else works."
  HAS_HYPR=0
fi

# --------------------------------------------------------------------------- #
step "Installing system dependencies"
# --------------------------------------------------------------------------- #
# pkg -> a binary it provides, used to detect installs done OUTSIDE pacman (e.g. uv's
# standalone installer). Empty = library with no binary (rely on pacman -Q only).
declare -A PKG_PROBE=(
  [uv]=uv [firefox]=firefox [mpv]=mpv [playerctl]=playerctl [curl]=curl
  [libnotify]=notify-send [avahi]=avahi-daemon [nss-mdns]="" [librsvg]=rsvg-convert
)
MISSING=()
for pkg in "${OFFICIAL_PKGS[@]}"; do
  if pacman -Qq "$pkg" &>/dev/null; then
    continue                              # already installed via pacman
  fi
  probe="${PKG_PROBE[$pkg]:-}"
  if [[ -n "$probe" ]] && command -v "$probe" >/dev/null 2>&1; then
    info "$pkg: '$probe' already on PATH (installed outside pacman) — skipping."
    continue
  fi
  MISSING+=("$pkg")
done

if [[ ${#MISSING[@]} -eq 0 ]]; then
  ok "All official dependencies already present."
else
  info "Need to install: ${MISSING[*]}"
  if ask_yes "Install these with pacman?"; then
    if sudo pacman -S --needed "${MISSING[@]}"; then
      ok "Official packages ready."
    else
      partial_upgrade_hint
      die "Dependency install failed — see the hint above, then re-run ./install.sh"
    fi
  else
    warn "Skipped — make sure they're already installed."
  fi
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
chmod +x "$REPO_DIR"/shou_*.sh 2>/dev/null || true
ok "Shou scripts are executable."

# --------------------------------------------------------------------------- #
step "Configuration"
# --------------------------------------------------------------------------- #
mkdir -p "$CONFIG_DIR"

# --- tiny conf helpers (KEY="value" lines) -------------------------------------
conf_has() { grep -qE "^[[:space:]]*$1=" "$CONFIG_FILE" 2>/dev/null; }
conf_get() { grep -E "^[[:space:]]*$1=" "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\042\047' || true; }
conf_set() {  # conf_set KEY VALUE  — replace in place or append
  if conf_has "$1"; then
    local t; t="$(mktemp)"; sed "s|^[[:space:]]*$1=.*|$1=\"$2\"|" "$CONFIG_FILE" >"$t" && mv "$t" "$CONFIG_FILE"
  else
    printf '%s="%s"\n' "$1" "$2" >>"$CONFIG_FILE"
  fi
}

# Canonical user-editable settings: KEY | default | comment.
# New Shou versions add rows here; re-running install backfills any the config is
# missing (handy after an update) WITHOUT changing values you've already set.
CONF_SETTINGS=(
  "PORT|4100|Port the server / phone remote listens on."
  "QUALITY|1080p|mpv playback quality passed to ani-cli (e.g. 1080p, 720p)."
  "WATCHED_PERCENT|90|Auto-mark an episode watched on AniList once playback passes this %."
)

if [[ ! -f "$CONFIG_FILE" ]]; then
  cat >"$CONFIG_FILE" <<'EOF'
# Shou configuration  —  ~/.config/shou/shou.conf
# KEY="value" lines. Re-running ./install.sh adds any new keys without
# overwriting ones you've set. Tokens below are managed automatically:
#   REMOTE_TOKEN  — auto-generated by the server on first launch
#   ANILIST_TOKEN — written by ./shou_auth.sh (optional AniList write access)
EOF
  ok "Created ${DIM}$CONFIG_FILE${RESET}"
fi

# ANILIST_USER — prompt only if unset/placeholder; otherwise keep silently.
CUR_USER="$(conf_get ANILIST_USER || true)"
if [[ -z "$CUR_USER" || "$CUR_USER" == "CHANGE_ME" ]]; then
  printf '   %s?%s Your %spublic%s AniList username (Settings → make list public): ' \
    "$MAGENTA" "$RESET" "$BOLD" "$RESET"
  read -r ANILIST_USER || true
  [[ -z "${ANILIST_USER:-}" ]] && ANILIST_USER="CHANGE_ME"
  if ! conf_has ANILIST_USER; then
    printf '# Your AniList username — the list MUST be public (Settings → Profile).\n' >>"$CONFIG_FILE"
  fi
  conf_set ANILIST_USER "$ANILIST_USER"
  [[ "$ANILIST_USER" == "CHANGE_ME" ]] && warn "ANILIST_USER left as a placeholder — set it later."
else
  ok "AniList user: ${BOLD}$CUR_USER${RESET}"
fi

# Backfill any missing settings with their defaults (idempotent).
ADDED=()
for row in "${CONF_SETTINGS[@]}"; do
  IFS='|' read -r key def cmt <<<"$row"
  if conf_has "$key"; then
    info "$key = ${BOLD}$(conf_get "$key")${RESET}"
  else
    printf '# %s\n' "$cmt" >>"$CONFIG_FILE"
    conf_set "$key" "$def"
    ADDED+=("$key=$def")
  fi
done
chmod 600 "$CONFIG_FILE" 2>/dev/null || true
if [[ ${#ADDED[@]} -gt 0 ]]; then
  ok "Added missing setting(s): ${BOLD}${ADDED[*]}${RESET}"
else
  ok "All settings present in ${DIM}$CONFIG_FILE${RESET}"
fi

# --------------------------------------------------------------------------- #
step "mDNS — reach the PC by name from your phone"
# --------------------------------------------------------------------------- #
if systemctl is-active --quiet avahi-daemon 2>/dev/null; then
  ok "avahi-daemon already running."
else
  if ask_yes "Enable avahi-daemon so the phone can use ${BOLD}$(get_hostname).local${RESET}?"; then
    sudo systemctl enable --now avahi-daemon
    ok "avahi-daemon enabled."
  else
    warn "Skipped — you'll have to use the PC's IP address from the phone."
  fi
fi
# nss-mdns wiring so *.local actually resolves locally too.
if ! grep -qE '^\s*hosts:.*mdns' /etc/nsswitch.conf 2>/dev/null; then
  if ask_yes "Add 'mdns_minimal' to /etc/nsswitch.conf for .local resolution? (a backup is made)"; then
    sudo cp /etc/nsswitch.conf "/etc/nsswitch.conf.shou.bak.$(date +%s)"
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
    printf '\n# Shou server (auto-added by install.sh)\n%s\n' "$EXEC_LINE" >>"$HYPR_TARGET"
    ok "Added autostart line."
  fi
else
  warn "No Hyprland config found to edit. Start Shou on login yourself with:"
  printf '       %s%s%s\n' "$DIM" "$DAEMON" "$RESET"
fi

# --------------------------------------------------------------------------- #
step "AniList write access — auto-mark episodes watched (optional)"
# --------------------------------------------------------------------------- #
if grep -q '^ANILIST_TOKEN=' "$CONFIG_FILE" 2>/dev/null; then
  ok "AniList token already configured."
else
  info "Lets Shou tick episodes off on AniList automatically once you finish them."
  info "Needs a one-time AniList API client + approval (write access)."
  if ask_yes "Set this up now?"; then
    "$REPO_DIR/shou_auth.sh" || warn "Auth didn't complete — run ./shou_auth.sh anytime."
  else
    warn "Skipped — run ${DIM}./shou_auth.sh${RESET} later to enable it."
  fi
fi

# --------------------------------------------------------------------------- #
step "Start the server now?"
# --------------------------------------------------------------------------- #
if curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null; then
  ok "Server already answering on :4100."
elif ask_yes "Launch the Shou daemon now?"; then
  setsid nohup "$DAEMON" >/dev/null 2>&1 &
  for _ in $(seq 1 20); do
    curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null && break
    sleep 0.5
  done
  curl -s -o /dev/null "http://127.0.0.1:4100/" 2>/dev/null \
    && ok "Server is up." \
    || warn "Server didn't answer yet — check ~/.config/shou/shou.log"
fi

# --------------------------------------------------------------------------- #
#  Done — next steps
# --------------------------------------------------------------------------- #
TOKEN="$(grep -E '^REMOTE_TOKEN=' "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\042\047' || true)"
HOST="$(get_hostname)"
printf '\n%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s  Shou is installed. 🎌%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n\n' "$BOLD" "$GREEN" "$RESET"

printf '%sPhone web-remote:%s\n' "$BOLD" "$RESET"
if [[ -n "$TOKEN" ]]; then
  printf '   %shttp://%s.local:4100/remote?k=%s%s\n' "$CYAN" "$HOST" "$TOKEN" "$RESET"
  info "Open it on your phone, then 'Add to Home screen' for a one-tap icon."
else
  info "Start the server once; it generates a token and prints the full /remote URL"
  info "to ${DIM}~/.config/shou/shou.log${RESET}. Then add that URL to your phone's home screen."
fi

printf '\n%sTip:%s if %s.local%s doesn'\''t resolve on the phone, use the PC'\''s LAN IP instead.\n' \
  "$BOLD" "$RESET" "$DIM" "$RESET"
if [[ "${ANILIST_USER:-}" == "CHANGE_ME" ]]; then
  printf '\n%s⚠ Remember to set ANILIST_USER in %s%s\n' "$YELLOW" "$CONFIG_FILE" "$RESET"
fi
printf '\n'
