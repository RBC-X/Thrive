#!/usr/bin/env bash
# Thrive release consistency check
# ---------------------------------
# Verifies the source-of-truth version metadata agrees across the repo, and
# (optionally) that a git tag and a built APK match that same version.
#
# Usage:
#   bash tools/check_release.sh                 # source-side consistency only
#   bash tools/check_release.sh v1.2.9          # + git tag checks
#   bash tools/check_release.sh v1.2.9 dist/Thrive-1.2.9-release.apk
#
# Exits non-zero on any disagreement. Never publishes or pushes anything.

set -u
cd "$(dirname "$0")/.." || exit 1

TAG="${1:-}"
APK="${2:-}"
FAILED=0

say() { printf '%s\n' "$*"; }
fail() { say "FAIL  $*"; FAILED=1; }
ok()   { say "  ok  $*"; }

# --- Gradle metadata ---------------------------------------------------------
GRADLE_VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
GRADLE_CODE="$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
if [ -z "$GRADLE_VERSION" ] || [ -z "$GRADLE_CODE" ]; then
  fail "cannot parse versionCode/versionName from app/build.gradle.kts"
else
  ok "gradle: versionName=$GRADLE_VERSION versionCode=$GRADLE_CODE"
fi

# --- README ------------------------------------------------------------------
README_LINE="$(grep -n 'Latest:' README.md | head -1 || true)"
if ! grep -q "Latest: \*\*$GRADLE_VERSION\*\*" README.md; then
  fail "README 'Latest:' line ($README_LINE) does not match Gradle versionName $GRADLE_VERSION"
else
  ok "readme: Latest matches $GRADLE_VERSION"
fi

# --- Release notes -----------------------------------------------------------
if ! grep -q "\"$GRADLE_VERSION\"" backend/release-notes.json; then
  fail "backend/release-notes.json has no entry for $GRADLE_VERSION"
else
  ok "release-notes: entry for $GRADLE_VERSION"
fi

# --- Git tag -----------------------------------------------------------------
if [ -n "$TAG" ]; then
  if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    fail "git tag $TAG does not exist"
  else
    ok "git tag $TAG exists"
    TAGGED_VERSION="$(printf '%s' "$TAG" | sed 's/^v//')"
    if [ "$TAGGED_VERSION" != "$GRADLE_VERSION" ]; then
      fail "tag $TAG (version $TAGGED_VERSION) does not match Gradle versionName $GRADLE_VERSION"
    else
      ok "git tag version matches Gradle versionName"
    fi
    if [ "$(git rev-parse "$TAG")" != "$(git rev-parse HEAD)" ]; then
      fail "tag $TAG does not point at HEAD (provenance: the release must come from the tagged commit)"
    else
      ok "tag $TAG points at HEAD"
    fi
    if [ -n "$(git status --porcelain)" ]; then
      fail "working tree is dirty — release should be built from a clean tagged checkout"
    else
      ok "working tree clean"
    fi
  fi
fi

# --- APK metadata ------------------------------------------------------------
if [ -n "$APK" ]; then
  if [ ! -f "$APK" ]; then
    fail "APK not found: $APK"
  else
    ok "apk exists: $APK ($(wc -c < "$APK") bytes, sha256 $(sha256sum "$APK" | cut -d' ' -f1))"
    # Canonical asset name for the update channel.
    CANONICAL="Thrive-$GRADLE_VERSION-release.apk"
    if [ "$(basename "$APK")" != "$CANONICAL" ]; then
      fail "APK filename should be $CANONICAL for the update channel to pick it up"
    fi
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    AAPT=""
    if [ -n "$SDK" ]; then
      AAPT="$(ls "$SDK"/build-tools/*/aapt* 2>/dev/null | head -1)"
    fi
    if [ -z "$AAPT" ]; then
      say "  warn aapt not found (set ANDROID_HOME) — skipping package/version verification"
    else
      BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null)"
      PKG="$(printf '%s' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*versionCode='\([0-9]*\)' versionName='\([^']*\)'.*/\1|\2|\3/p" | head -1)"
      NAME="${PKG%%|*}"; REST="${PKG#*|}"; CODE="${REST%%|*}"; VNAME="${REST#*|}"
      if [ "$NAME" != "com.thrive.app" ]; then
        fail "APK package is '$NAME', expected com.thrive.app"
      else
        ok "apk package com.thrive.app"
      fi
      if [ "$CODE" != "$GRADLE_CODE" ] || [ "$VNAME" != "$GRADLE_VERSION" ]; then
        fail "APK is version $VNAME (code $CODE); Gradle says $GRADLE_VERSION (code $GRADLE_CODE)"
      else
        ok "apk version matches Gradle ($VNAME, code $CODE)"
      fi
    fi
  fi
fi

say ""
if [ "$FAILED" -eq 0 ]; then
  say "All release consistency checks passed."
  exit 0
else
  say "Release consistency checks FAILED."
  exit 1
fi
