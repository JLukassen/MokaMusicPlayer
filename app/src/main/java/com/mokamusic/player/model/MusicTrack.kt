package com.mokamusic.player.model

import android.net.Uri

data class MusicTrack(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val mimeType: String?,
    val sizeBytes: Long,
    val relativePath: String?,
    val dateAddedEpochSeconds: Long = 0L,
    val dateModifiedEpochSeconds: Long = 0L,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val bitrateBps: Int? = null,
    val sourceFormatLabel: String? = null,
    val networkStreamLabel: String? = null,
    val networkSongId: String? = null,
    val normalizationGainDb: Float? = null,
    val albumNormalizationGainDb: Float? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val onlineArtworkUrl: String? = null,
    val sourceArtist: String? = null,
    val sourceAlbum: String? = null,
    val sourceAlbumArtist: String? = null,
    val sourceYear: String? = null,
    val sourceGenre: String? = null,
    val sourceNormalizationGainDb: Float? = null,
    val sourceAlbumNormalizationGainDb: Float? = null
) {
    val formatLabel: String
        get() = sourceFormatLabel ?: when {
            mimeType?.contains("flac", ignoreCase = true) == true || displayName.endsWith(".flac", true) -> "FLAC"
            mimeType?.contains("wav", ignoreCase = true) == true || displayName.endsWith(".wav", true) -> "WAV"
            mimeType?.contains("opus", ignoreCase = true) == true || displayName.endsWith(".opus", true) -> "OPUS"
            mimeType?.contains("mp4", ignoreCase = true) == true || displayName.endsWith(".m4a", true) -> "M4A"
            mimeType?.contains("mpeg", ignoreCase = true) == true || displayName.endsWith(".mp3", true) -> "MP3"
            else -> "AUDIO"
        }
}
