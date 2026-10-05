package com.mokamusic.player.audio.dsp

import android.content.Context
import android.content.SharedPreferences

enum class EqMode(val label: String) {
    FIR_MINIMUM_PHASE("FIR Minimum phase"),
    IIR_4("IIR 4th order"),
    IIR_6("IIR 6th order"),
    IIR_8("IIR 8th order"),
    IIR_10("IIR 10th order"),
    IIR_12("IIR 12th order")
}

enum class EqInterpolator(val label: String) {
    PCHIP("Piecewise Cubic Hermite"),
    MAKIMA("Modified Hiroshi Akima spline")
}

data class DspSettings(
    val masterEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -12f,
    val limiterReleaseMs: Float = 120f,
    val postGainDb: Float = 0f,
    val normalizationEnabled: Boolean = false,
    val normalizationAdaptiveFallback: Boolean = true,
    val normalizationPreampDb: Float = 0f,
    val eqEnabled: Boolean = true,
    val eqMode: EqMode = EqMode.FIR_MINIMUM_PHASE,
    val eqInterpolator: EqInterpolator = EqInterpolator.MAKIMA,
    val eqGainsDb: List<Float> = FAVORITE_EQ_GAINS,
    val ddcEnabled: Boolean = false,
    val ddcUri: String? = null,
    val ddcName: String? = null,
    val convolverEnabled: Boolean = false,
    val convolverUri: String? = null,
    val convolverName: String? = null,
    val convolverGainDb: Float = 0f
) {
    val anyProcessingEnabled: Boolean
        get() = masterEnabled && (
            eqEnabled || ddcEnabled || convolverEnabled || limiterEnabled ||
                normalizationEnabled || postGainDb != 0f
            )

    companion object {
        val EQ_FREQUENCIES_HZ = intArrayOf(25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000)
        val FAVORITE_EQ_GAINS = listOf(3.5f, 5.5f, 6.5f, 9.5f, 8.0f, 6.5f, 3.5f, 2.5f, 1.3f, 5.0f, 7.0f, 9.0f, 10.1f, 11.0f, 9.0f)
    }
}

class DspSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun load(): DspSettings {
        val gains = prefs.getString(KEY_EQ_GAINS, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == DspSettings.EQ_FREQUENCIES_HZ.size }
            ?: DspSettings.FAVORITE_EQ_GAINS
        return DspSettings(
            masterEnabled = prefs.getBoolean(KEY_MASTER, false),
            limiterEnabled = prefs.getBoolean(KEY_LIMITER, true),
            limiterThresholdDb = prefs.getFloat(KEY_LIMITER_THRESHOLD, -12f),
            limiterReleaseMs = prefs.getFloat(KEY_LIMITER_RELEASE, 120f),
            postGainDb = prefs.getFloat(KEY_POST_GAIN, 0f),
            normalizationEnabled = prefs.getBoolean(KEY_NORMALIZATION, false),
            normalizationAdaptiveFallback = prefs.getBoolean(KEY_NORMALIZATION_ADAPTIVE, true),
            normalizationPreampDb = prefs.getFloat(KEY_NORMALIZATION_PREAMP, 0f),
            eqEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true),
            eqMode = runCatching { EqMode.valueOf(prefs.getString(KEY_EQ_MODE, EqMode.FIR_MINIMUM_PHASE.name)!!) }.getOrDefault(EqMode.FIR_MINIMUM_PHASE),
            eqInterpolator = runCatching { EqInterpolator.valueOf(prefs.getString(KEY_EQ_INTERP, EqInterpolator.MAKIMA.name)!!) }.getOrDefault(EqInterpolator.MAKIMA),
            eqGainsDb = gains,
            ddcEnabled = prefs.getBoolean(KEY_DDC_ENABLED, false),
            ddcUri = prefs.getString(KEY_DDC_URI, null),
            ddcName = prefs.getString(KEY_DDC_NAME, null),
            convolverEnabled = prefs.getBoolean(KEY_CONV_ENABLED, false),
            convolverUri = prefs.getString(KEY_CONV_URI, null),
            convolverName = prefs.getString(KEY_CONV_NAME, null),
            convolverGainDb = prefs.getFloat(KEY_CONV_GAIN, 0f)
        )
    }

    fun save(settings: DspSettings) {
        prefs.edit()
            .putBoolean(KEY_MASTER, settings.masterEnabled)
            .putBoolean(KEY_LIMITER, settings.limiterEnabled)
            .putFloat(KEY_LIMITER_THRESHOLD, settings.limiterThresholdDb)
            .putFloat(KEY_LIMITER_RELEASE, settings.limiterReleaseMs)
            .putFloat(KEY_POST_GAIN, settings.postGainDb)
            .putBoolean(KEY_NORMALIZATION, settings.normalizationEnabled)
            .putBoolean(KEY_NORMALIZATION_ADAPTIVE, settings.normalizationAdaptiveFallback)
            .putFloat(KEY_NORMALIZATION_PREAMP, settings.normalizationPreampDb)
            .putBoolean(KEY_EQ_ENABLED, settings.eqEnabled)
            .putString(KEY_EQ_MODE, settings.eqMode.name)
            .putString(KEY_EQ_INTERP, settings.eqInterpolator.name)
            .putString(KEY_EQ_GAINS, settings.eqGainsDb.joinToString(","))
            .putBoolean(KEY_DDC_ENABLED, settings.ddcEnabled)
            .putString(KEY_DDC_URI, settings.ddcUri)
            .putString(KEY_DDC_NAME, settings.ddcName)
            .putBoolean(KEY_CONV_ENABLED, settings.convolverEnabled)
            .putString(KEY_CONV_URI, settings.convolverUri)
            .putString(KEY_CONV_NAME, settings.convolverName)
            .putFloat(KEY_CONV_GAIN, settings.convolverGainDb)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "moka_dsp"
        const val KEY_MASTER = "master"
        const val KEY_LIMITER = "limiter"
        const val KEY_LIMITER_THRESHOLD = "limiter_threshold"
        const val KEY_LIMITER_RELEASE = "limiter_release"
        const val KEY_POST_GAIN = "post_gain"
        const val KEY_NORMALIZATION = "normalization"
        const val KEY_NORMALIZATION_ADAPTIVE = "normalization_adaptive"
        const val KEY_NORMALIZATION_PREAMP = "normalization_preamp"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_MODE = "eq_mode"
        const val KEY_EQ_INTERP = "eq_interp"
        const val KEY_EQ_GAINS = "eq_gains"
        const val KEY_DDC_ENABLED = "ddc_enabled"
        const val KEY_DDC_URI = "ddc_uri"
        const val KEY_DDC_NAME = "ddc_name"
        const val KEY_CONV_ENABLED = "conv_enabled"
        const val KEY_CONV_URI = "conv_uri"
        const val KEY_CONV_NAME = "conv_name"
        const val KEY_CONV_GAIN = "conv_gain"
    }
}
