#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT="$ROOT/build/release-audit"
mkdir -p "$OUT"

echo "Moka release dependency/license audit"
echo "Output: $OUT"

./gradlew :app:dependencies --configuration releaseRuntimeClasspath   > "$OUT/runtime-dependencies.txt"

./gradlew :app:dependencies --configuration debugRuntimeClasspath   > "$OUT/debug-runtime-dependencies.txt"

{
  echo "Generated: $(date --iso-8601=seconds 2>/dev/null || date)"
  echo
  echo "Declared dependency lines from app/build.gradle.kts:"
  grep -nE 'implementation\(|debugImplementation\(|testImplementation\(|androidTestImplementation\(' app/build.gradle.kts || true
  echo
  echo "DSP provenance keyword scan:"
  grep -RniE 'JamesDSP|DSPManager|james34602|JDSP|ViPER|copyright|licensed under'     app/src/main app/src/test THIRD_PARTY_NOTICES.md docs/release 2>/dev/null || true
} > "$OUT/source-provenance-scan.txt"

cat <<MSG

Generated:
  $OUT/runtime-dependencies.txt
  $OUT/debug-runtime-dependencies.txt
  $OUT/source-provenance-scan.txt

Manual steps still required:
  1. Review every transitive releaseRuntimeClasspath artifact and its license/NOTICE.
  2. Complete docs/release/DSP_SOURCE_PROVENANCE.md.
  3. Update THIRD_PARTY_NOTICES.md with any required distributed notices.
MSG
