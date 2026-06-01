#!/usr/bin/env bash
#
#   ███████╗██╗  ██╗ ██████╗ ██╗   ██╗
#   ██╔════╝██║  ██║██╔═══██╗██║   ██║
#   ███████╗███████║██║   ██║██║   ██║
#   ╚════██║██╔══██║██║   ██║██║   ██║
#   ███████║██║  ██║╚██████╔╝╚██████╔╝
#   ╚══════╝╚═╝  ╚═╝ ╚═════╝  ╚═════╝
#
#  Phone-controlled AniList "Currently Watching" launcher — Linux installer.
#  Works on most distros (Arch, Debian/Ubuntu, Fedora, openSUSE, Void, Alpine, …).
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

# Portable hostname — `hostname` (inetutils) isn't installed everywhere.
get_hostname() {
  local h="${HOSTNAME:-}"
  [[ -z "$h" && -r /etc/hostname ]] && h="$(< /etc/hostname)"
  [[ -z "$h" && -r /proc/sys/kernel/hostname ]] && h="$(< /proc/sys/kernel/hostname)"
  [[ -z "$h" ]] && h="$(uname -n 2>/dev/null || echo localhost)"
  printf '%s' "$h"
}

partial_upgrade_hint() {
  printf '\n%s%s   pacman couldn'\''t install a dependency.%s\n' "$BOLD" "$YELLOW" "$RESET"
  printf '   If you saw %s"breaks dependency"%s, your system is in a %spartial-upgrade%s\n' \
    "$BOLD" "$RESET" "$BOLD" "$RESET"
  printf '   state. Fix it with a FULL upgrade, then re-run this installer:\n\n'
  printf '       %ssudo pacman -Syu%s\n' "$BOLD$CYAN" "$RESET"
  printf '       %s./install.sh%s\n\n' "$BOLD$CYAN" "$RESET"
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

# --------------------------------------------------------------------------- #
#  Package-manager abstraction (works across distros)
# --------------------------------------------------------------------------- #
PM=""
detect_pm() {
  if   command -v pacman        >/dev/null 2>&1; then PM=pacman
  elif command -v apt-get       >/dev/null 2>&1; then PM=apt
  elif command -v dnf           >/dev/null 2>&1; then PM=dnf
  elif command -v zypper        >/dev/null 2>&1; then PM=zypper
  elif command -v xbps-install  >/dev/null 2>&1; then PM=xbps
  elif command -v apk           >/dev/null 2>&1; then PM=apk
  else PM=""; fi
}

pm_refresh() {  # refresh package metadata where it's needed
  case "$PM" in
    apt)    sudo apt-get update ;;
    zypper) sudo zypper --non-interactive refresh ;;
    *) : ;;
  esac
}

pm_install() {  # pm_install pkg...   (returns non-zero on failure)
  case "$PM" in
    pacman) sudo pacman -S --needed "$@" ;;
    apt)    sudo apt-get install -y "$@" ;;
    dnf)    sudo dnf install -y "$@" ;;
    zypper) sudo zypper --non-interactive install "$@" ;;
    xbps)   sudo xbps-install -Sy "$@" ;;
    apk)    sudo apk add "$@" ;;
    *) return 1 ;;
  esac
}

pkg_name() {  # pkg_name <logical>  -> the package name on the detected distro
  case "$1:$PM" in
    firefox:apt)       echo firefox-esr ;;
    firefox:zypper)    echo MozillaFirefox ;;
    firefox:*)         echo firefox ;;
    libnotify:apt)     echo libnotify-bin ;;
    libnotify:zypper)  echo libnotify-tools ;;
    libnotify:apk)     echo libnotify ;;
    libnotify:*)       echo libnotify ;;
    avahi:apt)         echo avahi-daemon ;;
    avahi:*)           echo avahi ;;
    nss-mdns:apt)      echo libnss-mdns ;;
    nss-mdns:*)        echo nss-mdns ;;
    *)                 echo "$1" ;;
  esac
}

