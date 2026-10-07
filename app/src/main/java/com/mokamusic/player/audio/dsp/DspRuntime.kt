package com.mokamusic.player.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Lightweight live telemetry for the Now Playing signal-path panel and diagnostics export. */
data class DspMeterSnapshot(
    val active: Boolean = false,
    val engine: String = "Off",
    val inputPeakDbfs: Float = -120f,
    val outputPeakDbfs: Float = -120f,
    val intersamplePeakDbfs: Float = -120f,
    val clippedSamples: Long = 0L,
    val automaticHeadroomDb: Float = 0f,
    val throughputX: Float? = null,
    val audioTrackUnderruns: Int = 0,
    val dspQueueDepth: Int? = null,
    val dspQueueCapacity: Int? = null,
    val primedFrames: Int? = null,
    val hotReloadCount: Int = 0,
    val updatedAtMs: Long = 0L
)

object DspRuntimeMonitor {
    @Volatile private var snapshot = DspMeterSnapshot()

    fun snapshot(): DspMeterSnapshot = snapshot

    @Synchronized
    fun updateBlock(
        engine: String,
        inputPeak: Float,
        outputPeak: Float,
        intersamplePeak: Float,
        clippedSamples: Long,
        headroomDb: Float
    ) {
        val old = snapshot
        snapshot = old.copy(
            active = true,
            engine = engine,
            inputPeakDbfs = linearToDb(inputPeak),
            outputPeakDbfs = linearToDb(outputPeak),
            intersamplePeakDbfs = linearToDb(intersamplePeak),
            clippedSamples = old.clippedSamples + clippedSamples,
            automaticHeadroomDb = headroomDb,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun updateThroughput(engine: String, realtime: Float, headroomDb: Float) {
        val old = snapshot
        snapshot = old.copy(
            active = true,
            engine = engine,
            automaticHeadroomDb = headroomDb,
            throughputX = realtime,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun updateAudioPipeline(
        underruns: Int,
        queueDepth: Int?,
        queueCapacity: Int?,
        primedFrames: Int?
    ) {
        val old = snapshot
        snapshot = old.copy(
            audioTrackUnderruns = underruns.coerceAtLeast(0),
            dspQueueDepth = queueDepth,
            dspQueueCapacity = queueCapacity,
            primedFrames = primedFrames,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun noteHotReload() {
        val old = snapshot
        snapshot = old.copy(
            hotReloadCount = old.hotReloadCount + 1,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun resetSignal() {
        val old = snapshot
        snapshot = DspMeterSnapshot(
            audioTrackUnderruns = old.audioTrackUnderruns,
            dspQueueDepth = old.dspQueueDepth,
            dspQueueCapacity = old.dspQueueCapacity,
            primedFrames = old.primedFrames,
            hotReloadCount = old.hotReloadCount,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun clear() {
        snapshot = DspMeterSnapshot()
    }

    private fun linearToDb(value: Float): Float =
        if (!value.isFinite() || value <= 1e-6f) -120f else (20.0 * log10(value.toDouble())).toFloat()
}

/**
 * Conservative setup-time headroom estimate.
 *
 * Moka sums the positive peak gains of independent DSP stages because their maxima can overlap.
 * That intentionally errs on the safe side. IRS and VDC responses are sampled at setup only; no
 * extra frequency-response work runs in the real-time audio thread.
 */
object DspHeadroomEstimator {
    fun estimateDb(
        sampleRate: Int,
        settings: DspSettings,
        ddc: VdcProfile?,
        irs: IrsData?,
        normalizationGainDb: Float?
    ): Float {
        if (!settings.autoHeadroomEnabled || !settings.masterEnabled) return 0f

        var positiveDb = 0.0

        if (settings.eqEnabled) {
            positiveDb += max(0.0, settings.eqGainsDb.maxOrNull()?.toDouble() ?: 0.0)
        }

        if (settings.ddcEnabled && ddc != null) {
            positiveDb += max(0.0, peakSosGainDb(ddc.sectionsFor(sampleRate).orEmpty(), sampleRate))
        }

        if (settings.convolverEnabled && irs != null) {
            val irGain = peakIrsGainDb(irs, sampleRate) + settings.convolverGainDb
            positiveDb += max(0.0, irGain.toDouble())
        }

        if (settings.normalizationEnabled) {
            val normalization = when {
                normalizationGainDb != null -> normalizationGainDb + settings.normalizationPreampDb
                settings.normalizationAdaptiveFallback -> max(0f, settings.normalizationPreampDb) + 3f
                else -> settings.normalizationPreampDb
            }
            positiveDb += max(0.0, normalization.toDouble())
        }

        if (positiveDb <= 0.05) return 0f
        // Leave one extra dB so inter-stage interpolation/response error does not land exactly at 0 dBFS.
        return (-(positiveDb + 1.0)).coerceAtLeast(-24.0).toFloat()
    }

    private fun peakSosGainDb(sections: List<Sos>, sampleRate: Int): Double {
        if (sections.isEmpty()) return 0.0
        var best = 1.0
        val bins = 192
        for (i in 0 until bins) {
            val f = if (i == 0) 10.0 else {
                val t = i.toDouble() / (bins - 1).toDouble()
                10.0 * kotlin.math.exp(ln((sampleRate / 2.0) / 10.0) * t)
            }.coerceAtMost(sampleRate / 2.0 * 0.999)
            val w = 2.0 * PI * f / sampleRate.toDouble()
            val z1r = cos(w); val z1i = -sin(w)
            val z2r = cos(2.0 * w); val z2i = -sin(2.0 * w)
            var mag = 1.0
            for (s in sections) {
                val nr = s.b0 + s.b1 * z1r + s.b2 * z2r
                val ni = s.b1 * z1i + s.b2 * z2i
                val dr = 1.0 + s.a1 * z1r + s.a2 * z2r
                val di = s.a1 * z1i + s.a2 * z2i
                val den = dr * dr + di * di
                if (den > 1e-24) mag *= kotlin.math.sqrt((nr * nr + ni * ni) / den)
            }
            if (mag.isFinite()) best = max(best, mag)
        }
        return 20.0 * log10(max(best, 1e-12))
    }

    private fun peakIrsGainDb(irs: IrsData, outputRate: Int): Float {
        if (irs.channels.isEmpty()) return 0f
        var best = 1.0
        for (source in irs.channels) {
            val a = if (irs.sampleRate == outputRate) source else SincResampler.resample(source, irs.sampleRate, outputRate)
            if (a.isEmpty()) continue

            // Exact fast path for delayed/scalar impulse responses.
            var nonZero = 0
            var single = 0f
            for (v in a) {
                if (abs(v) > 1e-7f) {
                    nonZero++
                    single = v
                    if (nonZero > 1) break
                }
            }
            if (nonZero == 1) {
                best = max(best, abs(single).toDouble())
                continue
            }

            // Sample the response on a log-spaced grid. This runs only when a DSP chain is built.
            val bins = 96
            val nyquist = outputRate / 2.0
            for (bi in 0 until bins) {
                val f = if (bi == 0) 0.0 else {
                    val t = bi.toDouble() / (bins - 1).toDouble()
                    20.0 * kotlin.math.exp(ln(max(1.0, nyquist / 20.0)) * t)
                }.coerceAtMost(nyquist * 0.999)
                val step = -2.0 * PI * f / outputRate.toDouble()
                var re = 0.0
                var im = 0.0
                // Long kernels are sampled densely enough for a conservative practical estimate.
                val stride = max(1, a.size / 8192)
                var n = 0
                while (n < a.size) {
                    val phase = step * n
                    val v = a[n].toDouble() * stride.toDouble()
                    re += v * cos(phase)
                    im += v * sin(phase)
                    n += stride
                }
                val mag = kotlin.math.sqrt(re * re + im * im)
                if (mag.isFinite()) best = max(best, mag)
            }
        }
        return (20.0 * log10(max(best, 1e-12))).toFloat()
    }
}

enum class DspPresetId(val label: String) {
    KEEP("Keep current"),
    OFF("DSP off / pure"),
    SAFE("Safe DSP"),
    REFERENCE("Moka Reference")
}

object DspPresets {
    fun apply(preset: DspPresetId, current: DspSettings): DspSettings = when (preset) {
        DspPresetId.KEEP -> current
        DspPresetId.OFF -> current.copy(masterEnabled = false)
        DspPresetId.SAFE -> current.copy(
            masterEnabled = true,
            autoHeadroomEnabled = true,
            normalizationEnabled = false,
            eqEnabled = false,
            ddcEnabled = false,
            convolverEnabled = false,
            limiterEnabled = true,
            limiterThresholdDb = -1f,
            limiterReleaseMs = 120f,
            postGainDb = 0f
        )
        DspPresetId.REFERENCE -> current.copy(
            masterEnabled = true,
            autoHeadroomEnabled = true,
            limiterEnabled = true,
            limiterThresholdDb = -12f,
            limiterReleaseMs = 120f,
            postGainDb = 0f,
            eqEnabled = true,
            eqMode = EqMode.FIR_MINIMUM_PHASE,
            eqInterpolator = EqInterpolator.MAKIMA,
            eqGainsDb = DspSettings.FAVORITE_EQ_GAINS,
            ddcEnabled = current.ddcUri != null,
            convolverEnabled = current.convolverUri != null
        )
    }
}

class DspDeviceProfileStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moka_dsp_device_profiles", android.content.Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    fun get(route: RouteClass): DspPresetId = runCatching {
        DspPresetId.valueOf(prefs.getString("route_${route.name}", DspPresetId.KEEP.name)!!)
    }.getOrDefault(DspPresetId.KEEP)

    fun set(route: RouteClass, preset: DspPresetId) {
        prefs.edit().putString("route_${route.name}", preset.name).apply()
    }
}

enum class RouteClass(val label: String) { USB("USB"), BLUETOOTH("Bluetooth"), WIRED("Wired"), SPEAKER("Speaker / other") }

fun classifyRoute(routeLabel: String): RouteClass = when {
    routeLabel.contains("USB", true) -> RouteClass.USB
    routeLabel.contains("Bluetooth", true) -> RouteClass.BLUETOOTH
    routeLabel.contains("head", true) || routeLabel.contains("wired", true) -> RouteClass.WIRED
    else -> RouteClass.SPEAKER
}
