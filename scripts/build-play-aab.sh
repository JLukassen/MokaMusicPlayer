#!/usr/bin/env bash
# Build Moka's Play Store upload bundle using a LOCAL, user-controlled upload key.
# Never run this from CI with an ephemeral key; never commit keystore.properties.
set -euo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v keytool >/dev/null || fail "JDK keytool not found. Install a JDK (17 or compatible Gradle JDK)."
command -v jarsigner >/dev/null || fail "JDK jarsigner not found."
[[ -x ./gradlew ]] || fail "gradlew is not executable. Run chmod +x gradlew."

CONFIG="$ROOT/keystore.properties"
[[ -f "$CONFIG" ]] || fail "keystore.properties missing. Follow docs/PLAY_STORE_LAUNCH.md."
[[ ! -L "$CONFIG" ]] || fail "For safety, use a regular local keystore.properties file, not a symbolic link."

get_property() {
  local key="$1"
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$CONFIG" | tail -n 1 | tr -d '\r'
}

STORE_FILE="$(get_property storeFile)"
STORE_PASSWORD="$(get_property storePassword)"
KEY_ALIAS="$(get_property keyAlias)"
KEY_PASSWORD="$(get_property keyPassword)"

[[ -n "$STORE_FILE" && -n "$STORE_PASSWORD" && -n "$KEY_ALIAS" && -n "$KEY_PASSWORD" ]] ||
  fail "keystore.properties must contain storeFile, storePassword, keyAlias, and keyPassword."

# Gradle file(...) resolves absolute paths directly; a relative path is relative to app/.
if [[ "$STORE_FILE" = /* ]]; then
  KEYSTORE_PATH="$STORE_FILE"
else
  KEYSTORE_PATH="$ROOT/app/$STORE_FILE"
fi
[[ -s "$KEYSTORE_PATH" ]] || fail "Upload keystore file does not exist: $KEYSTORE_PATH"

# Do not let a debug certificate or an unsigned bundle go to Google Play.
./gradlew :app:testDebugUnitTest :app:bundleRelease --no-build-cache

BUNDLE="$ROOT/app/build/outputs/bundle/release/app-release.aab"
[[ -s "$BUNDLE" ]] || fail "Signed release bundle not produced at expected path."

VERIFY_LOG="$(mktemp)"
trap 'rm -f "$VERIFY_LOG"' EXIT
if ! jarsigner -verify -verbose "$BUNDLE" > "$VERIFY_LOG" 2>&1; then
  fail "The App Bundle signature did not verify. Review the signing setup."
fi
grep -q 'jar verified' "$VERIFY_LOG" ||
  fail "jarsigner did not report a verified signed bundle."

DEST="$ROOT/dist/play"
mkdir -p "$DEST"
cp "$BUNDLE" "$DEST/Moka-Music-Player-Play-upload.aab"
(cd "$DEST" && sha256sum Moka-Music-Player-Play-upload.aab > SHA256SUMS.txt)
printf '\nSigned Play upload bundle: %s\n' "$DEST/Moka-Music-Player-Play-upload.aab"
printf 'SHA-256 manifest: %s\n' "$DEST/SHA256SUMS.txt"
printf 'This file is for Play Console Internal testing. Keep the upload keystore backed up.\n'