BROWSER_BINS=(firefox firefox-esr librewolf waterfox chromium chromium-browser \
              google-chrome-stable google-chrome brave brave-browser vivaldi-stable vivaldi)
have_browser() {
  local b
  for b in "${BROWSER_BINS[@]}"; do command -v "$b" >/dev/null 2>&1 && return 0; done
  return 1
}

banner

# --------------------------------------------------------------------------- #
step "Pre-flight checks"
# --------------------------------------------------------------------------- #
[[ $EUID -eq 0 ]] && die "Run this as your normal user, not root (it uses sudo only where needed)."
[[ -f "$APP_DIR/server.py" ]] || die "Can't find shou/server.py next to this script. Run it from inside the repo."

DISTRO="Linux"
[[ -r /etc/os-release ]] && DISTRO="$( . /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-${NAME:-Linux}}" )"
detect_pm
if [[ -n "$PM" ]]; then
  ok "Detected ${BOLD}$DISTRO${RESET} (package manager: ${BOLD}$PM${RESET})"
else
  warn "No supported package manager found. Shou still installs, but you'll have to"
  warn "install system dependencies (mpv, a browser, curl, uv) yourself."
fi
ok "Repo at ${DIM}$REPO_DIR${RESET}"

# Window auto-focus is a Hyprland/Sway nicety; everything else is compositor-agnostic.
if command -v hyprctl >/dev/null 2>&1 || command -v swaymsg >/dev/null 2>&1; then
  ok "wlroots compositor tools found — kiosk auto-focus/fullscreen will work."
else
  info "No hyprctl/swaymsg — the kiosk still opens fullscreen via the browser's own"
  info "--kiosk; only the 'raise to front' nicety is skipped. Everything else works."
fi

# --------------------------------------------------------------------------- #
step "Installing system dependencies"
# --------------------------------------------------------------------------- #
if [[ -n "$PM" ]]; then pm_refresh || warn "Package metadata refresh failed (continuing)."; fi

# Core tools detected by the binary they provide (distro-independent).
for pair in "mpv:mpv" "curl:curl"; do
  bin="${pair%%:*}"; logical="${pair##*:}"
  if command -v "$bin" >/dev/null 2>&1; then
    ok "$logical already installed."
  elif [[ -z "$PM" ]]; then
    warn "$logical missing — install it with your package manager."
  elif ask_yes "Install $(pkg_name "$logical") with $PM?"; then
    if ! pm_install "$(pkg_name "$logical")"; then
      [[ "$PM" == pacman ]] && partial_upgrade_hint
      warn "Failed to install $logical — install it manually and re-run."
    fi
  else
    warn "Skipped $logical — make sure it's installed."
  fi
done

# Browser for the kiosk.
if have_browser; then
  ok "A kiosk-capable browser is already installed."
elif [[ -z "$PM" ]]; then
  warn "No browser found — install Firefox, Chromium, or Brave for the kiosk display."
elif ask_yes "No browser found. Install Firefox for the kiosk?"; then
  case "$PM" in
    apt)    cands=(firefox-esr firefox) ;;
    zypper) cands=(MozillaFirefox firefox) ;;
    *)      cands=(firefox) ;;
  esac
  installed=0
  for c in "${cands[@]}"; do
    if pm_install "$c"; then ok "Installed $c."; installed=1; break; fi
  done
  [[ "$installed" -eq 1 ]] || warn "Couldn't install Firefox automatically — install any browser manually."
else
  warn "Skipped — install firefox/chromium/brave so the kiosk can open."
fi

# uv — distro package where available, else the official installer (all distros).
if command -v uv >/dev/null 2>&1; then
  ok "uv already installed."
else
  info "uv (Python project/runtime manager) is required."
  case "$PM" in
    pacman|dnf|apk)
      ask_yes "Install uv with $PM?" && { pm_install uv || true; } ;;
  esac
  if ! command -v uv >/dev/null 2>&1; then
    if ask_yes "Install uv via the official installer (curl -LsSf https://astral.sh/uv/install.sh | sh)?"; then
      curl -LsSf https://astral.sh/uv/install.sh | sh || warn "uv installer failed."
      export PATH="$HOME/.local/bin:$PATH"
    fi
  fi
  command -v uv >/dev/null 2>&1 \
    && ok "uv installed." \
    || die "uv is still missing. Install it from https://docs.astral.sh/uv/ and re-run."
