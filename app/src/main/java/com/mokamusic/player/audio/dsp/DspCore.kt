package com.mokamusic.player.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** One biquad section using the same denominator convention as ViPER/JDSP VDC files. */
data class Sos(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

data class VdcProfile(val bySampleRate: Map<Int, List<Sos>>) {
    fun sectionsFor(sampleRate: Int): List<Sos>? {
        bySampleRate[sampleRate]?.let { return it }
        val source = bySampleRate[48000] ?: bySampleRate[44100] ?: return null
        val sourceRate = if (bySampleRate.containsKey(48000)) 48000 else 44100
        return VdcResampler.resample(source, sourceRate.toDouble(), sampleRate.toDouble())
    }
}

object VdcParser {
    fun parse(text: String): VdcProfile {
        val map = linkedMapOf<Int, List<Sos>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("SR_")) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 3) return@forEach
            val rate = line.substring(3, colon).toIntOrNull() ?: return@forEach
            val values = line.substring(colon + 1).split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (values.size < 5 || values.size % 5 != 0) return@forEach
            val sections = values.chunked(5).map { v ->
                // VDC stores denominator terms with the opposite sign of the internal DF2 form.
                Sos(v[0], v[1], v[2], -v[3], -v[4])
            }
            map[rate] = sections
        }
        require(map.isNotEmpty()) { "No SR_xxxxx VDC coefficient blocks found" }
        return VdcProfile(map)
    }
}

private object VdcResampler {
    fun resample(input: List<Sos>, inFs: Double, outFs: Double): List<Sos> {
        if (input.isEmpty() || inFs <= 0.0 || outFs <= 0.0) return emptyList()
        val result = ArrayList<Sos>(input.size)
        input.forEach { sos ->
            val response = analyzePeakingSection(sos, inFs)
            if (response.centerHz < outFs && response.centerHz > 0.0) {
                result += designPeaking(response.gainDb, response.centerHz, outFs, response.bandwidth)
            }
        }
        return result
    }

    private data class Peak(val gainDb: Double, val centerHz: Double, val bandwidth: Double)

    private fun analyzePeakingSection(s: Sos, fs: Double): Peak {
        val n = 32768
        var bestAbs = -1.0
        var bestDb = 0.0
        var bestIndex = 1
        for (i in 0 until n) {
            val w = PI * i / n.toDouble()
            val z1r = cos(w)
            val z1i = -sin(w)
            val z2r = z1r * z1r - z1i * z1i
            val z2i = 2.0 * z1r * z1i
            val nr = s.b0 + s.b1 * z1r + s.b2 * z2r
            val ni = s.b1 * z1i + s.b2 * z2i
            val dr = 1.0 + s.a1 * z1r + s.a2 * z2r
            val di = s.a1 * z1i + s.a2 * z2i
            val den = dr * dr + di * di
            val mag = if (den <= 1e-30) 0.0 else sqrt((nr * nr + ni * ni) / den)
            val db = if (mag <= 1e-20) -400.0 else 20.0 * kotlin.math.log10(mag)
            if (abs(db) > bestAbs) {
                bestAbs = abs(db)
                bestDb = db
                bestIndex = i
            }
        }
        var center = round(bestIndex * (fs / n / 2.0))
        if (center <= 0.0) center = 1e-9
        var b1 = s.b1
        if (abs(b1) < 1e-12) b1 = if (b1 < 0.0) -1e-12 else 1e-12
        val omega = 2.0 * PI * center / fs
        val a0 = (-2.0 * cos(omega)) / b1
        val linGain = 10.0.pow(bestDb / 40.0)
        val sinw = sin(omega).let { if (abs(it) < 1e-12) 1e-12 else it }
        var bandwidth = (asinh(((a0 - 1.0) * linGain) / sinw) * sinw / omega) / 0.34657359027997264
        if (!bandwidth.isFinite()) bandwidth = 1.0
        bandwidth = bandwidth.coerceIn(1e-4, 98.8)
        return Peak(bestDb, center, bandwidth)
    }

    private fun designPeaking(dbGain: Double, center: Double, fs: Double, bandwidth: Double): Sos {
        val at1d3 = atanh(1.0 / 3.0)
        val linGain = 10.0.pow(dbGain / 40.0)
        val omega = 2.0 * PI * center / fs
        val sw = sin(omega).let { if (abs(it) < 1e-12) 1e-12 else it }
        val cs = cos(omega)
        val alpha = sw * sinh((at1d3 * bandwidth * omega) / sw)
        val B0 = 1.0 + alpha * linGain
        val B1 = -2.0 * cs
        val B2 = 1.0 - alpha * linGain
        val A0 = 1.0 + alpha / linGain
        val A1 = -2.0 * cs
        val A2 = 1.0 - alpha / linGain
        return Sos(B0 / A0, B1 / A0, B2 / A0, A1 / A0, A2 / A0)
    }
}

