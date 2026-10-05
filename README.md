# Moka Music Player v3.6 — Native DSP + Cleaner Limiter

v3.6 is based on the v3.5 phone log. The important lines were a real 48 kHz / stereo / 32-bit-float `AudioTrack` with a 96,000-frame (2 second) buffer, followed by the DSP writer starting after 73,728 frames of pre-roll. Playback still eventually underruns with `queue=0/64`, proving that the Kotlin FIR/convolver producer still cannot sustain realtime on-device.

## Native real-time DSP

The heavy audio path is now implemented in **native C++ through JNI**:

- ViPER/DDC SOS filtering runs natively.
- FIR minimum-phase EQ convolution runs natively.
- IRS convolution runs natively.
- ReplayGain/adaptive normalization runs natively.
- Limiting/post gain runs natively.
- The Kotlin engine remains as a compatibility fallback.
- Moka logs which engine is active: `DSP engine=Native C++` or `DSP engine=Kotlin`.
- A new `MokaDSP` throughput line reports how many times faster than realtime the DSP block processing is.

When FIR EQ and an IRS convolver are both enabled, v3.6 **combines their FIR responses once during setup** and runs one partitioned convolver instead of two serial FFT convolution stages. This preserves the linear filter response while substantially reducing transforms in the real-time loop.

The supplied `Heroin.irs` is a special case: each channel contains a single delayed non-zero tap. When it is used without FIR EQ, v3.6 detects that shape and uses a native delay/gain path instead of doing FFT convolution at all.

Native blocks are 4,096 frames at 44.1/48 kHz, 8,192 at 88.2/96 kHz, and 16,384 at 176.4/192 kHz. The existing two-second AudioTrack buffer and processed-PCM queue remain as scheduling protection, but they are no longer expected to hide a slower-than-realtime DSP engine.

## Distortion reduction

v3.5 used an instantaneous sample-by-sample peak limiter. With the supplied favorite EQ reaching roughly +11 dB and DDC/IRS potentially adding additional gain, that limiter could change gain abruptly around transients and sound rough even though it stopped digital clipping.

v3.6 replaces the native path limiter with a small **look-ahead peak window** and smooth release. It anticipates peaks inside each DSP block before applying attenuation, sanitizes non-finite samples, and keeps final float output inside the valid PCM range. The EQ/DDC/IRS tuning itself is not flattened or normalized away.

## Volume normalization retained

The v3.5 **Volume normalization** controls remain available under DSP → Output control. ReplayGain/R128 tags are preferred; Adaptive fallback remains optional for untagged tracks. Normalization still happens before DDC/EQ/convolution and the limiter remains the final safety stage.

## Native build requirement

v3.6 adds `app/src/main/cpp` and CMake integration. Android Studio/AGP can install the required NDK/CMake components when licenses are accepted, or they can be installed from **SDK Manager → SDK Tools → NDK (Side by side) + CMake**. The project builds `arm64-v8a` for modern phones and `x86_64` for emulators. Android's documentation notes that NDK and CMake are the required components for compiling native Android C/C++ code.

## Test log

After installing, verify these lines first:

```text
MokaAudio: DSP engine=Native C++, block=4096 frames
MokaNativeDSP: Native DSP created: 48000 Hz, 2 ch, ...
MokaDSP: Native C++ DSP throughput=...x realtime
```

Then capture a 30–60 second test:

```bash
adb logcat -c
# play music with DSP enabled
adb logcat -d | grep -iE "MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT" > moka-audio-v36.txt
```

The target is **DSP throughput comfortably above 1.0x realtime and no increasing AudioTrack underrun count**.

---

# Moka Music Player v3.5 — Faster DSP + Volume Normalization

v3.5 is based on the v3.4 device log from October 4, 2026. The log showed a 48 kHz / stereo / 32-bit-float `AudioTrack` with a full 48,000-frame buffer, but DSP still began underrunning a few seconds after startup. That means the remaining problem was sustained DSP throughput, not simply a small AudioTrack buffer.

## DSP performance changes

