#!/usr/bin/env bash
# Thrive ship script — one command to cut a release: bump, build, push, publish.
#
#   1. Bump the version + build the signed release APK (reuses tools/release.sh)
#   2. Commit + push the release to GitHub
#   3. Create the GitHub release with the APK attached (the app's auto-update channel)
#   4. Restart the backend so the self-hosted update channel also advertises it
#   5. Install the APK onto the first connected adb device
#
# Usage:
#   bash tools/ship.sh              # bump patch, build, push, release, install
#   bash tools/ship.sh 1.3.0        # explicit version
#   bash tools/ship.sh --same       # rebuild current version without bumping
#   ADB_SERIAL=XYZ bash tools/ship.sh   # target a specific device
#
# Options (via environment variables):
#   SHIP_NOTES="..."        release notes (default: commits since the last release)
#   SHIP_MESSAGE="..."      commit message (default: "Thrive <version>")
#   JDK=...                 override the JDK used for the build
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Force the JDK (stale global JAVA_HOME breaks the build). Override with JDK=...
export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bsmit/AppData/Local/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

ADB="$ANDROID_HOME/platform-tools/adb.exe"
REPO="$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')"

# --- 1. bump + build + stage ----------------------------------------------------
echo "=== 1/5 Bumping + building + staging release ==="
bash tools/release.sh "$@"
NEW_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
APK="dist/Thrive-$NEW_NAME-release.apk"
echo ""

# --- 2. commit + push -----------------------------------------------------------
echo "=== 2/5 Committing + pushing to $REPO ==="
git add -A
if git diff --cached --quiet; then
  echo "Nothing to commit — the tree already matches the release."
else
  git commit -m "${SHIP_MESSAGE:-Thrive $NEW_NAME}"
fi
git push origin main
echo ""

# --- 3. create GitHub release ----------------------------------------------------
echo "=== 3/5 Creating GitHub release v$NEW_NAME ==="
NOTES="${SHIP_NOTES:-}"
if [[ -z "$NOTES" ]]; then
  git fetch --tags origin --quiet 2>/dev/null || true
  LAST_TAG="$(gh release list -R "$REPO" --limit 1 --json tagName --jq '.[0].tagName // empty' 2>/dev/null || true)"
  NOTES="$(git log --pretty=format:'- %s' "${LAST_TAG}..HEAD" 2>/dev/null || true)"
fi
if [[ -z "$NOTES" ]]; then
  NOTES="Thrive $NEW_NAME"
fi
gh release create "v$NEW_NAME" "$APK" \
  --repo "$REPO" \
  --title "Thrive $NEW_NAME" \
  --notes "$NOTES"
echo ""

# --- 4. restart backend -----------------------------------------------------------
echo "=== 4/5 Restarting backend (will advertise $NEW_NAME) ==="
taskkill //F //IM node.exe >/dev/null 2>&1 || true
sleep 1
powershell -NoProfile -Command \
  "Start-Process -FilePath 'node' -ArgumentList 'server.js' -WorkingDirectory 'C:\Users\bsmit\OneDrive\Documents\Thrive\backend' -WindowStyle Hidden"
sleep 3
curl -s -m 5 "http://localhost:4000/api/v1/health" >/dev/null \
  && echo "backend up (advertising $(curl -s -m 5 http://localhost:4000/api/v1/sync | python -c "import sys,json; print(json.load(sys.stdin).get('update',{}).get('versionName','n/a'))" 2>/dev/null || echo n/a))" \
  || echo "WARNING: backend did not come up — check server.js"
echo ""

# --- 5. install --------------------------------------------------------------------
echo "=== 5/5 Installing on device ==="
SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "WARNING: no adb device connected — skipping install (the GitHub release is live)." >&2
else
  echo "Installing $APK on $SERIAL ..."
  "$ADB" -s "$SERIAL" install -r "$APK"
  echo "=== Ship complete: v$NEW_NAME installed on $SERIAL ==="
fi
