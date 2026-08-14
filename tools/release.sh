#!/usr/bin/env bash
# Thrive release script
# ----------------------
# Bumps versionCode/versionName in app/build.gradle.kts, builds the signed
# release APK, and stages it at:
#   dist/Thrive-<version>-release.apk        (archive copy)
#   backend/public/Thrive-release.apk        (served by the update channel)
#
# Usage:
#   bash tools/release.sh              # bump patch (1.2.0 -> 1.2.1)
#   bash tools/release.sh 1.3.0        # set an explicit version
#   bash tools/release.sh 1.3.0 4      # explicit version + versionCode
#   bash tools/release.sh --same       # rebuild current version as-is
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
GRADLE="app/build.gradle.kts"

# Force the JDK: a stale global JAVA_HOME (e.g. an Atlas-Mobile jdk-17) breaks
# the build. Override with JDK=... if your machine uses a different install.
export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bsmit/AppData/Local/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

# --- read current version ---------------------------------------------------
CUR_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE" | head -1)"
CUR_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE" | head -1)"
echo "Current version: $CUR_NAME (code $CUR_CODE)"

# --- compute next version ---------------------------------------------------
if [[ "${1:-}" == "--same" ]]; then
  NEW_NAME="$CUR_NAME"
  NEW_CODE="$CUR_CODE"
  echo "Re-releasing current version: $NEW_NAME (code $NEW_CODE)"
else
  NEW_NAME="${1:-}"
  NEW_CODE="${2:-}"
  if [[ -z "$NEW_NAME" ]]; then
    IFS='.' read -r MAJ MIN PAT <<< "$CUR_NAME"
    PAT="${PAT:-0}"
    NEW_NAME="$MAJ.$MIN.$((PAT + 1))"
  fi
  if [[ -z "$NEW_CODE" ]]; then
    NEW_CODE=$((CUR_CODE + 1))
  fi
  echo "Releasing: $NEW_NAME (code $NEW_CODE)"
fi

# --- bump build config -------------------------------------------------------
sed -i "s/^\(\s*versionCode\s*=\s*\)[0-9]*/\1$NEW_CODE/" "$GRADLE"
sed -i "s/^\(\s*versionName\s*=\s*\"\)[^\"]*\"/\1$NEW_NAME\"/" "$GRADLE"
echo "Updated $GRADLE: versionCode=$NEW_CODE versionName=\"$NEW_NAME\""

# --- build --------------------------------------------------------------------
echo "Building release APK..."
./gradlew :app:assembleRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: build did not produce $APK" >&2
  exit 1
fi

# --- stage ---------------------------------------------------------------------
mkdir -p dist backend/public
DIST_APK="dist/Thrive-$NEW_NAME-release.apk"
cp "$APK" "$DIST_APK"
cp "$APK" "backend/public/Thrive-release.apk"

# --- verify signature -----------------------------------------------------------
APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner.bat 2>/dev/null | sort -V | tail -1 || true)"
if [[ -n "$APKSIGNER" ]]; then
  SIG="$("$APKSIGNER" verify --print-certs "$DIST_APK" 2>&1 | grep -o 'CN=[^,]*' | head -1 || true)"
  echo "Signature: ${SIG:-check failed}"
fi

SIZE="$(stat -c %s "$DIST_APK")"
echo ""
echo "Release complete:"
echo "  $DIST_APK  ($SIZE bytes)"
echo "  backend/public/Thrive-release.apk  ($(stat -c %s backend/public/Thrive-release.apk) bytes)"
echo "  version $NEW_NAME (code $NEW_CODE)"
echo ""
echo "Update channel: restart the backend and it will advertise $NEW_NAME."
echo "  (or stage a newer build with:  UPDATE_VERSION=$NEW_NAME node server.js)"
