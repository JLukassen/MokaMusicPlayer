# Moka Music Player 4.0 Beta 1

Moka 4.0 Beta 1 is the first public beta of the local-first hi-fi playback and DSP architecture.

## Highlights

- Native C++ real-time DSP with FIR and 4th/6th/8th/10th/12th-order IIR equalization.
- ViPER-DDC `.vdc` support.
- IRS/WAV convolution with partitioned processing and high-quality setup-time resampling.
- Automatic DSP headroom and look-ahead limiting.
- ReplayGain/R128 Track and Album normalization plus optional offline loudness analysis.
- Source-rate-aware direct PCM playback for supported local lossless files.
- USB route and signal-path reporting, including exact/bit-perfect capability where Android exposes it safely.
- Stable live DSP hot reload for EQ, VDC and convolution without repeated AudioTrack teardown once the float DSP path is active.
- Unicode-safe library handling for Korean, Japanese, Chinese, emoji and punctuation.
- Album artwork, artist mosaics, Favorites, Recently Added, saved queues and optional MusicBrainz/Cover Art Archive enrichment.
- Refined espresso/bronze Material 3 interface with simplified Now Playing and diagnostics moved behind Advanced settings.

## Device validation

Beta 1 was exercised on Pixel 8a and Samsung hardware with two USB audio devices per phone. The focused USB matrix completed without steady-state Moka underrun growth, AudioFlinger buffer timeouts, or fatal Moka exceptions in the captured sessions.

## Pixel 8a USB note

Real-device testing found that a DSP-off bit-perfect USB path on Pixel 8a could produce an unexpectedly high listening level. Beta 1 therefore keeps the user's saved DSP profile active while USB audio is routed on Pixel 8a and refuses the unsafe bit-perfect selection for that route. Other supported devices retain the direct USB behavior where it is safe.

## Known Beta limitations

- Moka does not yet claim seamless gapless handoff on the custom direct PCM/native-DSP engine.
- Offline true-peak is an estimate, not a standards-certified dBTP measurement.
- Device-specific Android USB behavior may vary across vendors and DACs.
- This remains beta software; keep volume conservative when testing a new USB audio route.

## Privacy

Moka is local-first. Local audio remains read-only and is not uploaded for playback, DSP, loudness analysis or diagnostics. Optional MusicBrainz enrichment sends artist/album text only when explicitly started by the user.
