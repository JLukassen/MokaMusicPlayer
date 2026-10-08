# Moka Music Player v4.0.0-beta05 — Samsung Library Performance

**Prerelease/test APK.** Built with the Android debug signing key. Device results are not yet established for this version.

## Improvements
- Reuse cached songs immediately; faster coverless artwork loads across app restarts.
- Persist negative artwork lookups with 24-hour expiry for local covers, automatically invalidated by changes to file identity.
- Reduce duplicate native metadata/thumbnail attempts for FLAC and WAV.
- Seek directly over large WAV audio payloads while still reading RIFF metadata before and after the data chunk.
- Skip native metadata retriever when FLAC/WAV technical data and title/artist/album information are already available.
- Add `MokaArtwork` and `MokaLibrary` timing diagnostics.
- Retain Beta 4's Samsung FLAC fallback, loudness retry behavior, native optimized LUFS analysis and app data caches.

## Install and verify
Use `adb install -r` only when existing APK and Beta 5 are signed with the same debug key. If Android reports a signature mismatch, **do not uninstall the installed build without backing up app data**.

Test on Samsung: launch Moka with an existing library; confirm cached songs appear immediately, album covers fill without blocking navigation, scroll through tracks, then rescan twice and compare slow-parser and artwork logs.

Run `adb -s SERIAL logcat -s MokaArtwork:I MokaLibrary:I MokaLoudness:I '*:S'`.

## Remaining release checks
- Samsung and Pixel hardware verification
- Offline loudness accuracy and background persistence
- Full third-party license/provenance sign-off

Beta 4 remains available as a rollback point.
