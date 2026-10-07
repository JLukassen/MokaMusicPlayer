package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.mokamusic.player.model.MusicTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline BS.1770-style loudness analyzer used to fill normalization metadata when files have no
 * ReplayGain/R128 tags. It uses K-weighting and the BS.1770 absolute/relative block gates.
 *
 * WAV/PCM is read directly from RIFF instead of being routed through Android's raw MediaCodec.
 * Compressed formats continue to use MediaExtractor + MediaCodec.
 *
 * The reported true-peak value is a 4x inter-sample estimate, intentionally labelled estimated
 * rather than standards-certified dBTP. It is diagnostic; normalization is driven by loudness.
 */
class Bs1770LoudnessAnalyzer(private val context: Context) {
    data class Result(
        val integratedLufs: Float,
        val estimatedTruePeakDbtp: Float,
        val trackGainDb: Float,
        val analysisTimeMs: Long,
        val decoderPath: String
    )

    fun analyze(track: MusicTrack, targetLufs: Float = -18f): Result {
        val started = SystemClock.elapsedRealtime()
        val wavCandidate =
            track.displayName.endsWith(".wav", true) ||
                track.displayName.endsWith(".wave", true) ||
                track.mimeType?.contains("wav", true) == true ||
                track.mimeType?.contains("wave", true) == true

        if (wavCandidate) {
            runCatching { analyzeWavDirect(track, targetLufs) }
                .onSuccess { raw ->
                    return raw.toResult(targetLufs, SystemClock.elapsedRealtime() - started, "Direct WAV PCM")
                }
                .onFailure { error ->
                    Log.w(LOG_TAG, "Direct WAV path failed for ${track.displayName}; falling back to MediaCodec", error)
                }
        }

        val raw = analyzeWithMediaCodec(track)
        return raw.toResult(targetLufs, SystemClock.elapsedRealtime() - started, "MediaCodec")
    }