class SosCascade(private val sections: List<Sos>, channels: Int) {
    private val v1 = Array(channels) { DoubleArray(sections.size) }
    private val v2 = Array(channels) { DoubleArray(sections.size) }

    fun process(interleaved: FloatArray, channels: Int) {
        if (sections.isEmpty()) return
        val frames = interleaved.size / channels
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                var x = interleaved[frame * channels + ch].toDouble()
                for (i in sections.indices) {
                    val s = sections[i]
                    val w = x - s.a1 * v1[ch][i] - s.a2 * v2[ch][i]
                    x = s.b0 * w + s.b1 * v1[ch][i] + s.b2 * v2[ch][i]
                    v2[ch][i] = v1[ch][i]
                    v1[ch][i] = w
                }
                interleaved[frame * channels + ch] = x.toFloat()
            }
        }
    }

    fun reset() {
        v1.forEach { it.fill(0.0) }
        v2.forEach { it.fill(0.0) }
    }
}

data class IrsData(val sampleRate: Int, val channels: Array<FloatArray>) {
    val frameCount: Int get() = channels.firstOrNull()?.size ?: 0
}

/** Reads RIFF/WAVE-based .irs/.wav kernels (PCM 16/24/32 or IEEE float32). */
object IrsWaveParser {
    fun parse(bytes: ByteArray): IrsData {
        require(bytes.size >= 44 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "IRS is not RIFF/WAVE" }
        var p = 12
        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var blockAlign = 0
        var dataOffset = -1
        var dataSize = 0
        while (p + 8 <= bytes.size) {
            val id = bytes.copyOfRange(p, p + 4).toString(Charsets.US_ASCII)
            val size = u32le(bytes, p + 4).toInt()
            val start = p + 8
            if (start + size > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    formatTag = u16le(bytes, start)
                    channels = u16le(bytes, start + 2)
                    sampleRate = u32le(bytes, start + 4).toInt()
                    blockAlign = u16le(bytes, start + 12)
                    bits = u16le(bytes, start + 14)
                    if (formatTag == 0xfffe && size >= 40) formatTag = u16le(bytes, start + 24)
                }
                "data" -> { dataOffset = start; dataSize = size }
            }
            p = start + size + (size and 1)
        }
        require(dataOffset >= 0 && channels in 1..4 && sampleRate > 0 && blockAlign > 0) { "Incomplete IRS WAV header" }
        val frames = dataSize / blockAlign
        val out = Array(channels) { FloatArray(frames) }
        var q = dataOffset
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                out[ch][f] = when {
                    formatTag == 3 && bits == 32 -> Float.fromBits(u32le(bytes, q).toInt())
                    formatTag == 1 && bits == 16 -> s16le(bytes, q) / 32768f
                    formatTag == 1 && bits == 24 -> s24le(bytes, q) / 8388608f
                    formatTag == 1 && bits == 32 -> s32le(bytes, q) / 2147483648f
                    else -> error("Unsupported IRS format tag=$formatTag bits=$bits")
                }
                q += bits / 8
            }
        }
        return IrsData(sampleRate, out)
    }

    private fun u16le(a: ByteArray, o: Int): Int = (a[o].toInt() and 0xff) or ((a[o + 1].toInt() and 0xff) shl 8)
    private fun u32le(a: ByteArray, o: Int): Long = (a[o].toLong() and 0xff) or ((a[o + 1].toLong() and 0xff) shl 8) or
        ((a[o + 2].toLong() and 0xff) shl 16) or ((a[o + 3].toLong() and 0xff) shl 24)
    private fun s16le(a: ByteArray, o: Int): Short = u16le(a, o).toShort()
    private fun s24le(a: ByteArray, o: Int): Int {
        var v = (a[o].toInt() and 0xff) or ((a[o + 1].toInt() and 0xff) shl 8) or ((a[o + 2].toInt() and 0xff) shl 16)
        if (v and 0x800000 != 0) v = v or -0x1000000
        return v
    }
    private fun s32le(a: ByteArray, o: Int): Int = u32le(a, o).toInt()
}