- real-time partitioned convolution now uses a cached **single-precision Float FFT** instead of Double FFT arrays
- normal-rate DSP blocks increased from 2,048 to **8,192 frames**
- 176.4/192 kHz+ uses **16,384-frame** blocks
- AudioTrack target buffer increased from ~1 second to **~2 seconds**
- DSP pre-roll increased to approximately **1.5 seconds** (or 75% of actual sink capacity)
- processed PCM queue increased from 32 to **64 packets**
- MediaCodec input/output polling waits reduced from 10 ms to **2 ms**
- repeated underrun logging is rate-limited so logcat itself cannot amplify an underrun storm
- underrun diagnostics now include processed-PCM queue depth
- pure local FLAC/WAV is no longer rejected just because Android's pre-playback route query fails to identify a USB-C headset; the created AudioTrack/mixer remains the source of truth

A desktop reference benchmark using the supplied `Heroin.vdc` + `Heroin.irs` + favorite FIR EQ showed the v3.5 DSP core materially faster than the v3.4 core. Device performance is still the authoritative test.

## Volume normalization

A new **Volume normalization** option is under DSP → Output control.

Behavior:

1. Moka reads `REPLAYGAIN_TRACK_GAIN` from FLAC Vorbis comments and ID3 `TXXX` frames when present.
2. Moka also accepts `R128_TRACK_GAIN`, translating its -23 LUFS reference to the traditional ReplayGain reference used by this normalizer.
3. Tagged tracks receive one fixed gain for the whole song.
4. For untagged tracks, **Adaptive fallback** uses a deliberately slow ~3-second RMS estimate with slow gain recovery. It is designed to correct differences between masters, not pump with individual beats.
5. Normalization is applied before DDC/EQ/convolution and the limiter remains last in the chain to catch peaks.
6. **Normalization preamp** provides ±6 dB user trim.

Normalization intentionally changes samples, so source bit-perfect status is false while it is active.

Existing cached libraries remain valid. A manual Rescan is only needed if you want Moka to discover newly-added ReplayGain/R128 tags in files that were already cached; Adaptive fallback works without a rescan.

---

# Moka Music Player v3.4 — Stable DSP Queue + Crash Fix

v3.4 is driven by device logs from the v3.3 build. The logs showed two separate problems: a Media3 state-transition crash in `HiFiHybridPlayer.getState()` and repeated AudioFlinger/AudioTrack underruns after DSP playback started.

## Crash fix

The hybrid player now normalizes the direct-engine state before giving it to Media3:

- `STATE_BUFFERING` is the only direct state allowed to report `isLoading=true`.
- direct `IDLE` / `ENDED` states force `isLoading=false`.
- stale fallback `playerError` is cleared while the direct engine owns playback.
- an expected `DirectUnsupported` route/codec handoff no longer publishes an intermediate `ERROR` state or gets written as a crash-like `last_error.txt` entry.
- if a DSP writer still exists during a direct->Media3 fallback, it is stopped before its `AudioTrack` is released.

This removes the invalid/transient state combination that was reaching `SimpleBasePlayer.State.Builder.build()` during asynchronous direct/fallback callbacks.

## DSP stutter fix: producer -> queue -> dedicated audio writer

v3.3 still decoded, processed, and wrote PCM from the same worker. The device log showed `AudioFlinger ... BUFFER TIMEOUT ... due to underrun` followed by `AudioTrack ... disabled due to previous underrun` repeatedly once DSP was enabled.

v3.4 separates those jobs:

```text
decoder / WAV reader
        ↓
DDC + multimodal EQ + IRS + limiter
        ↓
bounded processed-PCM queue (32 packets)
        ↓
dedicated MokaAudioWriter thread
        ↓
AudioTrack / USB headphones
```

The writer:

- requests Android `THREAD_PRIORITY_URGENT_AUDIO`
- pre-fills the hardware AudioTrack before starting playback
- targets roughly 0.5 seconds of initial processed PCM when the device buffer allows it
- falls back to starting as soon as the actual AudioTrack buffer is full
- uses blocking writes only after playback has started
- keeps up to 32 processed PCM packets queued behind AudioTrack to absorb decoder/DSP scheduling spikes
- flushes and re-primes safely after seeks
- stops before AudioTrack release on errors/fallbacks
- fails cleanly if end-of-stream drain cannot complete

## New diagnostics

Logcat now includes `MokaAudio` lines such as:

```text
AudioTrack created: rate=48000 channels=2 encoding=32-bit float buffer=... capacity=...
DSP writer started after ... frames; queue=.../32
AudioTrack underruns increased: 0 -> 1 (buffer=... frames)
```

