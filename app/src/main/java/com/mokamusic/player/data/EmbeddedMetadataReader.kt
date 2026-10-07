package com.mokamusic.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads metadata from the audio file itself instead of trusting MediaStore's cached columns.
 * FLAC: Vorbis comments + PICTURE blocks.
 * WAV: RIFF LIST/INFO + embedded ID3v2 chunks.
 * MediaMetadataRetriever is used as an additional native fallback.
 */
data class EmbeddedMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    /** ReplayGain/R128-derived static track gain, normalized to ReplayGain's ~-18 LUFS reference. */
    val normalizationGainDb: Float? = null,
    val albumNormalizationGainDb: Float? = null,
    val artworkBytes: ByteArray? = null
) {
    fun mergedWith(fallback: EmbeddedMetadata): EmbeddedMetadata = EmbeddedMetadata(
        title = title.clean() ?: fallback.title.clean(),
        artist = artist.clean() ?: fallback.artist.clean(),
        album = album.clean() ?: fallback.album.clean(),
        albumArtist = albumArtist.clean() ?: fallback.albumArtist.clean(),
        trackNumber = trackNumber ?: fallback.trackNumber,
        discNumber = discNumber ?: fallback.discNumber,
        year = year.clean() ?: fallback.year.clean(),
        genre = genre.clean() ?: fallback.genre.clean(),
        sampleRateHz = sampleRateHz ?: fallback.sampleRateHz,
        bitDepth = bitDepth ?: fallback.bitDepth,
        channelCount = channelCount ?: fallback.channelCount,
        normalizationGainDb = normalizationGainDb ?: fallback.normalizationGainDb,
        albumNormalizationGainDb = albumNormalizationGainDb ?: fallback.albumNormalizationGainDb,
        artworkBytes = artworkBytes ?: fallback.artworkBytes
    )
}

class EmbeddedMetadataReader(private val context: Context) {

    fun read(uri: Uri, displayName: String, includeArtwork: Boolean = false): EmbeddedMetadata {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val parsed = when (extension) {
            "flac" -> runCatching { readFlac(uri, includeArtwork) }.getOrNull()
            "wav", "wave" -> runCatching { readWav(uri, includeArtwork) }.getOrNull()
            else -> null
        } ?: EmbeddedMetadata()

        // Android's native extractor understands additional tag variants, so use it to fill holes.
        return parsed.mergedWith(readWithMediaMetadataRetriever(uri, includeArtwork))
    }

