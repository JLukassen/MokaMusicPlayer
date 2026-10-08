package com.mokamusic.player.audio.dsp

/** Musical EQ styles independently designed for Moka's 15-band multimodal EQ.
 * The names are familiar graphic-EQ categories; the coefficients are not copied from
 * other apps or headphone measurements. All gains are in dB.
 */
data class BuiltInEqPreset(val name: String, val gainsDb: List<Float>) {
    fun applyTo(current: DspSettings): DspSettings =
        current.copy(masterEnabled = true, eqEnabled = true, eqGainsDb = gainsDb)
}

object BuiltInEqPresets {
    private fun preset(name: String, vararg gains: Int): BuiltInEqPreset {
        require(gains.size == DspSettings.EQ_FREQUENCIES_HZ.size) { "Incorrect EQ band count" }
        require(gains.all { it in -15..15 }) { "EQ gain outside supported range" }
        return BuiltInEqPreset(name, gains.map { it.toFloat() })
    }

    val all: List<BuiltInEqPreset> = listOf(
        preset("Acoustic",  1,  2,  2,  1,  0, -1,  0,  1,  1,  2,  2,  1,  1,  2,  2),
        preset("Bass",  5,  5,  5,  4,  3,  2,  0,  0,  0,  0,  0, -1, -1, -1, -1),
        preset("Beats",  4,  4,  4,  3,  1, -1, -2, -2, -1,  0,  2,  3,  3,  2,  2),
        preset("Classic",  1,  2,  2,  1,  0,  0,  0,  1,  2,  2,  2,  1,  1,  1,  0),
        preset("Clear", -2, -2, -1, -1,  0,  0,  1,  2,  2,  2,  2,  3,  2,  2,  1),
        preset("Deep Bass",  6,  6,  5,  4,  2,  0, -1, -1, -1,  0,  0,  0,  0,  0,  0),
        preset("Dubstep",  6,  6,  5,  3,  0, -2, -3, -2, -1,  1,  3,  4,  3,  2,  1),
        preset("Electronic",  4,  4,  3,  2,  0, -1, -1,  0,  1,  2,  3,  4,  3,  2,  2),
        preset("Flat",  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0),
        preset("Hardstyle",  6,  5,  5,  3,  1, -1, -2, -2,  0,  2,  4,  4,  4,  3,  2),
        preset("Hip-Hop",  4,  4,  4,  3,  2,  1,  0,  1,  2,  2,  2,  1,  2,  2,  1),
        preset("Jazz",  1,  1,  2,  2,  1,  1,  0,  1,  2,  2,  1,  1,  2,  1,  1),
        preset("Metal",  3,  3,  2,  1, -1, -2, -1,  1,  2,  3,  3,  4,  4,  3,  2),
        preset("Movie",  4,  3,  3,  2,  1,  0,  0,  1,  2,  2,  3,  3,  3,  3,  2),
        preset("Pop",  2,  3,  3,  2,  1,  0,  0,  1,  2,  3,  3,  2,  2,  2,  1),
        preset("R&B",  4,  4,  3,  2,  2,  1,  1,  2,  3,  3,  2,  2,  2,  2,  1),
        preset("Rock",  3,  3,  3,  2,  0, -1, -1,  1,  2,  3,  3,  3,  3,  2,  2),
        preset("Vocal Booster", -2, -2, -1, -1,  0,  1,  2,  3,  4,  4,  3,  2,  1,  0, -1),
        preset("Warm",  3,  3,  3,  2,  2,  1,  0,  0,  0,  0, -1, -1, -1, -2, -2),
    ) + BuiltInEqPreset("Moka Reference", DspSettings.FAVORITE_EQ_GAINS.toList())

    init {
        require(all.map { it.name }.distinct().size == all.size) { "Duplicate EQ name" }
    }

    fun matching(gainsDb: List<Float>): BuiltInEqPreset? =
        all.firstOrNull { it.gainsDb == gainsDb }
}
