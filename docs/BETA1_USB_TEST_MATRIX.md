# Beta 1 USB 2×2 validation matrix

Run the same playback/DSP sequence with **two USB audio devices on each of two Android phones**. Keep the reference track and DSP files the same across runs whenever the USB hardware supports the source rate.

## Setup

Use Wireless debugging for ADB if possible so the phone's USB-C port remains available to the DAC/headset. Build/install `4.0.0-beta01` with **versionCode 19** or newer, then run:

```bash
chmod +x scripts/beta1-usb-matrix.sh
./scripts/beta1-usb-matrix.sh
```

The script captures full logcat plus audio/USB/AudioFlinger state and creates a tarball for analysis.

## Four sessions

| Phone | USB device | Result | Underrun growth | Buffer timeout | Route stayed USB | Notes |
|---|---|---|---:|---:|---|---|
| Phone 1 | USB 1 | ☐ |  |  |  |  |
| Phone 1 | USB 2 | ☐ |  |  |  |  |
| Phone 2 | USB 1 | ☐ |  |  |  |  |
| Phone 2 | USB 2 | ☐ |  |  |  |  |

## Sequence for every session

1. **DSP OFF / pure:** play 15–20 seconds.
2. **EQ only:** DSP on, EQ enabled, VDC/convolver disabled; play 15–20 seconds.
3. **VDC only:** enable the test `.vdc`, disable EQ/convolver; play 15–20 seconds.
4. **Convolver only:** enable the test `.irs`, disable EQ/VDC; play 15–20 seconds.
5. **All DSP:** EQ + VDC + convolver together; play 20–30 seconds.
6. **Hot toggle:** flip DSP master off/on three times, then change EQ/VDC/convolver once each while playback continues.

Prefer a 192 kHz lossless reference for the stress run. If a USB device does not support it, use the highest common rate and record that fact.

## Beta 1 pass criteria

- No Moka crash or ANR.
- No repeatable audible stutter during steady DSP playback.
- No AudioFlinger `BUFFER TIMEOUT` during steady-state playback.
- Moka's `AudioTrack underruns` count does not keep increasing.
- The first pure → DSP transition may create a float `AudioTrack` once. Subsequent DSP effect/toggle changes on that track should use `DSP hot reload applied` instead of repeatedly tearing down the decoder/output path.
- The reported `Output route:` remains the intended USB device.
- At 192 kHz, DSP throughput stays comfortably above `1.0× realtime`; higher margin is preferred.
- Android may expose bit-perfect capability, but Beta 1 must not automatically select it. DSP-off USB playback must honor the Android media-volume setting; a BitPerfect=true DSP-off Moka track is a failure for this build.

## What to send back

Upload the generated `moka-beta1-usb-results-*.tar.gz`. Each session contains `SUMMARY.txt`, focused/full logcat, and before/after Android audio/USB dumps.
