
package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Real-device safety guard discovered during Beta 1 testing.
 *
 * On Pixel 8a / Android 17, Moka's integer USB BIT_PERFECT path could bypass the
 * expected Android media-volume attenuation. The preferred sound path on that device
 * is the existing DSP float pipeline, so keep the stored DSP profile active whenever
 * USB audio is the current route and do not request BIT_PERFECT for that Pixel route.
 *
 * The stored user preference is not overwritten: unplugging USB restores the user's
 * normal DSP master setting.
 */
object UsbDspSafetyPolicy {
    fun requiresDsp(context: Context): Boolean {
        if (!isAffectedPixel()) return false
        val app = context.applicationContext
        val audioManager = app.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val routed = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getAudioDevicesForAttributes(attributes).toList() }
                .getOrDefault(emptyList())
        } else {
            runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrDefault(emptyList())
        }
        return routed.any { it.type.isUsbAudioForSafetyPolicy() }
    }

    fun shouldAvoidBitPerfect(context: Context, device: AudioDeviceInfo): Boolean =
        isAffectedPixel() && device.type.isUsbAudioForSafetyPolicy()

    private fun isAffectedPixel(): Boolean =
        Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
            Build.MODEL.equals("Pixel 8a", ignoreCase = true)
}

private fun Int.isUsbAudioForSafetyPolicy(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}
