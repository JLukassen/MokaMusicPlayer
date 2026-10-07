# Moka 4.0 Beta 1 release / mass-use gate

The feature implementation is Beta-level. The remaining unchecked items are real-device verification gates; do not interpret an unchecked hardware item as a hidden implemented feature.

## Library / metadata

- [x] Unicode-preserving display metadata
- [x] Korean CP949/EUC-KR WAV fallback
- [x] UTF-8 / UTF-16 / Shift-JIS / Windows-1252 legacy handling
- [x] punctuation-safe titles/artists/albums
- [x] locale-aware sorting
- [x] Unicode-normalized search
- [x] album artwork in album browsing
- [x] artist artwork mosaics
- [x] optional MusicBrainz core-metadata matching
- [x] optional Cover Art Archive fallback with local disk cache
- [ ] Physical-device torture library: FLAC/WAV/MP3/M4A/Opus with Korean/Japanese/Chinese/Western tags
- [ ] 5,000+ track scan / scroll / rescan test

## Playback / hi-fi

- [x] source-rate-aware direct PCM path
- [x] direct WAV integer/float PCM path
- [x] FLAC platform decode with source-precision verification when pure/direct
- [x] 32-bit float DSP output
- [x] no forced upsampling to DAC maximum rate
- [x] truthful source/AudioTrack/USB signal-path reporting
- [x] Media3 fallback for unsupported content/routes
- [ ] 8-hour screen-off signed-release playback without crash
- [ ] 2-hour DSP playback with no sustained underrun growth
- [ ] repeated FLAC/WAV seek stress test
- [ ] queue/repeat/shuffle service lifecycle regression
- [ ] true seamless direct-engine gapless handoff (documented Beta limitation until implemented)

## DSP / sound quality

- [x] native FIR minimum-phase EQ
- [x] native IIR 4/6/8/10/12
- [x] native DDC
- [x] native partitioned convolution
- [x] combined FIR + IRS setup path
- [x] sparse delayed/scalar IR optimization
- [x] automatic headroom
- [x] 5 ms native look-ahead limiter
- [x] Kotlin fallback look-ahead limiter
- [x] no hidden native hard clamp when limiter is disabled
- [x] high-quality Blackman-Harris windowed-sinc IRS resampling
- [x] ReplayGain/R128 Track + Album metadata support
- [x] optional offline K-weighted loudness analysis for untagged files
- [x] offline album-preserving normalization gain
- [x] live sample peak + inter-sample/true-peak estimate
- [x] DSP realtime-throughput telemetry
- [ ] numeric native-vs-Kotlin IIR regression on Android instrumentation
- [ ] laboratory/standards conformance test if Moka ever labels true peak as certified dBTP

## Device matrix

Test the signed release APK on at least:

- [x] Pixel 8a / AOSP-family current Android
- [x] Samsung current Android
- [ ] Android 13/14 device
- [ ] USB-C analog/digital headset
- [ ] USB DAC at 44.1 / 48 / 96 / 192 kHz where supported
- [ ] Bluetooth AAC
- [ ] Bluetooth LDAC/other high-quality codec where available
- [ ] built-in speaker
- [ ] wired route where available

For each route record source format, AudioTrack format, route, underruns and whether switching routes while playing remains stable.

### Beta 1 focused USB 2×2 matrix

Use `scripts/beta1-usb-matrix.sh` and `docs/BETA1_USB_TEST_MATRIX.md`.

- [x] Phone 1 + USB device 1: no steady-state underrun growth / no buffer timeout
- [x] Phone 1 + USB device 2: no steady-state underrun growth / no buffer timeout
- [x] Phone 2 + USB device 1: no steady-state underrun growth / no buffer timeout
- [x] Phone 2 + USB device 2: no steady-state underrun growth / no buffer timeout
- [x] EQ/VDC/convolver changes hot-reload without repeated AudioTrack recreation once float DSP playback is active
- [x] USB route remains stable through supported DSP changes; Pixel 8a guard blocks the unsafe USB DSP-off transition by design

## Release / safety

- [x] permanent release signing-key workflow
- [x] diagnostics export
- [x] crash/non-fatal log persistence
- [x] local library remains read-only
- [x] privacy document
- [x] MusicBrainz rate-limit / User-Agent behavior
- [x] streaming-service credentials removed from Beta product path
- [ ] verify upgrade from the last signed release without data loss
- [x] initial dependency/source-license inventory and release-audit tooling added
- [ ] final resolved/transitive release dependency NOTICE/license audit
- [ ] final JamesDSP/DSPManager DSP source-provenance review and sign-off