fi

# ani-cli — OPTIONAL extra source. Without it, Shou uses the bundled anipy scrapers.
if command -v ani-cli >/dev/null 2>&1; then
  ok "ani-cli already installed."
else
  info "ani-cli is an optional extra source. Without it, Shou's built-in anipy scrapers"
  info "are used (works fine) — so this is safe to skip."
  if [[ "$PM" == "pacman" ]]; then
    helper=""
    for h in paru yay; do command -v "$h" >/dev/null 2>&1 && { helper="$h"; break; }; done
    if [[ -n "$helper" ]]; then
      ask_yes "Install ani-cli with $helper (AUR)?" && { "$helper" -S --needed ani-cli || true; }
    else
      warn "No AUR helper (paru/yay) — install ani-cli from the AUR yourself if you want it."
    fi
  else
    if ask_yes "Download the ani-cli script to /usr/local/bin? (optional, needs sudo)"; then
      if sudo curl -fsSL "https://raw.githubusercontent.com/pystardust/ani-cli/master/ani-cli" \
           -o /usr/local/bin/ani-cli; then
        sudo chmod +x /usr/local/bin/ani-cli; ok "ani-cli installed to /usr/local/bin."
      else
        warn "Download failed — Shou will use the anipy scrapers instead."
      fi
    fi
  fi
  command -v ani-cli >/dev/null 2>&1 || info "Continuing without ani-cli (anipy scrapers will be used)."
fi

# Desktop notifications (optional).
if command -v notify-send >/dev/null 2>&1; then
  ok "Desktop notifications available (notify-send)."
elif [[ -n "$PM" ]] && ask_yes "Install $(pkg_name libnotify) for desktop notifications? (optional)"; then
  pm_install "$(pkg_name libnotify)" || warn "Couldn't install — notifications will be skipped."
fi

# --------------------------------------------------------------------------- #
step "Installing the Python environment (uv)"
# --------------------------------------------------------------------------- #
command -v uv >/dev/null 2>&1 || die "uv is required but missing."
( cd "$APP_DIR" && uv sync )
ok "Python virtualenv synced from uv.lock."

# --------------------------------------------------------------------------- #
step "Making control scripts executable"
# --------------------------------------------------------------------------- #
chmod +x "$REPO_DIR"/shou_*.sh "$REPO_DIR"/install.sh "$REPO_DIR"/uninstall.sh 2>/dev/null || true
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

