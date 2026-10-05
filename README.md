# 🎧 Moka Music Player

**Moka Music Player** is an experimental high-fidelity Android music player focused on local lossless playback, USB audio, source-rate preservation, and a powerful custom DSP pipeline inspired by tools such as JamesDSP and ViPER.

Moka is being built for listeners who want more control over how their music reaches their headphones or DAC — without automatically resampling every track to the highest rate a device supports.

> **Current status:** Alpha / active development  
> Expect bugs, compatibility issues, and major changes between releases.

---

## ✨ Features

### High-resolution local playback

- FLAC playback
- WAV playback
- MP3, AAC/M4A, Opus, and other Android-supported formats
- 16-bit, 24-bit, 32-bit integer PCM support
- 32-bit floating-point DSP output
- Source sample-rate matching
- High-resolution USB DAC and USB headphone support
- Wired headphone support
- Direct PCM playback path for supported local files

Moka attempts to preserve the source format wherever possible rather than automatically upsampling music.

For example:

```text
24-bit / 48 kHz FLAC
        ↓
24-bit / 48 kHz playback
```

rather than:

```text
24-bit / 48 kHz FLAC
        ↓
forced 192 kHz output
```

---

# 🎛️ Moka DSP

Moka includes its own high-resolution DSP engine for local music playback.

The current DSP pipeline is:

```text
Source audio
     ↓
Volume normalization
     ↓
ViPER-DDC
     ↓
Multimodal EQ
     ↓
IRS Convolver
     ↓
Output limiter
     ↓
32-bit float PCM
     ↓
Android AudioTrack
     ↓
USB DAC / headphones
```

DSP processing intentionally modifies the original samples, so Moka does **not** report playback as bit-perfect while DSP is enabled.

---

## 🎚️ Multimodal Equalizer

Moka includes a 15-band equalizer based on the frequency layout used by JamesDSP:

| Band | Frequency |
|---|---:|
| 1 | 25 Hz |
| 2 | 40 Hz |
| 3 | 63 Hz |
| 4 | 100 Hz |
| 5 | 160 Hz |
| 6 | 250 Hz |
| 7 | 400 Hz |
| 8 | 630 Hz |
| 9 | 1 kHz |
| 10 | 1.6 kHz |
| 11 | 2.5 kHz |
| 12 | 4 kHz |
| 13 | 6.3 kHz |
| 14 | 10 kHz |
| 15 | 16 kHz |

Supported modes include:

- FIR Minimum Phase
- IIR 4th order
- IIR 6th order
- IIR 8th order
- IIR 10th order
- IIR 12th order

FIR interpolation options include:

- PCHIP
- Modified Hiroshi Akima / MAKIMA

---

## 🎧 ViPER-DDC

Moka supports ViPER-style `.vdc` headphone correction files.

Example:

```text
SR_44100:...
SR_48000:...
```

Moka parses the biquad filter sections and selects or reconstructs the appropriate response for the playback sample rate.

This allows headphone-specific correction profiles to be used directly inside the player.

---

## 🌊 IRS Convolver

Moka supports impulse-response convolution using:

- `.irs`
- WAV impulse responses
- compatible decoded FLAC impulse responses

Supported impulse response formats include:

- 16-bit PCM
- 24-bit PCM
- 32-bit PCM
- 32-bit floating point
- mono
- stereo
- basic true-stereo impulse responses

Impulse responses are automatically resampled when needed to match the song's playback sample rate.

---

## 🔊 Volume Normalization

Moka includes optional volume normalization to reduce large loudness differences between songs.

Supported methods include:

- ReplayGain track gain
- R128 track gain
- adaptive fallback for music without loudness metadata
- adjustable normalization preamp

Unlike a fast automatic gain control, Moka prefers a stable per-track gain when loudness metadata is available.

This helps preserve the dynamics within a song while making different albums and tracks play at a more consistent perceived volume.

---

## 🛡️ Output Limiter

The DSP chain includes an output limiter with configurable:

- threshold
- release time
- post gain

The limiter runs at the end of the DSP chain to protect against clipping caused by EQ boosts, DDC correction, convolution, normalization, or combined processing.

Recent builds use a look-ahead limiter for smoother transient handling.

---

# 🚀 Native DSP Engine

Moka's newer DSP engine uses native C++ through Android's NDK/JNI.

Moving the heavy real-time DSP work out of Kotlin allows significantly better performance for:

- FIR filtering
- convolution
- DDC
- high-resolution PCM processing
- real-time output

Where possible, compatible FIR stages are combined to reduce the amount of real-time convolution work.

Certain simple impulse responses can also use optimized delay/gain processing instead of a full FFT convolution pass.

---

# 🔌 USB Audio

USB audio is one of Moka's primary development targets.

Moka attempts to inspect the real Android audio path, including:

- source sample rate
- source bit depth
- actual `AudioTrack` format
- output route
- USB mixer capabilities
- Android bit-perfect mixer support where available

Possible playback indicators include:

```text
USB · BIT PERFECT
```

```text
MOKA · DIRECT PCM
```

```text
MOKA · HI-RES DSP
```

### Bit-perfect playback

Moka only reports **bit-perfect** when it can verify that the source and output path match appropriately.

