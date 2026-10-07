package com.mokamusic.player.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIirDefinitionTest {
    @Test
    fun everyIirModeProducesFiniteNativeDefinition() {
        val gains = DspSettings.FAVORITE_EQ_GAINS
        listOf(EqMode.IIR_4, EqMode.IIR_6, EqMode.IIR_8, EqMode.IIR_10, EqMode.IIR_12).forEach { mode ->
            val definition = designMultimodalIirNativeDefinition(48_000, gains, mode)
            assertTrue("$mode should produce data", definition.isNotEmpty())
            assertEquals(14, definition.first().toInt())
            assertTrue("$mode contains non-finite coefficients", definition.all { it.isFinite() })
        }
    }
}
