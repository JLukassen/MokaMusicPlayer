package com.mokamusic.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.Size
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArtworkLoader(private val context: Context) {
    private val cache = object : LruCache<Long, Bitmap>(32) {}
    private val embeddedReader = EmbeddedMetadataReader(context)

    suspend fun load(track: MusicTrack, sizePx: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        val key = track.albumId.takeIf { it > 0 } ?: track.id
        cache.get(key)?.let { return@withContext it }

        // First preference: artwork actually embedded in FLAC/WAV tags.
        val direct = runCatching {
            embeddedReader.read(track.uri, track.displayName, includeArtwork = true).artworkBytes?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()

        val thumbnail = direct ?: runCatching {
            context.contentResolver.loadThumbnail(track.uri, Size(sizePx, sizePx), null)
        }.getOrNull()

        val bitmap = thumbnail ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            } finally {
                retriever.release()
            }
        }.getOrNull()

        bitmap?.let { cache.put(key, it) }
        bitmap
    }
}