object SincResampler {
    fun resample(input: FloatArray, inRate: Int, outRate: Int, halfTaps: Int = 24): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input.copyOf()
        val outLength = max(1, ((input.size.toLong() * outRate) / inRate).toInt())
        val out = FloatArray(outLength)
        val ratio = inRate.toDouble() / outRate.toDouble()
        val cutoff = min(1.0, outRate.toDouble() / inRate.toDouble())
        for (i in out.indices) {
            val src = i * ratio
            val center = src.toInt()
            var sum = 0.0
            var wsum = 0.0
            for (k in -halfTaps..halfTaps) {
                val idx = center + k
                if (idx !in input.indices) continue
                val x = src - idx
                val px = PI * x * cutoff
                val sinc = if (abs(px) < 1e-12) 1.0 else sin(px) / px
                val t = abs(x) / (halfTaps + 1.0)
                val window = if (t >= 1.0) 0.0 else 0.5 + 0.5 * cos(PI * t)
                val w = sinc * window * cutoff
                sum += input[idx] * w
                wsum += w
            }
            out[i] = if (abs(wsum) > 1e-12) (sum / wsum).toFloat() else 0f
        }
        return out
    }
}

private object FFT {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wlenR = cos(angle)
            val wlenI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vI = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nwr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n.toDouble(); im[i] /= n.toDouble() }
    }
}


private class FloatFftPlan(private val n: Int) {
    private val bitReverse = IntArray(n)
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        require(n > 0 && n and (n - 1) == 0)
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
        for (k in 0 until n / 2) {
            val angle = 2.0 * PI * k / n.toDouble()
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size == n && im.size == n)
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val step = n / len
            var base = 0
            while (base < n) {
                var tableIndex = 0
                for (k in 0 until half) {
                    val wr = cosTable[tableIndex]
                    val wi = if (inverse) sinTable[tableIndex] else -sinTable[tableIndex]
                    val j = base + k + half
                    val vr = re[j] * wr - im[j] * wi
                    val vi = re[j] * wi + im[j] * wr
                    val i = base + k
                    val ur = re[i]
                    val ui = im[i]
                    re[i] = ur + vr
                    im[i] = ui + vi
                    re[j] = ur - vr
                    im[j] = ui - vi
                    tableIndex += step
                }
                base += len
            }
            len = len shl 1
        }
        if (inverse) {
            val scale = 1f / n.toFloat()
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}

private object FloatFftPlans {
    private val plans = HashMap<Int, FloatFftPlan>()
    @Synchronized fun get(size: Int): FloatFftPlan =
        plans.getOrPut(size) { FloatFftPlan(size) }
}

private class MonoPartitionedConvolver(ir: FloatArray, private val blockSize: Int) {
    private val fftSize = blockSize * 2
    private val plan = FloatFftPlans.get(fftSize)
    private val parts = max(1, (ir.size + blockSize - 1) / blockSize)
    private val hR = Array(parts) { FloatArray(fftSize) }
    private val hI = Array(parts) { FloatArray(fftSize) }
    private val xR = Array(parts) { FloatArray(fftSize) }
    private val xI = Array(parts) { FloatArray(fftSize) }
    private val overlap = FloatArray(blockSize)
    private val yR = FloatArray(fftSize)
    private val yI = FloatArray(fftSize)
    private val output = FloatArray(blockSize)
    private var head = 0

    init {
        for (p in 0 until parts) {
            val r = hR[p]
            val start = p * blockSize
            val len = min(blockSize, ir.size - start)
            if (len > 0) System.arraycopy(ir, start, r, 0, len)
            plan.transform(r, hI[p], false)
        }
    }

    fun process(block: FloatArray): FloatArray {
        require(block.size == blockSize)
        val xr = xR[head]
        val xi = xI[head]
        xr.fill(0f)
        xi.fill(0f)
        System.arraycopy(block, 0, xr, 0, blockSize)
        plan.transform(xr, xi, false)

        yR.fill(0f)
        yI.fill(0f)
        for (p in 0 until parts) {
            var hist = head - p
            if (hist < 0) hist += parts
            val ar = xR[hist]
            val ai = xI[hist]
            val br = hR[p]
            val bi = hI[p]
            for (k in 0 until fftSize) {
                yR[k] += ar[k] * br[k] - ai[k] * bi[k]
                yI[k] += ar[k] * bi[k] + ai[k] * br[k]
            }
        }
        plan.transform(yR, yI, true)
        for (i in 0 until blockSize) {
            output[i] = yR[i] + overlap[i]
            overlap[i] = yR[i + blockSize]
        }
        head++
        if (head == parts) head = 0
        return output
    }

    fun reset() {
        xR.forEach { it.fill(0f) }
        xI.forEach { it.fill(0f) }
        overlap.fill(0f)
        yR.fill(0f)
        yI.fill(0f)
        output.fill(0f)
        head = 0
    }
}

