#!/usr/bin/env bash
# Thrive ship script — one command to get a new build onto a connected phone.
#
#   1. Build + stage the release APK (reuses tools/release.sh)
#   2. Restart the backend so the update channel advertises the new version
#   3. Install the APK onto the first connected adb device (phone or emulator)
#
# Usage:
#   bash tools/ship.sh              # bump patch, build, restart, install
#   bash tools/ship.sh 1.3.0        # explicit version
#   bash tools/ship.sh --same       # rebuild current version without bumping
#   ADB_SERIAL=XYZ bash tools/ship.sh   # target a specific device
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Force the JDK (stale global JAVA_HOME breaks the build). Override with JDK=...
export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bsmit/AppData/Local/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

# --- 1. build + stage ---------------------------------------------------------
echo "=== 1/3 Building + staging release ==="
bash tools/release.sh "$@"
NEW_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
echo ""

# --- 2. restart backend ---------------------------------------------------------
echo "=== 2/3 Restarting backend (will advertise $NEW_NAME) ==="
taskkill //F //IM node.exe >/dev/null 2>&1 || true
sleep 1
powershell -NoProfile -Command \
  "Start-Process -FilePath 'node' -ArgumentList 'server.js' -WorkingDirectory 'C:\Users\bsmit\OneDrive\Documents\Thrive\backend' -WindowStyle Hidden"
sleep 3
curl -s -m 5 "http://localhost:4000/api/v1/health" >/dev/null \
  && echo "backend up (advertising $(curl -s -m 5 http://localhost:4000/api/v1/sync | python -c "import sys,json; print(json.load(sys.stdin).get('update',{}).get('versionName','n/a'))" 2>/dev/null || echo n/a))" \
  || echo "WARNING: backend did not come up — check server.js"
echo ""

# --- 3. install ------------------------------------------------------------------
echo "=== 3/3 Installing on device ==="
APK="dist/Thrive-$NEW_NAME-release.apk"
SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ANDROID_HOME/platform-tools/adb.exe" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "ERROR: no adb device connected (USB debugging on + unlocked)." >&2
  echo "       Connect one, or set ADB_SERIAL=<serial>." >&2
  exit 1
fi
echo "Installing $APK on $SERIAL ..."
"$ANDROID_HOME/platform-tools/adb.exe" -s "$SERIAL" install -r "$APK"
echo ""
echo "=== Ship complete: v$NEW_NAME installed on $SERIAL ==="
echo "Tunnel (if running): check cloudflared for the current HTTPS URL to use as the sync server."
