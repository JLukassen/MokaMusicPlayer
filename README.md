# Moka Music Player

**Moka Music Player** is a local-first high-fidelity Android music player built around transparent audio routing, source-rate-aware playback and a native real-time DSP engine.

> **Latest test release: [v4.0.0-beta05](https://github.com/JLukassen/MokaMusicPlayer/releases/tag/v4.0.0-beta05)** · Android versionCode **26** · debug-signed prerelease
>
> **[Download the Beta 5 APK](https://github.com/JLukassen/MokaMusicPlayer/releases/download/v4.0.0-beta05/Moka-Music-Player-4.0.0-beta05-debug.apk)** · [SHA-256 checksums](https://github.com/JLukassen/MokaMusicPlayer/releases/download/v4.0.0-beta05/SHA256SUMS.txt) · [Full changelog](CHANGELOG.md) · [Previous Beta 4](https://github.com/JLukassen/MokaMusicPlayer/releases/tag/v4.0.0-beta04)
>
> Beta 5 is for device testing, **not a Play Store–signed production release**. Its GitHub Actions unit tests and debug APK build passed; Pixel 8a and Samsung hardware regression testing remains in progress. Moka intentionally does not offer subscription streaming in the beta: it controls local playback and DSP without relying on a streaming provider's DRM, SDK or developer program.

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

## What's new in Beta 5

This update focuses on **Samsung library browsing, metadata extraction and artwork responsiveness**, while keeping the validated playback/DSP path and the Pixel 8a USB-volume safety guard unchanged.

- **Fast cached-library startup:** already indexed music is restored without forcing a full metadata parse; MediaStore generation markers detect additions and changes.
- **Fewer native metadata calls:** existing FLAC/WAV metadata is reused when title, artist, album and audio technical fields are already available.
- **Faster WAV indexing:** a seekable RIFF parser skips large PCM data chunks instead of reading through them to reach later metadata.
- **Less redundant artwork work:** cover lookups are limited to three concurrent requests; missing local artwork is remembered for up to 24 hours (optional online-cover misses for five minutes), with file-change invalidation.
- **Better performance logs:** `MokaLibrary` reports slow files and scan completion; `MokaArtwork` reports slow artwork requests.
- **All Beta 4 improvements retained:** Samsung FLAC loudness extraction fallback, bounded retry/failure handling, native C++ loudness processing and cache-write fixes.

These are code-path improvements, not a guarantee of a particular load time on every device.

### Install or upgrade the test APK

Download the [Beta 5 debug APK](https://github.com/JLukassen/MokaMusicPlayer/releases/download/v4.0.0-beta05/Moka-Music-Player-4.0.0-beta05-debug.apk). For an ADB installation on a connected Android device:

```bash
adb devices -l
adb -s DEVICE_SERIAL install -r Moka-Music-Player-4.0.0-beta05-debug.apk
```

The `-r` flag preserves the installed app's data **when Android accepts the update and the signing certificates match**. Android will reject an upgrade if the existing Moka APK was signed with a different key. **Do not uninstall just to work around a signature mismatch** unless you have safely backed up any settings and analysis data you need.

The GitHub-hosted APK is debug-signed, so it may not share a certificate with a locally built APK or a future signed production APK. Keep [Beta 4](https://github.com/JLukassen/MokaMusicPlayer/releases/tag/v4.0.0-beta04) available as a comparison build; switching between builds also requires a compatible signing certificate.

---

## Hi-Fi playback philosophy

Moka does **not** resample a 44.1 kHz file to 192 kHz merely because the connected DAC advertises 192 kHz support.

With DSP disabled, compatible local lossless content can use the direct PCM path and Moka reports the source, AudioTrack and USB mixer state it can actually verify.

With DSP enabled, Moka intentionally changes the samples and therefore does **not** call the result source bit-perfect.

### USB routing and Pixel 8a safety

Moka retains its direct / exact-bit-perfect-capable USB path on devices where that route behaves correctly. Real-device testing found one important exception: on Pixel 8a, a DSP-off bit-perfect USB path could produce an unexpectedly high listening level.

Moka therefore retains a **targeted Pixel 8a USB guard** in subsequent betas. While USB audio is routed on Pixel 8a, Moka keeps the user's saved DSP profile active and refuses the unsafe bit-perfect selection for that route. The saved DSP preference itself is not overwritten, and normal behavior returns when USB is disconnected. Other supported devices keep the direct USB behavior rather than being forced through a global safe-mixer fallback.

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
- optimized native C++ BS.1770 accumulation, with a Kotlin fallback
- MediaCodec and fallback extraction for supported compressed formats, including a Samsung FLAC retry path
- per-track checkpoint/resume caching and persistent failure/retry records
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

Artwork lookup is local-first. Depending on the file type and available embedded artwork, Moka uses FLAC picture metadata, Android MediaStore, a native metadata fallback, or optional cached Cover Art Archive imagery (only after explicit enrichment). It avoids redundant lookups when an earlier source succeeds; not every file type uses every fallback.

Album views show cover art. Artist rows can build a 2×2 mosaic from up to four album covers.

### Incremental library and artwork caches

Moka stores a crash-safe on-device library index and normally **reuses unchanged songs** rather than reparsing all files. MediaStore updates trigger a debounced incremental refresh; a separate Full rescan action exists for intentional metadata rebuilds. The scan reports parsed/reused counts.

The Beta 5 artwork loader bounds concurrent extraction, caches successful covers in memory, and temporarily remembers missing covers across restarts. Local misses expire after 24 hours; the optional online-art miss TTL is five minutes. A changed track fingerprint or enrichment URL causes a new lookup.

Cached songs can appear before artwork finishes loading, allowing the library view to remain usable as cover images are obtained. Actual speed depends on storage, media provider and file tags.

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
- Android NDK 28.2.13676358 (as used by CI)
- CMake 3.22.1
- Media3 1.11.1

Clone/update the repository and run unit tests plus a fresh debug build:

```bash
git switch master
git pull --ff-only origin master
./gradlew clean :app:testDebugUnitTest :app:assembleDebug --no-build-cache
```

The locally built APK is at `app/build/outputs/apk/debug/app-debug.apk`. To install on a connected device **signed by the same debug key**:

```bash
adb devices -l
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

A local debug APK can have a different signing key than the [published test APK](https://github.com/JLukassen/MokaMusicPlayer/releases/tag/v4.0.0-beta05); a mismatched update will be rejected. Do not discard app data solely to switch keys.

To run the project-specific preflight checks:

```bash
./scripts/beta5-preflight.sh
```

GitHub Actions also runs the unit-test and debug-APK build before tagging and publishing a prerelease. See [Beta 5's CI workflow](.github/workflows/beta05-release.yml). A successful CI build **does not replace physical-device testing or Android instrumentation runs**.

USB regression testing, particularly the Pixel 8a safety guard:

```bash
./scripts/beta1-usb-matrix.sh
```

See [Beta 1 USB test matrix](docs/BETA1_USB_TEST_MATRIX.md) for the existing two-phone × two-USB-device test procedure and criteria.

A non-debug release compilation can be attempted with:

```bash
./gradlew clean :app:assembleRelease
```

Building a release variant does not by itself produce an app-store-signed production release. For stale CMake/Ninja state, use `./fix-native-build.sh`.

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

ADB debugging remains available. Replace `DEVICE_SERIAL` with the desired phone from `adb devices -l` (especially when Pixel and Samsung are both connected).

For **library indexing, metadata and artwork**:

```bash
adb -s DEVICE_SERIAL logcat -c
# Open Moka, browse the library, or start an incremental refresh.
adb -s DEVICE_SERIAL logcat -d -v threadtime \
  | grep -E 'MokaLibrary|MokaArtwork|AndroidRuntime|FATAL EXCEPTION|ANR in' \
  > moka-library.txt
```

`MokaLibrary` reports scan-complete total/parsed/reused/elapsed time and slow tag parses. `MokaArtwork` logs artwork requests taking at least 500 ms. Library entries and cover images do not necessarily finish loading at the same time.

For **offline loudness performance**:

```bash
adb -s DEVICE_SERIAL logcat -d -s MokaLoudness:I '*:S' > moka-loudness.txt
```

For **playback, DSP and USB output**:

```bash
adb -s DEVICE_SERIAL logcat -d | grep -iE \
  'MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT' \
  > moka-audio.txt
```

---

## Privacy

Moka is local-first. Local audio is not uploaded for playback, analysis or DSP.

Optional MusicBrainz enrichment sends only text metadata needed to match an album, such as artist and album title. Optional artwork is retrieved from the Cover Art Archive / Internet Archive and cached locally.

See `PRIVACY.md`.

---

## Beta status and known limitations

**v4.0.0-beta05 is a test prerelease**, not a stable or Play Store–signed release. Automated unit tests and debug assembly passed in GitHub Actions, but the new library/artwork changes still require Samsung and Pixel hardware verification.

- **Loudness accuracy:** offline integrated LUFS uses a BS.1770-style analyzer, while the 4× inter-sample peak is an estimate, not a certified true-peak measurement.
- **Interrupted background analysis:** per-track results and session state are persisted, but Android may pause or kill background processing; a dedicated durable foreground job is not yet implemented.
- **Codec and channel coverage:** some unsupported multichannel layouts may be skipped, with failure details recorded for diagnosis.
- **Direct-engine gapless:** seamless gapless handoff is not yet guaranteed for the custom native PCM/DSP path.
- **USB audio safety:** the targeted Pixel 8a USB guard remains enabled. Test new routes with a conservative volume level.
- **Release qualification:** physical-device playback, FLAC/WAV library browse/refresh, normalization, storage performance and long-running soak tests remain necessary. See [Beta checklist](BETA_CHECKLIST.md).
- **Licensing audit:** transitive dependency notices and third-party DSP source provenance must be verified before a stable release. See [dependency license inventory](docs/release/DEPENDENCY_LICENSE_INVENTORY.md) and [DSP source provenance](docs/release/DSP_SOURCE_PROVENANCE.md).

A beta can have documented limitations; Moka reports what it can verify and explicitly labels estimated or unsupported paths. See [changelog](CHANGELOG.md) for release-by-release changes and the [Beta 5 release](https://github.com/JLukassen/MokaMusicPlayer/releases/tag/v4.0.0-beta05) for the current APK.
