# Proposed Google Play listing — Moka Music Player

**Status:** Draft; review wording and capture actual device screenshots before publishing. Do not use generated/mock screenshots that misrepresent real functionality.

## Suggested classification
- App name: **Moka Music Player** (under Play Console's name length limit)
- Category: **Music & Audio**
- Pricing: **Free**, if the developer confirms this plan.
- Public developer brand: **Yoo Shastan**, subject to your verified Play Console public profile.
- Package name: `com.mokamusic.player`
- First Play test release: 4.0.0-beta06 / versionCode 38 unless a higher code has already been uploaded to this app.
- Support contact: **REQUIRED** — add a functioning brand-managed email to Play Console.
- Privacy URL: `https://yooshastan.com/moka/privacy/` **ONLY AFTER ACTUAL DEPLOYMENT AND VERIFICATION**.

## Short description (under 80 characters)

Local lossless music, powerful DSP, and optional Navidrome streaming.

## Full description

Moka Music Player is a high-fidelity Android music player designed for listeners who want more control over their sound.

Listen to music stored on your device, organize your library, and adjust playback with powerful audio tools. Moka focuses on clear playback information and giving you meaningful control over how your audio is processed.

**Your local music library**
- Browse tracks, albums, artists, and your existing local music.
- Play supported high-quality audio, including FLAC and WAV.
- Inspect source and output audio information where available.
- Manage playback queues and shuffle without requiring a cloud account.

**Personalize the sound**
- Explore a flexible equalizer and DSP processing controls.
- Import supported ViPER-DDC and convolution assets.
- Use listening profiles for supported output routes.
- Analyze music loudness on your device for optional normalization.

**Optional Navidrome streaming**
- Connect to your own HTTPS Navidrome/Subsonic-compatible server.
- Browse your hosted music collection from Moka.
- Prefer original/lossless streams when supported.
- Mix local and hosted tracks in your playback queue.

**Designed around your music**
Moka works without creating an account. Network services are optional and require a compatible server or internet access. Playback characteristics depend on the audio file, device, codec, and connected output equipment.

Built by Yoo Shastan, an independent Android development studio.

## Required real screenshots

Capture 4–8 portrait screenshots from a real Android build: library, album details, now playing/technical signal path, equalizer/DSP, sound presets, and optionally network library. Don't expose private Navidrome server URLs, tokens, personal song library metadata, or real user credentials in marketing screenshots.

## Play review / App access explanation

Local playback and DSP are available without an account: grant Android media-library access to see the device's own music. Optional Navidrome network browsing and streaming need access to a user-supplied HTTPS server. If Play review requires it, set up a disposable **publicly accessible demo server** with non-personal test credentials and only licensed/test media; revoke or rotate them afterward. Do not send reviewers a private Tailscale-only address or personal music server login.

## Required Play Console forms

Confirm Data safety based on actual behavior; foregroundServiceType mediaPlayback; media access justification; target audience; ads/no-ads; content rating; app access; and whether the app qualifies for Android Auto/media review. Capture requested foreground playback demo video if the console asks.

## Publishing checklist

- [ ] Permanent upload keystore generated and securely backed up
- [ ] Signed release AAB assembled locally and signature verified
- [ ] Privacy policy online and linked in the app
- [ ] Support contact functioning
- [ ] Real store graphics/screenshots reviewed
- [ ] Permissions, data-safety, content rating, account access declarations complete
- [ ] Play App Signing enrolled and bundle validated (including 16 KB checks)
- [ ] Internal testing install and playback regression pass
- [ ] Closed test / required 12 testers continuously opted-in for 14 days if subject to new-personal-account rules
- [ ] Production access granted before public release
