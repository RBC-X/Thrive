#!/usr/bin/env bash
# Thrive always-on service (self-healing watchdog)
# -------------------------------------------------
# Keeps the backend AND the public tunnel alive: every 30s it checks that
# (1) the backend answers on the API port and (2) cloudflared is still
# connected with a working public URL. Any dead piece is restarted, and the
# live tunnel URL is re-published to the latest GitHub release so the app's
# one-tap connect always points at a working server.
#
# For 24/7 operation on this machine:
#   1. schtasks /Create /TN ThriveService /TR "bash <ABS_PATH>/tools/thrive_service.sh" /SC ONSTART /RU %USERNAME% /RL HIGHEST
#      (or: run it inside a "Startup" folder shortcut / a terminal that stays open)
#   2. It restarts itself after the machine boots and after any crash.
#
# This keeps your PC as the host. For TRUE always-on hosting independent of
# this machine, deploy backend/ to a small VPS (see backend/DEPLOY.md).
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
BACKEND_PORT="${BACKEND_PORT:-4000}"
CHECK_EVERY="${CHECK_EVERY:-30}"
LOG="$ROOT/tools/thrive_service.log"

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

backend_up() { curl -s -m 3 "http://localhost:$BACKEND_PORT/api/v1/health" >/dev/null 2>&1; }

ensure_backend() {
  if backend_up; then return 0; fi
  log "backend down — starting"
  BACKEND_LAUNCHER="$ROOT/tools/start_backend_secure.ps1"
  powershell -NoProfile -Command \
    "Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','$(cygpath -w "$BACKEND_LAUNCHER" 2>/dev/null || echo "$BACKEND_LAUNCHER")' -WindowStyle Hidden" || true
  for _ in 1 2 3 4 5; do sleep 2; backend_up && { log "backend up"; return 0; }; done
  log "backend failed to start"
}

ensure_tunnel() {
  # Named-tunnel mode (stable hostname): THRIVE_HOSTNAME is set. The URL never
  # changes, so this only needs to keep the tunnel process alive.
  if [[ -n "${THRIVE_HOSTNAME:-}" ]]; then
    if curl -s -m 6 "https://$THRIVE_HOSTNAME/api/v1/health" >/dev/null 2>&1; then
      return 0
    fi
    log "named tunnel unreachable — restarting cloudflared"
    CONFIG="$ROOT/backend/cloudflared.yml"
    if [[ -f "$CONFIG" ]]; then
      (cloudflared tunnel --config "$CONFIG" run "${TUNNEL_NAME:-thrive}" >> "$LOG" 2>&1 &)
      for _ in $(seq 1 30); do
        sleep 2
        curl -s -m 6 "https://$THRIVE_HOSTNAME/api/v1/health" >/dev/null 2>&1 && { log "named tunnel up"; return 0; }
      done
      log "named tunnel did not come up after restart"
    else
      log "no named-tunnel config at $CONFIG — run tools/tunnel_named.sh setup"
    fi
    return 0
  fi

  # Quick-tunnel mode (legacy): URL changes on every restart, so re-publish.
  local last_url=""
  last_url="$(cat "$ROOT/.thrive-tunnel-url" 2>/dev/null || true)"
  if [[ -n "$last_url" ]] && curl -s -m 5 "$last_url/api/v1/health" >/dev/null 2>&1; then
    return 0
  fi
  # (Re)start the tunnel with a fresh URL and publish it.
  log "tunnel down or unreachable — restarting"
  bash "$ROOT/tools/tunnel.sh" --no-publish > /tmp/thrive_tunnel_start.log 2>&1 &
  for _ in $(seq 1 30); do
    sleep 2
    new_url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' /tmp/thrive_tunnel_start.log 2>/dev/null | head -1 || true)"
    [[ -n "$new_url" ]] && break
  done
  if [[ -n "$new_url" ]]; then
    echo "$new_url" > "$ROOT/.thrive-tunnel-url"
    # Re-publish so the app's discovery picks up the new URL.
    (cd "$ROOT" && echo "$new_url" > /tmp/thrive-sync-url.txt \
      && REPO="$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')" \
      && TAG="$(gh release list -R "$REPO" --limit 1 --json tagName --jq '.[0].tagName // empty' 2>/dev/null || true)" \
      && [[ -n "$TAG" ]] && gh release upload "$TAG" /tmp/thrive-sync-url.txt --clobber -R "$REPO" >/dev/null 2>&1) \
      && log "published $new_url"
  fi
}

log "Thrive service watchdog starting (check every ${CHECK_EVERY}s)"
while true; do
  ensure_backend
  ensure_tunnel
  sleep "$CHECK_EVERY"
done
