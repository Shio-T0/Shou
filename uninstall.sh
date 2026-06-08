#!/usr/bin/env bash
#
#  Shou uninstaller (macOS) — undoes what install.sh set up.
#
#  Conservative by design: it stops the server, unloads + removes the launchd
#  LaunchAgent, and (only if you ask) deletes your config. It does NOT remove
#  Homebrew packages (uv/mpv/ani-cli/your browser) — those may be used by other
#  things — and it does NOT touch the repo itself. Re-runnable.
#
set -euo pipefail

# --------------------------------------------------------------------------- #
#  Pretty output (mirrors install.sh)
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

ask_yes() {  # default YES
  local reply
  printf '   %s?%s %s %s[Y/n]%s ' "$MAGENTA" "$RESET" "$1" "$DIM" "$RESET"
  read -r reply || true
  [[ -z "$reply" || "$reply" =~ ^[Yy] ]]
}
ask_no() {   # default NO
  local reply
  printf '   %s?%s %s %s[y/N]%s ' "$MAGENTA" "$RESET" "$1" "$DIM" "$RESET"
  read -r reply || true
  [[ "$reply" =~ ^[Yy] ]]
}

# --------------------------------------------------------------------------- #
#  Paths
# --------------------------------------------------------------------------- #
# Portable absolute dir of this script (BSD/macOS readlink has no -f).
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
LAUNCH_AGENT="$HOME/Library/LaunchAgents/com.shou.daemon.plist"
PORT=4100
[[ -f "$CONFIG_DIR/shou.conf" ]] && PORT="$(grep -E '^PORT=' "$CONFIG_DIR/shou.conf" | head -1 | cut -d= -f2- | tr -d '\042\047' || echo 4100)"
PORT="${PORT:-4100}"

printf '%s%s\n   🎌  Shou — uninstaller (macOS)%s\n' "$BOLD" "$MAGENTA" "$RESET"

# --------------------------------------------------------------------------- #
step "Removing the launchd LaunchAgent"
# --------------------------------------------------------------------------- #
if [[ -f "$LAUNCH_AGENT" ]]; then
  launchctl unload "$LAUNCH_AGENT" 2>/dev/null || true
  rm -f "$LAUNCH_AGENT"
  ok "Removed + unloaded ${DIM}$LAUNCH_AGENT${RESET}"
else
  ok "No LaunchAgent present."
fi

# --------------------------------------------------------------------------- #
step "Stopping the running server"
# --------------------------------------------------------------------------- #
# macOS: find listeners on the port with lsof, plus the daemon/server by cmdline.
PIDS="$(lsof -ti "tcp:$PORT" 2>/dev/null || true)"
PIDS="$PIDS $(pgrep -f 'shou_daemon.sh' || true) $(pgrep -f 'shou/server.py' || true)"
PIDS="$(echo "$PIDS" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u | tr '\n' ' ' || true)"
if [[ -n "${PIDS// /}" ]]; then
  info "Found processes: $PIDS"
  # shellcheck disable=SC2086
  kill $PIDS 2>/dev/null || true
  sleep 1
  # shellcheck disable=SC2086
  kill -9 $PIDS 2>/dev/null || true
  ok "Server stopped."
else
  ok "Nothing running on :$PORT."
fi

# --------------------------------------------------------------------------- #
step "Configuration & state"
# --------------------------------------------------------------------------- #
if [[ -d "$CONFIG_DIR" ]]; then
  warn "This holds your AniList username, the remote token, logs and the browser kiosk profile:"
  info "${DIM}$CONFIG_DIR${RESET}"
  if ask_no "Delete $CONFIG_DIR entirely?"; then
    rm -rf "$CONFIG_DIR"
    ok "Config removed."
  else
    ok "Kept your config."
  fi
else
  ok "No config directory present."
fi

# --------------------------------------------------------------------------- #
step "Python virtualenv"
# --------------------------------------------------------------------------- #
if [[ -d "$APP_DIR/.venv" ]]; then
  if ask_yes "Remove the uv virtualenv at ${DIM}$APP_DIR/.venv${RESET}? (re-creatable with 'uv sync')"; then
    rm -rf "$APP_DIR/.venv"
    ok "Virtualenv removed."
  else
    ok "Kept the virtualenv."
  fi
else
  ok "No virtualenv present."
fi

# --------------------------------------------------------------------------- #
#  Done
# --------------------------------------------------------------------------- #
printf '\n%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s  Shou uninstalled.%s\n' "$BOLD" "$GREEN" "$RESET"
printf '%s%s━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%s\n\n' "$BOLD" "$GREEN" "$RESET"
info "Left untouched (remove manually if you want):"
info "  • Homebrew packages — ${DIM}mpv, uv, ani-cli, your browser${RESET} (likely used elsewhere)"
info "  • this repo folder"
printf '\n'
