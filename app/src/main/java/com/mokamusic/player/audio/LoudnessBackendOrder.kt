package com.mokamusic.player.audio

/**
 * Select first the backend that completed successfully for this codec/device.
 * On Samsung FLAC, try Media3 first until a different backend succeeds.
 * Keep all fallback options available for odd or broken files.
 */
object LoudnessBackendOrder {
    private val standard = listOf("Platform URI", "Platform FD", "Media3 fallback")
    fun select(preferred: String?, samsungFlac: Boolean): List<String> {
        val candidates = if (samsungFlac) {
            listOf("Media3 fallback", "Platform FD", "Platform URI")
        } else standard
        return if (preferred in candidates) listOf(preferred!!) + candidates.filterNot { it == preferred }
            else candidates
    }
}
