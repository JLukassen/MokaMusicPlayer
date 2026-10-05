package com.mokamusic.player.audio

/**
 * Stable boundary for the future bit-perfect USB output implementation.
 *
 * V1 UI talks to this abstraction instead of depending directly on AudioTrack,
 * making it possible to add Android bit-perfect mixer routing and, later,
 * an optional direct USB Audio Class backend without rewriting the UI.
 */
interface HiFiOutputEngine {
    val state: OutputState

    suspend fun configure(source: SourceFormat): OutputResult
    suspend fun start()
    suspend fun pause()
    suspend fun stop()
}

data class SourceFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int = 2,
    val codec: String = "FLAC"
)

data class OutputState(
    val deviceName: String = "Android audio",
    val mode: String = "System mixer",
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitPerfect: Boolean = false
)

sealed interface OutputResult {
    data object Ready : OutputResult
    data class Unsupported(val reason: String) : OutputResult
    data class Error(val throwable: Throwable) : OutputResult
}
