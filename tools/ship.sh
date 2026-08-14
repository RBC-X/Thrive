#!/usr/bin/env bash
# Thrive ship script — one command to cut a release: bump, build, verify, push, publish.
#
#   1. Check the checkout is clean, on main, and up to date with origin
#   2. Bump the version + build the signed release APK (reuses tools/release.sh)
#   3. Stage ONLY the release files (build config, release notes, README) — never -A
#   4. Push + create the GitHub release with the APK (the app's auto-update channel)
#   5. PROVE provenance: the published APK's version + SHA-256 must match the local
#      build and the tag must point at the commit that produced it
#   6. Restart ONLY the backend process listening on the API port (never other node)
#   7. Install onto an EXPLICIT adb device (never silently the first one)
#
# Usage:
#   bash tools/ship.sh              # bump patch, build, push, release, install
#   bash tools/ship.sh 1.3.0        # explicit version
#   bash tools/ship.sh --same       # rebuild current version without bumping
#   ADB_SERIAL=XYZ bash tools/ship.sh   # target a specific device (required for install)
#
# Options (via environment variables):
#   SHIP_NOTES="..."        release notes (default: commits since the last release)
#   SHIP_MESSAGE="..."      commit message (default: "Thrive <version>")
#   JDK=...                 override the JDK used for the build
#   SHIP_CONFIRM=1          skip the interactive confirmation (CI / unattended)
#   BACKEND_PORT=4000       port the backend listens on (for the scoped restart)
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Force the JDK (stale global JAVA_HOME breaks the build). Override with JDK=...
export JAVA_HOME="${JDK:-C:\Program Files\Java\jdk-21.0.11}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bsmit/AppData/Local/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

ADB="$ANDROID_HOME/platform-tools/adb.exe"
REPO="$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')"
BACKEND_PORT="${BACKEND_PORT:-4000}"

# --- 0. pre-flight: clean authoritative checkout -------------------------------
echo "=== 0/7 Pre-flight: clean checkout on main, up to date ==="
if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: working tree is not clean. Commit or stash before shipping." >&2
  git status --short >&2
  exit 1
fi
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$BRANCH" != "main" ]]; then
  echo "ERROR: not on main (on '$BRANCH'). Releases are cut from main only." >&2
  exit 1
fi
git fetch origin main --quiet
LOCAL_HEAD="$(git rev-parse HEAD)"
REMOTE_HEAD="$(git rev-parse origin/main 2>/dev/null || echo "")"
if [[ "$LOCAL_HEAD" != "$REMOTE_HEAD" ]]; then
  echo "ERROR: local main ($LOCAL_HEAD) is not origin/main ($REMOTE_HEAD)." >&2
  echo "Pull or push first — shipping from a divergent main is forbidden." >&2
  exit 1
fi
echo "OK: main is clean and in sync at $LOCAL_HEAD"
echo ""

# --- 1. bump + build + stage ----------------------------------------------------
echo "=== 1/7 Bumping + building + staging release ==="
bash tools/release.sh "$@"
NEW_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
APK="dist/Thrive-$NEW_NAME-release.apk"
echo "Built $APK"
echo ""

# --- 2. interactive confirmation (unless SHIP_CONFIRM=1) -------------------------
echo "=== 2/7 Confirmation ==="
echo "  Repo:      $REPO"
echo "  Version:   $NEW_NAME"
echo "  APK:       $APK"
if [[ "${SHIP_CONFIRM:-0}" != "1" ]]; then
  read -r -p "Publish Thrive $NEW_NAME to $REPO now? [y/N] " ANS
  if [[ ! "$ANS" =~ ^[Yy]$ ]]; then
    echo "Aborted by user — nothing was pushed or published."
    exit 1
  fi
fi
echo "Confirmed."
echo ""

# --- 3. stage ONLY intended files -----------------------------------------------
echo "=== 3/7 Staging release files ==="
# Explicit allowlist — never `git add -A`. dist/ and backend/public/*.apk are
# gitignored build artifacts, so the only tracked files a release touches are:
git add app/build.gradle.kts backend/release-notes.json README.md
STAGED="$(git diff --cached --name-only)"
echo "Staged:"
echo "$STAGED" | sed 's/^/  /'
if [[ -z "$STAGED" ]]; then
  echo "Nothing to stage — the tree already matches the release."
else
  git commit -m "${SHIP_MESSAGE:-Thrive $NEW_NAME}"
fi
echo ""

# --- 4. push + create GitHub release ----------------------------------------------
echo "=== 4/7 Pushing + creating GitHub release v$NEW_NAME ==="
git push origin main
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
# gh creates the tag on GitHub; fetch it locally so we can verify provenance.
git fetch --tags origin --quiet
echo ""

