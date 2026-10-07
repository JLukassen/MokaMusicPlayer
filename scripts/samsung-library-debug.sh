#!/usr/bin/env bash
set -Eeuo pipefail

SERIAL="${1:-RFCY21RZR3P}"
PKG="com.mokamusic.player"
OUT="${2:-moka-library-debug-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"

adb -s "$SERIAL" get-state >/dev/null
echo "serial=$SERIAL" | tee "$OUT/device.txt"
adb -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d "\r" | sed "s/^/manufacturer=/" | tee -a "$OUT/device.txt"
adb -s "$SERIAL" shell getprop ro.product.model | tr -d "\r" | sed "s/^/model=/" | tee -a "$OUT/device.txt"
adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d "\r" | sed "s/^/android=/" | tee -a "$OUT/device.txt"
adb -s "$SERIAL" shell dumpsys package "$PKG" | grep -m2 -E "versionName=|versionCode=" | tee -a "$OUT/device.txt" || true

adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

echo
echo "Moka launched on $SERIAL."
echo "Start/continue the library refresh, then reproduce the Samsung problem."
echo "When the library stops/restarts or after reopening Moka, press Enter here."
read -r _

adb -s "$SERIAL" logcat -d -v threadtime > "$OUT/logcat-full.txt"
grep -iE "MokaLibrary|Library scan|AndroidRuntime|FATAL EXCEPTION|ANR in|am_crash|am_anr|lowmemory|lmkd|OutOfMemory|SIGSEGV|SIGABRT" \
  "$OUT/logcat-full.txt" > "$OUT/logcat-library-focused.txt" || true
adb -s "$SERIAL" shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys activity processes > "$OUT/processes.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys package "$PKG" > "$OUT/package.txt" 2>&1 || true

echo
echo "Focused findings:"
cat "$OUT/logcat-library-focused.txt"
echo
echo "Saved: $OUT"