class StereoPartitionedConvolver(
    ir: IrsData,
    sampleRate: Int,
    private val channels: Int,
    private val blockSize: Int = 1024,
    gainDb: Float = 0f
) {
    private val gain = 10.0.pow(gainDb / 20.0).toFloat()
    private val irChannels: Array<FloatArray> = Array(ir.channels.size) { idx ->
        if (ir.sampleRate == sampleRate) ir.channels[idx].copyOf() else SincResampler.resample(ir.channels[idx], ir.sampleRate, sampleRate)
    }
    private val convs = when {
        channels == 1 -> arrayOf(MonoPartitionedConvolver(irChannels[0], blockSize))
        irChannels.size >= 4 -> Array(4) { MonoPartitionedConvolver(irChannels[it], blockSize) }
        irChannels.size >= 2 -> arrayOf(MonoPartitionedConvolver(irChannels[0], blockSize), MonoPartitionedConvolver(irChannels[1], blockSize))
        else -> arrayOf(MonoPartitionedConvolver(irChannels[0], blockSize), MonoPartitionedConvolver(irChannels[0], blockSize))
    }
    private val leftScratch = FloatArray(blockSize)
    private val rightScratch = FloatArray(blockSize)
    private val outputScratch = FloatArray(blockSize * channels)

    fun process(interleaved: FloatArray): FloatArray {
        require(interleaved.size == blockSize * channels)
        if (channels == 1) {
            System.arraycopy(interleaved, 0, leftScratch, 0, blockSize)
            val y = convs[0].process(leftScratch)
            for (i in 0 until blockSize) outputScratch[i] = y[i] * gain
            return outputScratch
        }
        for (i in 0 until blockSize) {
            leftScratch[i] = interleaved[i * 2]
            rightScratch[i] = interleaved[i * 2 + 1]
        }
        if (convs.size == 4) {
            val ll = convs[0].process(leftScratch)
            val lr = convs[1].process(leftScratch)
            val rl = convs[2].process(rightScratch)
            val rr = convs[3].process(rightScratch)
            for (i in 0 until blockSize) {
                outputScratch[i * 2] = (ll[i] + rl[i]) * gain
                outputScratch[i * 2 + 1] = (lr[i] + rr[i]) * gain
            }
        } else {
            val yl = convs[0].process(leftScratch)
            val yr = convs[1].process(rightScratch)
            for (i in 0 until blockSize) {
                outputScratch[i * 2] = yl[i] * gain
                outputScratch[i * 2 + 1] = yr[i] * gain
            }
        }
        return outputScratch
    }

    fun reset() = convs.forEach { it.reset() }
}

private interface CurveInterpolator { fun value(x: Double): Double }

private class MakimaInterpolator(private val x: DoubleArray, private val y: DoubleArray) : CurveInterpolator {
    private val m = DoubleArray(x.size)
    init {
        require(x.size == y.size && x.size >= 4)
        val n = x.size
        val d = DoubleArray(n - 1) { i -> (y[i + 1] - y[i]) / (x[i + 1] - x[i]) }
        val de = DoubleArray(n + 3)
        for (i in d.indices) de[i + 2] = d[i]
        de[1] = 2 * de[2] - de[3]
        de[0] = 2 * de[1] - de[2]
        de[n + 1] = 2 * de[n] - de[n - 1]
        de[n + 2] = 2 * de[n + 1] - de[n]
        for (i in 0 until n) {
            val dm2 = de[i]
            val dm1 = de[i + 1]
            val di = de[i + 2]
            val dip1 = de[i + 3]
            val w1 = abs(dip1 - di) + 0.5 * abs(dip1 + di)
            val w2 = abs(dm1 - dm2) + 0.5 * abs(dm1 + dm2)
            m[i] = if (w1 + w2 > 1e-14) (w1 * dm1 + w2 * di) / (w1 + w2) else 0.5 * (dm1 + di)
        }
    }
    override fun value(v: Double): Double {
        if (v <= x.first()) return y.first()
        if (v >= x.last()) return y.last()
        var lo = 0; var hi = x.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (x[mid] <= v) lo = mid else hi = mid }
        val h = x[lo + 1] - x[lo]
        val t = (v - x[lo]) / h
        val t2 = t * t; val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * y[lo] + h10 * h * m[lo] + h01 * y[lo + 1] + h11 * h * m[lo + 1]
    }
}

