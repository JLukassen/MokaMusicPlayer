#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
echo "Moka v4.0.0-beta05 preflight"
grep -q 'versionCode = 26' app/build.gradle.kts
grep -q 'versionName = "4.0.0-beta05"' app/build.gradle.kts
grep -q 'readWavSeekable' app/src/main/java/com/mokamusic/player/data/EmbeddedMetadataReader.kt
grep -q 'shouldUseNativeMetadataFallback' app/src/main/java/com/mokamusic/player/data/EmbeddedMetadataReader.kt
grep -q 'wasRecentlyMissing' app/src/main/java/com/mokamusic/player/data/ArtworkLoader.kt
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-build-cache --stacktrace