    private fun readFlac(uri: Uri, includeArtwork: Boolean): EmbeddedMetadata {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            if (input.readAscii(4) != "fLaC") return EmbeddedMetadata()

            var metadata = EmbeddedMetadata()
            var last = false
            while (!last) {
                val header = ByteArray(4)
                input.readFully(header)
                last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)

                when (type) {
                    0 -> metadata = metadata.mergedWith(parseFlacStreamInfo(input.readExact(length)))
                    4 -> metadata = metadata.mergedWith(parseVorbisComments(input.readExact(length)))
                    6 -> {
                        if (includeArtwork) {
                            val picture = parseFlacPicture(input.readExact(length))
                            if (picture != null) metadata = metadata.copy(artworkBytes = metadata.artworkBytes ?: picture)
                        } else input.skipFully(length.toLong())
                    }
                    else -> input.skipFully(length.toLong())
                }
            }
            return metadata
        }
        return EmbeddedMetadata()
    }

    private fun parseFlacStreamInfo(block: ByteArray): EmbeddedMetadata {
        if (block.size < 18) return EmbeddedMetadata()
        val sampleRate = ((block[10].toInt() and 0xFF) shl 12) or
            ((block[11].toInt() and 0xFF) shl 4) or
            ((block[12].toInt() and 0xF0) ushr 4)
        val channels = ((block[12].toInt() and 0x0E) ushr 1) + 1
        val bitsPerSample = (((block[12].toInt() and 0x01) shl 4) or
            ((block[13].toInt() and 0xF0) ushr 4)) + 1
        return EmbeddedMetadata(
            sampleRateHz = sampleRate.takeIf { it > 0 },
            bitDepth = bitsPerSample.takeIf { it > 0 },
            channelCount = channels.takeIf { it > 0 }
        )
    }

    private fun parseVorbisComments(block: ByteArray): EmbeddedMetadata {
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 8) return EmbeddedMetadata()
        val vendorLength = b.int.safeLength(b.remaining()) ?: return EmbeddedMetadata()
        b.position(b.position() + vendorLength)
        if (b.remaining() < 4) return EmbeddedMetadata()
        val count = b.int.coerceIn(0, 100_000)

        val values = linkedMapOf<String, MutableList<String>>()
        repeat(count) {
            if (b.remaining() < 4) return@repeat
            val len = b.int.safeLength(b.remaining()) ?: return@repeat
            val bytes = ByteArray(len)
            b.get(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val split = text.indexOf('=')
            if (split > 0) {
                val key = text.substring(0, split).trim().uppercase()
                val value = text.substring(split + 1).trim()
                if (value.isNotEmpty()) values.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { values[it]?.firstOrNull()?.clean() }
        return EmbeddedMetadata(
            title = first("TITLE"),
            artist = first("ARTIST", "PERFORMER"),
            album = first("ALBUM"),
            albumArtist = first("ALBUMARTIST", "ALBUM ARTIST"),
            trackNumber = parseNumber(first("TRACKNUMBER", "TRACK")),
            discNumber = parseNumber(first("DISCNUMBER", "DISC")),
            year = first("DATE", "YEAR"),
            genre = first("GENRE"),
            normalizationGainDb = parseReplayGainDb(first("REPLAYGAIN_TRACK_GAIN"))
                ?: parseR128GainToReplayGainDb(first("R128_TRACK_GAIN")),
            albumNormalizationGainDb = parseReplayGainDb(first("REPLAYGAIN_ALBUM_GAIN"))
                ?: parseR128GainToReplayGainDb(first("R128_ALBUM_GAIN"))
        )
    }

    private fun parseFlacPicture(block: ByteArray): ByteArray? = runCatching {
        val b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        if (b.remaining() < 8) return@runCatching null
        b.int // picture type
        val mimeLen = b.int.safeLength(b.remaining()) ?: return@runCatching null
        b.position(b.position() + mimeLen)
        if (b.remaining() < 4) return@runCatching null
        val descLen = b.int.safeLength(b.remaining()) ?: return@runCatching null
        b.position(b.position() + descLen)
        if (b.remaining() < 20) return@runCatching null
        repeat(4) { b.int } // width, height, depth, indexed colors
        val dataLen = b.int.safeLength(b.remaining()) ?: return@runCatching null
        ByteArray(dataLen).also { b.get(it) }
    }.getOrNull()

    private fun readWav(uri: Uri, includeArtwork: Boolean): EmbeddedMetadata {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            if (input.readAscii(4) != "RIFF") return EmbeddedMetadata()
            input.skipFully(4) // RIFF size
            if (input.readAscii(4) != "WAVE") return EmbeddedMetadata()

            var metadata = EmbeddedMetadata()
            while (true) {
                val chunkId = runCatching { input.readAscii(4) }.getOrNull() ?: break
                val sizeBytes = ByteArray(4)
                if (!input.tryReadFully(sizeBytes)) break
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (chunkSize > Int.MAX_VALUE && (chunkId == "LIST" || chunkId.equals("id3 ", true))) {
                    input.skipFully(chunkSize)
                } else when {
                    chunkId == "fmt " -> metadata = metadata.mergedWith(parseWavFormat(input.readExact(chunkSize.toInt())))
                    chunkId == "LIST" -> metadata = metadata.mergedWith(parseWavListInfo(input.readExact(chunkSize.toInt())))
                    chunkId.equals("id3 ", true) || chunkId == "ID3 " -> {
                        metadata = metadata.mergedWith(parseId3(input.readExact(chunkSize.toInt()), includeArtwork))
                    }
                    else -> input.skipFully(chunkSize)
                }
                if ((chunkSize and 1L) == 1L) input.skipFully(1) // RIFF chunks are word aligned
            }
            return metadata
        }
        return EmbeddedMetadata()
    }

    private fun parseWavFormat(bytes: ByteArray): EmbeddedMetadata {
        if (bytes.size < 16) return EmbeddedMetadata()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.short // format code
        val channels = b.short.toInt() and 0xFFFF
        val sampleRate = b.int
        b.int // byte rate
        b.short // block align
        val bitsPerSample = b.short.toInt() and 0xFFFF
        return EmbeddedMetadata(
            sampleRateHz = sampleRate.takeIf { it > 0 },
            bitDepth = bitsPerSample.takeIf { it > 0 },
            channelCount = channels.takeIf { it > 0 }
        )
    }

    private fun parseWavListInfo(bytes: ByteArray): EmbeddedMetadata {
        if (bytes.size < 4 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "INFO") return EmbeddedMetadata()
        val b = ByteBuffer.wrap(bytes, 4, bytes.size - 4).slice().order(ByteOrder.LITTLE_ENDIAN)
        val values = mutableMapOf<String, String>()
        while (b.remaining() >= 8) {
            val idBytes = ByteArray(4).also { b.get(it) }
            val id = idBytes.toString(Charsets.US_ASCII)
            val len = b.int
            if (len < 0 || len > b.remaining()) break
            val valueBytes = ByteArray(len).also { b.get(it) }
            val value = decodeRiffInfoText(valueBytes)
            if (value.isNotBlank()) values[id] = value
            if ((len and 1) == 1 && b.hasRemaining()) b.get()
        }
        return EmbeddedMetadata(
            title = values["INAM"],
            artist = values["IART"],
            album = values["IPRD"],
            trackNumber = parseNumber(values["ITRK"] ?: values["IPRT"]),
            year = values["ICRD"],
            genre = values["IGNR"]
        )
    }

    private fun parseId3(tag: ByteArray, includeArtwork: Boolean): EmbeddedMetadata {
        if (tag.size < 10 || tag.copyOfRange(0, 3).toString(Charsets.US_ASCII) != "ID3") return EmbeddedMetadata()
        val version = tag[3].toInt() and 0xFF
        if (version !in 3..4) return EmbeddedMetadata()
        val flags = tag[5].toInt() and 0xFF
        val declared = synchsafe(tag, 6).coerceAtMost(tag.size - 10)
        var pos = 10
        val end = (10 + declared).coerceAtMost(tag.size)

        if ((flags and 0x40) != 0 && pos + 4 <= end) {
            val extSize = if (version == 4) synchsafe(tag, pos) else readBeInt(tag, pos)
            pos += if (version == 4) extSize else extSize + 4
            if (pos > end) return EmbeddedMetadata()
        }

        var result = EmbeddedMetadata()
        while (pos + 10 <= end) {
            val id = tag.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
            if (id.all { it == '\u0000' }) break
            val size = if (version == 4) synchsafe(tag, pos + 4) else readBeInt(tag, pos + 4)
            if (size <= 0 || pos + 10 + size > end) break
            val payload = tag.copyOfRange(pos + 10, pos + 10 + size)
            when (id) {
                "TIT2" -> result = result.copy(title = decodeId3Text(payload))
                "TPE1" -> result = result.copy(artist = decodeId3Text(payload))
                "TALB" -> result = result.copy(album = decodeId3Text(payload))
                "TPE2" -> result = result.copy(albumArtist = decodeId3Text(payload))
                "TRCK" -> result = result.copy(trackNumber = parseNumber(decodeId3Text(payload)))
                "TPOS" -> result = result.copy(discNumber = parseNumber(decodeId3Text(payload)))
                "TDRC", "TYER" -> result = result.copy(year = decodeId3Text(payload))
                "TCON" -> result = result.copy(genre = decodeId3Text(payload))
                "TXXX" -> {
                    val userText = decodeId3UserText(payload)
                    val key = userText?.first?.trim()?.uppercase()
                    val value = userText?.second
                    when (key) {
                        "REPLAYGAIN_TRACK_GAIN" -> parseReplayGainDb(value)?.let { gain ->
                            if (result.normalizationGainDb == null) result = result.copy(normalizationGainDb = gain)
                        }
                        "R128_TRACK_GAIN" -> parseR128GainToReplayGainDb(value)?.let { gain ->
                            if (result.normalizationGainDb == null) result = result.copy(normalizationGainDb = gain)
                        }
                        "REPLAYGAIN_ALBUM_GAIN" -> parseReplayGainDb(value)?.let { gain ->
                            if (result.albumNormalizationGainDb == null) result = result.copy(albumNormalizationGainDb = gain)
                        }
                        "R128_ALBUM_GAIN" -> parseR128GainToReplayGainDb(value)?.let { gain ->
                            if (result.albumNormalizationGainDb == null) result = result.copy(albumNormalizationGainDb = gain)
                        }
                    }
                }
                "APIC" -> if (includeArtwork && result.artworkBytes == null) {
                    result = result.copy(artworkBytes = parseApic(payload))
                }
            }
            pos += 10 + size
        }
        return result
    }

    private fun decodeId3Text(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
        return payload.copyOfRange(1, payload.size).toString(charset).trim('\u0000', ' ', '\r', '\n').clean()
    }

    private fun decodeId3UserText(payload: ByteArray): Pair<String, String>? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        val body = payload.copyOfRange(1, payload.size)
        val separatorBytes = if (encoding == 1 || encoding == 2) 2 else 1
        var split = -1
        var i = 0
        while (i <= body.size - separatorBytes) {
            val zero = if (separatorBytes == 2) {
                body[i].toInt() == 0 && body[i + 1].toInt() == 0
            } else {
                body[i].toInt() == 0
            }
            if (zero) {
                split = i
                break
            }
            i += separatorBytes
        }
        if (split < 0) return null
        val description = body.copyOfRange(0, split).toString(charset).trim('\u0000', ' ', '\r', '\n')
        val valueStart = (split + separatorBytes).coerceAtMost(body.size)
        val value = body.copyOfRange(valueStart, body.size).toString(charset).trim('\u0000', ' ', '\r', '\n')
        if (description.isBlank() || value.isBlank()) return null
        return description to value
    }

    private fun parseApic(payload: ByteArray): ByteArray? = runCatching {
        if (payload.size < 5) return@runCatching null
        val enc = payload[0].toInt() and 0xFF
        var pos = 1
        while (pos < payload.size && payload[pos].toInt() != 0) pos++ // MIME
        pos++
        if (pos >= payload.size) return@runCatching null
        pos++ // picture type

        if (enc == 1 || enc == 2) {
            while (pos + 1 < payload.size) {
                if (payload[pos].toInt() == 0 && payload[pos + 1].toInt() == 0) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < payload.size && payload[pos].toInt() != 0) pos++
            pos++
        }
        if (pos >= payload.size) null else payload.copyOfRange(pos, payload.size)
    }.getOrNull()

    private fun readWithMediaMetadataRetriever(uri: Uri, includeArtwork: Boolean): EmbeddedMetadata {
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                EmbeddedMetadata(
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    trackNumber = parseNumber(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    discNumber = parseNumber(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                    year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    sampleRateHz = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                    bitDepth = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
                    artworkBytes = if (includeArtwork) r.embeddedPicture else null
                )
            } finally {
                r.release()
            }
        }.getOrDefault(EmbeddedMetadata())
    }
}