private class PchipInterpolator(private val x: DoubleArray, private val y: DoubleArray) : CurveInterpolator {
    private val m = DoubleArray(x.size)
    init {
        val n = x.size
        val h = DoubleArray(n - 1) { x[it + 1] - x[it] }
        val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }
        if (n == 2) { m[0] = d[0]; m[1] = d[0] } else {
            m[0] = endpoint(h[0], h[1], d[0], d[1])
            m[n - 1] = endpoint(h[n - 2], h[n - 3], d[n - 2], d[n - 3])
            for (i in 1 until n - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else {
                    val w1 = 2 * h[i] + h[i - 1]
                    val w2 = h[i] + 2 * h[i - 1]
                    (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
        }
    }
    private fun endpoint(h0: Double, h1: Double, d0: Double, d1: Double): Double {
        var v = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (v * d0 <= 0) v = 0.0 else if (d0 * d1 < 0 && abs(v) > abs(3 * d0)) v = 3 * d0
        return v
    }
    override fun value(v: Double): Double {
        if (v <= x.first()) return y.first(); if (v >= x.last()) return y.last()
        var lo = 0; var hi = x.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (x[mid] <= v) lo = mid else hi = mid }
        val h = x[lo + 1] - x[lo]; val t = (v - x[lo]) / h; val t2 = t*t; val t3=t2*t
        return (2*t3-3*t2+1)*y[lo] + (t3-2*t2+t)*h*m[lo] + (-2*t3+3*t2)*y[lo+1] + (t3-t2)*h*m[lo+1]
    }
}

object MinimumPhaseEqGenerator {
    const val FILTER_LENGTH = 8192
    private const val FFT_SIZE = FILTER_LENGTH * 2
    fun generate(sampleRate: Int, gains: List<Float>, interpolation: EqInterpolator): FloatArray {
        require(gains.size == DspSettings.EQ_FREQUENCIES_HZ.size)
        val x = DoubleArray(gains.size + 2)
        val y = DoubleArray(gains.size + 2)
        x[0] = 0.0; y[0] = gains.first().toDouble()
        for (i in gains.indices) { x[i + 1] = DspSettings.EQ_FREQUENCIES_HZ[i].toDouble(); y[i + 1] = gains[i].toDouble().coerceIn(-64.0, 64.0) }
        x[x.lastIndex] = 24000.0; y[y.lastIndex] = gains.last().toDouble()
        val interp: CurveInterpolator = if (interpolation == EqInterpolator.MAKIMA) MakimaInterpolator(x, y) else PchipInterpolator(x, y)

        val logMagR = DoubleArray(FFT_SIZE)
        val logMagI = DoubleArray(FFT_SIZE)
        for (i in 0..FILTER_LENGTH) {
            val f = i * sampleRate.toDouble() / FFT_SIZE
            val db = interp.value(f)
            val lm = db * ln(10.0) / 20.0
            logMagR[i] = lm
            if (i != 0 && i != FILTER_LENGTH) logMagR[FFT_SIZE - i] = lm
        }
        FFT.transform(logMagR, logMagI, true)
        // Minimum-phase real cepstrum: retain zero/Nyquist, double positive quefrencies, zero negative.
        for (i in 1 until FILTER_LENGTH) { logMagR[i] *= 2.0; logMagI[i] *= 2.0 }
        for (i in FILTER_LENGTH + 1 until FFT_SIZE) { logMagR[i] = 0.0; logMagI[i] = 0.0 }
        FFT.transform(logMagR, logMagI, false)
        val hr = DoubleArray(FFT_SIZE)
        val hi = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val e = exp(logMagR[i])
            hr[i] = e * cos(logMagI[i]); hi[i] = e * sin(logMagI[i])
        }
        FFT.transform(hr, hi, true)
        return FloatArray(FILTER_LENGTH) { hr[it].toFloat() }
    }
}

