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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

/**
 * Shared, size-aware artwork loader used by tracks, albums and artist mosaics.
 *
 * Order is deliberately local-first:
 *  1. embedded artwork
 *  2. MediaStore thumbnail
 *  3. MediaMetadataRetriever fallback
 *  4. optional cached MusicBrainz/Cover Art Archive artwork
 *
 * The online path is only reachable after the user runs library enrichment, so normal playback
 * and library browsing remain fully offline-capable.
 */
class ArtworkLoader(private val context: Context) {
    private val embeddedReader = EmbeddedMetadataReader(context.applicationContext)
    private val diskCacheDir = context.applicationContext.cacheDir.resolve("moka_online_art").apply { mkdirs() }

    suspend fun load(track: MusicTrack, sizePx: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        val bucket = sizeBucket(sizePx)
        val identity = track.albumId.takeIf { it > 0 }?.let { "album:$it" } ?: "track:${track.id}"
        val onlineIdentity = track.onlineArtworkUrl?.hashCode() ?: 0
        val key = "$identity@$bucket#$onlineIdentity"
        sharedCache.get(key)?.let { return@withContext it }

        val direct = runCatching {
            embeddedReader.read(track.uri, track.displayName, includeArtwork = true).artworkBytes
                ?.let { bytes -> decodeSampled(bytes, bucket) }
        }.getOrNull()

        val thumbnail = direct ?: runCatching {
            context.contentResolver.loadThumbnail(track.uri, Size(bucket, bucket), null)
        }.getOrNull()

        val retrieverBitmap = thumbnail ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                retriever.embeddedPicture?.let { bytes -> decodeSampled(bytes, bucket) }
            } finally {
                retriever.release()
            }
        }.getOrNull()

        val bitmap = retrieverBitmap ?: track.onlineArtworkUrl?.let { url ->
            runCatching { loadOnline(url, bucket) }.getOrNull()
        }

        bitmap?.let { sharedCache.put(key, it) }
        bitmap
    }

    private fun loadOnline(url: String, target: Int): Bitmap? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val file = diskCacheDir.resolve(sha256(url) + ".img")
        if (file.exists() && file.length() in 1..MAX_ONLINE_ART_BYTES) {
            runCatching { decodeSampled(file.readBytes(), target) }.getOrNull()?.let { return it }
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 18_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "MokaMusicPlayer/4.0.0-beta01")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ONLINE_ART_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ONLINE_ART_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return null
            runCatching { file.writeBytes(bytes) }
            decodeSampled(bytes, target)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_ONLINE_ART_BYTES = 12L * 1024L * 1024L

        private val maxCacheKb: Int by lazy {
            val runtimeKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            (runtimeKb / 16).coerceIn(8 * 1024, 48 * 1024)
        }

        private val sharedCache: LruCache<String, Bitmap> by lazy {
            object : LruCache<String, Bitmap>(maxCacheKb) {
                override fun sizeOf(key: String, value: Bitmap): Int =
                    max(1, value.allocationByteCount / 1024)
            }
        }

        fun clearMemoryCache() = sharedCache.evictAll()

        private fun sizeBucket(requested: Int): Int = when {
            requested <= 128 -> 128
            requested <= 256 -> 256
            requested <= 512 -> 512
            requested <= 1024 -> 1024
            else -> 1536
        }

        private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            var width = bounds.outWidth
            var height = bounds.outHeight
            while (width / 2 >= target && height / 2 >= target) {
                sample *= 2
                width /= 2
                height /= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
