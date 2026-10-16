package com.mokamusic.player.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessBackendOrderTest {
    @Test fun samsungFlacStartsWithMedia3UnlessSuccessfulPathLearned() {
        assertEquals("Media3 fallback", LoudnessBackendOrder.select(null, true).first())
        assertEquals("Platform FD", LoudnessBackendOrder.select("Platform FD", true).first())
    }
    @Test fun allFallbacksAreRetained() {
        val paths = LoudnessBackendOrder.select("Platform URI", true)
        assertEquals(3, paths.size)
        assertEquals(3, paths.distinct().size)
    }
    @Test fun otherDevicesStartWithPlatformExtractor() {
        assertEquals("Platform URI", LoudnessBackendOrder.select(null, false).first())
    }
}