/** JamesDSP-style multimodal high-order IIR equalizer. */
class MultimodalIirEq(sampleRate: Int, gains: List<Float>, mode: EqMode, private val channels: Int) {
    private data class Sec(val c1: Float, val c2: Float, val d0: Float, val d1: Float)
    private data class Stage(val sections: Array<Sec>, val overallGain: Float)
    private val stages: List<Stage>
    private val z1: Array<Array<FloatArray>>
    private val z2: Array<Array<FloatArray>>
    init {
        val order = when (mode) { EqMode.IIR_4 -> 4; EqMode.IIR_6 -> 6; EqMode.IIR_8 -> 8; EqMode.IIR_10 -> 10; EqMode.IIR_12 -> 12; else -> 4 }
        val f = DspSettings.EQ_FREQUENCIES_HZ
        stages = (0 until f.size - 1).map { i ->
            val delta = gains[i + 1] - gains[i]
            val designFreq = if (i == 0) f[i].toDouble() else (f[i + 1] + f[i]) * 0.5
            createStage(sampleRate.toDouble(), designFreq, order, delta.toDouble(), if (i == 0) gains[i].toDouble() else 0.0)
        }
        z1 = Array(channels) { Array(stages.size) { s -> FloatArray(stages[s].sections.size) } }
        z2 = Array(channels) { Array(stages.size) { s -> FloatArray(stages[s].sections.size) } }
    }
    private fun createStage(fs: Double, fc: Double, order: Int, gainDb: Double, overallDb: Double): Stage {
        val overall = 10.0.pow(overallDb / 20.0).toFloat()
        if (abs(gainDb) < 1e-12) return Stage(emptyArray(), overall)
        val L = order / 2
        val Dw = PI * (fc / fs - 0.5)
        val GB = 10.0.pow((1.0 / sqrt(2.0)) * gainDb / 20.0)
        val G = 10.0.pow(gainDb / 20.0)
        val gR = (G * G - GB * GB) / (GB * GB - 1.0)
        val ratOrd = gR.pow(1.0 / order)
        val ntD = tan(Dw); val ntD2 = ntD * ntD
        val stD = sin(Dw); val ctD = cos(Dw)
        val ratRO = gR.pow(1.0 / (2.0 * order))
        val gP1 = G.pow(1.0 / order); val gP2 = G.pow(2.0 / order)
        val secs = Array(L) { idx ->
            val si = sin((2.0 * (idx + 1) - 1.0) * PI / (2.0 * order))
            val den1 = ntD2 + ratOrd - 2.0 * ratRO * ntD * si
            val den2 = ratRO * ctD - si * stD
            Sec(
                (2.0 - 2.0 * (ntD2 - ratOrd) / den1).toFloat(),
                ((ratRO * ctD) / den2).toFloat(),
                ((ratOrd + gP2 * ntD2 - 2.0 * gP1 * ratRO * ntD * si) / den1).toFloat(),
                ((ratRO * ctD - gP1 * si * stD) / den2).toFloat()
            )
        }
        return Stage(secs, overall)
    }
    fun reset() {
        z1.forEach { stages -> stages.forEach { it.fill(0f) } }
        z2.forEach { stages -> stages.forEach { it.fill(0f) } }
    }
    fun process(a: FloatArray) {
        val frames = a.size / channels
        for (f in 0 until frames) for (ch in 0 until channels) {
            var x = a[f * channels + ch]
            stages.forEachIndexed { si, stage ->
                stage.sections.forEachIndexed { j, s ->
                    val y = x - z1[ch][si][j] - z2[ch][si][j]
                    x = s.d0 * y + s.d1 * z1[ch][si][j] + z2[ch][si][j]
                    z2[ch][si][j] += s.c2 * z1[ch][si][j]
                    z1[ch][si][j] += s.c1 * y
                }
                if (si == 0) x *= stage.overallGain
            }
            a[f * channels + ch] = x
        }
    }
}


/**
 * Per-track volume normalization.
 *
 * If a ReplayGain/R128-derived static gain is available, Moka applies one fixed gain to the
 * whole track. Otherwise an optional very-slow adaptive RMS fallback is used. The adaptive
 * path is deliberately slow so it evens out differently mastered tracks without pumping
 * on individual drum hits or quiet passages.
 */
class TrackNormalizer(
    sampleRate: Int,
    private val channels: Int,
    staticGainDb: Float?,
    adaptiveFallback: Boolean,
    preampDb: Float
) {
    private val staticGain = staticGainDb?.let { 10.0.pow((it + preampDb) / 20.0).toFloat() }
    private val adaptive = staticGain == null && adaptiveFallback
    private val targetRms = 10.0.pow(-18.0 / 20.0).toFloat()
    private val maxBoost = 10.0.pow(12.0 / 20.0).toFloat()
    private val maxCut = 10.0.pow(-18.0 / 20.0).toFloat()
    private val rmsCoeff = exp(-1.0 / (3.0 * sampleRate)).toFloat()
    private val gainAttackCoeff = exp(-1.0 / (0.35 * sampleRate)).toFloat()
    private val gainReleaseCoeff = exp(-1.0 / (5.0 * sampleRate)).toFloat()
    private val adaptivePreamp = 10.0.pow(preampDb / 20.0).toFloat()
    private var meanSquare = targetRms * targetRms
    private var gain = 1f

    val modeLabel: String
        get() = when {
            staticGain != null -> "ReplayGain/R128"
            adaptive -> "Adaptive"
            else -> "Off"
        }

    fun reset() {
        meanSquare = targetRms * targetRms
        gain = 1f
    }

    fun process(a: FloatArray) {
        if (staticGain != null) {
            val g = staticGain
            for (i in a.indices) a[i] *= g
            return
        }
        if (!adaptive) {
            if (adaptivePreamp != 1f) for (i in a.indices) a[i] *= adaptivePreamp
            return
        }

        val frames = a.size / channels
        for (frame in 0 until frames) {
            var power = 0f
            val base = frame * channels
            for (ch in 0 until channels) {
                val v = a[base + ch]
                power += v * v
            }
            power /= channels.toFloat()
            meanSquare = rmsCoeff * meanSquare + (1f - rmsCoeff) * power
            val rms = sqrt(max(meanSquare, 1e-12f))
            val wanted = (targetRms / rms).coerceIn(maxCut, maxBoost) * adaptivePreamp
            val coeff = if (wanted < gain) gainAttackCoeff else gainReleaseCoeff
            gain = coeff * gain + (1f - coeff) * wanted
            for (ch in 0 until channels) a[base + ch] *= gain
        }
    }
}

