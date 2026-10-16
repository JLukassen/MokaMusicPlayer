package com.mokamusic.player.audio.dsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedEqCurve(
    val name: String,
    val mode: EqMode,
    val interpolator: EqInterpolator,
    val gains: List<Float>
) {
    fun applyTo(current: DspSettings): DspSettings = current.copy(
        eqEnabled = true,
        eqMode = mode,
        eqInterpolator = interpolator,
        eqGainsDb = gains
    )
}

object BuiltInEqCurves {
    val flat = List(DspSettings.EQ_FREQUENCIES_HZ.size) { 0f }
    val warm = listOf(3f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, -0.5f, -1f, -1f, -1f, -1f)
    val vocal = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0f, 0.5f, 1.5f, 2f, 2.5f, 2.5f, 1.5f, 0.5f, 0f, -0.5f)
}

class UserEqCurveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moka_eq_curves", Context.MODE_PRIVATE)
    private val key = "saved_eq_curves_v1"

    fun list(): List<SavedEqCurve> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            if (name.isEmpty() || name.length > 40) return@mapNotNull null
            val mode = runCatching { EqMode.valueOf(item.optString("mode")) }.getOrNull()
                ?: return@mapNotNull null
            val interp = runCatching { EqInterpolator.valueOf(item.optString("interpolator")) }.getOrNull()
                ?: return@mapNotNull null
            val gainsArray = item.optJSONArray("gains") ?: return@mapNotNull null
            if (gainsArray.length() != DspSettings.EQ_FREQUENCIES_HZ.size) return@mapNotNull null
            val gains = (0 until gainsArray.length()).map { gainsArray.optDouble(it, Double.NaN).toFloat() }
            if (gains.any { !it.isFinite() || it !in -15f..15f }) return@mapNotNull null
            SavedEqCurve(name, mode, interp, gains)
        }
    }.getOrDefault(emptyList())

    fun save(name: String, settings: DspSettings): List<SavedEqCurve> {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 40) { "Name must be 1–40 characters" }
        require(settings.eqGainsDb.size == DspSettings.EQ_FREQUENCIES_HZ.size)
        require(settings.eqGainsDb.all { it.isFinite() && it in -15f..15f })
        val curves = list().filterNot { it.name.equals(cleaned, ignoreCase = true) }.toMutableList()
        require(curves.size < 30) { "Maximum 30 saved EQ curves" }
        curves += SavedEqCurve(cleaned, settings.eqMode, settings.eqInterpolator, settings.eqGainsDb.toList())
        write(curves)
        return curves
    }

    fun delete(name: String): List<SavedEqCurve> =
        list().filterNot { it.name == name }.also(::write)

    private fun write(curves: List<SavedEqCurve>) {
        val array = JSONArray()
        for (curve in curves) {
            array.put(JSONObject().apply {
                put("name", curve.name)
                put("mode", curve.mode.name)
                put("interpolator", curve.interpolator.name)
                val bands = JSONArray()
                curve.gains.forEach { bands.put(it) }
                put("gains", bands)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
