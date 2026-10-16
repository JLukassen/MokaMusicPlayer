# Moka Music Player 4.0 Beta 6

**Android version:** `4.0.0-beta06` · **versionCode:** `38` · **package:** `com.mokamusic.player`

This is a **GitHub prerelease for testing**, not a Play Store/production-signed build. Its APK is **debug-signed by GitHub Actions**. The developer reported substantial everyday use of the closely related RC2.5 (versionCode 37) without recurring playback problems. Automated Kotlin tests and Android APK/AndroidTest builds are release gates; broad cross-device testing is still ongoing.

## Highlights
- **Network music in Library:** full-height Navidrome/Subsonic Tracks, Albums, Artists and Genres browsing over HTTPS (including private Tailscale Serve), pagination and searching. Network account configuration remains in Settings.
- **Remembered login:** encrypted Android Keystore credentials, remembered server URL and username, reconnect on return to the Network library, with controls to disconnect or forget the server.
- **Playback and queue:** continuous Navidrome shuffle, mixed local + hosted tracks, queue / next / previous navigation, and Bluetooth/car track-transition stability improvements.
- **Quality and metadata:** Original/Lossless preference requests raw streams where supported, correctly marks actual transcodes, preserves original technical metadata fields and adds WAV technical metadata fallback. WMA uses an MP3 compatibility transcode when required.
- **Local library care:** duplicate audit and user-confirmed file deletion, improved scan/loudness compatibility, caching and track metadata handling.
- **DSP and integration:** headphone AutoEq discovery, EQ preset library, per-device sound profiles and Android Auto browsing.

## Download
Download `Moka-Music-Player-4.0.0-beta06-debug.apk` from this release, with `SHA256SUMS.txt` to verify its checksum. The APK targets Android 8.0+ (minSdk 26) and supports arm64-v8a and x86_64.

## Install / signing caution
**This test APK is debug-signed, not signed with a stable production release key.** Android will reject an in-place update when the old APK was signed by a different certificate, even if versionCode is higher. **Do not uninstall your existing working Moka build just to force installation if it contains settings or other data you want to retain.** Back up or plan migration first, or wait for a production-signed APK using a consistent private keystore. A future production key requires a separate signing setup.

## Server and privacy
Navidrome streaming is optional and uses your own HTTPS/Subsonic-compatible server. The app does not upload a local media library for enrichment. Private Tailscale streaming requires both server and Android device to be in the same tailnet. Encrypted saved server credentials are device-local and excluded from backup.

## Known limitations
- Release packaging is debug-signed; this is an opt-in prerelease and not a production distribution.
- Some audio pathways remain device- and codec-dependent. Check Bluetooth/Google Maps interruptions, USB DAC routing, WMA transcoding and continuous shuffle on your device.
- Greyed-out tracks or HTTP 403/stream failures caused by inaccessible Navidrome-hosted files must be repaired at the server (filesystem/SELinux mount permissions), not inside Moka.
- Production release signing, long-term auto-upgrade support and Play Store distribution are separate follow-up work.

See [CHANGELOG.md](../CHANGELOG.md) and the repository documentation for technical details.