class PeakLimiter(sampleRate: Int, private val channels: Int, thresholdDb: Float, releaseMs: Float, postGainDb: Float) {
    private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat().coerceIn(1e-4f, 1f)
    private val post = 10.0.pow(postGainDb / 20.0).toFloat()
    private val releaseCoeff = exp(-1.0 / (max(1f, releaseMs) * 0.001 * sampleRate)).toFloat()
    private var gain = 1f
    fun reset() { gain = 1f }
    fun process(a: FloatArray) {
        val frames = a.size / channels
        for (f in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(a[f * channels + ch]))
            val wanted = if (peak > threshold && peak > 0f) threshold / peak else 1f
            gain = if (wanted < gain) wanted else (1f - releaseCoeff) * wanted + releaseCoeff * gain
            for (ch in 0 until channels) a[f * channels + ch] *= gain * post
        }
    }
}


interface DspBlockProcessor : AutoCloseable {
    val blockSize: Int
    val implementationLabel: String get() = "Kotlin"
    fun reset()
    fun processBlock(interleaved: FloatArray): FloatArray
    override fun close() {}
}

/** Fixed-block DSP chain. FIR EQ and IRS convolution use partitioned convolution. */
class DspChain(
    private val sampleRate: Int,
    private val channels: Int,
    settings: DspSettings,
    ddc: VdcProfile?,
    irs: IrsData?,
    normalizationGainDb: Float? = null,
    override val blockSize: Int = 1024
) : DspBlockProcessor {
    private val normalizer = if (settings.normalizationEnabled) {
        TrackNormalizer(
            sampleRate = sampleRate,
            channels = channels,
            staticGainDb = normalizationGainDb,
            adaptiveFallback = settings.normalizationAdaptiveFallback,
            preampDb = settings.normalizationPreampDb
        )
    } else null
    private val ddcProcessor = if (settings.ddcEnabled && ddc != null) ddc.sectionsFor(sampleRate)?.let { SosCascade(it, channels) } else null
    private val iirEq = if (settings.eqEnabled && settings.eqMode != EqMode.FIR_MINIMUM_PHASE) MultimodalIirEq(sampleRate, settings.eqGainsDb, settings.eqMode, channels) else null
    private val firEq = if (settings.eqEnabled && settings.eqMode == EqMode.FIR_MINIMUM_PHASE) {
        val impulse = MinimumPhaseEqGenerator.generate(sampleRate, settings.eqGainsDb, settings.eqInterpolator)
        StereoPartitionedConvolver(IrsData(sampleRate, arrayOf(impulse)), sampleRate, channels, blockSize)
    } else null
    private val convolver = if (settings.convolverEnabled && irs != null) StereoPartitionedConvolver(irs, sampleRate, channels, blockSize, settings.convolverGainDb) else null
    private val limiter = if (settings.limiterEnabled || settings.postGainDb != 0f) PeakLimiter(sampleRate, channels, settings.limiterThresholdDb, settings.limiterReleaseMs, settings.postGainDb) else null

    override fun reset() {
        normalizer?.reset()
        ddcProcessor?.reset()
        iirEq?.reset()
        firEq?.reset()
        convolver?.reset()
        limiter?.reset()
    }

    override fun processBlock(interleaved: FloatArray): FloatArray {
        require(interleaved.size == blockSize * channels)
        var x = interleaved
        normalizer?.process(x)
        ddcProcessor?.process(x, channels)
        iirEq?.process(x)
        firEq?.let { x = it.process(x) }
        convolver?.let { x = it.process(x) }
        limiter?.process(x)
        return x
    }
}

