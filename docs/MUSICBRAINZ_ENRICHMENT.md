# MusicBrainz enrichment

Moka's online metadata feature is optional and does not participate in audio playback.

## Flow

1. Moka groups the local library by album artist/artist + album.
2. The user starts **Enrich library** from More.
3. Moka queries the MusicBrainz v2 release-group search endpoint.
4. Requests are serialized/rate-limited to roughly one per second and include a Moka User-Agent.
5. Matches below Moka's confidence threshold are ignored.
6. Core match data and the release-group MBID are cached locally.
7. A Cover Art Archive 500px front-cover URL becomes a final artwork fallback.

Embedded/local tags remain authoritative. Online matching does not rewrite audio files.

## Why core metadata only

Moka's public-distribution path intentionally uses MusicBrainz core release metadata rather than importing supplementary tags/ratings into the app's local database. This keeps the licensing boundary simpler; core database data is CC0.

## Artwork priority

1. embedded cover
2. MediaStore thumbnail
3. MediaMetadataRetriever
4. cached Cover Art Archive image

If no trustworthy online match exists, Moka keeps the normal local fallback artwork.
