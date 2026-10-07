package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Media3 AudioOutputProvider that does two things:
 *  1) On Android 14+, asks Android for a BIT_PERFECT USB mixer that exactly matches the
 *     AudioTrack format Media3 is about to create.
 *  2) Reads the actual AudioTrack format after creation and publishes it to AudioPathMonitor.
 *
 * Matching the USB mixer to AudioTrack verifies the Android transport side. Moka separately
 * compares that AudioTrack format with the source file metadata before claiming source bit-perfect.
 */
@OptIn(UnstableApi::class)
class MokaAudioOutputProvider(context: Context) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build()
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var activeUsbDevice: AudioDeviceInfo? = null

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val directOwnsPath = AudioPathMonitor.snapshot().directEngineActive
        val selectedMixer = when {
            directOwnsPath -> null
            Build.VERSION.SDK_INT >= 34 && !config.isOffload && config.encoding.isLinearPcm() -> selectExactUsbMixer(config)
            else -> {
                clearPreferredUsbMixer()
                null
            }
        }

        val output = super.getAudioOutput(config)
        val audioTrackOutput = output as? AudioTrackAudioOutput
        val track = audioTrackOutput?.getAudioTrack()
        val actualFormat = runCatching { track?.format }.getOrNull()

        val requestedChannels = channelCountForMask(config.channelMask)
        val actualSampleRate = actualFormat?.sampleRate?.takeIf { it > 0 }
        val actualEncoding = actualFormat?.encoding?.takeIf { it != AudioFormat.ENCODING_INVALID }
        val actualChannels = actualFormat?.channelCount?.takeIf { it > 0 }

        val transportVerified = selectedMixer != null && actualFormat != null &&
            selectedMixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            selectedMixer.format.sampleRate == actualSampleRate &&
            selectedMixer.format.encoding == actualEncoding &&
            selectedMixer.format.channelCount == actualChannels

        AudioPathMonitor.recordOutput(
            requestedSampleRateHz = config.sampleRate.takeIf { it > 0 },
            requestedEncoding = config.encoding,
            requestedChannels = requestedChannels,
            actualSampleRateHz = actualSampleRate,
            actualEncoding = actualEncoding,
            actualChannels = actualChannels,
            usbMixerRequested = selectedMixer != null,
            usbMixerBitPerfect = selectedMixer?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            usbMixerSampleRateHz = selectedMixer?.format?.sampleRate?.takeIf { it > 0 },
            usbMixerEncoding = selectedMixer?.format?.encoding,
            usbMixerChannels = selectedMixer?.format?.channelCount?.takeIf { it > 0 },
            usbTransportVerified = transportVerified,
            isOffload = config.isOffload
        )

        return output
    }

    override fun release() {
        clearPreferredUsbMixer()
        AudioPathMonitor.clearOutput()
        super.release()
    }

    @androidx.annotation.RequiresApi(34)
    private fun selectExactUsbMixer(config: AudioOutputProvider.OutputConfig): AudioMixerAttributes? {
        val usbDevice = runCatching {
            audioManager.getAudioDevicesForAttributes(mediaAttributes)
                .firstOrNull { it.type.isUsbAudio() }
        }.getOrNull()

        if (usbDevice == null) {
            clearPreferredUsbMixer()
            return null
        }

        if (UsbDspSafetyPolicy.shouldAvoidBitPerfect(appContext, usbDevice)) {
            runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, usbDevice) }
            activeUsbDevice = null
            return null
        }

        val desiredChannels = channelCountForMask(config.channelMask)
        val exact = runCatching { audioManager.getSupportedMixerAttributes(usbDevice) }
            .getOrDefault(emptyList())
            .firstOrNull { mixer ->
                mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    mixer.format.sampleRate == config.sampleRate &&
                    mixer.format.encoding == config.encoding &&
                    (desiredChannels == null || mixer.format.channelCount == desiredChannels)
            }

        if (exact == null) {
            clearPreferredUsbMixer()
            return null
        }

        if (activeUsbDevice?.id != null && activeUsbDevice?.id != usbDevice.id) {
            clearPreferredUsbMixer()
        }

        val selected = runCatching {
            audioManager.setPreferredMixerAttributes(mediaAttributes, usbDevice, exact)
        }.getOrDefault(false)

        activeUsbDevice = usbDevice.takeIf { selected }
        return exact.takeIf { selected }
    }

    private fun clearPreferredUsbMixer() {
        if (Build.VERSION.SDK_INT < 34) {
            activeUsbDevice = null
            return
        }
        val device = activeUsbDevice ?: return
        runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, device) }
        activeUsbDevice = null
    }

    private fun channelCountForMask(channelMask: Int): Int? = runCatching {
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(channelMask)
            .build()
            .channelCount
            .takeIf { it > 0 }
    }.getOrNull()
}

private fun Int.isLinearPcm(): Boolean = when (this) {
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_FLOAT -> true
    else -> false
}

private fun Int.isUsbAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}
