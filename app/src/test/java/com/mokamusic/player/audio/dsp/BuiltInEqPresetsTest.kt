package com.mokamusic.player.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInEqPresetsTest {
    @Test fun containsFamiliarStyles() {
        val names = BuiltInEqPresets.all.map { it.name }
        val expected = listOf(
            "Acoustic", "Bass", "Beats", "Classic", "Clear", "Deep Bass",
            "Dubstep", "Electronic", "Flat", "Hardstyle", "Hip-Hop", "Jazz",
            "Metal", "Movie", "Pop", "R&B", "Rock", "Vocal Booster"
        )
        assertTrue(names.containsAll(expected))
        assertEquals(20, names.size)
        assertEquals(names.size, names.distinct().size)
    }

    @Test fun curvesAreSafeAndMatchFifteenBandEq() {
        for (preset in BuiltInEqPresets.all) {
            assertEquals(DspSettings.EQ_FREQUENCIES_HZ.size, preset.gainsDb.size)
            assertTrue(preset.gainsDb.all { it.isFinite() && it in -15f..15f })
        }
        assertNotNull(BuiltInEqPresets.matching(DspSettings.FAVORITE_EQ_GAINS))
        assertEquals("Flat", BuiltInEqPresets.matching(List(15) { 0f })?.name)
    }

    @Test fun presetPreservesOtherDspStagesAndEqMode() {
        val initial = DspSettings(
            masterEnabled = false, eqEnabled = false,
            eqMode = EqMode.IIR_8, eqInterpolator = EqInterpolator.PCHIP,
            ddcEnabled = true, convolverEnabled = true, normalizationEnabled = true,
            limiterThresholdDb = -9f, postGainDb = -3f
        )
        val selected = BuiltInEqPresets.all.first { it.name == "Jazz" }.applyTo(initial)
        assertTrue(selected.masterEnabled)
        assertTrue(selected.eqEnabled)
        assertEquals(EqMode.IIR_8, selected.eqMode)
        assertEquals(EqInterpolator.PCHIP, selected.eqInterpolator)
        assertEquals(initial.ddcEnabled, selected.ddcEnabled)
        assertEquals(initial.convolverEnabled, selected.convolverEnabled)
        assertEquals(initial.normalizationEnabled, selected.normalizationEnabled)
        assertEquals(initial.limiterThresholdDb, selected.limiterThresholdDb)
        assertEquals(initial.postGainDb, selected.postGainDb)
    }
}
