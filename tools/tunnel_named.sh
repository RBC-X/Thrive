#!/usr/bin/env bash
# Thrive permanent (named) tunnel — one stable HTTPS hostname, forever
# ---------------------------------------------------------------------
# A named tunnel keeps ONE public hostname, so the app's one-tap connect
# never changes. Unlike the quick tunnel (random URL on every restart), you
# set this up once and the published sync URL stays valid indefinitely.
#
# What YOU need (one-time, ~10 minutes, free):
#   * a Cloudflare account (https://dash.cloudflare.com/signup)
#   * a domain you control, with its nameservers on Cloudflare
#     (or a subdomain delegated to Cloudflare)
#
# Usage:
#   bash tools/tunnel_named.sh login                          # browser auth (once)
#   THRIVE_HOSTNAME=sync.yourdomain.com bash tools/tunnel_named.sh setup
#   bash tools/tunnel_named.sh publish                        # stable URL -> GitHub asset
#   bash tools/tunnel_named.sh run                            # start tunnel (foreground)
#   bash tools/tunnel_named.sh service install                # auto-start on boot (24/7)
#   bash tools/tunnel_named.sh status                         # health check
#
# After `service install`, the tunnel runs as a Windows service and the
# backend watchdog (tools/thrive_service.sh) keeps the Node server alive.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-4000}"
TUNNEL_NAME="${TUNNEL_NAME:-thrive}"
HOSTNAME="${THRIVE_HOSTNAME:-}"
CONFIG="$ROOT/backend/cloudflared.yml"
CF_HOME="$HOME/.cloudflared"

log() { echo "[$(date '+%F %T')] $*"; }

require_hostname() {
  if [[ -z "$HOSTNAME" ]]; then
    echo "ERROR: set THRIVE_HOSTNAME to the subdomain you want, e.g." >&2
    echo "  THRIVE_HOSTNAME=sync.yourdomain.com bash tools/tunnel_named.sh setup" >&2
    exit 1
  fi
}

require_login() {
  if [[ ! -f "$CF_HOME/cert.pem" ]]; then
    echo "ERROR: not logged in to Cloudflare yet. Run:" >&2
    echo "  bash tools/tunnel_named.sh login" >&2
    echo "(that opens a browser — log in, pick your account, allow the tunnel permission)" >&2
    exit 1
  fi
}

# --- login: one-time browser authorization -----------------------------------
login() {
  if [[ -f "$CF_HOME/cert.pem" ]]; then
    echo "Already logged in ($CF_HOME/cert.pem exists). If login is stale:"
    echo "  rm \"$CF_HOME/cert.pem\" && bash tools/tunnel_named.sh login"
    return 0
  fi
  echo "=== Opening Cloudflare login in your browser ==="
  echo "Log in, choose the account that owns your domain, and click Allow."
  cloudflared tunnel login
  echo ""
  if [[ -f "$CF_HOME/cert.pem" ]]; then
    echo "OK — logged in. Next: THRIVE_HOSTNAME=sync.YOURDOMAIN.com bash tools/tunnel_named.sh setup"
  else
    echo "Login did not produce a certificate. Try again or check the browser step." >&2
    exit 1
  fi
}

# --- setup: create tunnel + DNS route + project config -------------------------
setup() {
  require_hostname
  require_login
  echo "=== Creating named tunnel '$TUNNEL_NAME' ==="
  # Reuse an existing tunnel with the same name (idempotent setup).
  if ! cloudflared tunnel list --name "$TUNNEL_NAME" 2>/dev/null | grep -q "$TUNNEL_NAME"; then
    cloudflared tunnel create "$TUNNEL_NAME"
  fi
  local tunnel_id
  tunnel_id="$(cloudflared tunnel list --name "$TUNNEL_NAME" 2>/dev/null | grep "$TUNNEL_NAME" | awk '{print $1}')"
  if [[ -z "$tunnel_id" ]]; then
    echo "ERROR: could not find the tunnel id for '$TUNNEL_NAME'." >&2
    cloudflared tunnel list >&2
    exit 1
  fi
  local cred="$CF_HOME/$tunnel_id.json"
  if [[ ! -f "$cred" ]]; then
    echo "ERROR: credentials file missing at $cred" >&2
    exit 1
  fi

  echo "=== Routing $HOSTNAME -> tunnel $TUNNEL_NAME ==="
  # Idempotent: routing an already-routed hostname is harmless.
  cloudflared tunnel route dns "$TUNNEL_NAME" "$HOSTNAME" || true

  echo "=== Writing tunnel config $CONFIG ==="
  {
    echo "tunnel: $TUNNEL_NAME"
    echo "credentials-file: $(cygpath -w "$cred" 2>/dev/null || echo "$cred")"
    echo ""
    echo "ingress:"
    echo "  - hostname: $HOSTNAME"
    echo "    service: http://localhost:$BACKEND_PORT"
    echo "  - service: http_status:404"
  } > "$CONFIG"
  echo "OK — config written."
  echo ""
  echo "Next steps:"
  echo "  1. bash tools/tunnel_named.sh run        # start it once to verify"
  echo "  2. bash tools/tunnel_named.sh publish    # stable URL -> GitHub asset"
  echo "  3. bash tools/tunnel_named.sh service install   # auto-start on boot"
}

