package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mokamusic.player.model.MusicTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * The reported true-peak value is a 4x inter-sample estimate, intentionally labelled estimated
 * rather than standards-certified dBTP. It is diagnostic; normalization is driven by loudness.
 */
class Bs1770LoudnessAnalyzer(private val context: Context) {
    data class Result(
        val integratedLufs: Float,
        val estimatedTruePeakDbtp: Float,
        val trackGainDb: Float
    )

    fun analyze(track: MusicTrack, targetLufs: Float = -18f): Result {
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
                return decode(codec, extractor, targetLufs)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decode(codec: MediaCodec, extractor: MediaExtractor, targetLufs: Float): Result {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var accumulator: LoudnessAccumulator? = null
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var channels = 2

        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: error("Decoder input missing")
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

            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    val rate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: error("Decoder sample rate missing")
                    channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: error("Decoder channel count missing")
                    require(channels in 1..2) { "Loudness scan supports mono/stereo" }
                    encoding = f.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                    accumulator = LoudnessAccumulator(rate, channels)
                }
                else -> if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (info.size > 0 && output != null) {
                        val bytesPerSample = bytesPerSample(encoding)
                        val samples = info.size / bytesPerSample
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = decodePcm(output.slice().order(ByteOrder.LITTLE_ENDIAN), encoding, samples)
                        accumulator?.process(pcm)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        val raw = (accumulator ?: error("Decoder produced no PCM")).finish()
        val gain = (targetLufs - raw.integratedLufs).coerceIn(-30f, 20f)
        return Result(raw.integratedLufs, raw.truePeakDbtp, gain)
    }

    private data class RawResult(val integratedLufs: Float, val truePeakDbtp: Float)

    private class LoudnessAccumulator(sampleRate: Int, private val channels: Int) {
        private val shelf = Array(channels) { Biquad(highShelf(sampleRate.toDouble(), 1681.974450955533, 3.999843853973347)) }
        private val highPass = Array(channels) { Biquad(highPass(sampleRate.toDouble(), 38.13547087602444, 0.5003270373238773)) }
        private val segmentFrames = max(1, sampleRate / 10) // 100 ms
        private var segmentFrameCount = 0
        private var segmentEnergy = 0.0
        private val segments = ArrayList<Double>()
        private var samplePeak = 0f
        private var intersamplePeak = 0f
        private val history = Array(channels) { FloatArray(4) }
        private var historyCount = 0

        fun process(interleaved: FloatArray) {
            val frames = interleaved.size / channels
            for (frame in 0 until frames) {
                var weightedPower = 0.0
                for (ch in 0 until channels) {
                    val x = interleaved[frame * channels + ch].let { if (it.isFinite()) it else 0f }
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
            h[0] = h[1]; h[1] = h[2]; h[2] = h[3]; h[3] = value
            if (historyCount >= 3) {
                val ym1 = h[0]; val y0 = h[1]; val y1 = h[2]; val y2 = h[3]
                for (step in 1..3) {
                    val t = step / 4f
                    val t2 = t * t
                    val t3 = t2 * t
                    val y = 0.5f * (2f * y0 + (-ym1 + y1) * t +
                        (2f * ym1 - 5f * y0 + 4f * y1 - y2) * t2 +
                        (-ym1 + 3f * y0 - 3f * y1 + y2) * t3)
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

        private fun lufs(energy: Double): Double = if (energy <= 1e-20) -120.0 else -0.691 + 10.0 * log10(energy)
    }

    private data class Coeff(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

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
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

        fun decodePcm(buffer: ByteBuffer, encoding: Int, samples: Int): FloatArray {
            val out = FloatArray(samples)
            when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until samples) out[i] = ((buffer.get().toInt() and 0xff) - 128) / 128f
                AudioFormat.ENCODING_PCM_16BIT -> for (i in 0 until samples) out[i] = buffer.short / 32768f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until samples) {
                    var v = (buffer.get().toInt() and 0xff) or ((buffer.get().toInt() and 0xff) shl 8) or ((buffer.get().toInt() and 0xff) shl 16)
                    if (v and 0x800000 != 0) v = v or -0x1000000
                    out[i] = v / 8388608f
                }
                AudioFormat.ENCODING_PCM_32BIT -> for (i in 0 until samples) out[i] = buffer.int / 2147483648f
                AudioFormat.ENCODING_PCM_FLOAT -> for (i in 0 until samples) out[i] = buffer.float
                else -> for (i in 0 until samples) out[i] = buffer.short / 32768f
            }
            return out
        }

        fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }
}
