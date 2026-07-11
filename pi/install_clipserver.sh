#!/usr/bin/env bash
# Install/update TeslaSync clipserver on the Radxa board.
# Run from the repository root on the board: sudo pi/install_clipserver.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_PY="$ROOT_DIR/pi/clipserver.py"
SRC_SERVICE="$ROOT_DIR/pi/clipserver.service"
INSTALL_PY="/mutable/clipserver.py"
SERVICE_PATH="/etc/systemd/system/clipserver.service"
ARCHIVE_ROOT="/backingfiles/archive_share"

if [[ "${EUID}" -ne 0 ]]; then
  echo "ERROR: run as root, e.g. sudo pi/install_clipserver.sh" >&2
  exit 1
fi
if [[ ! -f "$SRC_PY" || ! -f "$SRC_SERVICE" ]]; then
  echo "ERROR: run this from a TeslaSync checkout containing pi/clipserver.py" >&2
  exit 1
fi
if [[ ! -d /mutable ]]; then
  echo "ERROR: /mutable is missing. teslausb writable partition not mounted?" >&2
  exit 1
fi

# teslausb normally remounts / read-only. Make install idempotent either way.
if findmnt -no OPTIONS / | grep -qw ro; then
  echo "Remounting / read-write for systemd unit install..."
  mount -o remount,rw /
fi

install -m 0755 "$SRC_PY" "$INSTALL_PY"
install -m 0644 "$SRC_SERVICE" "$SERVICE_PATH"
systemctl daemon-reload
systemctl enable --now clipserver.service

# Service can start even before the archive contains events; /healthz is the fast check.
sleep 1
systemctl --no-pager --full status clipserver.service | sed -n '1,12p'
if command -v curl >/dev/null 2>&1; then
  echo
  echo "Health check:"
  curl -fsS "http://127.0.0.1:8080/healthz" && echo
else
  echo "curl not installed; open http://127.0.0.1:8080/healthz or phone http://192.168.4.1:8080/healthz"
fi

if [[ ! -d "$ARCHIVE_ROOT" ]]; then
  echo "WARN: $ARCHIVE_ROOT does not exist yet; /api/events will be empty until teslausb archive is present." >&2
fi
