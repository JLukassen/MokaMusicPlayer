package com.mokamusic.player.audio.dsp

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DspFileLoader(private val context: Context) {
    fun loadVdc(uriText: String?): VdcProfile? {
        if (uriText.isNullOrBlank()) return null
        val uri = Uri.parse(uriText)
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readCapped(MAX_VDC_BYTES)
            bytes.toString(Charsets.UTF_8)
        } ?: return null
        return VdcParser.parse(text)
    }

    fun loadIrs(uriText: String?): IrsData? {
        if (uriText.isNullOrBlank()) return null
        val uri = Uri.parse(uriText)
        val head = context.contentResolver.openInputStream(uri)?.use { input ->
            val h = ByteArray(12)
            val n = input.read(h)
            if (n > 0) h.copyOf(n) else ByteArray(0)
        } ?: return null
        if (head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readCapped(MAX_IR_BYTES)
            } ?: return null
            return IrsWaveParser.parse(bytes)
        }
        return decodeFlacImpulse(uri)
    }

    private fun decodeFlacImpulse(uri: Uri): IrsData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!mime.contains("flac", true)) return null
            val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: return null
            val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: return null
            if (channels !in 1..4) return null

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val channelOut = Array(channels) { ArrayList<Float>() }
                var inputEnd = false
                var outputEnd = false
                var outEncoding = AudioFormat.ENCODING_PCM_16BIT
                while (!outputEnd) {
                    if (!inputEnd) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val input = codec.getInputBuffer(idx) ?: return null
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnd = true
                            } else {
                                codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val oi = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outEncoding = codec.outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                        }
                        else -> if (oi >= 0) {
                            val b = codec.getOutputBuffer(oi)
                            if (b != null && info.size > 0) {
                                b.position(info.offset); b.limit(info.offset + info.size)
                                val bytesPer = when (outEncoding) {
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    else -> 2
                                }
                                val samples = info.size / bytesPer
                                val data = PcmFloatCodec.decode(b.slice().order(ByteOrder.LITTLE_ENDIAN), outEncoding, samples)
                                data.forEachIndexed { si, v -> channelOut[si % channels].add(v) }
                                if (channelOut[0].size > MAX_IR_FRAMES) return null
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnd = true
                            codec.releaseOutputBuffer(oi, false)
                        }
                    }
                }
                return IrsData(rate, Array(channels) { ch -> FloatArray(channelOut[ch].size) { channelOut[ch][it] } })
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
    private companion object {
        const val MAX_VDC_BYTES = 2 * 1024 * 1024
        const val MAX_IR_BYTES = 64 * 1024 * 1024
        const val MAX_IR_FRAMES = 2_000_000
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun java.io.InputStream.readCapped(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        require(total <= maxBytes) { "DSP tuning file is too large" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