    private fun analyzeWithMediaCodec(track: MusicTrack): RawResult {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, track.uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio stream")
            extractor.selectTrack(index)
            val inputFormat = extractor.getTrackFormat(index)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME missing")
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                return decodeCodec(codec, extractor)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeCodec(codec: MediaCodec, extractor: MediaExtractor): RawResult {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var accumulator: LoudnessAccumulator? = null
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var channels = 2

        fun updateOutputFormat() {
            val f = codec.outputFormat
            val rate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: error("Decoder sample rate missing")
            channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: error("Decoder channel count missing")
            require(channels in 1..2) { "Loudness scan supports mono/stereo" }
            encoding = f.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
            accumulator = LoudnessAccumulator(rate, channels)
        }

        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: error("Decoder input missing")
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateOutputFormat()
                else -> if (outputIndex >= 0) {
                    if (accumulator == null) updateOutputFormat()
                    val output = codec.getOutputBuffer(outputIndex)
                    if (info.size > 0 && output != null) {
                        val bytesPerSample = bytesPerSample(encoding)
                        val samples = info.size / bytesPerSample
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        accumulator?.processPcm(
                            output.slice().order(ByteOrder.LITTLE_ENDIAN),
                            encoding,
                            samples
                        )
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        return (accumulator ?: error("Decoder produced no PCM")).finish()
    }

    /**
     * Fast path for ordinary RIFF/WAVE PCM. MediaCodec's audio/raw component is unnecessary here
     * and showed a ~10x device-to-device performance swing during Beta 2 testing.
     */
    private fun analyzeWavDirect(track: MusicTrack, targetLufs: Float): RawResult {
        val pfd = context.contentResolver.openFileDescriptor(track.uri, "r")
            ?: error("Unable to open WAV")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val channel = input.channel
            val info = parseWav(channel)
            require(info.channels in 1..2) { "Loudness scan supports mono/stereo" }

            val accumulator = LoudnessAccumulator(info.sampleRate, info.channels)
            val bytesPerSample = bytesPerSample(info.encoding)
            val frameSize = info.blockAlign.coerceAtLeast(bytesPerSample * info.channels)
            val capacity = (DIRECT_WAV_BUFFER_BYTES / frameSize).coerceAtLeast(1) * frameSize
            val buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(info.dataOffset)
            var remaining = info.dataSize

            while (remaining >= frameSize) {
                val wanted = minOf(buffer.capacity().toLong(), remaining).toInt()
                val aligned = wanted - (wanted % frameSize)
                if (aligned <= 0) break

                buffer.clear()
                buffer.limit(aligned)
                var readTotal = 0
                while (readTotal < aligned) {
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        Thread.yield()
                        continue
                    }
                    readTotal += read
                }
                if (readTotal <= 0) break

                val completeBytes = readTotal - (readTotal % frameSize)
                if (completeBytes <= 0) break
                buffer.flip()
                buffer.limit(completeBytes)
                accumulator.processPcm(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), info.encoding, completeBytes / bytesPerSample)
                remaining -= completeBytes.toLong()

                if (readTotal < aligned) break
            }

            return accumulator.finish()
        }
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val encoding: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private fun parseWav(channel: FileChannel): WavInfo {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0L)
        readFully(channel, header)
        header.flip()

        val riff = ByteArray(4).also(header::get).toString(Charsets.US_ASCII)
        header.int
        val wave = ByteArray(4).also(header::get).toString(Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Unsupported WAV container" }

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var formatCode = 0
        var extensibleSubFormat = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataSize = -1L

        val chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (channel.position() + 8 <= channel.size()) {
            chunkHeader.clear()
            readFully(channel, chunkHeader)
            chunkHeader.flip()
            val id = ByteArray(4).also(chunkHeader::get).toString(Charsets.US_ASCII)
            val size = chunkHeader.int.toLong() and 0xffffffffL
            val chunkStart = channel.position()

            when (id) {
                "fmt " -> {
                    val toRead = minOf(size, 64L).toInt()
                    val fmt = ByteBuffer.allocate(toRead).order(ByteOrder.LITTLE_ENDIAN)
                    readFully(channel, fmt)
                    fmt.flip()
                    require(fmt.remaining() >= 16) { "Invalid WAV fmt chunk" }
                    formatCode = fmt.short.toInt() and 0xffff
                    channels = fmt.short.toInt() and 0xffff
                    sampleRate = fmt.int
                    fmt.int // byte rate
                    blockAlign = fmt.short.toInt() and 0xffff
                    bits = fmt.short.toInt() and 0xffff
                    if (formatCode == WAVE_FORMAT_EXTENSIBLE && fmt.remaining() >= 24) {
                        val cbSize = fmt.short.toInt() and 0xffff
                        if (cbSize >= 22 && fmt.remaining() >= 22) {
                            fmt.short // valid bits
                            fmt.int // channel mask
                            extensibleSubFormat = fmt.short.toInt() and 0xffff
                        }
                    }
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = minOf(size, (channel.size() - chunkStart).coerceAtLeast(0L))
                }
            }

            val next = chunkStart + size + (size and 1L)
            channel.position(next.coerceAtMost(channel.size()))
            if (dataOffset >= 0 && sampleRate > 0) break
        }

        require(dataOffset >= 0 && dataSize > 0 && sampleRate > 0 && channels > 0 && bits > 0 && blockAlign > 0) {
            "Incomplete WAV header"
        }
        val effectiveFormat = if (formatCode == WAVE_FORMAT_EXTENSIBLE) extensibleSubFormat else formatCode
        val encoding = when (effectiveFormat) {
            WAVE_FORMAT_PCM -> bitDepthToEncoding(bits)
            WAVE_FORMAT_IEEE_FLOAT -> if (bits == 32) AudioFormat.ENCODING_PCM_FLOAT else null
            else -> null
        } ?: error("Unsupported WAV PCM format code=$effectiveFormat bits=$bits")

        return WavInfo(sampleRate, channels, encoding, blockAlign, dataOffset, dataSize)
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) error("Unexpected end of WAV")
        }
    }

    private data class RawResult(val integratedLufs: Float, val truePeakDbtp: Float)

    private fun RawResult.toResult(targetLufs: Float, elapsedMs: Long, path: String): Result {
        val gain = (targetLufs - integratedLufs).coerceIn(-30f, 20f)
        return Result(integratedLufs, truePeakDbtp, gain, elapsedMs, path)
    }

    private class LoudnessAccumulator(sampleRate: Int, private val channels: Int) {
        private val shelf = Array(channels) { Biquad(highShelf(sampleRate.toDouble(), 1681.974450955533, 3.999843853973347)) }
        private val highPass = Array(channels) { Biquad(highPass(sampleRate.toDouble(), 38.13547087602444, 0.5003270373238773)) }
        private val segmentFrames = max(1, sampleRate / 10)
        private var segmentFrameCount = 0
        private var segmentEnergy = 0.0
        private val segments = ArrayList<Double>()
        private var samplePeak = 0f
        private var intersamplePeak = 0f
        private val history = Array(channels) { FloatArray(4) }
        private var historyCount = 0

        fun processPcm(buffer: ByteBuffer, encoding: Int, samples: Int) {
            val frames = samples / channels
            for (frame in 0 until frames) {
                var weightedPower = 0.0
                for (ch in 0 until channels) {
                    val raw = readPcmSample(buffer, encoding)
                    val x = if (raw.isFinite()) raw else 0f
                    samplePeak = max(samplePeak, abs(x))
                    pushPeak(ch, x)
                    val y = highPass[ch].process(shelf[ch].process(x.toDouble()))
                    weightedPower += y * y
                }
                segmentEnergy += weightedPower
                segmentFrameCount++
                if (segmentFrameCount >= segmentFrames) flushSegment()
            }
        }

        private fun pushPeak(ch: Int, value: Float) {
            val h = history[ch]
            h[0] = h[1]
            h[1] = h[2]
            h[2] = h[3]
            h[3] = value
            if (historyCount >= 3) {
                val ym1 = h[0]
                val y0 = h[1]
                val y1 = h[2]
                val y2 = h[3]
                for (step in 1..3) {
                    val t = step / 4f
                    val t2 = t * t
                    val t3 = t2 * t
                    val y = 0.5f * (
                        2f * y0 +
                            (-ym1 + y1) * t +
                            (2f * ym1 - 5f * y0 + 4f * y1 - y2) * t2 +
                            (-ym1 + 3f * y0 - 3f * y1 + y2) * t3
                        )
                    intersamplePeak = max(intersamplePeak, abs(y))
                }
            }
            if (ch == channels - 1) historyCount++
        }

        private fun flushSegment() {
            if (segmentFrameCount > 0) {
                segments += segmentEnergy / segmentFrameCount.toDouble()
                segmentEnergy = 0.0
                segmentFrameCount = 0
            }
        }

        fun finish(): RawResult {
            flushSegment()
            require(segments.isNotEmpty()) { "No PCM samples decoded" }
            val blocks = ArrayList<Double>()
            if (segments.size < 4) {
                blocks += segments.average()
            } else {
                for (i in 3 until segments.size) {
                    blocks += (segments[i] + segments[i - 1] + segments[i - 2] + segments[i - 3]) / 4.0
                }
            }

            val absGated = blocks.filter { lufs(it) >= -70.0 }
            val firstPass = if (absGated.isNotEmpty()) absGated else blocks
            val ungatedLufs = lufs(firstPass.average())
            val relativeGate = ungatedLufs - 10.0
            val finalGate = max(-70.0, relativeGate)
            val gated = firstPass.filter { lufs(it) >= finalGate }
            val integrated = lufs((if (gated.isNotEmpty()) gated else firstPass).average()).toFloat()
            val peak = max(samplePeak, intersamplePeak)
            val peakDb = if (peak <= 1e-9f) -120f else (20.0 * log10(peak.toDouble())).toFloat()
            return RawResult(integrated, peakDb)
        }

        private fun lufs(energy: Double): Double =
            if (energy <= 1e-20) -120.0 else -0.691 + 10.0 * log10(energy)
    }

    private data class Coeff(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    private class Biquad(private val c: Coeff) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = c.b0 * x + z1
            z1 = c.b1 * x - c.a1 * y + z2
            z2 = c.b2 * x - c.a2 * y
            return y
        }
    }

    private companion object {
        const val LOG_TAG = "MokaLoudness"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DIRECT_WAV_BUFFER_BYTES = 256 * 1024
        const val WAVE_FORMAT_PCM = 0x0001
        const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        const val WAVE_FORMAT_EXTENSIBLE = 0xfffe

        fun highPass(fs: Double, f0: Double, q: Double): Coeff {
            val w0 = 2.0 * PI * f0 / fs
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * q)
            val b0 = (1.0 + cw) / 2.0
            val b1 = -(1.0 + cw)
            val b2 = (1.0 + cw) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cw
            val a2 = 1.0 - alpha
            return Coeff(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        fun highShelf(fs: Double, f0: Double, gainDb: Double): Coeff {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / 2.0 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1.0) + (a - 1.0) * cw + twoSqrtAAlpha)
            val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
            val b2 = a * ((a + 1.0) + (a - 1.0) * cw - twoSqrtAAlpha)
            val a0 = (a + 1.0) - (a - 1.0) * cw + twoSqrtAAlpha
            val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
            val a2 = (a + 1.0) - (a - 1.0) * cw - twoSqrtAAlpha
            return Coeff(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Unsupported PCM encoding=$encoding")
        }

        fun bitDepthToEncoding(bitDepth: Int): Int? = when (bitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> null
        }

        fun readPcmSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT ->
                ((buffer.get().toInt() and 0xff) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT ->
                buffer.short / 32768f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var value =
                    (buffer.get().toInt() and 0xff) or
                        ((buffer.get().toInt() and 0xff) shl 8) or
                        ((buffer.get().toInt() and 0xff) shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                value / 8388608f
            }
            AudioFormat.ENCODING_PCM_32BIT ->
                buffer.int / 2147483648f
            AudioFormat.ENCODING_PCM_FLOAT ->
                buffer.float
            else -> error("Unsupported PCM encoding=$encoding")
        }

        fun MediaFormat.intOrNull(key: String): Int? =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }
}
