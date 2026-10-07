#!/usr/bin/env bash
set -euo pipefail

# Build, verify and publish a signed Moka APK to GitHub Releases.
# Usage:
#   ./scripts/publish-release.sh [v4.0.0-beta01]
#
# Requirements:
#   - gh authenticated with write access to the repository
#   - keystore.properties configured at the repository root
#   - Android SDK build-tools containing apksigner
#   - a clean Git working tree

TAG="${1:-v4.0.0-beta01}"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$ROOT" ]]; then
  echo "Run this inside the Moka Git repository." >&2
  exit 1
fi
cd "$ROOT"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes before publishing." >&2
  git status --short
  exit 1
fi

if [[ ! -f keystore.properties ]]; then
  echo "Missing keystore.properties. Release signing is not configured." >&2
  exit 1
fi

command -v gh >/dev/null || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
gh auth status -h github.com >/dev/null

if [[ ! -x ./gradlew ]]; then
  echo "Gradle wrapper ./gradlew is missing or not executable." >&2
  exit 1
fi

VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [[ -n "$VERSION_NAME" ]]; then
  echo "Moka versionName: $VERSION_NAME"
fi

echo "Building signed release APK..."
./gradlew clean :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ -f "$UNSIGNED" ]]; then
    echo "Gradle produced an unsigned APK. Check keystore.properties and app/build.gradle.kts." >&2
  else
    echo "Release APK not found under app/build/outputs/apk/release/." >&2
  fi
  exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
APKSIGNER="$(find "$SDK_ROOT/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1)"
if [[ -z "$APKSIGNER" ]]; then
  echo "apksigner was not found under $SDK_ROOT/build-tools" >&2
  exit 1
fi

echo "Verifying APK signature..."
"$APKSIGNER" verify --verbose --print-certs "$APK"

mkdir -p dist
SAFE_TAG="${TAG#v}"
RELEASE_APK="dist/Moka-Music-Player-${SAFE_TAG}.apk"
cp "$APK" "$RELEASE_APK"

# Push the current branch first. This should be a fast-forward because the tree is clean.
BRANCH="$(git branch --show-current)"
if [[ -n "$BRANCH" ]]; then
  git push origin "$BRANCH"
fi

if ! git rev-parse "$TAG" >/dev/null 2>&1; then
  git tag -a "$TAG" -m "Moka Music Player $TAG"
fi

git push origin "$TAG"

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "Release $TAG already exists; replacing APK asset if necessary..."
  gh release upload "$TAG" "$RELEASE_APK#Moka Music Player Android APK" --clobber
else
  echo "Creating GitHub prerelease $TAG..."
  NOTES_FILE="RELEASE_NOTES_v4.0.0-beta01.md"
  if [[ "$TAG" == "v4.0.0-beta01" && -f "$NOTES_FILE" ]]; then
    gh release create "$TAG" \
      "$RELEASE_APK#Moka Music Player Android APK" \
      --prerelease \
      --title "Moka Music Player 4.0 Beta 1" \
      --notes-file "$NOTES_FILE"
  else
    gh release create "$TAG" \
      "$RELEASE_APK#Moka Music Player Android APK" \
      --prerelease \
      --title "Moka Music Player $TAG" \
      --generate-notes
  fi
fi

echo
echo "Published:"
gh release view "$TAG" --json url -q .url