For a focused test:

```bash
adb logcat -c
# play a track, enable DSP, let it run for ~20-30 seconds
adb logcat -d | grep -iE "MokaAudio|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT" > moka-audio-v34.txt
```

If `MokaAudio` reports zero underrun increases and the AudioFlinger timeout messages disappear, the starvation problem is resolved.

## Retained features

v3.4 keeps the persistent music-library cache, MediaSession/background controls, direct PCM/USB source matching, decoder-backed DSP for local formats, ViPER/DDC `.vdc`, IRS/WAV/FLAC convolution, JamesDSP-style 15-band multimodal EQ, limiter/post-gain, live DSP reload, and the supplied screenshot tuning preset.

---

Moka v3.3 is a performance/stability pass on the v3.2 persistent-library + live-DSP build. It targets audible stutter that occurred whenever DDC, multimodal EQ, or IRS convolution was enabled, especially on USB headphones/DACs.

## Why v3.2 could stutter

The direct WAV path read up to 256 KiB at a time. For a 24-bit / 48 kHz stereo WAV that is roughly 0.9 seconds of audio. Moka decoded and processed that whole batch synchronously, while the AudioTrack buffer was only about 0.25 seconds. Under DSP load the output buffer could drain before the next processed batch was ready.

The DSP hot path also allocated FFT accumulation arrays, channel split arrays, output arrays, and new fixed blocks continuously. Those allocations could trigger GC pauses on the playback thread.

## v3.3 changes

- requests Android `THREAD_PRIORITY_AUDIO` inside the direct/DSP playback worker
- increases the streaming AudioTrack buffer to roughly one second
- DSP WAV input is processed in 4096-frame chunks instead of 256 KiB bursts
- pre-rolls processed PCM before starting AudioTrack instead of starting an empty track
- re-primes DSP after seeks instead of immediately restarting an empty AudioTrack
- reuses FFT real/imaginary accumulation buffers
- reuses convolver left/right/output channel buffers
- reuses the fixed DSP input block instead of allocating a new block after every process call
- avoids the unconditional per-block copy in `DspChain`
- uses 2048-frame convolution/DSP blocks for normal sample rates and 4096 frames at 176.4/192 kHz+
- keeps the v3.2 persistent library cache, crash logs, live DSP reload, DDC/VDC, multimodal EQ, IRS convolution, limiter, and local decoder bridge

## Expected result

DDC-only and IIR EQ should be very lightweight. FIR minimum-phase EQ and long IRS kernels are still more CPU intensive, but playback now has enough buffering and far fewer real-time allocations to avoid the repeated starvation pattern seen in v3.2.

If stutter remains, capture `adb logcat -d | grep -iE "AudioTrack|underrun|Moka|MediaCodec"` plus `files/last_error.txt`; that will distinguish device/HAL USB underruns from DSP CPU starvation.

---

# Previous v3.2 feature notes

Moka is a local Android hi-fi player focused on FLAC/WAV and other local audio, USB/wired headphone quality, Media3 background/session controls, a direct PCM path, and JamesDSP-compatible DDC/EQ/convolution.

## New in v3.2

### Library cache: no full rescan every launch

Moka now writes a crash-safe persistent library index to app storage after a successful scan.

- The first v3.2 launch performs one normal scan.
- Later launches load the cached library immediately.
- Moka compares Android MediaStore's opaque version string; it only performs a full refresh when MediaStore reports that the media database changed, or when you press **Rescan**.
- The original music files stay read-only.
- If a refresh fails, the already-cached library stays available and the Library screen shows the error instead of wiping the list.

### DSP hot reload

DSP settings no longer wait for the next track.

Changes to the master switch, DDC, IRS convolver, limiter, EQ mode/interpolator, or EQ gains are observed by the playback service. Changes are debounced for roughly 350 ms so dragging sliders does not repeatedly tear down the audio device. The current item is rebuilt at approximately the same playback position.

When the DSP master switch is on, **Now Playing → Signal Path** should show **MOKA · HI-RES DSP** after the reload.

### DSP now covers MP3 / AAC / M4A / Opus and other local decodable audio

With DSP enabled, local non-WAV formats are routed through Moka's decoder-backed PCM bridge:

