# Moka Music Player

**Moka Music Player** is an experimental high-fidelity Android music player for local audio. It focuses on source-rate-aware playback, USB/wired audio, truthful signal-path reporting, and a custom real-time DSP engine with ViPER-DDC, multimodal EQ, convolution, normalization, and output protection.

> **Current development build: v3.7.0-alpha01 — Feature / UX iteration**
>
> Moka is still alpha software. Audio routing, native DSP, device compatibility, and the UI are actively being developed.

## What is new in v3.7

### Library and navigation

- **Albums are sorted by album artist/artist first, then album title.**
- Artist pages now group tracks by album instead of presenting one flat track list.
- Added **Recently Added** and **Favorites** filters.
- Search covers track title, artist, album, and genre.
- Music-library cache schema was updated; the first v3.7 launch may perform one clean MediaStore rescan.

### Notification → Now Playing

Tapping the Android media notification/card now opens Moka directly to **Now Playing**. Play, pause, previous, and next remain normal media actions and do not force the app into the foreground.

### New Moka identity

v3.7 includes the new dark espresso / bronze **Moka** launcher icon as both legacy and adaptive Android icon resources.

### Better Now Playing and queue controls

Now Playing now exposes:

- favorite / unfavorite
- queue access
- Play Next
- Add to Queue
- remove queue item
- move queue item up/down
- clear upcoming tracks
- save the current queue as a local named playlist

Saved queues store MediaStore IDs only. Moka does not modify the audio files.

### Signal-path view

The expanded signal-path panel now makes the active playback chain visible instead of only showing a generic Hi-Fi label. Depending on the current route and DSP state it can show:

- source format / sample rate / bit depth
- direct PCM / Media3 path
- USB and source bit-perfect verification state
- automatic DSP headroom
- normalization
- DDC profile
- EQ mode
- impulse response / convolver
- limiter threshold
- DSP engine
- input peak
- output peak
- near/full-scale sample count
- measured DSP throughput

Moka only reports **source bit-perfect** when the samples and verified output path support that claim. DSP deliberately changes PCM, so source bit-perfect is false while DSP is active.

## DSP safety improvements

### Automatic headroom

v3.7 adds **Automatic headroom**, enabled by default for DSP playback.

At DSP-chain setup, Moka estimates positive gain contributed by enabled stages, including:

- multimodal EQ boosts
- ViPER-DDC response
- IRS/convolver response and convolver gain
- fixed normalization gain / normalization preamp

It then applies conservative pre-DSP attenuation plus a small safety margin before the real-time chain. The estimate is setup-time work; it does not add frequency-response calculations to the audio thread.

This is especially useful for aggressive EQ + IRS combinations where multiple positive-gain stages can otherwise drive the limiter continuously.

### Live DSP telemetry

Moka now tracks live DSP information for Now Playing and diagnostics:

- native/Kotlin engine label
- automatic headroom applied
- input peak in dBFS
- output peak in dBFS
- near/full-scale sample count
- DSP processing speed relative to real time

### DSP presets

New one-tap presets:

- **Moka Reference** — FIR minimum-phase / MAKIMA favorite curve, automatic headroom, reference limiter settings, and selected DDC/IRS when available
- **Safe DSP** — conservative limiter + automatic headroom with corrective filters disabled
- **DSP off / pure** — disables the DSP master path

### Output-device profiles

Optional route profiles can automatically select a DSP preset when Moka moves between:

- USB
- Bluetooth
- wired headphones
- speaker / other

This behavior is **off by default**. `Keep current` is the default route action so connecting a device does not unexpectedly alter the signal chain.

## Diagnostics and alpha testing

The new **More** screen includes:

- app/version information
- current output route and engine
- DSP state
- saved queue playlists
- GitHub link
- one-tap **Export diagnostics**
- first-run screen reset

The exported text includes device/Android version, playback route, source information, bit-perfect state, DSP settings, live DSP meters, throughput, and Moka's most recent stored crash/non-fatal error when present.

For lower-level audio debugging you can still capture:

