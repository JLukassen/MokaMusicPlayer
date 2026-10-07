#!/usr/bin/env bash
set -Eeuo pipefail

PKG="com.mokamusic.player"
OUT_ROOT="${OUT_ROOT:-$PWD/moka-beta1-usb-results-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT_ROOT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }
need adb
need awk
need grep
need sed
need tar

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if (( ${#DEVICES[@]} < 1 )); then
  cat >&2 <<'MSG'
No ADB devices are connected.
For USB-DAC testing, Wireless debugging is recommended so the phone's USB-C port stays free.
Connect with `adb pair` / `adb connect`, then rerun this script.
MSG
  exit 1
fi

print_devices() {
  echo "Connected ADB devices:"
  local i serial model
  for i in "${!DEVICES[@]}"; do
    serial="${DEVICES[$i]}"
    model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    printf '  [%d] %s  %s\n' "$((i+1))" "$serial" "${model:-unknown-model}"
  done
}

choose_device() {
  local label="$1" default_index="$2" answer idx
  print_devices >&2
  read -r -p "$label device number [${default_index}]: " answer
  answer="${answer:-$default_index}"
  [[ "$answer" =~ ^[0-9]+$ ]] || { echo "Invalid device number" >&2; exit 1; }
  idx=$((answer-1))
  (( idx >= 0 && idx < ${#DEVICES[@]} )) || { echo "Device number out of range" >&2; exit 1; }
  printf '%s' "${DEVICES[$idx]}"
}

PHONE1="${1:-}"
PHONE2="${2:-}"
if [[ -z "$PHONE1" ]]; then PHONE1="$(choose_device 'Phone 1' 1)"; fi
if [[ -z "$PHONE2" ]]; then
  if (( ${#DEVICES[@]} >= 2 )); then
    PHONE2="$(choose_device 'Phone 2' 2)"
  else
    echo "Only one ADB target is connected. Connect the second phone over Wireless debugging, or pass its adb serial/IP:port as argument 2." >&2
    exit 1
  fi
fi

adb_ok() { adb -s "$1" get-state 2>/dev/null | grep -qx device; }
for serial in "$PHONE1" "$PHONE2"; do
  adb_ok "$serial" || { echo "ADB device unavailable: $serial" >&2; exit 1; }
done

safe_name() { printf '%s' "$1" | sed -E 's/[^A-Za-z0-9._-]+/_/g'; }
marker() {
  local serial="$1" msg="$2"
  adb -s "$serial" shell log -t MokaUsbTest "$msg" >/dev/null 2>&1 || true
}

capture_static() {
  local serial="$1" dir="$2"
  {
    echo "timestamp=$(date --iso-8601=seconds 2>/dev/null || date)"
    echo "serial=$serial"
    echo "manufacturer=$(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')"
    echo "model=$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    echo "android=$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
    echo "sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    adb -s "$serial" shell dumpsys package "$PKG" 2>/dev/null | grep -E 'versionName=|versionCode=' | head -4 || true
  } > "$dir/device.txt"
  adb -s "$serial" shell dumpsys audio > "$dir/audio-before.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys usb > "$dir/usb-before.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys media.audio_flinger > "$dir/audioflinger-before.txt" 2>&1 || true
}

phase_prompt() {
  local serial="$1" code="$2" instruction="$3"
  echo
  echo "[$code] $instruction"
  read -r -p "Press Enter when this phase is configured and has played for ~15-20 seconds... " _
  marker "$serial" "PHASE_END $code"
}

summarize() {
  local dir="$1" log="$dir/logcat-full.txt" focused="$dir/logcat-focused.txt"
  grep -E 'Moka(Audio|DSP|Hybrid|UsbTest|Library|Metadata)|AudioFlinger|AudioTrack|MediaFocusControl|AndroidRuntime|FATAL EXCEPTION|ANR in com\.mokamusic\.player|am_crash|am_anr' "$log" > "$focused" || true
  {
    echo "Moka Beta 1 USB session summary"
    echo "================================"
    cat "$dir/device.txt"
    echo
    echo "Moka AudioTrack creations: $(grep -c 'MokaAudio: AudioTrack created:' "$log" || true)"
    echo "DSP hot reload requests: $(grep -c 'DSP hot reload requested' "$log" || true)"
    echo "DSP hot reload prepared: $(grep -c 'DSP hot reload prepared' "$log" || true)"
    echo "DSP hot reload applied: $(grep -c 'DSP hot reload applied' "$log" || true)"
    echo "Audio-focus requests: $(grep -c 'requestAudioFocus()' "$log" || true)"
    echo "Moka underrun-growth events: $(grep -c 'AudioTrack underruns increased:' "$log" || true)"
    echo "AudioFlinger BUFFER TIMEOUT events: $(grep -c 'AudioFlinger.*BUFFER TIMEOUT' "$log" || true)"
    echo "AudioTrack disabled-after-underrun events: $(grep -c 'disabled due to previous underrun' "$log" || true)"
    echo "Moka fatal exceptions: $(grep -c -E 'FATAL EXCEPTION.*|AndroidRuntime.*com\.mokamusic\.player' "$log" || true)"
    echo
    echo "USB / output route evidence"
    grep -E 'MokaAudio: (USB candidate|USB safe|Output route:)' "$log" || true
    echo
    echo "DSP lifecycle evidence"
    grep -E 'Moka(Hybrid|Audio|DSP).*(DSP preference change scheduled|DSP hot reload (requested|prepared|applied)|DSP engine=|DSP writer started|throughput=|underruns increased)' "$log" || true
    echo
    echo "Phase markers"
    grep 'MokaUsbTest' "$log" || true
  } > "$dir/SUMMARY.txt"
}

run_session() {
  local serial="$1" phone_label="$2" usb_number="$3"
  local model usb_label dir pid
  model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
  echo
  echo "============================================================"
  echo "$phone_label ($model) — USB device $usb_number"
  echo "============================================================"
  read -r -p "Enter a short USB device label (example: Apple-dongle or DAC-1): " usb_label
  usb_label="${usb_label:-USB-$usb_number}"
  dir="$OUT_ROOT/$(safe_name "$phone_label")-usb${usb_number}-$(safe_name "$usb_label")"
  mkdir -p "$dir"

  echo "USB label: $usb_label" > "$dir/session.txt"
  echo "Phone label: $phone_label" >> "$dir/session.txt"
  echo "ADB serial: $serial" >> "$dir/session.txt"

  echo
  echo "Connect '$usb_label' to $model. Keep ADB connected (Wireless debugging is recommended)."
  echo "Force-stop/reopen Moka if you want a clean run, then load the SAME reference track used for all four sessions."
  read -r -p "Press Enter when the DAC/headset is connected and Moka is ready... " _

  capture_static "$serial" "$dir"
  adb -s "$serial" logcat -c || true
  adb -s "$serial" logcat -v threadtime > "$dir/logcat-full.txt" 2>&1 &
  pid=$!
  trap 'kill '"$pid"' 2>/dev/null || true' RETURN
  sleep 1
  marker "$serial" "BEGIN phone=$phone_label usb=$usb_label"

  echo
  echo "Use one reference track for every phase. A 24/32-bit 192 kHz FLAC/WAV is ideal for stressing the DSP path;"
  echo "if that DAC does not support it, use the highest common source rate and note it in session-notes.txt."
  touch "$dir/session-notes.txt"

  if [[ "$model" == "Pixel 8a" ]]; then
    marker "$serial" "PHASE_BEGIN PIXEL_USB_GUARD"
    echo "[PIXEL_USB_GUARD] DSP must remain ON. Do NOT test DSP-off listening on Pixel 8a USB."
    echo "Confirm the DSP master control is guarded and your saved DSP profile is active. Start at a conservative volume."
    read -r -p "Press Enter after confirming the guard and normal volume control... " _
    marker "$serial" "PHASE_END PIXEL_USB_GUARD"
  else
    marker "$serial" "PHASE_BEGIN PURE_DSP_OFF"
    echo "[PURE_DSP_OFF] DSP master OFF. Unaffected devices may use direct / exact bit-perfect playback where supported."
    echo "SAFETY: Start conservatively and confirm expected device volume behavior before listening normally."
    read -r -p "Press Enter after ~15-20 seconds at a safe level... " _
    marker "$serial" "PHASE_END PURE_DSP_OFF"
  fi

  marker "$serial" "PHASE_BEGIN EQ_ONLY"
  echo "[EQ_ONLY] Turn DSP ON. Enable EQ only; disable VDC and Convolver. Keep playback running."
  read -r -p "Press Enter after ~15-20 seconds... " _
  marker "$serial" "PHASE_END EQ_ONLY"

  marker "$serial" "PHASE_BEGIN VDC_ONLY"
  echo "[VDC_ONLY] Keep DSP ON. Disable EQ/Convolver and enable your VDC profile only."
  read -r -p "Press Enter after ~15-20 seconds... " _
  marker "$serial" "PHASE_END VDC_ONLY"

  marker "$serial" "PHASE_BEGIN CONVOLVER_ONLY"
  echo "[CONVOLVER_ONLY] Keep DSP ON. Disable EQ/VDC and enable your IRS/Convolver only."
  read -r -p "Press Enter after ~15-20 seconds... " _
  marker "$serial" "PHASE_END CONVOLVER_ONLY"

  marker "$serial" "PHASE_BEGIN ALL_DSP"
  echo "[ALL_DSP] Enable EQ + VDC + Convolver together. Keep playback running."
  read -r -p "Press Enter after ~20-30 seconds... " _
  marker "$serial" "PHASE_END ALL_DSP"

  marker "$serial" "PHASE_BEGIN HOT_TOGGLE"
  if [[ "$model" == "Pixel 8a" ]]; then
    echo "[HOT_TOGGLE] Pixel USB guard: leave DSP master ON. Change EQ/VDC/Convolver individually several times."
    echo "We expect in-place reloads and no repeated AudioTrack/audio-focus recreation."
  else
    echo "[HOT_TOGGLE] Flip DSP master OFF/ON three times, waiting ~2 seconds, then change EQ/VDC/Convolver individually."
    echo "We expect in-place reloads once the float path is active, not repeated AudioTrack/audio-focus recreation."
  fi
  read -r -p "Press Enter when complete... " _
  marker "$serial" "PHASE_END HOT_TOGGLE"

  marker "$serial" "END phone=$phone_label usb=$usb_label"
  sleep 1
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  trap - RETURN

  adb -s "$serial" shell dumpsys audio > "$dir/audio-after.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys usb > "$dir/usb-after.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys media.audio_flinger > "$dir/audioflinger-after.txt" 2>&1 || true
  summarize "$dir"
  echo
  cat "$dir/SUMMARY.txt"
  echo
  echo "Saved session: $dir"
}

cat <<'MSG'
Moka Beta 1 USB 2×2 matrix

Pass target per session:
  • Test build must report versionCode 21 or newer.
  • Pixel 8a USB: DSP guard must remain active; no DSP-off listening test; no BIT_PERFECT mixer selection.
  • Other devices: direct / exact bit-perfect playback remains allowed where supported.
  • No Moka crash / ANR.
  • No AudioFlinger BUFFER TIMEOUT during steady DSP playback.
  • Moka's AudioTrack underrun counter should not grow during steady-state playback.
  • The first DSP OFF -> ON transition may create a float AudioTrack once.
  • EQ/VDC/Convolver changes after that should log `DSP hot reload applied` without repeatedly creating AudioTracks or reacquiring audio focus.
  • `Output route:` should remain on the intended USB device.

For USB-C DAC testing, Wireless debugging is strongly recommended so ADB remains connected while the USB audio device occupies the port.
MSG

run_session "$PHONE1" "phone1" 1
run_session "$PHONE1" "phone1" 2
run_session "$PHONE2" "phone2" 1
run_session "$PHONE2" "phone2" 2

tar -C "$(dirname "$OUT_ROOT")" -czf "$OUT_ROOT.tar.gz" "$(basename "$OUT_ROOT")"
echo
echo "All four sessions complete."
echo "Results folder: $OUT_ROOT"
echo "Upload this archive back to ChatGPT: $OUT_ROOT.tar.gz"
