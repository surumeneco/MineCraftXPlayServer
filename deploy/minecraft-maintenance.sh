#!/usr/bin/env bash
set -euo pipefail

MINECRAFT_SERVICE="${MINECRAFT_SERVICE:-minecraft.service}"
SERVER_DIR="${MINECRAFT_SERVER_DIR:-/opt/minecraft/server}"
RCON_CLIENT="${SERVER_DIR}/deploy/rcon.py"
PYTHON_BIN="${PYTHON_BIN:-/usr/bin/python3}"

log() {
  printf '[minecraft-maintenance] %s\n' "$*"
}

if ! systemctl is-active --quiet "${MINECRAFT_SERVICE}"; then
  log "${MINECRAFT_SERVICE} is not active; skipping scheduled restart."
  exit 0
fi

if [[ ! -x "${PYTHON_BIN}" ]]; then
  log "Python executable not found: ${PYTHON_BIN}"
  exit 1
fi

if [[ ! -f "${RCON_CLIENT}" ]]; then
  log "RCON client not found: ${RCON_CLIENT}"
  exit 1
fi

rcon() {
  "${PYTHON_BIN}" "${RCON_CLIENT}" "$1"
}

log "Sending five-minute restart notice."
rcon "say サーバーを5分後に再起動します。"

sleep 290

for seconds in {10..1}; do
  if ! systemctl is-active --quiet "${MINECRAFT_SERVICE}"; then
    log "${MINECRAFT_SERVICE} stopped during countdown; aborting scheduled restart."
    exit 0
  fi

  rcon "say ${seconds}秒後にサーバーを再起動します。"
  sleep 1
done

log "Flushing world saves."
rcon "save-all flush"

log "Restarting ${MINECRAFT_SERVICE}."
systemctl restart "${MINECRAFT_SERVICE}"
