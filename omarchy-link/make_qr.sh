#!/usr/bin/env bash
# Generate the Omarchy<->OhmLauncher link QR as a PNG.
#
# Usage: make_qr.sh [ip] [port] [id]
#   ip   : this PC's LAN IP (default: auto-detected)
#   port : OhmLauncher phone listener port (default 8753)
#   id   : peer id shown to the phone (default: omarchy-pc)
#
# Output: /tmp/omarchy-link-qr.png  (overwritten)
#
# The QR encodes: omarchy://<ip>:<port>?id=<id>
# The phone scans it with the system camera; Android routes the omarchy://
# intent to OhmLauncher, which connects back to this PC.
set -euo pipefail

IP="${1:-}"
PORT="${2:-8753}"
ID="${3:-omarchy-pc}"
OUT="/tmp/omarchy-link-qr.png"

if [ -z "$IP" ]; then
  IP="$(ip -4 addr show 2>/dev/null | grep -oP 'inet \K[0-9.]+' | grep -v '^127\.' | head -1)"
fi
[ -n "$IP" ] || { echo "could not detect LAN IP" >&2; exit 1; }

URI="omarchy://${IP}:${PORT}?id=${ID}"
qrencode -o "$OUT" -s 8 -m 2 "$URI"
echo "wrote $OUT -> $URI"
