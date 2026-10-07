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
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    /**
     * Static per-track gain in dB for ReplayGain-style normalization.
     * Null means no trusted file-provided loudness gain was found.
     */
    val normalizationGainDb: Float? = null,
    /** Album-level ReplayGain/R128 gain. Used when normalization mode is Album. */
    val albumNormalizationGainDb: Float? = null,
    /** Optional online enrichment. Local embedded metadata always remains authoritative. */
    val musicBrainzReleaseGroupId: String? = null,
    val onlineArtworkUrl: String? = null
) {
    val formatLabel: String
        get() = when {
            mimeType?.contains("flac", ignoreCase = true) == true || displayName.endsWith(".flac", true) -> "FLAC"
            mimeType?.contains("wav", ignoreCase = true) == true || displayName.endsWith(".wav", true) -> "WAV"
            mimeType?.contains("opus", ignoreCase = true) == true || displayName.endsWith(".opus", true) -> "OPUS"
            mimeType?.contains("mp4", ignoreCase = true) == true || displayName.endsWith(".m4a", true) -> "M4A"
            mimeType?.contains("mpeg", ignoreCase = true) == true || displayName.endsWith(".mp3", true) -> "MP3"
            else -> "AUDIO"
        }
}
