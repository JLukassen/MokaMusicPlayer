package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build

data class AudioOutputStatus(
    val deviceName: String = "Android audio",
    val routeLabel: String = "System output",
    val directPlaybackAvailable: Boolean = false,
    val usbBitPerfectAvailable: Boolean = false,
    val maxDirectSampleRateHz: Int? = null,
    val maxUsbMixerSampleRateHz: Int? = null,
    val preferredUsbMixerSet: Boolean = false,
    val preferredUsbMixerBitPerfect: Boolean = false,
    val preferredUsbMixerSampleRateHz: Int? = null,
    val preferredUsbMixerBitDepth: Int? = null,
    val preferredUsbMixerChannels: Int? = null,
    val audioTrackCreated: Boolean = false,
    val actualAudioTrackSampleRateHz: Int? = null,
    val actualAudioTrackBitDepth: Int? = null,
    val actualAudioTrackEncodingLabel: String? = null,
    val actualAudioTrackChannels: Int? = null,
    val sourceMatchesAudioTrack: Boolean = false,
    val highResFloatPrecisionPreserved: Boolean = false,
    val usbTransportBitPerfectVerified: Boolean = false,
    val sourceBitPerfectVerified: Boolean = false,
    val directEngineActive: Boolean = false,
    val directEngineLabel: String? = null,
    val dspActive: Boolean = false,
    val note: String = "Source-rate playback when supported by the device"
)

/**
 * Inspects the route Android would use for media playback and combines it with the actual
 * AudioTrack snapshot produced by MokaAudioOutputProvider.
 */