/** Converts PCM bytes from the direct engine to/from normalized float samples. */
object PcmFloatCodec {
    fun decode(buffer: ByteBuffer, encoding: Int, samples: Int): FloatArray {
        val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(samples)
        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until samples) out[i] = ((b.get().toInt() and 0xff) - 128) / 128f
            android.media.AudioFormat.ENCODING_PCM_16BIT -> for (i in 0 until samples) out[i] = b.short / 32768f
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until samples) {
                var v = (b.get().toInt() and 0xff) or ((b.get().toInt() and 0xff) shl 8) or ((b.get().toInt() and 0xff) shl 16)
                if (v and 0x800000 != 0) v = v or -0x1000000
                out[i] = v / 8388608f
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> for (i in 0 until samples) out[i] = b.int / 2147483648f
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> for (i in 0 until samples) out[i] = b.float
            else -> error("Unsupported PCM encoding $encoding")
        }
        return out
    }
}


class DspStreamAdapter(
    private val chain: DspBlockProcessor,
    private val channels: Int,
    private val sampleRate: Int
) : AutoCloseable {
    private val blockFrames = chain.blockSize
    private var inputBlock = FloatArray(blockFrames * channels)
    private var pendingFrames = 0
    private var meterFrames = 0L
    private var meterNanos = 0L
    private var meterStartedAt = System.nanoTime()

    /**
     * Feeds arbitrary-sized interleaved PCM into the fixed-block DSP chain.
     * Returns only complete processed blocks, so startup is buffered rather than
     * padded with silence. Call [flush] once at end-of-stream.
     */
    fun process(input: FloatArray): FloatArray {
        require(input.size % channels == 0)
        val inputFrames = input.size / channels
        val completeBlocks = (pendingFrames + inputFrames) / blockFrames
        if (completeBlocks == 0) {
            copyFrames(input, 0, inputFrames)
            return FloatArray(0)
        }

        val out = FloatArray(completeBlocks * blockFrames * channels)
        var outSamples = 0
        var sourceFrame = 0
        while (sourceFrame < inputFrames) {
            val copyFrames = min(blockFrames - pendingFrames, inputFrames - sourceFrame)
            val srcStart = sourceFrame * channels
            val dstStart = pendingFrames * channels
            System.arraycopy(input, srcStart, inputBlock, dstStart, copyFrames * channels)
            sourceFrame += copyFrames
            pendingFrames += copyFrames

            if (pendingFrames == blockFrames) {
                val started = System.nanoTime()
                val processed = chain.processBlock(inputBlock)
                meterNanos += System.nanoTime() - started
                meterFrames += blockFrames.toLong()
                maybeLogThroughput()
                System.arraycopy(processed, 0, out, outSamples, processed.size)
                outSamples += processed.size
                inputBlock.fill(0f)
                pendingFrames = 0
            }
        }
        return if (outSamples == out.size) out else out.copyOf(outSamples)
    }

    /** Processes the final partial block padded with silence, returning only real source frames. */
    fun flush(): FloatArray {
        if (pendingFrames == 0) return FloatArray(0)
        val realSamples = pendingFrames * channels
        val started = System.nanoTime()
        val processed = chain.processBlock(inputBlock)
        meterNanos += System.nanoTime() - started
        meterFrames += blockFrames.toLong()
        maybeLogThroughput()
        val out = processed.copyOf(realSamples)
        inputBlock.fill(0f)
        pendingFrames = 0
        return out
    }

    private fun copyFrames(input: FloatArray, sourceFrame: Int, frameCount: Int) {
        if (frameCount <= 0) return
        System.arraycopy(input, sourceFrame * channels, inputBlock, pendingFrames * channels, frameCount * channels)
        pendingFrames += frameCount
    }

    fun reset() {
        chain.reset()
        inputBlock.fill(0f)
        pendingFrames = 0
    }

    private fun maybeLogThroughput() {
        val now = System.nanoTime()
        if (now - meterStartedAt < 5_000_000_000L || meterNanos <= 0L) return
        val audioSeconds = meterFrames.toDouble() / sampleRate.toDouble()
        val cpuSeconds = meterNanos.toDouble() / 1_000_000_000.0
        val realtime = if (cpuSeconds > 0.0) audioSeconds / cpuSeconds else 0.0
        android.util.Log.i(
            "MokaDSP",
            "${chain.implementationLabel} DSP throughput=${"%.2f".format(realtime)}x realtime " +
                "(block=$blockFrames, audio=${"%.1f".format(audioSeconds)}s, dsp=${"%.2f".format(cpuSeconds)}s)"
        )
        meterFrames = 0L
        meterNanos = 0L
        meterStartedAt = now
    }

    override fun close() = chain.close()
}

