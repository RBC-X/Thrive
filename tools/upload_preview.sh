#!/usr/bin/env bash
# Thrive -> Appetize preview uploader
# ------------------------------------
# Uploads the latest release APK in dist/ to Appetize so anyone (including
# iPhone users) can try the app in a browser-based Android emulator at a
# shareable link. No install, no Play Store, no TestFlight, no payment.
#
# Usage:
#   APPETIZE_TOKEN=xxxx bash tools/upload_preview.sh          # latest APK in dist/
#   APPETIZE_TOKEN=xxxx bash tools/upload_preview.sh dist/Thrive-1.2.3-release.apk
#
# The app is uploaded with public run permissions so the link works for
# anyone without an Appetize account.

set -euo pipefail

TOKEN="${APPETIZE_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: set APPETIZE_TOKEN (get one free at https://appetize.io -> Settings -> API tokens)" >&2
  exit 1
fi

APK="${1:-}"
if [[ -z "$APK" ]]; then
  APK=$(ls -t dist/Thrive-*-release.apk 2>/dev/null | head -1)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "ERROR: no APK found in dist/ (or the path you gave doesn't exist)" >&2
  exit 1
fi

WORK="$(mktemp -d)"
ZIP="$WORK/thrive-preview.zip"
python - "$APK" "$ZIP" <<'PY'
import sys, zipfile, os
apk, z = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as f:
    f.write(apk, os.path.basename(apk))
PY

echo "Uploading $(basename "$APK") ($(du -h "$APK" | cut -f1)) to Appetize..."
RESP=$(curl -sS -X POST https://api.appetize.io/v1/apps \
  -H "X-API-KEY: $TOKEN" \
  -F "file=@$ZIP" \
  -F "platform=android" \
  -F "appPermissions.run=public")

PUBKEY=$(printf '%s' "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('publicKey',''))" 2>/dev/null || true)
rm -rf "$WORK"

if [[ -n "$PUBKEY" ]]; then
  echo
  echo "✅ Preview live — share this link with anyone (works on iPhone):"
  echo "   https://appetize.io/app/$PUBKEY"
else
  echo
  echo "Upload failed. Response from Appetize:" >&2
  echo "$RESP" >&2
  exit 1
fi
