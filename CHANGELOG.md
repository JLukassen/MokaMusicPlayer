# Changelog

## 3.7.0-alpha01 — Feature / UX iteration

- Sort albums by album artist/artist, then album title.
- Open Now Playing when the Android media notification/card is tapped.
- Add new Moka adaptive + legacy launcher icon.
- Add favorites and Recently Added library filtering.
- Group artist pages by album.
- Add queue sheet with play, move, remove, clear-upcoming, Play Next, and Add to Queue controls.
- Add locally saved named queues/playlists.
- Expand Now Playing signal-path details.
- Add conservative automatic DSP headroom.
- Add live DSP input/output peak, full-scale sample, engine, and throughput telemetry.
- Add Moka Reference, Safe DSP, and DSP-off presets.
- Add optional per-output-route DSP profiles.
- Add first-run onboarding.
- Add More/About screen and one-tap diagnostics export.
- Keep direct/native-DSP gapless explicitly experimental rather than reporting unsupported behavior as gapless.
- Add optional local Gradle release signing via ignored `keystore.properties`.

## 3.6.1

- Fixed native build packaging/timestamp issue that could cause Ninja to repeatedly regenerate `build.ninja`.

## 3.6.0

- Moved heavy DSP processing to native C++/JNI.
- Combined compatible FIR EQ + IRS stages into one real-time convolver.
- Added sparse single-tap impulse optimization.
- Improved output limiting and native DSP throughput logging.
