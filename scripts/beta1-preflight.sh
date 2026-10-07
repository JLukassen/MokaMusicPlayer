#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "[preflight] checking Kotlin primitive-array fallbacks"
if grep -R -n -F 'dsp?.flush().orEmpty()' app/src/main/java >/dev/null 2>&1; then
  echo "ERROR: unsupported nullable FloatArray.orEmpty() remains in DirectPcmEngine" >&2
  exit 1
fi

fail=0
check() { if "$@"; then printf 'PASS  %s\n' "$*"; else printf 'FAIL  %s\n' "$*"; fail=1; fi; }

echo "Moka Beta 1 RC preflight"
echo "Project: $ROOT"

grep -q 'versionCode = 22' app/build.gradle.kts && echo 'PASS  versionCode 22' || { echo 'FAIL  versionCode is not 22'; fail=1; }
grep -q 'UsbDspSafetyPolicy' app/src/main/java/com/mokamusic/player/audio/DirectPcmEngine.kt && grep -q 'MIXER_BEHAVIOR_BIT_PERFECT' app/src/main/java/com/mokamusic/player/audio/DirectPcmEngine.kt && echo 'PASS  targeted Pixel USB DSP guard + direct bit-perfect routing present' || { echo 'FAIL  Beta 1 USB routing policy missing'; fail=1; }
grep -q 'versionName = "4.0.0-beta01"' app/build.gradle.kts && echo 'PASS  versionName 4.0.0-beta01' || { echo 'FAIL  versionName mismatch'; fail=1; }

if grep -R -n -E '<<<<<<<|>>>>>>>|^=======$' --exclude='beta1-preflight.sh' app/src/main README.md CHANGELOG.md BETA_CHECKLIST.md docs scripts 2>/dev/null; then
  echo 'FAIL  merge-conflict markers found'
  fail=1
else
  echo 'PASS  no merge-conflict markers'
fi

bash -n scripts/beta1-usb-matrix.sh && echo 'PASS  USB matrix script syntax'

if [[ -x ./gradlew ]]; then
  echo 'Running Android unit tests + debug assembly...'
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-build-cache
else
  echo 'INFO  gradlew is not present in this source bundle; run Gradle verification from the full local Android project after applying the overlay.'
fi

exit "$fail"