class AudioOutputInspector(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaAttributes = PlatformAudioAttributes.Builder()
        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun inspect(): AudioOutputStatus {
        val routed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                audioManager.getAudioDevicesForAttributes(mediaAttributes)
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        }.getOrDefault(emptyList())

        val device = routed.firstOrNull { it.type.isPersonalAudio() }
            ?: routed.firstOrNull()
            ?: runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull() }.getOrNull()

        val directProfiles = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getDirectProfilesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
        } else emptyList()

        val maxDirectRate = directProfiles
            .flatMap { it.sampleRates.toList() }
            .filter { it > 0 }
            .maxOrNull()

        val usbAttrs = if (Build.VERSION.SDK_INT >= 34 && device?.type.isUsbAudio()) {
            runCatching { audioManager.getSupportedMixerAttributes(device!!) }.getOrDefault(emptyList())
        } else emptyList()

        val bitPerfectAvailable = if (Build.VERSION.SDK_INT >= 34) {
            usbAttrs.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        } else false

        val maxUsbRate = usbAttrs.mapNotNull { attr ->
            attr.format.sampleRate.takeIf { it > 0 }
        }.maxOrNull()

        val preferredUsbMixer = if (Build.VERSION.SDK_INT >= 34 && device?.type.isUsbAudio()) {
            runCatching { audioManager.getPreferredMixerAttributes(mediaAttributes, device!!) }.getOrNull()
        } else null
        val preferredFormat = preferredUsbMixer?.format

        val runtime = AudioPathMonitor.snapshot()
        val actualEncoding = runtime.actualEncoding
        val actualBitDepth = actualEncoding?.pcmBitDepthOrNull()

        val sameRate = runtime.sourceSampleRateHz != null && runtime.sourceSampleRateHz == runtime.actualSampleRateHz
        val sameChannels = runtime.sourceChannels != null && runtime.sourceChannels == runtime.actualChannels
        val expectedSourceEncoding = runtime.sourceEncoding ?: when (runtime.sourceBitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> null
        }
        val strictEncodingMatch = expectedSourceEncoding != null && actualEncoding == expectedSourceEncoding
        val sourceMatchesAudioTrack = sameRate && sameChannels && strictEncodingMatch

        // 32-bit float has enough precision to carry 24-bit integer PCM without reducing its
        // numerical resolution, but its representation differs, so this is deliberately not
        // labeled bit-perfect.
        val highResFloatPreserved = sameRate && sameChannels &&
            (runtime.sourceBitDepth ?: 0) in 17..24 && actualEncoding == AudioFormat.ENCODING_PCM_FLOAT

        val route = device?.type.routeLabel() ?: "System output"
        val deviceName = device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: route
        val usbRoute = device?.type.isUsbAudio()
        val transportVerified = usbRoute && runtime.usbTransportVerified
        val sourceVerified = transportVerified && sourceMatchesAudioTrack && !runtime.dspActive

        val note = when {
            runtime.dspActive && transportVerified ->
                "DSP is active: processed PCM is transported without mixer alteration, but source bit-perfect is intentionally false"
            runtime.dspActive ->
                "Moka DSP is active in 32-bit float; the audio is intentionally modified by the selected tuning chain"
            sourceVerified && runtime.directEngineActive ->
                "Verified: Moka Direct PCM, source format, AudioTrack, and Android BIT_PERFECT USB mixer all match"
            sourceVerified ->
                "Verified: source format, AudioTrack, and Android BIT_PERFECT USB mixer match"
            transportVerified && !sourceMatchesAudioTrack ->
                "USB transport matches Android's BIT_PERFECT mixer, but Media3 converted the source before AudioTrack"
            highResFloatPreserved && usbRoute ->
                "High-resolution source is preserved as 32-bit float; USB source bit-perfect is not yet active"
            runtime.directEngineActive && usbRoute ->
                "Moka is feeding integer PCM directly to AudioTrack; exact USB mixer verification determines bit-perfect status"
            runtime.directEngineActive ->
                "Moka is feeding source-depth integer PCM directly to AudioTrack; downstream device/HAL processing can still apply"
            highResFloatPreserved ->
                "High-resolution source is preserved as 32-bit float for headphone playback"
            runtime.outputCreated ->
                "Moka is reporting the actual AudioTrack format; downstream Android/device processing may still apply"
            device?.type.isUsbAudio() && bitPerfectAvailable ->
                "USB exposes bit-perfect mixers; Moka verifies the AudioTrack before claiming bit-perfect playback"
            device?.type.isBluetoothAudio() ->
                "Bluetooth quality is limited by the negotiated Bluetooth codec"
            directProfiles.isNotEmpty() ->
                "Android reports a direct playback path for this media route"
            else ->
                "Lossless decode; DSP and normalization are shown explicitly when they are active"
        }

        return AudioOutputStatus(
            deviceName = deviceName,
            routeLabel = route,
            directPlaybackAvailable = directProfiles.isNotEmpty(),
            usbBitPerfectAvailable = bitPerfectAvailable,
            maxDirectSampleRateHz = maxDirectRate,
            maxUsbMixerSampleRateHz = maxUsbRate,
            preferredUsbMixerSet = preferredUsbMixer != null,
            preferredUsbMixerBitPerfect = preferredUsbMixer?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            preferredUsbMixerSampleRateHz = preferredFormat?.sampleRate?.takeIf { it > 0 },
            preferredUsbMixerBitDepth = preferredFormat?.encoding?.pcmBitDepthOrNull(),
            preferredUsbMixerChannels = preferredFormat?.channelCount?.takeIf { it > 0 },
            audioTrackCreated = runtime.outputCreated,
            actualAudioTrackSampleRateHz = runtime.actualSampleRateHz,
            actualAudioTrackBitDepth = actualBitDepth,
            actualAudioTrackEncodingLabel = actualEncoding?.pcmEncodingLabel(),
            actualAudioTrackChannels = runtime.actualChannels,
            sourceMatchesAudioTrack = sourceMatchesAudioTrack,
            highResFloatPrecisionPreserved = highResFloatPreserved,
            usbTransportBitPerfectVerified = transportVerified,
            sourceBitPerfectVerified = sourceVerified,
            directEngineActive = runtime.directEngineActive,
            directEngineLabel = runtime.directEngineLabel,
            dspActive = runtime.dspActive,
            note = note
        )
    }
}

private fun Int?.isUsbAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}

private fun Int?.isBluetoothAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
    else -> false
}

private fun Int?.isPersonalAudio(): Boolean = isUsbAudio() || isBluetoothAudio() || when (this) {
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
    else -> false
}

private fun Int?.routeLabel(): String = when (this) {
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headphones"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE audio"
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE broadcast"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog output"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital output"
    else -> "Android audio"
}
