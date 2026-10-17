#!/usr/bin/env bash
# Build a locally signed Moka APK for direct distribution or Uptodown.
# Never publish an unsigned or debug-signed APK. Do not upload signing keys to CI.
set -euo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

EXPECTED_PACKAGE="com.mokamusic.player"
EXPECTED_CODE="38"
EXPECTED_VERSION="4.0.0-beta06"

[[ -f keystore.properties ]] || die "Local keystore.properties is missing. See docs/UPTODOWN_RELEASE.md."
[[ ! -L keystore.properties ]] || die "Use a regular local file for keystore.properties, not a symlink."
[[ -x ./gradlew ]] || die "Gradle wrapper not executable: chmod +x gradlew"
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || die "Set ANDROID_HOME to your Android SDK."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"

# Prefer known Android build tools; otherwise select the most recent installed version.
if [[ -x "$SDK/build-tools/37.0.0/apksigner" ]]; then
  TOOLS="$SDK/build-tools/37.0.0"
else
  TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null | sort -V | tail -n 1)"
fi
[[ -n "$TOOLS" && -x "$TOOLS/apksigner" && -x "$TOOLS/aapt" ]] ||
  die "Android SDK build tools with apksigner and aapt are required."
command -v java >/dev/null || die "Java is missing. Set JAVA_HOME to a full supported JDK."
command -v keytool >/dev/null || die "JDK keytool is not available."

# Gradle will use the locally configured release signing key. Do not pass passwords
# to command-line arguments or print keystore.properties into logs.
./gradlew :app:testDebugUnitTest :app:assembleRelease --no-build-cache --stacktrace

APK="app/build/outputs/apk/release/app-release.apk"
[[ -s "$APK" ]] || die "No release APK at $APK (verify release signing setup)."

BADGING="$("$TOOLS/aapt" dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -q "package: name='$EXPECTED_PACKAGE'.*versionCode='$EXPECTED_CODE'.*versionName='$EXPECTED_VERSION'" ||
  die "Package ID or version doesn't match $EXPECTED_PACKAGE $EXPECTED_VERSION ($EXPECTED_CODE). Review script before every version bump."

SIGNATURE_LOG="$(mktemp)"
trap 'rm -f "$SIGNATURE_LOG"' EXIT
"$TOOLS/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$APK" > "$SIGNATURE_LOG" ||
  die "APK signature verification failed. Do not distribute this file."

if grep -Eiq 'Signer #[0-9]+ certificate DN:.*(CN=Android Debug|OU=Android)' "$SIGNATURE_LOG"; then
  die "APK looks debug-signed. Do not distribute it; configure your private release keystore."
fi

# This is a useful packaging check, not a substitute for real device testing.
if [[ -x "$TOOLS/zipalign" ]]; then
  "$TOOLS/zipalign" -c -P 16 -v 4 "$APK" > /dev/null ||
    die "APK alignment verification failed. Check native library packaging and 16 KB alignment."
fi

DEST="dist/uptodown"
mkdir -p "$DEST"
NAME="Moka-Music-Player-4.0.0-beta06-android-release.apk"
cp "$APK" "$DEST/$NAME"
(cd "$DEST" && sha256sum "$NAME" > SHA256SUMS.txt)
sed -n '/Signer #1 certificate SHA-256 digest:/p' "$SIGNATURE_LOG"

printf '\nSIGNED APK: %s/%s\n' "$DEST" "$NAME"
printf 'SHA256:    %s/SHA256SUMS.txt\n' "$DEST"
printf '\nUpload the APK to Uptodown Developers Console only AFTER release testing and licensing review.\n'
printf 'Keep the SAME release signing key for every Uptodown/direct APK update.\n'
