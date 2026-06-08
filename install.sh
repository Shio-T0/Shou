#!/usr/bin/env bash
#
#   ███████╗██╗  ██╗ ██████╗ ██╗   ██╗
#   ██╔════╝██║  ██║██╔═══██╗██║   ██║
#   ███████╗███████║██║   ██║██║   ██║
#   ╚════██║██╔══██║██║   ██║██║   ██║
#   ███████║██║  ██║╚██████╔╝╚██████╔╝
#   ╚══════╝╚═╝  ╚═╝ ╚═════╝  ╚═════╝
#
#  Phone-controlled AniList "Currently Watching" launcher — macOS installer.
#  Uses Homebrew for dependencies and a launchd LaunchAgent for login autostart.
#  Idempotent: safe to re-run. It only touches your system with your confirmation.
#
#  (Linux users: see the `main` branch. Windows users: the `windows` branch.)
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
   │   朱   S H O U   ·   I N S T A L L E R  (mac)  │
   │   your anime, controlled from your phone       │
   └──────────────────────────────────────────────┘
ART
  printf '%s' "$RESET"
}

# --------------------------------------------------------------------------- #
#  Paths
# --------------------------------------------------------------------------- #
# Portable absolute dir of this script — BSD/macOS `readlink` has no -f, so we
# follow symlinks by hand.
self_dir() {
  local src="${BASH_SOURCE[0]:-$0}" dir
  while [ -h "$src" ]; do
    dir="$(cd -P "$(dirname "$src")" >/dev/null 2>&1 && pwd)"
    src="$(readlink "$src")"; case "$src" in /*) ;; *) src="$dir/$src";; esac
  done
  cd -P "$(dirname "$src")" >/dev/null 2>&1 && pwd
}
REPO_DIR="$(self_dir)"
APP_DIR="$REPO_DIR/shou"
CONFIG_DIR="$HOME/.config/shou"
CONFIG_FILE="$CONFIG_DIR/shou.conf"
DAEMON="$REPO_DIR/shou_daemon.sh"
LAUNCH_AGENT="$HOME/Library/LaunchAgents/com.shou.daemon.plist"

# --------------------------------------------------------------------------- #
#  Homebrew helpers
# --------------------------------------------------------------------------- #
brew_bin() {  # echo the brew path if present (handles Apple Silicon + Intel)
  if command -v brew >/dev/null 2>&1; then command -v brew; return; fi
  for p in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    [[ -x "$p" ]] && { echo "$p"; return; }
  done
  return 1
}

banner

# --------------------------------------------------------------------------- #
step "Pre-flight checks"
# --------------------------------------------------------------------------- #
[[ "$(uname -s)" == "Darwin" ]] || die "This installer is for macOS. On Linux use the 'main' branch; on Windows the 'windows' branch."
[[ $EUID -eq 0 ]] && die "Run this as your normal user, not with sudo (Homebrew refuses root anyway)."
[[ -f "$APP_DIR/server.py" ]] || die "Can't find shou/server.py next to this script. Run it from inside the repo."

MACOS_VER="$(sw_vers -productVersion 2>/dev/null || echo '?')"
ARCH="$(uname -m)"
ok "macOS ${BOLD}$MACOS_VER${RESET} (${BOLD}$ARCH${RESET})"
ok "Repo at ${DIM}$REPO_DIR${RESET}"

# Homebrew — the macOS package manager. Everything else flows from it.
if BREW="$(brew_bin)"; then
  eval "$("$BREW" shellenv)"   # make brew + its installed bins available this session
  ok "Homebrew found (${DIM}$BREW${RESET})."
else
  warn "Homebrew is not installed. It's the standard way to get mpv/uv/a browser on macOS."
  if ask_yes "Install Homebrew now (runs the official brew.sh installer)?"; then
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" \
      || die "Homebrew install failed — see https://brew.sh and re-run."
    BREW="$(brew_bin)" || die "Homebrew still not found after install — open a new terminal and re-run."
    eval "$("$BREW" shellenv)"
    ok "Homebrew installed."
  else
    die "Homebrew is required for the macOS installer. Install it from https://brew.sh, then re-run."
  fi
fi

# --------------------------------------------------------------------------- #
step "Installing dependencies with Homebrew"
# --------------------------------------------------------------------------- #
brew_install() {  # brew_install <formula>  (idempotent; --quiet)
  if brew list --formula "$1" >/dev/null 2>&1; then
    ok "$1 already installed."
  elif ask_yes "Install $1 with Homebrew?"; then
    brew install "$1" && ok "Installed $1." || warn "Failed to install $1 — install it manually and re-run."
  else
    warn "Skipped $1 — make sure it's installed."
  fi
}

# mpv (player) + uv (Python runtime/project manager). curl ships with macOS.
command -v mpv >/dev/null 2>&1 && ok "mpv already installed." || brew_install mpv
command -v uv  >/dev/null 2>&1 && ok "uv already installed."  || brew_install uv

# ani-cli — optional extra source; the bundled anipy scrapers work without it.
if command -v ani-cli >/dev/null 2>&1; then
  ok "ani-cli already installed."
else
  info "ani-cli is an optional extra source (Homebrew formula). Without it, Shou's"
  info "built-in anipy scrapers are used — so this is safe to skip."
  ask_yes "Install ani-cli with Homebrew?" && { brew install ani-cli || warn "ani-cli install failed — anipy scrapers will be used."; }
fi

# Browser for the kiosk — the server probes the usual .app bundles. Detect any.
MAC_BROWSERS=(
  "/Applications/Firefox.app"
  "/Applications/Google Chrome.app"
  "/Applications/Brave Browser.app"
  "/Applications/Chromium.app"
  "/Applications/Microsoft Edge.app"
  "/Applications/Vivaldi.app"
)
have_browser=0
for b in "${MAC_BROWSERS[@]}"; do [[ -d "$b" ]] && { have_browser=1; break; }; done
if [[ "$have_browser" -eq 1 ]]; then
  ok "A kiosk-capable browser is installed."
else
  warn "No Firefox/Chrome/Brave/Chromium/Edge/Vivaldi found in /Applications."
  warn "Safari can't do a clean --kiosk, so install one of the above for the big-screen kiosk:"
  if ask_yes "Install Firefox with Homebrew (brew install --cask firefox)?"; then
    brew install --cask firefox && ok "Installed Firefox." \
      || warn "Couldn't install Firefox — install any of the listed browsers manually."
  else
    warn "Skipped — install a Chromium-family or Firefox browser so the kiosk can open."
  fi
fi

info "Desktop notifications use macOS's built-in osascript — nothing to install."

# --------------------------------------------------------------------------- #
step "Installing the Python environment (uv)"
# --------------------------------------------------------------------------- #
command -v uv >/dev/null 2>&1 || die "uv is required but missing. Install with 'brew install uv' and re-run."
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
step "mDNS — reach the PC by name from your phone"
# --------------------------------------------------------------------------- #
HOST_LOCAL="$(scutil --get LocalHostName 2>/dev/null || hostname -s 2>/dev/null || echo shou)"
ok "macOS Bonjour is built in — your phone can reach ${BOLD}${HOST_LOCAL}.local${RESET} with no setup."
info "If .local doesn't resolve on the phone, use the Mac's LAN IP instead (always works)."

# --------------------------------------------------------------------------- #
step "Autostart on login (launchd LaunchAgent)"
# --------------------------------------------------------------------------- #
if [[ -f "$LAUNCH_AGENT" ]]; then
  ok "LaunchAgent already present at ${DIM}$LAUNCH_AGENT${RESET}"
  if ask_yes "Refresh it (in case the repo moved)?"; then write_agent=1; else write_agent=0; fi
elif ask_yes "Add a login LaunchAgent so Shou starts when you log in?"; then
  write_agent=1
else
  write_agent=0
  warn "Skipped — start Shou manually with ${DIM}./shou_daemon.sh${RESET} when you want it."
fi

if [[ "${write_agent:-0}" -eq 1 ]]; then
  mkdir -p "$HOME/Library/LaunchAgents"
  cat >"$LAUNCH_AGENT" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.shou.daemon</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$DAEMON</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$CONFIG_DIR/launchd.log</string>
  <key>StandardErrorPath</key><string>$CONFIG_DIR/launchd.log</string>
</dict>
</plist>
EOF
  # Reload: unload an old copy first so launchctl picks up changes.
  launchctl unload "$LAUNCH_AGENT" 2>/dev/null || true
  if launchctl load -w "$LAUNCH_AGENT" 2>/dev/null; then
    ok "LaunchAgent installed and loaded (${DIM}$LAUNCH_AGENT${RESET})."
  else
    warn "Wrote the LaunchAgent but couldn't load it now — it'll take effect next login,"
    warn "or run: ${DIM}launchctl load -w $LAUNCH_AGENT${RESET}"
  fi
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
  nohup "$DAEMON" >/dev/null 2>&1 &
  for _ in $(seq 1 20); do
    curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/" 2>/dev/null && break
    sleep 0.5
  done
  curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/" 2>/dev/null \
    && ok "Server is up." \
    || warn "Server didn't answer yet — check $CONFIG_DIR/shou.log"
fi

# --------------------------------------------------------------------------- #
#  Done — next steps
# --------------------------------------------------------------------------- #
TOKEN="$(grep -E '^REMOTE_TOKEN=' "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\042\047' || true)"
printf '\n%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s  Shou is installed. 🎌%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n\n' "$BOLD" "$GREEN" "$RESET"

printf '%sPhone web-remote:%s\n' "$BOLD" "$RESET"
if [[ -n "$TOKEN" ]]; then
  printf '   %shttp://%s.local:%s/remote?k=%s%s\n' "$CYAN" "$HOST_LOCAL" "$PORT_NUM" "$TOKEN" "$RESET"
  info "Open it on your phone, then 'Add to Home screen' for a one-tap icon."
else
  info "Start the server once; it generates a token and prints the full /remote URL"
  info "to ${DIM}$CONFIG_DIR/shou.log${RESET}. Then add that URL to your phone's home screen."
fi

printf '\n%sTip:%s if %s.local%s doesn'\''t resolve on the phone, use the Mac'\''s LAN IP instead.\n' \
  "$BOLD" "$RESET" "$DIM" "$RESET"
if [[ "${ANILIST_USER:-}" == "CHANGE_ME" ]]; then
  printf '\n%s⚠ Remember to set ANILIST_USER in %s%s\n' "$YELLOW" "$CONFIG_FILE" "$RESET"
fi
printf '\n'
