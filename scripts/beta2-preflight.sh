#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
echo "Moka Beta 2 preflight"
grep -q 'versionCode = 23' app/build.gradle.kts && echo 'PASS  versionCode 23' || { echo 'FAIL  versionCode'; fail=1; }
grep -q 'versionName = "4.0.0-beta02"' app/build.gradle.kts && echo 'PASS  versionName 4.0.0-beta02' || { echo 'FAIL  versionName'; fail=1; }
grep -q 'DATE_MODIFIED' app/src/main/java/com/mokamusic/player/data/MusicLibraryRepository.kt && echo 'PASS  incremental fingerprint' || { echo 'FAIL  incremental fingerprint'; fail=1; }
grep -q 'ContentObserver' app/src/main/java/com/mokamusic/player/MokaViewModel.kt && echo 'PASS  MediaStore observer' || { echo 'FAIL  MediaStore observer'; fail=1; }
grep -q 'PREFER_ENGLISH_LATIN' app/src/main/java/com/mokamusic/player/metadata/MetadataNamePreference.kt && echo 'PASS  metadata preference' || { echo 'FAIL  metadata preference'; fail=1; }

if grep -R -n -E '<<<<<<<|>>>>>>>|^=======$' --exclude='beta2-preflight.sh' app/src/main README.md CHANGELOG.md docs scripts 2>/dev/null; then
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
