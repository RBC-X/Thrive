#!/usr/bin/env bash
# Thrive -> TestMu (LambdaTest) uploader
# --------------------------------------
# Uploads the latest release APK to TestMu so it can be launched on a
# cloud emulator and shared with anyone in a browser (iPhone included).
#
# Usage:
#   LT_USERNAME=you@email.com LT_ACCESS_KEY=xxxx bash tools/upload_testmu.sh
#   LT_USERNAME=... LT_ACCESS_KEY=... bash tools/upload_testmu.sh dist/Thrive-1.2.3-release.apk
#
# Credentials come from your TestMu account (Settings -> Password / API
# keys). The upload returns an app id used to launch emulator sessions.

set -euo pipefail

USERNAME="${LT_USERNAME:-}"
KEY="${LT_ACCESS_KEY:-}"
if [[ -z "$USERNAME" || -z "$KEY" ]]; then
  echo "ERROR: set LT_USERNAME and LT_ACCESS_KEY from your TestMu account" >&2
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

echo "Uploading $(basename "$APK") to TestMu..."
RESP=$(curl -sS -u "$USERNAME:$KEY" \
  -F "file=@$APK" \
  -F "name=Thrive-$(basename "$APK" .apk)" \
  https://api.lambdatest.com/app/v1/android/upload)

APP_ID=$(printf '%s' "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('app_id') or d.get('appId') or '')" 2>/dev/null || true)

if [[ -n "$APP_ID" ]]; then
  echo
  echo "✅ Uploaded. App id:"
  echo "   $APP_ID"
  echo
  echo "Next: open https://app.testmuai.com (or the TestMu dashboard) ->"
  echo "Real Device -> App Testing -> emulator -> pick 'Thrive' from your"
  echo "uploaded apps, launch it, then use the session's Share/Public link."
else
  echo
  echo "Upload failed. Response:" >&2
  echo "$RESP" >&2
  exit 1
fi
