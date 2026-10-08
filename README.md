# Moka Music Player

**Moka Music Player** is a local-first high-fidelity Android music player built around transparent audio routing, source-rate-aware playback and a native real-time DSP engine.

> **Current development build: 4.0.0-beta04 — Beta 4**
>
> This build intentionally removes subscription streaming from the beta scope. Moka controls the local playback/DSP path instead of depending on a streaming provider's DRM, SDK or developer program.

## Why Moka

Moka follows one rule:

> **Preserve the source when the listener wants pure playback, and make every intentional DSP change visible when the listener wants tuning.**

Key features:

- local FLAC/WAV plus Android-supported audio formats
- source-rate-aware direct PCM for compatible local lossless files
- truthful AudioTrack / USB / bit-perfect-capability status instead of a generic Hi-Res badge
- native C++ real-time DSP
- ViPER-DDC `.vdc` compatibility
- JamesDSP-inspired multimodal FIR/IIR equalization
- `.irs` / WAV convolution
- automatic DSP headroom
- look-ahead output limiting
- ReplayGain/R128 Track and Album normalization
- optional offline loudness analysis for untagged files
- input/output/estimated true-peak and DSP-throughput telemetry
- per-output DSP profiles
- Korean/Japanese/Chinese/Unicode-safe metadata and search
- album covers and artist artwork mosaics
- optional MusicBrainz / Cover Art Archive enrichment
- no account required for local playback
- local audio files remain read-only
- consistent Moka espresso/bronze Material 3 interface with technical diagnostics kept behind an Advanced view

---

## Hi-Fi playback philosophy

Moka does **not** resample a 44.1 kHz file to 192 kHz merely because the connected DAC advertises 192 kHz support.

With DSP disabled, compatible local lossless content can use the direct PCM path and Moka reports the source, AudioTrack and USB mixer state it can actually verify.

With DSP enabled, Moka intentionally changes the samples and therefore does **not** call the result source bit-perfect.

### USB routing and Pixel 8a safety in Beta 1

Moka retains its direct / exact-bit-perfect-capable USB path on devices where that route behaves correctly. Real-device testing found one important exception: on Pixel 8a, a DSP-off bit-perfect USB path could produce an unexpectedly high listening level.

Beta 1 therefore applies a **targeted Pixel 8a USB guard**. While USB audio is routed on Pixel 8a, Moka keeps the user's saved DSP profile active and refuses the unsafe bit-perfect selection for that route. The saved DSP preference itself is not overwritten, and normal behavior returns when USB is disconnected. Other supported devices keep the direct USB behavior rather than being forced through a global safe-mixer fallback.

```text
Local file
    ↓
Decode to PCM
    ↓
Automatic headroom
    ↓
Track / album normalization
    ↓
ViPER-DDC
    ↓
FIR or high-order IIR EQ
    ↓
IRS convolution
    ↓
Look-ahead limiter / post gain
    ↓
32-bit float PCM
    ↓
AudioTrack
    ↓
USB / wired / Bluetooth / Android output
```

### Native DSP

The C++ real-time path supports:

- FIR minimum phase
- IIR 4th order
- IIR 6th order
- IIR 8th order
- IIR 10th order
- IIR 12th order
- ViPER-DDC-compatible SOS filter cascades
- partitioned convolution
- FIR + IRS combination at setup
- sparse delayed/scalar impulse optimization
- normalization
- look-ahead limiting

The Kotlin DSP implementation remains as a compatibility fallback.

### Headroom and clipping

Moka estimates positive gain contributed by enabled EQ/DDC/IRS/normalization stages and applies conservative pre-DSP headroom. The live signal panel reports:

- input peak
- output sample peak
- 4× inter-sample / true-peak estimate
- full-scale sample count
- automatic headroom
- measured DSP speed relative to realtime

The native limiter no longer silently hard-clamps finite PCM when the limiter is disabled. If the listener disables protection, Moka reports the resulting peaks rather than pretending the limiter is off while clipping behind the scenes.

### High-quality DSP asset resampling

Impulse responses that need conversion to the active output rate use a longer 97-tap Blackman-Harris windowed-sinc path. This work happens while building the DSP chain, not continuously on the audio thread.

---

## Loudness normalization

Moka supports file-provided ReplayGain/R128 metadata and two listening modes:

- **Track** — normalize tracks independently
- **Album** — preserve intentional loud/quiet relationships inside an album

For local files without complete trusted loudness tags, Beta includes an optional offline scanner:

- direct RIFF/WAV PCM analysis without the Android raw MediaCodec path
- MediaCodec decoding for compressed formats
- per-track checkpoint/resume caching
- fully tagged albums skipped automatically
- per-track analysis timing in `MokaLoudness` logcat
- K-weighting
- BS.1770-style absolute and relative loudness gates
- target: approximately -18 LUFS for Moka's ReplayGain-style normalization path
- track gain
- album-preserving gain
- estimated inter-sample peak

Offline results are cached inside Moka. Audio files are never rewritten.

The true-peak value is deliberately labelled an **estimate** rather than a standards-certified dBTP conformance measurement.

---

## International metadata

Moka is designed for real multilingual libraries.

Visible metadata preserves Korean, Japanese, Chinese, emoji and punctuation such as:

```text
빌려온 고양이 (Do the Dance)
I'LL LIKE YOU!
NOT CUTE ANYMORE: "Special"!
日本語タイトル
中文歌曲
🎧 Test Track
```

Metadata behavior includes:

