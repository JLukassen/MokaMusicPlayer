package com.mokamusic.player.audio

/**
 * Capability model used by the upcoming API 34+ USB bit-perfect implementation.
 */
data class UsbAudioCapabilities(
    val deviceName: String,
    val sampleRatesHz: Set<Int>,
    val bitDepths: Set<Int>,
    val supportsBitPerfect: Boolean
)