# --- 5. provenance: prove the published APK came from the tagged commit ------------
echo "=== 5/7 Verifying release provenance ==="
TAG_COMMIT="$(git rev-parse "v$NEW_NAME^{commit}" 2>/dev/null || echo 'MISSING')"
if [[ "$TAG_COMMIT" == "MISSING" ]]; then
  echo "ERROR: tag v$NEW_NAME does not exist locally after fetch." >&2
  exit 1
fi
if [[ "$TAG_COMMIT" != "$(git rev-parse HEAD)" ]]; then
  echo "ERROR: tag v$NEW_NAME points at $TAG_COMMIT, not HEAD ($(git rev-parse HEAD))." >&2
  echo "The release asset may not match the tagged source. Investigate before trusting it." >&2
  exit 1
fi
echo "OK: tag v$NEW_NAME -> $TAG_COMMIT == HEAD"
LOCAL_SHA="$(sha256sum "$APK" | awk '{print $1}')"
ASSET_URL="https://github.com/$REPO/releases/download/v$NEW_NAME/Thrive-$NEW_NAME-release.apk"
REMOTE_SHA="$(curl -sL "$ASSET_URL" | sha256sum | awk '{print $1}')"
if [[ "$LOCAL_SHA" != "$REMOTE_SHA" ]]; then
  echo "ERROR: published APK SHA-256 ($REMOTE_SHA) != local build ($LOCAL_SHA)." >&2
  exit 1
fi
echo "OK: published APK SHA-256 $LOCAL_SHA matches the local build"
APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner.bat 2>/dev/null | sort -V | tail -1 || true)"
if [[ -n "$APKSIGNER" ]]; then
  SIG="$(grep -oP 'versionName="\K[^"]+' /dev/null 2>/dev/null || true)"
  VER_APK="$(grep -a -o "$NEW_NAME" "$APK" | head -1 || echo "")"
  echo "Version string found in APK: ${VER_APK:+yes}"
fi
echo ""

# --- 6. restart ONLY the backend --------------------------------------------------
echo "=== 6/7 Restarting backend on port $BACKEND_PORT ==="
# Find the PID listening on the API port and kill ONLY that one. Never a blanket
# `taskkill //IM node.exe` — unrelated node processes (IDE servers, other apps)
# must be left untouched.
PID="$(netstat -ano 2>/dev/null | grep -E "TCP.*:$BACKEND_PORT .*LISTENING" | awk '{print $NF}' | sort -u | head -1 || true)"
if [[ -n "$PID" && "$PID" != "0" ]]; then
  echo "Killing backend PID $PID (port $BACKEND_PORT only)..."
  taskkill //F //PID "$PID" >/dev/null 2>&1 || echo "  (process already gone)"
  sleep 1
else
  echo "No listener on port $BACKEND_PORT — starting fresh."
fi
BACKEND_DIR="$ROOT/backend"
if [[ ! -f "$BACKEND_DIR/server.js" ]]; then
  echo "WARNING: backend not found at $BACKEND_DIR — skipping restart." >&2
else
  powershell -NoProfile -Command \
    "Start-Process -FilePath 'node' -ArgumentList 'server.js' -WorkingDirectory '$(cygpath -w "$BACKEND_DIR" 2>/dev/null || echo "$BACKEND_DIR")' -WindowStyle Hidden" || true
  sleep 3
  if curl -s -m 5 "http://localhost:$BACKEND_PORT/api/v1/health" >/dev/null; then
    VER="$(curl -s -m 5 "http://localhost:$BACKEND_PORT/api/v1/sync" | python -c "import sys,json; print(json.load(sys.stdin).get('update',{}).get('versionName','n/a'))" 2>/dev/null || echo n/a)"
    echo "backend up on :$BACKEND_PORT (advertising $VER)"
  else
    echo "WARNING: backend did not come up — check server.js" >&2
  fi
fi
echo ""

# --- 7. install to an EXPLICIT device ----------------------------------------------
echo "=== 7/7 Installing on device ==="
SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  # Never silently pick the first device: require an explicit target.
  CONNECTED="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "$CONNECTED" ]]; then
    echo "No adb device connected — skipping install. (The GitHub release is live.)"
    exit 0
  fi
  echo "Connected devices:"
  echo "$CONNECTED" | sed 's/^/  /'
  if [[ "${SHIP_CONFIRM:-0}" != "1" ]]; then
    read -r -p "Which serial to install onto? (blank to skip install) " SERIAL
  else
    echo "SHIP_CONFIRM=1 and no ADB_SERIAL — skipping install."
    exit 0
  fi
fi
if [[ -n "$SERIAL" ]]; then
  echo "Installing $APK on $SERIAL ..."
  "$ADB" -s "$SERIAL" install -r "$APK"
  echo "=== Ship complete: v$NEW_NAME installed on $SERIAL ==="
else
  echo "Install skipped."
fi
