package com.mokamusic.player.audio.dsp

import android.util.Log

/**
 * JNI bridge for Moka's real-time DSP core.
 *
 * Heavy FIR/convolution work lives in optimized native C++ so the Kotlin playback thread only
 * moves blocks of float PCM across the JNI boundary. If the native library is unavailable Moka
 * transparently falls back to the Kotlin DSP implementation.
 */
object NativeDspBridge {
    private const val TAG = "MokaNativeDSP"

    val available: Boolean = runCatching {
        System.loadLibrary("moka_dsp")
        true
    }.onFailure {
        Log.w(TAG, "Native DSP unavailable; using Kotlin fallback", it)
    }.getOrDefault(false)

    external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        blockSize: Int,
        ddcCoefficients: DoubleArray,
        eqImpulse: FloatArray?,
        ir0: FloatArray?,
        ir1: FloatArray?,
        ir2: FloatArray?,
        ir3: FloatArray?,
        irChannelCount: Int,
        convolverGainDb: Float,
        normalizationEnabled: Boolean,
        normalizationStaticGainDb: Float,
        normalizationAdaptiveFallback: Boolean,
        normalizationPreampDb: Float,
        limiterEnabled: Boolean,
        limiterThresholdDb: Float,
        limiterReleaseMs: Float,
        postGainDb: Float
    ): Long

    external fun nativeProcess(handle: Long, interleaved: FloatArray)
    external fun nativeReset(handle: Long)
    external fun nativeRelease(handle: Long)
}

class NativeDspChain private constructor(
    override val blockSize: Int,
    private var handle: Long
) : DspBlockProcessor {
    override val implementationLabel: String = "Native C++"

    @Synchronized
    override fun processBlock(interleaved: FloatArray): FloatArray {
        check(handle != 0L) { "Native DSP chain is closed" }
        NativeDspBridge.nativeProcess(handle, interleaved)
        return interleaved
    }

    @Synchronized
    override fun reset() {
        if (handle != 0L) NativeDspBridge.nativeReset(handle)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeDspBridge.nativeRelease(h)
    }

    companion object {
        fun createOrNull(
            sampleRate: Int,
            channels: Int,
            blockSize: Int,
            settings: DspSettings,
            ddc: VdcProfile?,
            irs: IrsData?,
            normalizationGainDb: Float?
        ): NativeDspChain? {
            if (!NativeDspBridge.available) return null
            // Keep the Kotlin implementation for JamesDSP-style high-order IIR modes until the
            // native engine has the exact same coefficient design. FIR minimum-phase is the
            // expensive mode that benefits most from native acceleration.
            if (settings.eqEnabled && settings.eqMode != EqMode.FIR_MINIMUM_PHASE) return null

            val ddcFlat = if (settings.ddcEnabled) {
                ddc?.sectionsFor(sampleRate).orEmpty().flatMap { s ->
                    listOf(s.b0, s.b1, s.b2, s.a1, s.a2)
                }.toDoubleArray()
            } else DoubleArray(0)

            val eqImpulse = if (settings.eqEnabled && settings.eqMode == EqMode.FIR_MINIMUM_PHASE) {
                MinimumPhaseEqGenerator.generate(sampleRate, settings.eqGainsDb, settings.eqInterpolator)
            } else null

            val nativeIrs = if (settings.convolverEnabled && irs != null) {
                Array(irs.channels.size.coerceAtMost(4)) { index ->
                    if (irs.sampleRate == sampleRate) {
                        irs.channels[index].copyOf()
                    } else {
                        SincResampler.resample(irs.channels[index], irs.sampleRate, sampleRate)
                    }
                }
            } else emptyArray()

            val ir0 = nativeIrs.getOrNull(0)
            val ir1 = nativeIrs.getOrNull(1)
            val ir2 = nativeIrs.getOrNull(2)
            val ir3 = nativeIrs.getOrNull(3)

            val handle = NativeDspBridge.nativeCreate(
                sampleRate = sampleRate,
                channels = channels,
                blockSize = blockSize,
                ddcCoefficients = ddcFlat,
                eqImpulse = eqImpulse,
                ir0 = ir0,
                ir1 = ir1,
                ir2 = ir2,
                ir3 = ir3,
                irChannelCount = nativeIrs.size,
                convolverGainDb = if (nativeIrs.isNotEmpty()) settings.convolverGainDb else 0f,
                normalizationEnabled = settings.normalizationEnabled,
                normalizationStaticGainDb = normalizationGainDb ?: Float.NaN,
                normalizationAdaptiveFallback = settings.normalizationAdaptiveFallback,
                normalizationPreampDb = settings.normalizationPreampDb,
                limiterEnabled = settings.limiterEnabled,
                limiterThresholdDb = settings.limiterThresholdDb,
                limiterReleaseMs = settings.limiterReleaseMs,
                postGainDb = settings.postGainDb
            )
            return handle.takeIf { it != 0L }?.let { NativeDspChain(blockSize, it) }
        }
    }
}
