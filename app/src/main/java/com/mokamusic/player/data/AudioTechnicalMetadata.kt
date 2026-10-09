package com.mokamusic.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioTechnicalMetadata(
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** Bits per second, not kilobits per second. */
    val bitrate: Int? = null
)

class AudioTechnicalMetadataReader(private val context: Context) {
    suspend fun read(track: MusicTrack): AudioTechnicalMetadata = withContext(Dispatchers.IO) {
        val known = AudioTechnicalMetadata(
            sampleRateHz = track.sampleRateHz,
            bitDepth = track.bitDepth,
            bitrate = track.bitrateBps
        )
        // Subsonic URLs are signed and short-lived. Do not ask Android's retriever
        // to open a second stream merely to discover catalog metadata.
        if (track.networkSongId != null ||
            track.uri.scheme.equals("https", ignoreCase = true) ||
            track.uri.scheme.equals("http", ignoreCase = true)) {
            return@withContext known
        }

        val retriever = MediaMetadataRetriever()
        val retrieved = try {
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
            // Preserve existing library fields if the Android retriever fails.
            known
        } finally {
            retriever.release()
        }

        // Some Android decoders expose WAV playback but omit the PCM technical
        // properties. A bounded read of the local RIFF header is more reliable.
        val wav = if (track.formatLabel == "WAV" &&
            (retrieved.sampleRateHz == null || retrieved.bitDepth == null || retrieved.bitrate == null)) {
            runCatching { readLocalWavHeader(context, track.uri) }.getOrNull()
        } else null
        AudioTechnicalMetadata(
            sampleRateHz = retrieved.sampleRateHz ?: wav?.sampleRateHz,
            bitDepth = retrieved.bitDepth ?: wav?.bitDepth,
            bitrate = retrieved.bitrate ?: wav?.bitrate
        )
    }

    private fun readLocalWavHeader(context: Context, uri: Uri): AudioTechnicalMetadata? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n <= 0) break
                count += n
            }
            parsePcmWavHeader(buffer.copyOf(count))
        }
}

/** Parse an uncompressed RIFF/WAVE PCM/IEEE-float format chunk, even after JUNK/LIST chunks.
 * Returns null for compressed/unsupported WAV formats rather than fabricating a bit depth.
 * The input is bounded by the caller to the first 64 KiB.
 */
internal fun parsePcmWavHeader(bytes: ByteArray): AudioTechnicalMetadata? {
    if (bytes.size < 36) return null
    fun marker(offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)
    fun u16(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    fun u32(offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    if (marker(0) != "RIFF" || marker(8) != "WAVE") return null
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val size = u32(offset + 4)
        val start = offset + 8
        if (size > bytes.size.toLong() - start) return null
        if (marker(offset) == "fmt " && size >= 16) {
            val formatTag = u16(start)
            if (formatTag != 1 && formatTag != 3 && formatTag != 0xfffe) return null
            val channels = u16(start + 2)
            val sampleRate = u32(start + 4)
            val byteRate = u32(start + 8)
            val containerBits = u16(start + 14)
            val validBits = if (formatTag == 0xfffe && size >= 40)
                u16(start + 18).takeIf { it in 1..containerBits } else null
            val depth = validBits ?: containerBits
            if (channels !in 1..32 || sampleRate !in 1L..768_000L ||
                depth !in 1..64 || byteRate !in 1L..(Int.MAX_VALUE / 8L)) return null
            return AudioTechnicalMetadata(sampleRate.toInt(), depth, (byteRate * 8).toInt())
        }
        val next = start.toLong() + size + (size and 1L)
        if (next > bytes.size || next <= offset) return null
        offset = next.toInt()
    }
    return null
}