private fun String?.clean(): String? = UnicodeText.display(this)?.takeUnless {
    it.equals("<unknown>", true) || it.equals("unknown", true)
}

/**
 * RIFF LIST/INFO does not define one universal character encoding and real music libraries contain
 * UTF-8, UTF-16, CP949/EUC-KR, Shift-JIS and Windows-1252 tags. Decode conservatively without
 * rewriting punctuation or transliterating the metadata.
 */
internal fun decodeRiffInfoText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    fun cleaned(text: String): String = UnicodeText.display(text.trim('\u0000', ' ', '\r', '\n')) ?: ""

    // Explicit BOMs are authoritative.
    if (bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        if (b0 == 0xff && b1 == 0xfe) return cleaned(bytes.toString(Charsets.UTF_16LE))
        if (b0 == 0xfe && b1 == 0xff) return cleaned(bytes.toString(Charsets.UTF_16BE))
    }

    // Some RIFF writers emit UTF-16 without a BOM. Alternating NULs are a strong signal.
    if (bytes.size >= 6) {
        val oddZeros = (1 until bytes.size step 2).count { bytes[it].toInt() == 0 }
        val evenZeros = (0 until bytes.size step 2).count { bytes[it].toInt() == 0 }
        val half = bytes.size / 2
        if (oddZeros >= half / 2) return cleaned(bytes.toString(Charsets.UTF_16LE))
        if (evenZeros >= half / 2) return cleaned(bytes.toString(Charsets.UTF_16BE))
    }

    // Prefer strict UTF-8 when the byte sequence is actually valid UTF-8.
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null) return cleaned(utf8)

    data class Candidate(val text: String, val score: Int)
    fun score(text: String): Int {
        var points = 0
        for (ch in text) {
            when (ch.code) {
                in 0xAC00..0xD7AF -> points += 8 // Hangul syllables
                in 0x1100..0x11FF, in 0x3130..0x318F -> points += 6 // Hangul Jamo
                in 0x3040..0x30FF -> points += 7 // Hiragana / Katakana
                in 0x4E00..0x9FFF -> points += 4 // CJK
                0xFFFD -> points -= 20
                in 0x00..0x08, in 0x0B..0x1F -> points -= 5
            }
        }
        return points
    }

    val candidates = listOf("x-windows-949", "EUC-KR", "Shift_JIS", "windows-1252")
        .mapNotNull { name ->
            runCatching { cleaned(bytes.toString(Charset.forName(name))) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Candidate(it, score(it)) }
        }

    return candidates.maxByOrNull { it.score }?.text ?: cleaned(bytes.toString(Charsets.ISO_8859_1))
}

