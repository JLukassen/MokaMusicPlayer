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
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            AudioTechnicalMetadata(
                sampleRateHz = track.sampleRateHz
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                bitDepth = track.bitDepth
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            )
        } catch (_: Exception) {
            AudioTechnicalMetadata()
        } finally {
            retriever.release()
        }
    }
}
