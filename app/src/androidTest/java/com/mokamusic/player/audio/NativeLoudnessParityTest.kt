package com.mokamusic.player.audio

import android.media.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NativeLoudnessParityTest {
    private fun syntheticSine(sampleRate: Int, samples: Int, amplitude: Double = 0.1): ByteBuffer =
        ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(samples) { i ->
                val x = sin(2.0 * PI * 1000.0 * i / sampleRate) * amplitude
                putShort((x * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
            }
            flip()
        }

    private fun analyze(rate: Int, segments: List<Int>): FloatArray {
        assertTrue("Native loudness library unavailable", NativeLoudnessBridge.available)
        val handle = NativeLoudnessBridge.nativeCreate(rate, 1)
        assertTrue(handle != 0L)
        try {
            segments.forEach { samples ->
                assertTrue(
                    NativeLoudnessBridge.nativeProcessPcm(
                        handle, syntheticSine(rate, samples),
                        AudioFormat.ENCODING_PCM_16BIT, samples
                    )
                )
            }
            return NativeLoudnessBridge.nativeFinish(handle)!!
        } finally {
            NativeLoudnessBridge.nativeRelease(handle)
        }
    }

    @Test fun expectedSineEnergyAndPeak() {
        val result = analyze(48000, listOf(48000))
        assertTrue("LUFS must be finite", result[0].isFinite())
        assertTrue("peak must be finite", result[1].isFinite())
        assertEquals("0.1 peak amplitude is -20 dBFS", -20.0, result[1].toDouble(), 1.0)
        // 1-kHz sine RMS is -23.01 dBFS and K-weighting is close to unity at 1 kHz.
        assertEquals(-23.0, result[0].toDouble(), 2.0)
    }

    @Test fun chunkedAndContiguousProduceSameLoudness() {
        val contiguous = analyze(48000, listOf(48000))
        val chunked = analyze(48000, List(10) { 4800 })
        assertEquals(contiguous[0].toDouble(), chunked[0].toDouble(), 0.01)
        assertEquals(contiguous[1].toDouble(), chunked[1].toDouble(), 0.01)
    }
}