```text
local audio (FLAC / MP3 / AAC / M4A / Opus / etc.)
        ↓
Android MediaExtractor + MediaCodec decoder
        ↓
actual decoded PCM
        ↓
ViPER-DDC
        ↓
Multimodal EQ
        ↓
IRS convolver
        ↓
Limiter / post gain
        ↓
32-bit float AudioTrack
        ↓
USB / wired / Bluetooth / Android route
```

Media3 still owns the queue, MediaSession, notification, lock-screen controls, background playback, previous/next, shuffle, and repeat. Moka only swaps the renderer for the current local item when DSP/direct PCM is appropriate.

For pure FLAC playback with DSP off, the exact integer PCM checks from v3.0 remain in place.

### USB DSP bug addressed

Two behaviors in v3.1 could make the DSP switch appear ineffective:

1. DSP settings were only read when a new direct engine was prepared.
2. If a high-resolution FLAC decoder did not return the exact requested integer depth, Moka fell back to Media3, which bypassed v3.1's DSP chain.

v3.2 hot-reloads the current track and, when DSP is enabled, accepts the decoder's actual PCM output and processes that PCM instead of silently falling back around the DSP.

DSP playback intentionally does **not** claim source bit-perfect, because DDC/EQ/convolution modify samples. The DSP output remains 32-bit float.

### Crash hardening

- uncaught crashes are written to `files/last_crash.txt`
- recoverable playback/library errors are written to `files/last_error.txt`
- direct/DSP playback errors fall back instead of taking down the process where possible
- WAV reads are aligned to whole PCM frames before DSP conversion
- VDC files are capped at 2 MiB
- WAV/IRS inputs loaded fully into memory are capped at 64 MiB
- decoded FLAC impulse responses are capped at 2,000,000 frames per channel
- rapid DSP setting changes are debounced to reduce AudioTrack/MediaCodec churn

To retrieve diagnostics over ADB after a crash:

```bash
adb shell run-as com.mokamusic.player cat files/last_crash.txt
adb shell run-as com.mokamusic.player cat files/last_error.txt
```

## JamesDSP-compatible features retained

- ViPER/DDC `.vdc`
- IRS/WAV/FLAC convolver kernels
- 15-band multimodal EQ
- FIR minimum phase
- PCHIP or Modified Hiroshi Akima interpolation
- IIR 4th / 6th / 8th / 10th / 12th order modes
- peak limiter, release and post gain
- supplied screenshot tuning preset

Your supplied reference profile remains:

`+3.5, +5.5, +6.5, +9.5, +8.0, +6.5, +3.5, +2.5, +1.3, +5.0, +7.0, +9.0, +10.1, +11.0, +9.0 dB`

Limiter defaults from the screenshot remain **-12 dB / 120 ms / 0 dB post gain**.

The v3.2 DSP core was re-run against the supplied 24-bit/48 kHz WAV with `Heroin.vdc` and `Heroin.irs`: the processed output remained finite and the limiter peak was ~0.2511886 linear (about -12 dBFS).

## Apple Music

Apple provides the **Apple Music API** and **MusicKit for Android**. An Android app can authenticate an Apple Music subscriber, query the catalog and personal library, access playlists/recently played data, and use Apple's Android playback library.

That integration requires Apple developer/MusicKit credentials and user authorization. Moka should treat Apple Music playback as a separate protected streaming source; this project does not assume that Apple Music's protected stream can be extracted into Moka's custom PCM/DDC/IRS engine. Local files remain the path where Moka can guarantee its custom DSP chain.

## Build

- Android Gradle Plugin 9.4.0
- built-in Kotlin + Compose compiler plugin 2.2.10
- compileSdk 37
- targetSdk 36
- minSdk 26
- Media3 1.11.1

Open the folder containing `settings.gradle.kts` and `app/` in Android Studio.

If your working copy has the Gradle wrapper:

```bash
./gradlew clean
./gradlew :app:assembleDebug
```

## v3.6.1 native build timestamp fix

If Ninja reports `manifest 'build.ninja' still dirty after 100 tries`, the extracted source timestamps are newer than the local system time. v3.6.1 is repackaged with timezone-safe historical ZIP timestamps and pins CMake 3.22.1.

For an already-extracted project, run:

```bash
./fix-native-build.sh
```

The script normalizes source mtimes, removes `.cxx` intermediates, stops Gradle daemons, and performs a clean debug build.