# --- run: start the named tunnel (foreground) ----------------------------------
run() {
  require_hostname
  [[ -f "$CONFIG" ]] || { echo "ERROR: no config at $CONFIG — run setup first." >&2; exit 1; }
  echo "=== Starting named tunnel $TUNNEL_NAME ($HOSTNAME) ==="
  cloudflared tunnel --config "$CONFIG" run "$TUNNEL_NAME"
}

# --- publish: verify + publish the STABLE url to the latest release -------------
publish() {
  require_hostname
  local url="https://$HOSTNAME"
  echo "=== Verifying $url ==="
  if ! curl -s -m 12 "$url/api/v1/health" | grep -q '"ok"'; then
    echo "ERROR: $url did not answer /api/v1/health — is the tunnel running?" >&2
    echo "  Run: bash tools/tunnel_named.sh run   (or: bash tools/tunnel_named.sh service install)" >&2
    exit 1
  fi
  echo "  public health OK: $url/api/v1/health"
  local repo tag
  repo="$(git -C "$ROOT" remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')"
  tag="$(gh release list -R "$repo" --limit 1 --json tagName --jq '.[0].tagName // empty' 2>/dev/null || true)"
  if [[ -z "$tag" ]]; then
    echo "WARNING: no GitHub release found — wrote $ROOT/backend/public_url.txt instead." >&2
    echo "$url" > "$ROOT/backend/public_url.txt"
    exit 0
  fi
  echo "$url" > /tmp/thrive-sync-url.txt
  if gh release upload "$tag" /tmp/thrive-sync-url.txt --clobber -R "$repo" 2>&1; then
    echo "Published (stable forever): https://github.com/$repo/releases/download/$tag/thrive-sync-url.txt"
    echo "  -> $url"
  else
    echo "WARNING: asset upload failed (is gh authenticated?). URL is: $url" >&2
  fi
}

# --- service: install/uninstall the tunnel as a Windows service ------------------
service() {
  require_hostname
  local action="${1:-install}"
  case "$action" in
    install)
      [[ -f "$CONFIG" ]] || { echo "ERROR: run setup first." >&2; exit 1; }
      echo "=== Installing cloudflared as a Windows service (starts on boot) ==="
      cloudflared --config "$CONFIG" service install
      echo "Service installed. To start now:"
      echo "  powershell -NoProfile -Command \"Start-Service cloudflared\""
      echo ""
      echo "Then keep the backend alive 24/7 with the watchdog:"
      echo "  THRIVE_HOSTNAME=$HOSTNAME bash tools/thrive_service.sh &"
      ;;
    uninstall)
      cloudflared service uninstall
      ;;
    *) echo "usage: $0 service {install|uninstall}" >&2; exit 1 ;;
  esac
}

# --- status ----------------------------------------------------------------------
status() {
  if [[ -n "$HOSTNAME" ]]; then
    echo "public hostname: https://$HOSTNAME"
    curl -s -m 8 "https://$HOSTNAME/api/v1/health" | head -c 200 || echo "(unreachable — tunnel not running)"
    echo ""
  fi
  cloudflared tunnel list --name "$TUNNEL_NAME" 2>/dev/null || true
}

cmd="${1:-}"
case "$cmd" in
  login) login ;;
  setup) setup ;;
  run) run ;;
  publish) publish ;;
  service) service "${2:-install}" ;;
  status) status ;;
  *)
    echo "usage: $0 {login|setup|run|publish|service install|status}" >&2
    exit 1
    ;;
esac