# Canonical user-editable settings: KEY | default | comment. Re-running install
# backfills any the config is missing WITHOUT changing values you've already set.
CONF_SETTINGS=(
  "PORT|4100|Port the server / phone remote listens on."
  "QUALITY|1080p|Playback quality passed to ani-cli when it's used (e.g. 1080p, 720p)."
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
step "mDNS — reach the PC by name from your phone (optional)"
# --------------------------------------------------------------------------- #
if command -v systemctl >/dev/null 2>&1; then
  if systemctl is-active --quiet avahi-daemon 2>/dev/null; then
    ok "avahi-daemon already running."
  elif ask_yes "Install + enable avahi so the phone can use ${BOLD}$(get_hostname).local${RESET}?"; then
    [[ -n "$PM" ]] && { pm_install "$(pkg_name avahi)" "$(pkg_name nss-mdns)" || warn "avahi/nss-mdns install failed."; }
    sudo systemctl enable --now avahi-daemon 2>/dev/null && ok "avahi-daemon enabled." \
      || warn "Couldn't enable avahi-daemon — you can use the PC's LAN IP instead."
  else
    warn "Skipped — you'll use the PC's IP address from the phone."
  fi
else
  warn "No systemd detected — enable avahi via your init system for <hostname>.local,"
  warn "or just use the PC's LAN IP from the phone (always works)."
fi
# nss-mdns wiring so *.local actually resolves locally too.
if [[ -f /etc/nsswitch.conf ]] && ! grep -qE '^\s*hosts:.*mdns' /etc/nsswitch.conf 2>/dev/null; then
  if ask_yes "Add 'mdns_minimal' to /etc/nsswitch.conf for .local resolution? (a backup is made)"; then
    sudo cp /etc/nsswitch.conf "/etc/nsswitch.conf.shou.bak.$(date +%s)"
    sudo sed -i -E 's/^(hosts:\s*)(.*)$/\1mdns_minimal [NOTFOUND=return] \2/' /etc/nsswitch.conf
    ok "nsswitch.conf updated (backup saved)."
  fi
elif [[ -f /etc/nsswitch.conf ]]; then
  ok "nss-mdns already configured in nsswitch.conf."
fi

# --------------------------------------------------------------------------- #
step "Autostart on login"
# --------------------------------------------------------------------------- #
EXEC_LINE="exec-once = $DAEMON"
HYPR_TARGET=""
for f in "$HOME/.config/hypr/hyprland/execs.conf" "$HOME/.config/hypr/hyprland.conf"; do
  [[ -f "$f" ]] && { HYPR_TARGET="$f"; break; }
done
if [[ -n "$HYPR_TARGET" ]]; then
  if grep -qF "$DAEMON" "$HYPR_TARGET"; then
    ok "Autostart already present in ${DIM}$HYPR_TARGET${RESET}"
  elif ask_yes "Add the daemon to ${DIM}$HYPR_TARGET${RESET} so it starts on login?"; then
    printf '\n# Shou server (auto-added by install.sh)\n%s\n' "$EXEC_LINE" >>"$HYPR_TARGET"
    ok "Added Hyprland autostart line."
  fi
else
  # XDG autostart — honored by GNOME, KDE, XFCE, Cinnamon, MATE, LXQt, …
  AUTOSTART="$HOME/.config/autostart/shou.desktop"
  if [[ -f "$AUTOSTART" ]]; then
    ok "Autostart entry already present in ${DIM}$AUTOSTART${RESET}"
  elif ask_yes "Add a login autostart entry (~/.config/autostart/shou.desktop)?"; then
    mkdir -p "$HOME/.config/autostart"
    cat >"$AUTOSTART" <<EOF
[Desktop Entry]
Type=Application
Name=Shou
Comment=Phone-controlled AniList launcher
Exec=$DAEMON
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
    ok "Created ${DIM}$AUTOSTART${RESET}"
  fi
  info "On Sway/other wlroots compositors, add this to your config instead: ${DIM}exec $DAEMON${RESET}"
fi

# --------------------------------------------------------------------------- #
step "AniList write access — auto-mark episodes watched (optional)"
# --------------------------------------------------------------------------- #
if grep -q '^ANILIST_TOKEN=' "$CONFIG_FILE" 2>/dev/null; then
  ok "AniList token already configured."
else
  info "Lets Shou tick episodes off on AniList automatically once you finish them."
  if ask_yes "Set this up now?"; then
    "$REPO_DIR/shou_auth.sh" || warn "Auth didn't complete — run ./shou_auth.sh anytime."
  else
    warn "Skipped — run ${DIM}./shou_auth.sh${RESET} later to enable it."
  fi
fi

# --------------------------------------------------------------------------- #
step "Start the server now?"
# --------------------------------------------------------------------------- #
PORT_NUM="$(conf_get PORT || true)"; PORT_NUM="${PORT_NUM:-4100}"
if curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/" 2>/dev/null; then
  ok "Server already answering on :${PORT_NUM}."
elif ask_yes "Launch the Shou daemon now?"; then
  setsid nohup "$DAEMON" >/dev/null 2>&1 &
  for _ in $(seq 1 20); do
    curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/" 2>/dev/null && break
    sleep 0.5
  done
  curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/" 2>/dev/null \
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
  printf '   %shttp://%s.local:%s/remote?k=%s%s\n' "$CYAN" "$HOST" "$PORT_NUM" "$TOKEN" "$RESET"
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
