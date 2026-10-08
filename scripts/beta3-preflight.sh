#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
echo "Moka Beta 3 preflight"
grep -q 'versionCode = 24' app/build.gradle.kts && echo 'PASS  versionCode 24' || { echo 'FAIL  versionCode'; fail=1; }
grep -q 'versionName = "4.0.0-beta03"' app/build.gradle.kts && echo 'PASS  versionName 4.0.0-beta03' || { echo 'FAIL  versionName'; fail=1; }
grep -q 'analyzeWavDirect' app/src/main/java/com/mokamusic/player/audio/Bs1770LoudnessAnalyzer.kt && echo 'PASS  direct WAV loudness path' || { echo 'FAIL  direct WAV path'; fail=1; }
grep -q 'processPcm' app/src/main/java/com/mokamusic/player/audio/Bs1770LoudnessAnalyzer.kt && echo 'PASS  zero-copy PCM accumulator path' || { echo 'FAIL  PCM accumulator path'; fail=1; }
grep -q 'NativeLoudnessBridge' app/src/main/java/com/mokamusic/player/audio/Bs1770LoudnessAnalyzer.kt && echo 'PASS  native loudness hot loop' || { echo 'FAIL  native loudness path'; fail=1; }
grep -q 'moka_loudness.cpp' app/src/main/cpp/CMakeLists.txt && echo 'PASS  native loudness CMake' || { echo 'FAIL  native loudness CMake'; fail=1; }
grep -q 'Extractor raw PCM' app/src/main/java/com/mokamusic/player/audio/Bs1770LoudnessAnalyzer.kt && echo 'PASS  raw PCM extractor bypass' || { echo 'FAIL  raw extractor bypass'; fail=1; }
grep -q 'LOUDNESS_CHECKPOINT_TRACKS = 1' app/src/main/java/com/mokamusic/player/MokaViewModel.kt && echo 'PASS  per-track loudness checkpoints' || { echo 'FAIL  loudness checkpoints'; fail=1; }
grep -q 'LOUDNESS_SESSION_ACTIVE' app/src/main/java/com/mokamusic/player/MokaViewModel.kt && echo 'PASS  loudness session resume' || { echo 'FAIL  loudness session resume'; fail=1; }
grep -q 'sourceAlbumNormalizationGainDb' app/src/main/java/com/mokamusic/player/MokaViewModel.kt && echo 'PASS  trusted-tag skip gate' || { echo 'FAIL  trusted-tag skip gate'; fail=1; }
grep -q 'MokaLoudness' app/src/main/java/com/mokamusic/player/MokaViewModel.kt && echo 'PASS  per-track timing telemetry' || { echo 'FAIL  timing telemetry'; fail=1; }

if grep -R -n -E '<<<<<<<|>>>>>>>|^=======$' --exclude='beta3-preflight.sh' app/src/main README.md CHANGELOG.md docs scripts 2>/dev/null; then
  echo 'FAIL  merge-conflict markers found'; fail=1
else
  echo 'PASS  no merge-conflict markers'
fi

if [[ -x ./gradlew ]]; then
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-build-cache
else
  echo 'INFO  gradlew missing; run Gradle verification from the full project.'
fi
exit "$fail"
