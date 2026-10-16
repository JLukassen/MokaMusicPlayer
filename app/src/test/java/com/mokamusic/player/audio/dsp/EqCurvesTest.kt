package com.mokamusic.player.audio.dsp

import org.junit.Assert.*
import org.junit.Test

class EqCurvesTest {
    @Test fun builtInsHaveValidBandCount() {
        for (curve in listOf(BuiltInEqCurves.flat, BuiltInEqCurves.warm, BuiltInEqCurves.vocal)) {
            assertEquals(DspSettings.EQ_FREQUENCIES_HZ.size, curve.size)
            assertTrue(curve.all { it.isFinite() && it in -15f..15f })
        }
    }
    @Test fun applyingSavedCurveDoesNotReplaceOutputControls() {
        val original = DspSettings(
            normalizationEnabled = true, ddcEnabled = true, limiterThresholdDb = -9f
        )
        val curve = SavedEqCurve("Vocal", EqMode.IIR_8, EqInterpolator.PCHIP, BuiltInEqCurves.vocal)
        val applied = curve.applyTo(original)
        assertEquals(EqMode.IIR_8, applied.eqMode)
        assertEquals(BuiltInEqCurves.vocal, applied.eqGainsDb)
        assertEquals(original.ddcEnabled, applied.ddcEnabled)
        assertEquals(original.normalizationEnabled, applied.normalizationEnabled)
        assertEquals(original.limiterThresholdDb, applied.limiterThresholdDb)
    }
}
