#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "Usage: runtime-check.sh PATCHED_NOTIFICATION_PLUGIN OMARCHY_SHELL_ROOT" >&2
  exit 2
fi

ohm_runtime_plugin=$(realpath "$1")
ohm_runtime_shell=$(realpath "$2")
ohm_runtime_tool=$(dirname "$(realpath "$0")")
ohm_runtime_stage=$(mktemp -d -t ohm-flux-notification-runtime.XXXXXXXX)
trap 'rm -rf "$ohm_runtime_stage"' EXIT

cp "$ohm_runtime_tool/runtime-check.qml" "$ohm_runtime_stage/shell.qml"
ln -s "$ohm_runtime_shell/Commons" "$ohm_runtime_stage/Commons"
ln -s "$ohm_runtime_shell/Ui" "$ohm_runtime_stage/Ui"
mkdir -m 700 "$ohm_runtime_stage/run" "$ohm_runtime_stage/cache"

# This config constructs one card offscreen. It owns no notification server,
# D-Bus action handler, IPC target or input permission.
OHM_FLUX_NOTIFICATION_PLUGIN="$ohm_runtime_plugin" QT_QPA_PLATFORM=offscreen \
  DBUS_SESSION_BUS_ADDRESS=unix:path=/dev/null DBUS_SYSTEM_BUS_ADDRESS=unix:path=/dev/null \
  DISPLAY= WAYLAND_DISPLAY= HYPRLAND_INSTANCE_SIGNATURE= \
  XDG_RUNTIME_DIR="$ohm_runtime_stage/run" XDG_CACHE_HOME="$ohm_runtime_stage/cache" \
  timeout 10s quickshell -p "$ohm_runtime_stage"
