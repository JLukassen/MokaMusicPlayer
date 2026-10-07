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

## Accounts and streaming

Moka Beta does not require a streaming-service account and does not contain Apple Music, YouTube Music, TIDAL or other subscription-streaming authentication.
