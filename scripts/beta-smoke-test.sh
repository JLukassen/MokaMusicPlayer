#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v adb >/dev/null || { echo 'adb is required.' >&2; exit 1; }
[[ -x ./gradlew ]] || { echo 'Gradle wrapper missing in this checkout.' >&2; exit 1; }

DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
if [[ "$DEVICE_COUNT" -lt 1 ]]; then
  echo 'No authorized Android device found with adb.' >&2
  exit 1
fi

printf '\n== Moka Beta build/test ==\n'
./fix-native-build.sh
./gradlew clean :app:testDebugUnitTest :app:assembleRelease --no-build-cache

APK=$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' ! -name '*unsigned*' | head -1 || true)
if [[ -z "$APK" ]]; then
  echo 'No signed release APK found. Check keystore.properties/release signing.' >&2
  find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' -print 2>/dev/null || true
  exit 1
fi

echo "APK: $APK"

APKSIGNER=""
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  APKSIGNER=$(find "$ANDROID_SDK_ROOT/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1 || true)
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1 || true)
else
  APKSIGNER=$(find "$HOME/Android/Sdk/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1 || true)
fi

if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --verbose --print-certs "$APK"
else
  echo 'apksigner not found; skipping explicit signature verification.'
fi

adb install -r "$APK"
adb shell am force-stop com.mokamusic.player
adb logcat -c
adb shell monkey -p com.mokamusic.player -c android.intent.category.LAUNCHER 1 >/dev/null

printf '\nInstalled package:\n'
adb shell dumpsys package com.mokamusic.player | grep -m1 -E 'versionName=|versionCode=' || true

cat <<'MSG'

Moka is installed and launched. Test these before calling the build broadly ready:
  1. FLAC/WAV pure playback with DSP off
  2. FIR, each IIR order, VDC and IRS separately
  3. combined VDC + EQ + IRS + limiter
  4. USB route changes and source-rate reporting
  5. Korean/Japanese/Chinese/punctuation library search
  6. album/artist covers and MusicBrainz enrichment
  7. Track and Album normalization after offline analysis
  8. screen-off/background/notification controls

After a playback test, capture the relevant audio log with:
  adb logcat -d | grep -iE 'MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT' > moka-beta-audio.txt
MSG