- Unicode NFC display normalization
- NFKC search/group keys
- locale-aware sorting
- FLAC Vorbis UTF-8 comments
- ID3 ISO-8859-1 / UTF-16 / UTF-16BE / UTF-8
- WAV UTF-8 / UTF-16 detection
- WAV CP949/EUC-KR fallback
- WAV Shift-JIS fallback
- Windows-1252 fallback for legacy Western metadata

Moka does not strip punctuation from visible titles, albums or artist names.

---

## Albums, artists and artwork

Library views include:

- Tracks
- Albums — sorted by album artist/artist, then album title
- Artists — grouped by album on detail pages
- Genres
- Recently Added
- Favorites
- Unicode-aware search

Artwork priority is local-first:

1. embedded file artwork
2. Android MediaStore thumbnail
3. MediaMetadataRetriever fallback
4. optional cached Cover Art Archive image after explicit MusicBrainz enrichment

Album views show cover art. Artist rows can build a 2×2 mosaic from up to four album covers.

---

## Optional MusicBrainz enrichment

Streaming playback is intentionally not part of Moka Beta.

Instead, Moka can optionally enrich the **local** collection using MusicBrainz core metadata and Cover Art Archive imagery.

The enrichment pass:

- is started explicitly by the user
- matches unique artist/album pairs
- uses a meaningful Moka User-Agent
- rate-limits MusicBrainz requests
- rejects weak matches
- caches results locally
- never changes the underlying audio tags
- uses online metadata only to fill gaps such as release date/identifier/artwork

Moka uses MusicBrainz core release metadata for this feature rather than importing supplementary community tags into the distributed application.

See `docs/MUSICBRAINZ_ENRICHMENT.md`.

---

## JamesDSP and ViPER credits

### JamesDSP

Moka's DSP design is influenced by concepts and processing approaches used by **JamesDSP**, created by James Fung (`james34602`), including multimodal FIR/IIR equalization, partitioned convolution and ViPER-DDC processing.

Upstream project:

https://github.com/james34602/JamesDSPManager

Moka is a separate project and is not affiliated with, endorsed by, or an official distribution of JamesDSP.

### ViPER / ViPER-DDC

Moka imports compatible **ViPER-DDC `.vdc` headphone-correction profiles**. The ViPER name is used to describe format/ecosystem compatibility. Moka is not an official ViPER4Android application and is not affiliated with the original ViPER developers.

See `THIRD_PARTY_NOTICES.md`.

---

## Android integration

Media3 remains the media-session, queue and broad-codec compatibility layer while Moka's direct engine renders eligible local audio.

- background playback
- lock-screen controls
- Android media notification
- Bluetooth media buttons
- notification/card tap → Now Playing
- queue reorder/remove
- Play Next / Add to Queue
- saved local queues
- shuffle / repeat

### Gapless status

Media3 retains normal gapless behavior when the decoder/container exposes the required metadata.

Moka **does not yet claim seamless gapless handoff on the custom direct PCM/native-DSP engine**. Proper direct gapless requires preparing the next decoder/DSP chain and handing it to the same output stream without an AudioTrack teardown. This remains a documented Beta limitation rather than a fake toggle.

---

## Build requirements

- Android SDK / Android Studio
- compileSdk 37
- targetSdk 36
- minSdk 26
- JDK 17
- Android NDK
- CMake 3.22.1
- Media3 1.11.1

Debug:

```bash
./gradlew clean :app:assembleDebug
```

Beta 1 USB validation after installing the RC:

```bash
./scripts/beta1-usb-matrix.sh
```

See `docs/BETA1_USB_TEST_MATRIX.md` for the two-phones × two-USB-devices test sequence and pass criteria.

Release:

```bash
./gradlew clean :app:assembleRelease
```

Tests:

```bash
./gradlew :app:testDebugUnitTest
```

If CMake/Ninja state becomes stale:

```bash
./fix-native-build.sh
```

---

## Diagnostics

Moka can export diagnostics directly from **More**. The report includes:

- app/device/Android version
- current route and output device
- source format/rate/bit depth
- direct/Media3 engine state
- USB/source bit-perfect verification state
- DSP configuration
- automatic headroom
- live peak / true-peak estimate / clipping telemetry
- DSP throughput
- AudioTrack underrun count, DSP queue depth, primed frames and in-place reload count
- track/album normalization gains
- offline loudness result for the current track
- cached MusicBrainz and loudness-analysis counts
- last non-fatal playback error / crash record

ADB debugging remains available:

```bash
adb logcat -c
adb logcat -d | grep -iE \
  "MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT" \
  > moka-audio.txt
```

---

## Privacy

Moka is local-first. Local audio is not uploaded for playback, analysis or DSP.

Optional MusicBrainz enrichment sends only text metadata needed to match an album, such as artist and album title. Optional artwork is retrieved from the Cover Art Archive / Internet Archive and cached locally.

See `PRIVACY.md`.

---

## Beta status

`4.0.0-beta03` keeps the validated playback/DSP path frozen while adding resumable library scanning plus a substantially faster, checkpointed offline loudness analyzer—especially for WAV/PCM libraries on Pixel-class devices. The implementation includes the planned local-library and hi-fi feature set, but mass-use confidence still requires the signed-build hardware/soak matrix in `BETA_CHECKLIST.md`.

A Beta can have documented limitations; it should not have hidden behavior. Moka therefore reports what it can verify and explicitly labels estimated or unsupported paths.