```bash
adb logcat -c
# Reproduce the playback problem.
adb logcat -d | grep -iE \
  "MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT" \
  > moka-audio.txt
```

## Playback architecture

With DSP disabled, compatible local content can use Moka's direct PCM path. Media3 remains the fallback and owns the Android media session, queue, background playback, notification, lock-screen controls, and broad codec compatibility.

With DSP enabled:

```text
Local audio
    ↓
Decode to PCM
    ↓
Automatic headroom
    ↓
Volume normalization
    ↓
ViPER-DDC
    ↓
Multimodal EQ
    ↓
IRS convolver
    ↓
Limiter / post gain
    ↓
32-bit float PCM
    ↓
AudioTrack
    ↓
USB / wired / Bluetooth / Android output
```

The heavy real-time FIR/convolver/DDC path can run through the native C++ engine. Compatible FIR EQ + IRS stages are combined at setup to reduce real-time FFT work, and sparse single-tap IRs can use an optimized delay/gain path.

## Gapless status

Media3 playback retains its normal gapless behavior when decoder/container metadata supports it.

**True seamless handoff between tracks owned by Moka's custom direct PCM/native-DSP engine is still experimental.** v3.7 does not pretend that path is gapless. Proper support requires preparing and buffering the next direct track before the current AudioTrack reaches end of stream.

## Supported DSP features

- ViPER-DDC `.vdc`
- 15-band JamesDSP-style multimodal EQ
- FIR minimum phase
- PCHIP / MAKIMA interpolation
- IIR 4th / 6th / 8th / 10th / 12th order compatibility path
- IRS/WAV/compatible decoded impulse responses
- native partitioned convolution
- ReplayGain / R128-style fixed normalization metadata
- adaptive fallback normalization
- limiter / release / post gain
- automatic headroom
- live DSP telemetry
- route-based DSP presets

## Build requirements

- Android Studio / Android SDK
- compileSdk **37**
- targetSdk **36**
- minSdk **26**
- JDK **17**
- Android NDK
- CMake **3.22.1**
- Media3 **1.11.1**

Open the project directory containing `settings.gradle.kts` and `app/`.

Build a debug APK:

```bash
./gradlew clean :app:assembleDebug
```

Build a release APK:

```bash
./gradlew clean :app:assembleRelease
```

### Release signing

The project supports an optional root `keystore.properties` file. It is ignored by Git and must **never** be committed.

Example:

```properties
storeFile=/home/you/.android-keys/moka-release.jks
storePassword=YOUR_PASSWORD
keyAlias=moka
keyPassword=YOUR_PASSWORD
```

When the file exists, `assembleRelease` uses that signing config. Without it, Gradle produces an unsigned release APK.

Verify a signed APK with:

```bash
APKSIGNER=$(find ~/Android/Sdk/build-tools -name apksigner -type f | sort -V | tail -1)
"$APKSIGNER" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

## Native-build recovery

If CMake/Ninja state becomes stale:

```bash
./fix-native-build.sh
```

or manually:

```bash
rm -rf app/.cxx app/build/intermediates/cxx
./gradlew --stop
./gradlew clean :app:assembleDebug --no-build-cache
```

## Installing development builds

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug-signed APK cannot update a release-signed installation with the same package ID. If switching signing identities, Android requires the old installation to be removed first.

## Publish a GitHub prerelease from the terminal

After `keystore.properties` and `gh auth` are configured, v3.7 includes a release helper:

```bash
./scripts/publish-release.sh v3.7.0-alpha01
```

The helper requires a clean working tree, builds `assembleRelease`, refuses to publish an unsigned APK, verifies the signing certificate with `apksigner`, pushes the current branch/tag, and creates or updates the GitHub prerelease asset. The staged `dist/` APK is ignored by Git.

## Privacy

Moka is local-first. Local music and DSP profile files stay on the device and are not uploaded for playback or DSP processing.

## Project philosophy

> **Play the source faithfully when possible, and give the listener precise control when they intentionally want to change it.**

Pure playback should stay pure. DSP playback should be powerful. Moka should be honest about which path is active.
