package com.mokamusic.player.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspQualityTest {
    @Test
    fun windowedSincPreservesDcAwayFromEdges() {
        val input = FloatArray(4410) { 0.5f }
        val output = SincResampler.resample(input, 44_100, 48_000)
        assertTrue(output.size in 4795..4805)
        val middle = output.copyOfRange(200, output.size - 200)
        val mean = middle.average()
        assertTrue("DC gain drifted: $mean", abs(mean - 0.5) < 0.002)
    }

    @Test
    fun automaticHeadroomProtectsLargeReferenceEqBoosts() {
        val settings = DspSettings(
            masterEnabled = true,
            autoHeadroomEnabled = true,
            eqEnabled = true,
            eqGainsDb = DspSettings.FAVORITE_EQ_GAINS,
            ddcEnabled = false,
            convolverEnabled = false,
            normalizationEnabled = false
        )
        val headroom = DspHeadroomEstimator.estimateDb(48_000, settings, null, null, null)
        assertTrue("headroom should attenuate boosted EQ", headroom <= -11.5f)
        assertTrue("headroom safety cap changed unexpectedly", headroom >= -24f)
    }

    @Test
    fun flatEqDoesNotNeedLargeHeadroom() {
        val settings = DspSettings(
            masterEnabled = true,
            autoHeadroomEnabled = true,
            eqEnabled = true,
            eqGainsDb = List(DspSettings.EQ_FREQUENCIES_HZ.size) { 0f },
            ddcEnabled = false,
            convolverEnabled = false,
            normalizationEnabled = false
        )
        assertEquals(0f, DspHeadroomEstimator.estimateDb(48_000, settings, null, null, null), 0.001f)
    }
}
