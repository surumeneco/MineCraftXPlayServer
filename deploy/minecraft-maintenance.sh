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
  RCON_TIMEOUT_SECONDS="${2:-5}" "${PYTHON_BIN}" "${RCON_CLIENT}" "$1"
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


# Explicitly opt in only after both consumer plugins expose the verified
# WCL-MIGRATION RCON status protocol. Never invoke unknown commands in production.
migration_enabled="${WONDERFUL_MIGRATION_ENABLED:-0}"
if [[ "${migration_enabled}" != "0" && "${migration_enabled}" != "1" ]]; then
  log "WONDERFUL_MIGRATION_ENABLED must be 0 or 1."
  exit 1
fi

validate_migration_message() {
  local line="$1"
  # Exact single-line machine protocol; ordinary RCON success is insufficient.
  [[ "${line}" =~ ^WCL-MIGRATION[[:space:]]job=([a-zA-Z0-9-]+)[[:space:]]status=(RUNNING|SUCCESS|FAILED)[[:space:]]processed=([0-9]+)[[:space:]]copied=([0-9]+)[[:space:]]skipped=([0-9]+)[[:space:]]failed=([0-9]+)$ ]]
}

migrate_consumer() {
  local prefix="$1"
  local max_polls="${WONDERFUL_MIGRATION_MAX_POLLS:-20}"
  local interval="${WONDERFUL_MIGRATION_POLL_SECONDS:-2}"
  if [[ ! "${max_polls}" =~ ^[1-9][0-9]?$ ]] || [[ ! "${interval}" =~ ^[0-9]$ ]]; then
    log "Invalid migration polling settings."
    return 1
  fi

  log "Starting immutable payload migration for ${prefix}."
  local started
  if ! started="$(rcon "${prefix} storage migrate" 30)"; then
    log "${prefix} migration command failed at transport level."
    return 1
  fi
  if ! validate_migration_message "${started}"; then
    log "${prefix} migration returned an invalid start response: ${started}"
    return 1
  fi
  local job="${BASH_REMATCH[1]}"
  local status="${BASH_REMATCH[2]}"
  local failed="${BASH_REMATCH[6]}"
  if [[ "${status}" == "FAILED" || "${failed}" != "0" ]]; then
    log "${prefix} migration failed: ${started}"
    return 1
  fi

  local response
  for ((attempt=1; attempt<=max_polls; attempt++)); do
    if ! response="$(rcon "${prefix} storage status" 5)"; then
      log "${prefix} migration status transport error."
      return 1
    fi
    if ! validate_migration_message "${response}"; then
      log "${prefix} migration returned invalid status: ${response}"
      return 1
    fi
    if [[ "${BASH_REMATCH[1]}" != "${job}" ]]; then
      log "${prefix} migration status job mismatch."
      return 1
    fi
    status="${BASH_REMATCH[2]}"
    failed="${BASH_REMATCH[6]}"
    if [[ "${status}" == "SUCCESS" && "${failed}" == "0" ]]; then
      log "${prefix} migration completed: ${response}"
      return 0
    fi
    if [[ "${status}" != "RUNNING" || "${failed}" != "0" ]]; then
      log "${prefix} migration failed: ${response}"
      return 1
    fi
    if ((attempt < max_polls)); then sleep "${interval}"; fi
  done
  log "${prefix} migration did not complete within ${max_polls} polls."
  return 1
}

if [[ "${migration_enabled}" == "1" ]]; then
  migrate_consumer "wwc"
  migrate_consumer "www"
fi

log "Flushing world saves."
rcon "save-all flush" 120

log "Restarting ${MINECRAFT_SERVICE}."
systemctl restart "${MINECRAFT_SERVICE}"
