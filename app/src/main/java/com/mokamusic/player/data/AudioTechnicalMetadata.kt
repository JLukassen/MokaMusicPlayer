package com.mokamusic.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioTechnicalMetadata(
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrate: Int? = null
)

class AudioTechnicalMetadataReader(private val context: Context) {
    suspend fun read(track: MusicTrack): AudioTechnicalMetadata = withContext(Dispatchers.IO) {
        val known = AudioTechnicalMetadata(
            sampleRateHz = track.sampleRateHz,
            bitDepth = track.bitDepth,
            bitrate = track.bitrateBps
        )
        // Subsonic URLs are signed and short-lived. Do not make a second HTTP stream
        // request with Android's retriever merely to display catalog metadata.
        if (track.networkSongId != null ||
            track.uri.scheme.equals("https", ignoreCase = true) ||
            track.uri.scheme.equals("http", ignoreCase = true)) {
            return@withContext known
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            AudioTechnicalMetadata(
                sampleRateHz = known.sampleRateHz
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                bitDepth = known.bitDepth
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
                bitrate = known.bitrate
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            )
        } catch (_: Exception) {
            // Retain embedded/library fields even if Android's retriever fails.
            known
        } finally {
            retriever.release()
        }
    }
}
