#!/usr/bin/env bash
# Thrive permanent (named) tunnel — stable HTTPS hostname
# ---------------------------------------------------------
# Unlike the quick tunnel (random URL per run), a named tunnel keeps ONE
# hostname forever, so the app's one-tap connect never has to re-discover a
# changing URL. Requires a free Cloudflare account + a domain on it.
#
# One-time setup (browser step — only you can do this):
#   1. cloudflared tunnel login          # opens a browser; log in to Cloudflare
#   2. bash tools/tunnel_named.sh setup  # creates the tunnel + DNS route
#   3. bash tools/tunnel_named.sh run    # starts it (add to startup for 24/7)
#
# After setup, the URL is stable, so publish it ONCE:
#   echo "https://sync.YOURDOMAIN.com" > /tmp/thrive-sync-url.txt
#   gh release upload <tag> /tmp/thrive-sync-url.txt --clobber -R RBC-X/Thrive
#
# See backend/DEPLOY.md Option A for the full always-on server path.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-4000}"
TUNNEL_NAME="${TUNNEL_NAME:-thrive}"
HOSTNAME="${THRIVE_HOSTNAME:-sync.thrive.example.com}"  # set THRIVE_HOSTNAME

setup() {
  echo "=== Creating named tunnel '$TUNNEL_NAME' ==="
  cloudflared tunnel create "$TUNNEL_NAME"
  echo ""
  echo "Now give it a hostname under YOUR domain:"
  echo "  cloudflared tunnel route dns $TUNNEL_NAME $HOSTNAME"
  echo "Then edit the tunnel config (~/.cloudflared/config.yml):"
  echo "  tunnel: $TUNNEL_NAME"
  echo "  credentials-file: /home/<you>/.cloudflared/<tunnel-id>.json"
  echo "  ingress:"
  echo "    - hostname: $HOSTNAME"
  echo "      service: http://localhost:$BACKEND_PORT"
  echo "    - service: http_status:404"
  echo "Finally run: bash tools/tunnel_named.sh run"
}

run() {
  echo "=== Running named tunnel $TUNNEL_NAME ==="
  cloudflared tunnel run "$TUNNEL_NAME"
}

cmd="${1:-}"
case "$cmd" in
  setup) setup ;;
  run) run ;;
  *) echo "usage: $0 {setup|run}" >&2; exit 1 ;;
esac
