package com.mokamusic.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessRetryPolicyTest {
    @Test fun retryBackoffIsBounded() {
        val now = 100000L
        assertEquals(now + 21600L, LoudnessRetryPolicy.retryAfter(now, 1, false))
        assertEquals(now + 43200L, LoudnessRetryPolicy.retryAfter(now, 2, false))
        assertEquals(now + 86400L, LoudnessRetryPolicy.retryAfter(now, 3, false))
        assertEquals(now + 345600L, LoudnessRetryPolicy.retryAfter(now, 999, false))
    }

    @Test fun unsupportedFilesStaySuppressedUntilChangedOrReset() {
        val never = LoudnessRetryPolicy.retryAfter(100L, 1, true)
        assertEquals(Long.MAX_VALUE, never)
        assertTrue(LoudnessRetryPolicy.shouldSkip(true, never, 10_000L))
        assertFalse(LoudnessRetryPolicy.shouldSkip(false, never, 10_000L))
    }

    @Test fun recoverableFailureCanRetryLater() {
        val retry = LoudnessRetryPolicy.retryAfter(1000L, 1, false)
        assertTrue(LoudnessRetryPolicy.shouldSkip(true, retry, retry - 1))
        assertFalse(LoudnessRetryPolicy.shouldSkip(true, retry, retry))
    }

    @Test fun retryDeadlineDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, LoudnessRetryPolicy.retryAfter(Long.MAX_VALUE - 2L, 8, false))
    }
}
