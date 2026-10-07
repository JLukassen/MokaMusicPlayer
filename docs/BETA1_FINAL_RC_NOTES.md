# Moka 4.0 Beta 1 final RC4 notes

Build identity: **4.0.0-beta01 / versionCode 21**.

RC4 keeps the RC2 DSP-stability fixes and corrects RC3's over-broad USB policy. The four-device RC3 test completed with **0 Moka underrun-growth events, 0 AudioFlinger BUFFER TIMEOUT events, and 0 Moka fatal exceptions** across both Pixel 8a USB devices and both Samsung USB devices. The remaining problem was sound quality: forcing every USB route through the RC3 safe/default mixer defeated the preferred direct/DSP behavior and sounded audibly worse.

## RC4 routing policy

- Unaffected devices return to the RC2 direct PCM / exact `MIXER_BEHAVIOR_BIT_PERFECT` selection behavior when Android exposes an exact match.
- Pixel 8a USB is handled separately because the earlier DSP-off 24-bit bit-perfect test produced dangerously high output relative to the Android media-volume setting.
- While USB audio is routed on Pixel 8a, Moka forces the user's **existing saved DSP profile** active. It does not replace the profile with a flat preset.
- Pixel 8a USB does not request a BIT_PERFECT mixer while the guard is active; it retains the working DSP float path that was preferred in listening tests.
- The user's stored DSP master preference is not overwritten. Disconnecting USB restores normal DSP master behavior.
- The DSP master switch is disabled in the DSP screen while the Pixel USB guard is active and explains why.
- Media3 fallback also refuses BIT_PERFECT selection on Pixel 8a USB so a codec fallback cannot silently reintroduce the high-output route.

## USB retest gate

1. Confirm the installed app reports **versionCode 21**.
2. Pixel 8a + USB: DSP should already be ON and the master switch should be guarded. Do **not** perform a DSP-off listening test on this route.
3. Confirm the saved EQ/VDC/Convolver profile sounds the same as the preferred pre-RC3 DSP path.
4. Confirm Android media volume remains effective on the Pixel DSP path.
5. Samsung / unaffected devices may still exercise DSP-off direct playback and bit-perfect verification where supported.
6. Re-run the two-phone × two-USB matrix with `scripts/beta1-usb-matrix.sh`; the script now treats Pixel and non-Pixel USB routes differently.

## RC2 fixes retained

- Serialized library-cache writes and safe MediaStore generation update.
- In-place DSP hot reload with background chain preparation.
- 8,192-frame high-rate DSP blocks and 500 ms startup target.
- Lower telemetry overhead and improved DSP throughput accounting.
- AudioTrack underrun/queue/priming diagnostics.
- USB route diagnostics and the 2×2 device harness.
- MusicBrainz result/failure diagnostics.
- Kotlin DSP flush compile correction.
