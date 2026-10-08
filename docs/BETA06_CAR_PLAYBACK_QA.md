# Beta 6 RC2: Car playback transition QA

This test build is `4.0.0-beta06-rc2-dev` (versionCode 32). It is **not** a
release and should not be tagged or merged without Android device validation.

## Why

The October 8 car-session logs showed Moka's Media3/ExoPlayer and direct PCM
renderer both requesting audio focus, as well as excessive AudioTrack
initialization near track changes.

## Changes to verify

- One focus request owner for both Moka renderers. Media3 is configured with
  `handleAudioFocus=false`; DirectPcmEngine delegates focus to the hybrid
  session. Navigation ducking reduces and restores both output types.
- Transient loss pauses and resumes only if playback had been requested.
  Permanent loss pauses without automatic resume. Explicit user pause cancels
  resume. Headset/car-disconnect pauses and invalidates cached output.
- For local WAV/FLAC, one next-source descriptor or extractor can be prepared
  asynchronously. Direct playback retains a stopped AudioTrack at natural
  end-of-stream and reuses it only when PCM format and output route are compatible;
  USB bit-perfect output is never cached.
- `MokaTransition` marks track change, decoder-ready, and AudioTrack
  start. `MokaAudio` marks prefetch reuse and PCM output reuse.

## Car test matrix

1. Local WAV -> WAV same format; repeat several transitions. Watch for
   `AudioTrack reused` and record `transition# ... totalMs`.
2. Local FLAC -> FLAC same format, and 44.1 -> 48 kHz or bit-depth change.
   Confirm incompatible tracks rebuild and compatible tracks reuse.
3. Turn DSP on/off while playback is active. Audio should remain clean; no
   repeat focus requests, and output should not be torn down for hot reloads.
4. Bluetooth car + Google Maps navigation voice announcements: test ducking,
   transient pause/resume, and permanent focus loss. Do **not** auto-resume a
   user-paused track or one halted by an incoming call with permanent focus loss.
5. Bluetooth/AUX/USB disconnect and reconnect: Moka pauses, and the next
   playback must use the new output route.
6. Shuffle and repeat-one; repeat-one must replay rather than remain ended.
7. Navigate across local/streamed Navidrome tracks. Network tracks stay on
   Media3, without prefetch attempts on remote URIs.
8. Screen off for a full drive. Test notification, headset controls,
   pause/play/next, and Android Auto if available.

## Capture transition metrics

```bash
adb logcat -c
# Reproduce the gap (while parked or with a passenger operating the phone).
adb logcat -d -v year,threadtime -s MokaTransition MokaFocus MokaAudio MokaHybrid > moka-rc2-car-playback.txt
```

On the log, a `transition# ... audio-start totalMs=` event measures the
elapsed time from next-track selection to the AudioTrack's start request.
It is **not** a precise measure of the first audible PCM frame; use a loopback
measurement for that. Compare with RC1 using the same songs and same output
route. Confirm playback is not truncated at the end of each track.
