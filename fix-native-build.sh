#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

echo "Normalizing project source timestamps..."
find app/src -type f -exec touch {} +
touch app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties

echo "Removing stale native/Gradle intermediates..."
rm -rf app/.cxx app/build/intermediates/cxx app/build/.cxx
./gradlew --stop >/dev/null 2>&1 || true

echo "Rebuilding native + Android project..."
./gradlew clean :app:assembleDebug --no-build-cache
