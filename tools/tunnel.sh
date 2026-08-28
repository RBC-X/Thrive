#!/usr/bin/env bash
# Thrive public sync tunnel
# -------------------------
# Exposes the local backend over a real public HTTPS URL via a cloudflared
# quick tunnel (no account, no domain needed) and publishes that URL as a
# small `thrive-sync-url.txt` asset on the latest GitHub release. The app's
# Settings can then offer a one-tap "Connect to public backup server" —
# ordinary users never type IPs or URLs.
#
# NOTE: quick-tunnel URLs change on every restart, so this script re-publishes
# the asset each run. For a permanent hostname, `cloudflared tunnel login`
# (free Cloudflare account) + a named tunnel on your own domain is the
# upgrade path — see README.
#
# Usage:
#   bash tools/tunnel.sh                 # start backend + tunnel + publish URL
#   bash tools/tunnel.sh --no-publish    # start tunnel, skip GitHub upload
#   BACKEND_PORT=4000 bash tools/tunnel.sh
#   JDK=... bash tools/tunnel.sh
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bsmit/AppData/Local/Android/Sdk}"
BACKEND_PORT="${BACKEND_PORT:-4000}"
PUBLISH="${1:-publish}"

echo "=== Thrive public sync tunnel ==="

# --- 1. ensure the backend is running (scoped to our port) ----------------------
echo "[1/4] Backend on :$BACKEND_PORT ..."
if ! curl -s -m 3 "http://localhost:$BACKEND_PORT/api/v1/health" >/dev/null 2>&1; then
  echo "  backend not running — starting it"
  BACKEND_LAUNCHER="$ROOT/tools/start_backend_secure.ps1"
  powershell -NoProfile -Command \
    "Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','$(cygpath -w "$BACKEND_LAUNCHER" 2>/dev/null || echo "$BACKEND_LAUNCHER")' -WindowStyle Hidden" || true
  for i in 1 2 3 4 5; do
    sleep 1
    curl -s -m 2 "http://localhost:$BACKEND_PORT/api/v1/health" >/dev/null 2>&1 && break
  done
fi
curl -s -m 3 "http://localhost:$BACKEND_PORT/api/v1/health" >/dev/null 2>&1 \
  && echo "  backend OK" \
  || { echo "ERROR: backend did not come up on :$BACKEND_PORT" >&2; exit 1; }

# --- 2. start the quick tunnel ---------------------------------------------------
echo "[2/4] Starting cloudflared quick tunnel ..."
URL_FILE="$(mktemp)"
( cloudflared tunnel --url "http://localhost:$BACKEND_PORT" 2>&1 \
    | tee "$URL_FILE" ) &
TUNNEL_PID=$!

URL=""
for i in $(seq 1 30); do
  # cloudflared also logs its API host (https://api.trycloudflare.com); only a
  # real tunnel subdomain works as a public endpoint, so exclude the api host.
  URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$URL_FILE" 2>/dev/null | grep -v 'https://api\.trycloudflare\.com' | head -1 || true)"
  [[ -n "$URL" ]] && break
  sleep 1
done
if [[ -z "$URL" ]]; then
  echo "ERROR: tunnel did not produce a URL in 30s. Output so far:" >&2
  cat "$URL_FILE" >&2 || true
  kill "$TUNNEL_PID" 2>/dev/null || true
  exit 1
fi
echo "  tunnel URL: $URL"

# --- 3. verify the public HTTPS endpoint end to end -------------------------------
echo "[3/4] Verifying public endpoint ..."
# $URL already includes the https:// scheme — never double-prefix it.
if ! curl -s -m 10 "$URL/api/v1/health" 2>/dev/null | grep -q '"ok"'; then
  echo "WARNING: health check over HTTPS did not return ok (checking once more)..." >&2
  sleep 3
  curl -s -m 10 "$URL/api/v1/health" 2>/dev/null | head -c 300
  echo ""
fi
echo "  public health: $(curl -s -m 10 "$URL/api/v1/health" 2>/dev/null | head -c 120)"

# --- 4. publish the URL as a release asset ----------------------------------------
if [[ "$PUBLISH" == "publish" ]]; then
  echo "[4/4] Publishing $URL to the latest GitHub release ..."
  REPO="$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')"
  TAG="$(gh release list -R "$REPO" --limit 1 --json tagName --jq '.[0].tagName // empty' 2>/dev/null || true)"
  if [[ -z "$TAG" ]]; then
    echo "  WARNING: no GitHub release found — skipping publish (tunnel still works locally)." >&2
  else
    echo "$URL" > /tmp/thrive-sync-url.txt
    # Keep a local copy so release/ship flows can attach the current URL.
    echo "$URL" > "$ROOT/backend/public_url.txt"
    if gh release upload "$TAG" /tmp/thrive-sync-url.txt --clobber -R "$REPO" 2>&1; then
      echo "  published: https://github.com/$REPO/releases/download/$TAG/thrive-sync-url.txt -> $URL"
    else
      echo "  WARNING: asset upload failed (is gh authenticated?) — tunnel still works." >&2
    fi
  fi
else
  echo "[4/4] --no-publish: skipping GitHub upload."
fi

echo ""
echo "=== Tunnel live: $URL ==="
echo "The app's Settings → 'Public backup server' will now offer a one-tap connect."
echo "Press Ctrl+C to stop the tunnel. (The backend keeps running.)"

# Keep the tunnel process in the foreground so Ctrl+C stops it cleanly.
wait "$TUNNEL_PID" 2>/dev/null || true
