# Moka Music Player Privacy

Moka is designed as a local-first music player.

## Local audio

Moka reads local audio through Android storage/media APIs in order to build the library and play music. Audio files remain read-only. Moka does not upload local audio for playback, DSP, loudness analysis or diagnostics.

## DSP and loudness analysis

DSP processing occurs on the device. Optional offline loudness analysis decodes the selected local files on the device and stores only calculated values such as loudness gain and peak estimates in Moka's private app storage.

## Optional online metadata

MusicBrainz enrichment is opt-in and is not required for playback. When the user starts enrichment, Moka sends artist and album text to the MusicBrainz web service to find a likely release-group match. Moka can then request cover artwork from the Cover Art Archive / Internet Archive.

Moka caches enrichment results and downloaded artwork locally. Moka does not send local audio data to MusicBrainz.

## Diagnostics

Diagnostics are generated locally. Exporting a diagnostic report happens only after the user chooses a destination through Android's document picker. The report can include device model, Android version, audio route, local track metadata, DSP settings and recent Moka error information. It does not include the audio contents of the music file.

## Accounts and optional streaming

Moka does **not** require a developer-operated or subscription-streaming account for local playback. The optional Navidrome/Subsonic integration connects only to a **server chosen by the user**, over HTTPS, when the user supplies that server URL and their own credentials.

When **Remember login** is enabled, Moka stores the server URL and username in device-local app preferences and encrypts the saved password using an Android Keystore AES-GCM key; the encrypted login file lives in app-private `noBackupFilesDir` and is excluded from Android backup. Saved credentials can be deleted using the **Forget server / saved login** control. Disconnecting from the server does not itself erase a remembered login, so that the user can reconnect later. Unchecking remembered login deletes the saved encrypted credentials.

For Subsonic server requests, the app uses salted-token authentication in HTTPS request parameters; server operators and reverse proxies should avoid logging signed API/stream URLs or authentication parameters. Music files are streamed on demand from the user's chosen server; Moka does not automatically upload local music files to the server.

Moka's local music playback and DSP features remain available without configuring Navidrome. A user's choice to use a third-party server is governed by that server operator's policies.

## Optional features and downloaded data

- **AutoEq headphone profile search**: When requested, Moka downloads a public headphone index and selected profile files from GitHub. This sends normal HTTPS requests to GitHub-hosted resources.
- **MusicBrainz / Cover Art Archive**: When invoked, the app can request public metadata and artwork; normal HTTPS request data may be visible to the respective network services.
- **Library health audit**: Inspects device-local library information and does not transmit audio files. Duplicate removal requires explicit user confirmation.
- **Saved sound presets**: Device-specific DSP preferences are stored locally.

## Control and contact

The user may decline optional online integrations and continue using local audio. Uninstalling Moka removes app-private data according to Android's app-removal behavior; the encrypted Navidrome password is not included in standard Android app backups.

For questions or to request corrections to this notice, open an issue at https://github.com/JLukassen/MokaMusicPlayer/issues. Before submitting a public issue, remove private server URLs, tokens, personal information, and music filenames you do not want to disclose.

**Google Play readiness:** This statement should be reviewed against the actual shipped app and Google Play Data safety form before submitting any store release. It is not a substitute for the required Play Console declarations.