When DSP is enabled, playback is intentionally **not** bit-perfect because the PCM samples are being modified.

---

# 📚 Music Library

Moka scans Android's MediaStore and builds a local music library containing:

- Tracks
- Albums
- Artists
- Genres
- Embedded artwork
- Track numbers
- Disc numbers
- Album artists
- Dates / years

Moka also reads metadata directly from supported FLAC and WAV files when possible.

The library index is cached locally, so Moka does not need to completely rescan the device every time the app opens.

A manual rescan remains available when music has changed.

---

# ▶️ Playback

Moka uses Android Media3 for its media session and system integration.

Supported controls include:

- Play / pause
- Previous / next
- Seek
- ±10 second seek
- Shuffle
- Repeat
- Queues
- Album queues
- Artist queues
- Genre queues

Background playback supports:

- Notification controls
- Lock-screen controls
- Bluetooth media buttons
- Audio focus
- Headphone disconnect handling
- Screen-off playback

---

# 📱 Requirements

Recommended:

- Android 14 or newer
- USB-C headphones or USB DAC for high-resolution testing
- Local FLAC or WAV files

Minimum supported Android version:

```text
Android 8.0 / API 26
```

Some advanced USB and mixer features require newer Android versions.

---

# 📦 Installing an Early Release

Download the APK from the project's **GitHub Releases** page.

Because Moka is not currently distributed through Google Play, Android may ask for permission to install applications from your browser or file manager.

Early releases are signed with Moka's release signing certificate.

If you previously installed a developer/debug build of Moka, Android may report:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

This happens because debug builds and public release builds use different signing certificates.

You will need to uninstall the old development build once before installing the release-signed version.

Your music files will not be deleted, but Moka's application settings and cache will be reset.

---

# 🛠️ Building From Source

## Requirements

Install:

- Android Studio
- Android SDK
- Android SDK Platform 37
- Android NDK
- CMake 3.22.1
- JDK 17

The project currently uses:

```text
compileSdk 37
targetSdk 36
minSdk 26
Java 17
Media3
Jetpack Compose
Kotlin
C++ / JNI
```

Clone the repository:

```bash
git clone <repository-url>
cd MokaMusicPlayer
```

Build a debug APK:

```bash
./gradlew clean :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Native build problems

If CMake or Ninja gets into a bad state:

```bash
rm -rf app/.cxx
rm -rf app/build/intermediates/cxx

./gradlew --stop
./gradlew clean :app:assembleDebug --no-build-cache
```

If included in your checkout, you can also use:

```bash
./fix-native-build.sh
```

---

# 🧪 Reporting Bugs

Moka is currently in alpha, so bug reports are extremely useful.

When reporting audio problems, please include:

- Phone model
- Android version
- USB DAC / headphones
- File type
- Sample rate
- Bit depth
- Whether DSP was enabled
- Which DSP modules were enabled
- Steps to reproduce

For audio glitches or stuttering:

```bash
adb logcat -c
```

Reproduce the problem, then:

```bash
adb logcat -d | grep -iE \
"MokaAudio|MokaDSP|MokaNativeDSP|AudioTrack|AudioFlinger|underrun|BUFFER TIMEOUT" \
> moka-audio.txt
```

Attach `moka-audio.txt` to the GitHub issue.

---

# ⚠️ Current Limitations

Moka is still experimental.

Known areas under active development include:

- USB routing differences between Android devices
- Native DSP optimization
- Convolver performance
- DAC compatibility
- FLAC decoding behavior across Android vendors
- DSP hot switching
- Loudness analysis
- Error recovery
- UI refinement
- Battery and thermal optimization
- Apple Music integration research

Do not assume an alpha release is suitable for critical or professional playback environments.

---

# 🍎 Apple Music

Apple provides MusicKit and the Apple Music API, and integration is being investigated.

Possible future functionality includes:

- Apple Music authentication
- Catalog search
- Library browsing
- Playlists
- Recommendations
- Recently played music

Apple Music's protected streaming audio is separate from Moka's local PCM/DSP engine, so custom DDC, EQ and IRS processing is currently focused on local audio.

---

# 🗺️ Roadmap

Potential future work includes:

- Further native DSP optimization
- Better ReplayGain / R128 analysis
- Album loudness normalization
- DSP presets
- Importable headphone profiles
- DSP profile auto-selection by output device
- Native FLAC decoding
- More accurate USB DAC capability detection
- Improved bit-perfect verification
- Gapless playback
- Crossfade as an optional non-bit-perfect mode
- Apple Music browsing
- Automated signed GitHub releases
- Better crash diagnostics
- Performance and battery telemetry
- Additional JamesDSP-compatible processing modules

---

# 🔐 Privacy

Moka is designed primarily as a local music player.

Your local music files remain on your device.

Moka does not need to upload your local music collection in order to play or process it.

DSP profile files such as `.vdc` and `.irs` are also processed locally.

---

# ❤️ Project Philosophy

Moka is built around a simple idea:

> **Play the source faithfully when possible, and give the listener precise control when they intentionally want to change it.**

Pure playback should stay pure.

DSP playback should be powerful.

And the app should be honest about which one it is doing.

---

## Moka Music Player

**Local music. High-resolution playback. Your signal path.**