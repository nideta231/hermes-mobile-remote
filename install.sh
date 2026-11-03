#!/usr/bin/env bash
# Install the Hermes Mobile Remote bridge on this PC (Linux, systemd user service).
#
#   ./install.sh              install or update, enable the service, offer firewall + pairing
#   ./install.sh --uninstall  stop and remove the service (keeps paired devices and config)
#
# Safe to re-run. Never needs sudo except an optional firewall change, which asks first.
set -euo pipefail

REPO="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
BRIDGE="$REPO/bridge"
UNIT_NAME="hermes-remote-bridge.service"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/hermes-remote"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
BIN="$BRIDGE/.venv/bin/hermes-remote-bridge"
TRAY="$BRIDGE/linux/tray.py"
APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
LOCAL_BIN="$HOME/.local/bin"
DESKTOP_FILE="hermes-remote-tray.desktop"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die()  { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
ask()  { local a; read -r -p "  $1 [Y/n] " a </dev/tty || a=n; [[ -z "$a" || "$a" =~ ^[Yy] ]]; }

# The first Python that can import PyQt6. The system one comes first: `python3` on PATH is often
# a tool-bundled or virtualenv Python without it, and the tray must not depend on PATH order.
qt_python() {
  local py
  for py in /usr/bin/python3 /usr/local/bin/python3 "$(command -v python3 || true)"; do
    [[ -x "$py" ]] && "$py" -c 'import PyQt6.QtWidgets' 2>/dev/null && { echo "$py"; return 0; }
  done
  return 1
}

# Only replace a command in ~/.local/bin that this installer made (a symlink into this repo).
link_command() {
  local target="$1" name="$2"
  if [[ -L "$LOCAL_BIN/$name" && "$(readlink "$LOCAL_BIN/$name")" != "$REPO"/* ]] ||
     [[ -e "$LOCAL_BIN/$name" && ! -L "$LOCAL_BIN/$name" ]]; then
    info "Left $LOCAL_BIN/$name alone (not ours)."; return
  fi
  ln -sfn "$target" "$LOCAL_BIN/$name"
}

if [[ "${1:-}" == "--uninstall" ]]; then
  systemctl --user disable --now "$UNIT_NAME" 2>/dev/null || true
  rm -f "$UNIT_DIR/$UNIT_NAME" "$APPS_DIR/$DESKTOP_FILE" "$AUTOSTART_DIR/$DESKTOP_FILE" "$ICON_DIR/hermes-remote.svg"
  for name in hermes-remote-bridge hermes-remote-tray; do
    [[ -L "$LOCAL_BIN/$name" && "$(readlink "$LOCAL_BIN/$name")" == "$REPO"/* ]] && rm -f "$LOCAL_BIN/$name"
  done
  pkill -f "$REPO/bridge/linux/tray.py" 2>/dev/null || true
  systemctl --user daemon-reload
  echo "Service removed. Paired devices and settings are still in $CONF_DIR (delete it to forget them)."
  exit 0
fi

bold "1/5 Checking requirements"
[[ "$(uname -s)" == "Linux" ]] || die "The bridge supports Linux only."
command -v systemctl >/dev/null || die "systemd is required (systemctl not found)."
command -v uv >/dev/null || die "uv is required: https://docs.astral.sh/uv/getting-started/installation/"
[[ -d "$HERMES_HOME" ]] || die "Hermes Agent not found at $HERMES_HOME. Install Hermes first: https://hermes-agent.nousresearch.com"
command -v avahi-publish-service >/dev/null || info "Optional: install avahi (avahi-utils) so the app finds the PC when its IP changes."
command -v tailscale >/dev/null || info "Optional: Tailscale is not installed; the phone will only connect on trusted Wi-Fi."
info "ok"

bold "2/5 Hermes"
info "The phone shares the Hermes the desktop app runs (or the bridge starts one); nothing to enable."

bold "3/5 Installing the bridge"
(cd "$BRIDGE" && uv sync --quiet --no-dev --inexact)
mkdir -p "$CONF_DIR"; chmod 700 "$CONF_DIR"
[[ -f "$CONF_DIR/config.toml" ]] || printf '# See bridge/hermes_remote_bridge/config.py for all keys.\nlan = true\n' > "$CONF_DIR/config.toml"
mkdir -p "$LOCAL_BIN"
link_command "$BIN" hermes-remote-bridge
# The notes skill, so Hermes can read and edit the app's Notes. Never overwrite one the user changed.
SKILL_DST="$HERMES_HOME/skills/productivity/personal-notes"
if [[ ! -e "$SKILL_DST/SKILL.md" ]]; then
  mkdir -p "$SKILL_DST" && cp "$BRIDGE/skills/personal-notes/SKILL.md" "$SKILL_DST/SKILL.md" && info "Hermes skill installed: personal-notes"
fi
# The blueprint skill: Hermes reads a project's diagram + board before working on it and keeps it current.
SKILL_DST="$HERMES_HOME/skills/software-development/project-blueprint"
if [[ ! -e "$SKILL_DST/SKILL.md" ]]; then
  mkdir -p "$SKILL_DST" && cp "$BRIDGE/skills/project-blueprint/SKILL.md" "$SKILL_DST/SKILL.md" && info "Hermes skill installed: project-blueprint"
fi
info "installed into $BRIDGE/.venv; command: hermes-remote-bridge"
[[ ":$PATH:" == *":$LOCAL_BIN:"* ]] || info "Add $LOCAL_BIN to your PATH to use it without the full path."

bold "4/5 Background service"
mkdir -p "$UNIT_DIR"
mkdir -p "${XDG_STATE_HOME:-$HOME/.local/state}/hermes-remote"
rm -f "$UNIT_DIR/$UNIT_NAME"  # may be an old symlink into the repo; never write through it
sed "s|@BIN@|$BIN|; s|@REPO@|$REPO|" "$BRIDGE/systemd/$UNIT_NAME" > "$UNIT_DIR/$UNIT_NAME"
systemctl --user daemon-reload
systemctl --user enable --now "$UNIT_NAME" >/dev/null
systemctl --user restart "$UNIT_NAME"
if [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]]; then
  if ask "Keep the bridge running after you log out / start it at boot (loginctl enable-linger)?"; then
    loginctl enable-linger "$USER" || info "Couldn't enable linger; the bridge will run while you're logged in."
  fi
fi
info "running (journalctl --user -u hermes-remote-bridge -f for logs)"

bold "5/5 Network"
if ask "Is this your home or office network (let the phone connect over this Wi-Fi without Tailscale)?"; then
  "$BIN" trust || true
fi
"$BIN" firewall --if-needed || true

bold "Tray app"
if [[ -n "${WAYLAND_DISPLAY:-}${DISPLAY:-}" ]]; then
  if PY="$(qt_python)"; then
    mkdir -p "$APPS_DIR" "$ICON_DIR"
    install -m 644 "$BRIDGE/linux/hermes-remote.svg" "$ICON_DIR/hermes-remote.svg"
    "$PY" "$TRAY" --desktop-entry > "$APPS_DIR/$DESKTOP_FILE"
    printf '#!/bin/sh\nexec "%s" "%s" "$@"\n' "$PY" "$TRAY" > "$BRIDGE/linux/hermes-remote-tray"
    chmod 755 "$BRIDGE/linux/hermes-remote-tray"
    link_command "$BRIDGE/linux/hermes-remote-tray" hermes-remote-tray
    command -v update-desktop-database >/dev/null && update-desktop-database -q "$APPS_DIR" 2>/dev/null || true
    command -v kbuildsycoca6 >/dev/null && kbuildsycoca6 >/dev/null 2>&1 || true  # KDE menu cache
    info "added 'Hermes Remote' to the app menu (command: hermes-remote-tray, using $PY)"
    # Start at login unless the user said no before; the tray menu toggles it later.
    if [[ -f "$AUTOSTART_DIR/$DESKTOP_FILE" ]] || ask "Start the Hermes Remote tray automatically at login?"; then
      mkdir -p "$AUTOSTART_DIR"
      cp "$APPS_DIR/$DESKTOP_FILE" "$AUTOSTART_DIR/$DESKTOP_FILE"
      info "starts at login ($AUTOSTART_DIR/$DESKTOP_FILE)"
    fi
    # Replace a running tray so it picks up this version (a second tray would exit by itself).
    pkill -f "$TRAY" 2>/dev/null && sleep 1 || true
    (setsid "$PY" "$TRAY" >/dev/null 2>&1 < /dev/null &)
  else
    info "The tray needs PyQt6 for the system Python (e.g. sudo pacman -S python-pyqt6 /"
    info "sudo apt install python3-pyqt6 / sudo dnf install python3-pyqt6), then re-run ./install.sh."
  fi
else
  info "No desktop session here; re-run ./install.sh from your desktop to add the tray."
fi

echo
"$BIN" doctor || true
echo
bold "Next: pair your phone"
info "Install the app (APK from the GitHub releases page), then run:"
info "  hermes-remote-bridge pair phone"
info "and scan the QR code with the app (or tray icon -> Devices -> Pair a new device...)."
