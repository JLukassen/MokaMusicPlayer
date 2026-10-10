package com.mokamusic.player.audio.dsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Opt-in DSP snapshots matched to a *named* playback device.
 * Ambiguous outputs (e.g. generic phone speaker/wired route) cannot be auto-matched.
 */
class HeadphoneProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moka_headphone_profiles_v1", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    fun save(key: String, current: DspSettings) {
        require(key.isNotBlank())
        prefs.edit().putString("profile:$key", encode(current).toString()).apply()
    }
    fun remove(key: String) { prefs.edit().remove("profile:$key").apply() }
    fun has(key: String): Boolean = prefs.contains("profile:$key")
    fun get(key: String): DspSettings? = runCatching {
        val raw = prefs.getString("profile:$key", null) ?: return@runCatching null
        decode(JSONObject(raw))
    }.getOrNull()

    private fun encode(s: DspSettings): JSONObject = JSONObject().apply {
        put("master", s.masterEnabled)
        put("limiter", s.limiterEnabled)
        put("limiterThreshold", s.limiterThresholdDb.toDouble())
        put("limiterRelease", s.limiterReleaseMs.toDouble())
        put("postGain", s.postGainDb.toDouble())
        put("headroom", s.autoHeadroomEnabled)
        put("normalization", s.normalizationEnabled)
        put("normalizationMode", s.normalizationMode.name)
        put("normalizationAdaptive", s.normalizationAdaptiveFallback)
        put("normalizationPreamp", s.normalizationPreampDb.toDouble())
        put("eqEnabled", s.eqEnabled)
        put("eqMode", s.eqMode.name)
        put("eqInterpolator", s.eqInterpolator.name)
        put("eqGains", JSONArray().apply { s.eqGainsDb.forEach { put(it.toDouble()) } })
        put("ddcEnabled", s.ddcEnabled)
        put("ddcUri", s.ddcUri)
        put("ddcName", s.ddcName)
        put("convEnabled", s.convolverEnabled)
        put("convUri", s.convolverUri)
        put("convName", s.convolverName)
        put("convGain", s.convolverGainDb.toDouble())
    }
    private fun decode(j: JSONObject): DspSettings {
        val array = j.getJSONArray("eqGains")
        require(array.length() == DspSettings.EQ_FREQUENCIES_HZ.size)
        val gains = (0 until array.length()).map { array.getDouble(it).toFloat() }
        require(gains.all { it.isFinite() && it in -15f..15f })
        fun optional(key: String) = j.optString(key).takeUnless { it.isBlank() || it == "null" }
        fun f(key: String, fallback: Float) = j.optDouble(key, fallback.toDouble()).toFloat()
            .takeIf { it.isFinite() } ?: fallback
        return DspSettings(
            masterEnabled = j.optBoolean("master", true),
            limiterEnabled = j.optBoolean("limiter", true),
            limiterThresholdDb = f("limiterThreshold", -12f),
            limiterReleaseMs = f("limiterRelease", 120f),
            postGainDb = f("postGain", 0f),
            autoHeadroomEnabled = j.optBoolean("headroom", true),
            normalizationEnabled = j.optBoolean("normalization", false),
            normalizationMode = NormalizationMode.valueOf(j.getString("normalizationMode")),
            normalizationAdaptiveFallback = j.optBoolean("normalizationAdaptive", true),
            normalizationPreampDb = f("normalizationPreamp", 0f),
            eqEnabled = j.optBoolean("eqEnabled", true),
            eqMode = EqMode.valueOf(j.getString("eqMode")),
            eqInterpolator = EqInterpolator.valueOf(j.getString("eqInterpolator")),
            eqGainsDb = gains,
            ddcEnabled = j.optBoolean("ddcEnabled", false),
            ddcUri = optional("ddcUri"),
            ddcName = optional("ddcName"),
            convolverEnabled = j.optBoolean("convEnabled", false),
            convolverUri = optional("convUri"),
            convolverName = optional("convName"),
            convolverGainDb = f("convGain", 0f)
        )
    }

    companion object {
        fun identity(routeLabel: String, productName: String): String? {
            val route = routeLabel.trim().lowercase(Locale.ROOT)
            val device = productName.trim().lowercase(Locale.ROOT)
            if (!route.contains("bluetooth") && !route.contains("usb") && !route.contains("wired")) return null
            if (device.isEmpty() || device == "android audio" || device == route ||
                device in listOf("wired headphones", "bluetooth headphones", "usb dac", "usb audio"))
                return null
            return "${route.take(80)}|${device.take(120)}"
        }
    }
}
