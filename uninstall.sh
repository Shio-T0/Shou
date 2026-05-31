#!/usr/bin/env bash
#
#  Shou uninstaller — undoes what install.sh set up.
#
#  Conservative by design: it stops the server, removes the Hyprland autostart
#  line, and (only if you ask) deletes your config. It does NOT remove system
#  packages (uv/firefox/mpv/ani-cli/avahi…) — those may be used by other things —
#  and it does NOT touch the repo itself. Re-runnable.
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
die()   { printf '\n%s✗ %s%s\n' "$RED" "$1" "$RESET" >&2; exit 1; }

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
REPO_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DIR="$REPO_DIR/shou"
CONFIG_DIR="$HOME/.config/shou"
DAEMON="$REPO_DIR/shou_daemon.sh"
PORT=4100
[[ -f "$CONFIG_DIR/shou.conf" ]] && PORT="$(grep -E '^PORT=' "$CONFIG_DIR/shou.conf" | head -1 | cut -d= -f2- | tr -d '\042\047' || echo 4100)"
PORT="${PORT:-4100}"

printf '%s%s\n   🎌  Shou — uninstaller%s\n' "$BOLD" "$MAGENTA" "$RESET"

# --------------------------------------------------------------------------- #
step "Stopping the running server"
# --------------------------------------------------------------------------- #
PIDS="$(ss -ltnp 2>/dev/null | grep ":$PORT" | grep -oP 'pid=\K[0-9]+' | sort -u || true)"
# Also catch the daemon wrapper + uv runner by command line.
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
step "Removing Hyprland autostart"
# --------------------------------------------------------------------------- #
REMOVED=0
for f in "$HOME/.config/hypr/hyprland/execs.conf" "$HOME/.config/hypr/hyprland.conf"; do
  [[ -f "$f" ]] || continue
  if grep -qF "$DAEMON" "$f"; then
    cp "$f" "$f.shou.bak.$(date +%s)"
    # Drop our comment line and the exec-once line referencing the daemon.
    grep -vF "$DAEMON" "$f" | grep -vF '# Shou server (auto-added by install.sh)' >"$f.tmp" && mv "$f.tmp" "$f"
    ok "Removed autostart from ${DIM}$f${RESET} (backup saved)."
    REMOVED=1
  fi
done
[[ "$REMOVED" -eq 0 ]] && ok "No autostart line found."

# --------------------------------------------------------------------------- #
step "Configuration & state"
# --------------------------------------------------------------------------- #
if [[ -d "$CONFIG_DIR" ]]; then
  warn "This holds your AniList username, the remote token, logs and the Firefox kiosk profile:"
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
info "  • system packages — ${DIM}sudo pacman -Rns ani-cli librsvg${RESET} (avahi/mpv/firefox likely used elsewhere)"
info "  • nss-mdns line in /etc/nsswitch.conf (a .bak was made by install.sh)"
info "  • your KDE Connect Run-Command buttons (remove them in the app)"
info "  • this repo folder"
printf '\n'