private fun parseReplayGainDb(value: String?): Float? {
    val text = value?.trim() ?: return null
    val numeric = text.replace(Regex("""\s*[dD][bB]\s*$"""), "").trim()
    return numeric.toFloatOrNull()?.takeIf { it.isFinite() && it in -60f..60f }
}

private fun parseR128GainToReplayGainDb(value: String?): Float? {
    val q78 = value?.trim()?.toIntOrNull() ?: return null
    // R128_TRACK_GAIN is Q7.8 dB referenced to -23 LUFS. ReplayGain's traditional
    // target is approximately -18 LUFS, so shift the stored gain by +5 dB.
    return (q78 / 256f + 5f).takeIf { it.isFinite() && it in -60f..60f }
}

private fun parseNumber(value: String?): Int? = value
    ?.substringBefore('/')
    ?.trim()
    ?.filter { it.isDigit() }
    ?.takeIf { it.isNotEmpty() }
    ?.toIntOrNull()

private fun Int.safeLength(remaining: Int): Int? = takeIf { it >= 0 && it <= remaining }

private fun InputStream.readAscii(count: Int): String = readExact(count).toString(Charsets.US_ASCII)

private fun InputStream.readExact(count: Int): ByteArray = ByteArray(count).also { readFully(it) }

private fun InputStream.readFully(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) throw EOFException()
        offset += read
    }
}

private fun InputStream.tryReadFully(buffer: ByteArray): Boolean {
    return runCatching { readFully(buffer); true }.getOrDefault(false)
}

private fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() == -1) throw EOFException()
            remaining--
        }
    }
}

private fun synchsafe(bytes: ByteArray, offset: Int): Int {
    if (offset + 3 >= bytes.size) return 0
    return ((bytes[offset].toInt() and 0x7F) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
        (bytes[offset + 3].toInt() and 0x7F)
}

private fun readBeInt(bytes: ByteArray, offset: Int): Int {
    if (offset + 3 >= bytes.size) return 0
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
