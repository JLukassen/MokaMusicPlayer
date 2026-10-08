package com.mokamusic.player.audio

/**
 * Typed loudness errors allow unsupported files to be distinguished from retryable extractor
 * failures without ever replacing a failed result with an invalid LUFS measurement.
 */
class LoudnessAnalysisException(
    val failureCode: String,
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
