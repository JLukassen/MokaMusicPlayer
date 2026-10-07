# Changelog

## 4.0.0-beta01 — Local-First Hi-Fi Beta

### Beta 1 launch polish (versionCode 22)
- Freeze the validated RC4 audio/DSP architecture and keep the targeted Pixel 8a USB DSP safety guard unchanged.
- Add a consistent Moka espresso/bronze Material 3 identity instead of device-dependent dynamic color.
- Refine Now Playing around artwork, track hierarchy, compact source-format badges and an at-a-glance DSP/direct/bit-perfect status pill.
- Add a visual EQ response preview and group all 15 EQ bands into one coherent module.
- Highlight enabled EQ, DDC and convolver modules while keeping inactive processing visually quieter.
- Simplify Settings so routine library features stay prominent and technical route/underrun diagnostics live behind an Advanced expander.
- Refresh first-run, empty Now Playing, About and release copy for the public Beta 1 experience.
- Rename the More navigation destination to Settings and replace development-only tuning copy with launch-ready wording.

### Beta 1 final RC4 route-specific USB safety (versionCode 21)
- Restored RC2's direct / exact-bit-perfect-capable USB routing for unaffected devices after RC3's blanket safe-mixer policy audibly degraded the preferred listening path in real-device testing.
- Added a targeted Pixel 8a USB guard instead: when USB audio is routed on Pixel 8a, Moka keeps the user's saved DSP profile active and refuses BIT_PERFECT selection on that route. The stored DSP preference is not overwritten and returns normally after USB is disconnected.
- Serialized `AtomicFile` library-cache access to prevent concurrent `.new`/rename/sync failures and preserve a recoverable cache on transient read failure. The MediaStore generation marker now advances only after a successful cache commit.
- Added in-place DSP hot reload on the existing 32-bit-float output path, avoiding decoder/AudioTrack/audio-focus teardown for EQ, VDC, convolver, limiter and master-DSP changes after the float path is active.
- Expensive FIR/IRS/DDC replacement chains are prepared on a dedicated builder thread while the old chain continues playing, then swapped only at a fixed-block boundary so a settings change cannot starve the output or discard a partially-filled block.
- Reduced 176.4/192 kHz native/Kotlin DSP block size from 16,384 to 8,192 frames and changed DSP preroll to a 500 ms target with a smaller producer queue.
- Reduced real-time diagnostic overhead by sampling the expensive inter-sample peak estimator about four times per second while still tracking sample peaks every block.
- Realtime throughput now measures the full DSP adapter cost rather than hiding diagnostic work.
- Added live/exported AudioTrack underrun, DSP queue, priming and in-place reload telemetry.
- Added USB route/mixer logging plus a four-session two-phones × two-USB-devices validation harness.
- Added MusicBrainz request/result/failure diagnostics without changing local tags.

### Product direction
- Removed Apple Music / subscription-streaming integration from the beta product path.
- Moka Beta is local-first: local files remain the only playback source handled by Moka's hi-fi/DSP engine.
- Added optional MusicBrainz + Cover Art Archive enrichment for local libraries.

### International library
- Unicode NFC display normalization and NFKC search/group keys.
- Locale-aware artist/album/track sorting.
- Korean CP949/EUC-KR, UTF-8/UTF-16, Shift-JIS and Windows-1252 WAV LIST/INFO fallbacks.
- Preserves Korean/Japanese/Chinese text, emoji and punctuation such as `:`, `'`, `"`, `!`, `?`, `&`, parentheses and brackets.

### Artwork and metadata
- Album rows and detail pages display cover art.
- Artist rows can display a 2×2 album-art mosaic.
- Shared size-aware memory cache plus sampled embedded-image decoding.
- Optional MusicBrainz album matching uses core release-group data only.
- Optional Cover Art Archive front covers are cached to disk after explicit enrichment.
- Embedded/local metadata always wins over online enrichment.

### DSP / hi-fi
- Native C++ FIR minimum-phase EQ.
- Native multimodal IIR 4th/6th/8th/10th/12th-order modes.
- Native ViPER-DDC-compatible SOS processing.
- Native partitioned IRS convolution with combined FIR+IRS setup and sparse delayed-scalar optimization.
- Automatic combined-stage headroom estimation.
- Native 5 ms look-ahead limiter; Kotlin fallback now mirrors look-ahead behavior.
- Fixed hidden-clipping behavior: disabling the native limiter no longer silently hard-clamps finite samples.
- Upgraded setup-time IRS resampling to a 97-tap Blackman-Harris windowed-sinc path.
- ReplayGain/R128 track and album normalization.
- Added optional offline BS.1770-style K-weighted loudness analysis for untagged local files.
- Offline analysis generates track gain, album-preserving gain and an estimated 4× inter-sample peak.
- Live DSP input/output/true-peak estimate, clipping count and realtime-throughput telemetry.
- Source-rate-aware direct PCM remains preferred; Moka does not upsample just because a DAC advertises a higher maximum rate.

### Android / beta UX
- Notification/card tap opens Now Playing.
- Queue editing, Play Next, saved local queues, Favorites and Recently Added.
- Per-route DSP presets for USB/Bluetooth/wired/speaker.
- Exportable diagnostics include source/output/DSP path, normalization data and cached offline loudness information.
- Version is `4.0.0-beta01`; the launch-polish build uses versionCode `22`.

### Known beta limitations
- Seamless handoff between consecutive tracks owned by Moka's custom direct PCM/DSP engine is still not claimed as gapless. Media3 retains normal gapless metadata handling on its path.
- Offline true-peak is explicitly an estimate, not a standards-certified conformance measurement.
- Public mass-use readiness still requires signed-build soak/device testing across multiple Android vendors, USB DACs and Bluetooth codecs.
