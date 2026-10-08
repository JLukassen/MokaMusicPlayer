package com.mokamusic.player.audio

/**
 * A failed scan must not be retried on every activity recreation.
 * Unsupported formats wait for a file change or explicit cache reset.
 * Recoverable extraction/codec failures get bounded exponential backoff.
 */
internal object LoudnessRetryPolicy {
    private const val FIRST_RETRY_SECONDS = 6L * 60L * 60L
    private const val MAX_DELAY_SECONDS = 4L * 24L * 60L * 60L

    fun retryAfter(nowEpochSeconds: Long, attempts: Int, permanent: Boolean): Long {
        if (permanent) return Long.MAX_VALUE
        val multiplier = 1L shl (attempts - 1).coerceIn(0, 5)
        val delay = (FIRST_RETRY_SECONDS * multiplier).coerceAtMost(MAX_DELAY_SECONDS)
        return if (nowEpochSeconds > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowEpochSeconds + delay
    }

    fun shouldSkip(fingerprintMatches: Boolean, retryAfter: Long, nowEpochSeconds: Long): Boolean =
        fingerprintMatches && nowEpochSeconds < retryAfter
}
