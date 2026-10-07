package com.mokamusic.player.audio

import android.media.AudioFormat

/** Runtime snapshot of Moka's source and output path. */
data class AudioPathSnapshot(
    val sourceSampleRateHz: Int? = null,
    val sourceBitDepth: Int? = null,
    val sourceChannels: Int? = null,
    val sourceEncoding: Int? = null,
    val outputCreated: Boolean = false,
    val requestedSampleRateHz: Int? = null,
    val requestedEncoding: Int? = null,
    val requestedChannels: Int? = null,
    val actualSampleRateHz: Int? = null,
    val actualEncoding: Int? = null,
    val actualChannels: Int? = null,
    val usbMixerRequested: Boolean = false,
    val usbMixerBitPerfect: Boolean = false,
    val usbMixerSampleRateHz: Int? = null,
    val usbMixerEncoding: Int? = null,
    val usbMixerChannels: Int? = null,
    val usbTransportVerified: Boolean = false,
    val isOffload: Boolean = false,
    val directEngineActive: Boolean = false,
    val directEngineLabel: String? = null,
    val dspActive: Boolean = false
)

object AudioPathMonitor {
    const val EXTRA_SAMPLE_RATE_HZ = "moka.sample_rate_hz"
    const val EXTRA_BIT_DEPTH = "moka.bit_depth"
    const val EXTRA_CHANNEL_COUNT = "moka.channel_count"
    const val EXTRA_FORMAT_LABEL = "moka.format_label"
    const val EXTRA_NORMALIZATION_GAIN_DB = "moka.normalization_gain_db"
    const val EXTRA_ALBUM_NORMALIZATION_GAIN_DB = "moka.album_normalization_gain_db"

    @Volatile private var current = AudioPathSnapshot()

    fun snapshot(): AudioPathSnapshot = current

    @Synchronized
    fun setSource(sampleRateHz: Int?, bitDepth: Int?, channels: Int?, encoding: Int? = null) {
        current = current.copy(
            sourceSampleRateHz = sampleRateHz,
            sourceBitDepth = bitDepth,
            sourceChannels = channels,
            sourceEncoding = encoding
        )
    }

    @Synchronized
    fun beginDirectPath(label: String = "Moka Direct PCM") {
        current = current.copy(
            directEngineActive = true,
            directEngineLabel = label,
            dspActive = label.contains("DSP", ignoreCase = true)
        )
    }

    @Synchronized
    fun endDirectPath() {
        current = current.copy(directEngineActive = false, directEngineLabel = null, dspActive = false)
    }

    /** Media3 fallback output. Ignored while the direct engine owns the audio path. */
    @Synchronized
    fun recordOutput(
        requestedSampleRateHz: Int?,
        requestedEncoding: Int?,
        requestedChannels: Int?,
        actualSampleRateHz: Int?,
        actualEncoding: Int?,
        actualChannels: Int?,
        usbMixerRequested: Boolean,
        usbMixerBitPerfect: Boolean,
        usbMixerSampleRateHz: Int?,
        usbMixerEncoding: Int?,
        usbMixerChannels: Int?,
        usbTransportVerified: Boolean,
        isOffload: Boolean
    ) {
        if (current.directEngineActive) return
        current = current.copy(
            outputCreated = true,
            requestedSampleRateHz = requestedSampleRateHz,
            requestedEncoding = requestedEncoding,
            requestedChannels = requestedChannels,
            actualSampleRateHz = actualSampleRateHz,
            actualEncoding = actualEncoding,
            actualChannels = actualChannels,
            usbMixerRequested = usbMixerRequested,
            usbMixerBitPerfect = usbMixerBitPerfect,
            usbMixerSampleRateHz = usbMixerSampleRateHz,
            usbMixerEncoding = usbMixerEncoding,
            usbMixerChannels = usbMixerChannels,
            usbTransportVerified = usbTransportVerified,
            isOffload = isOffload,
            directEngineActive = false,
            directEngineLabel = null,
            dspActive = false
        )
    }

    /** Direct PCM output. This is the authoritative snapshot while direct playback is active. */
    @Synchronized
    fun recordDirectOutput(
        sampleRateHz: Int,
        encoding: Int,
        channels: Int,
        usbMixerRequested: Boolean,
        usbMixerBitPerfect: Boolean,
        usbTransportVerified: Boolean,
        label: String
    ) {
        current = current.copy(
            outputCreated = true,
            requestedSampleRateHz = sampleRateHz,
            requestedEncoding = encoding,
            requestedChannels = channels,
            actualSampleRateHz = sampleRateHz,
            actualEncoding = encoding,
            actualChannels = channels,
            usbMixerRequested = usbMixerRequested,
            usbMixerBitPerfect = usbMixerBitPerfect,
            usbMixerSampleRateHz = sampleRateHz.takeIf { usbMixerRequested },
            usbMixerEncoding = encoding.takeIf { usbMixerRequested },
            usbMixerChannels = channels.takeIf { usbMixerRequested },
            usbTransportVerified = usbTransportVerified,
            isOffload = false,
            directEngineActive = true,
            directEngineLabel = label,
            dspActive = label.contains("DSP", ignoreCase = true)
        )
    }

    @Synchronized
    fun clearOutput() {
        current = current.copy(
            outputCreated = false,
            requestedSampleRateHz = null,
            requestedEncoding = null,
            requestedChannels = null,
            actualSampleRateHz = null,
            actualEncoding = null,
            actualChannels = null,
            usbMixerRequested = false,
            usbMixerBitPerfect = false,
            usbMixerSampleRateHz = null,
            usbMixerEncoding = null,
            usbMixerChannels = null,
            usbTransportVerified = false,
            isOffload = false
        )
    }

    @Synchronized
    fun clearAll() { current = AudioPathSnapshot() }
}

internal fun Int.pcmBitDepthOrNull(): Int? = when (this) {
    AudioFormat.ENCODING_PCM_8BIT -> 8
    AudioFormat.ENCODING_PCM_16BIT -> 16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
    else -> null
}

internal fun Int.pcmEncodingLabel(): String = when (this) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit packed"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit integer"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM 32-bit float"
    AudioFormat.ENCODING_INVALID -> "Invalid"
    else -> "Encoding $this"
}
