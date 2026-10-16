package com.mokamusic.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The only audio-focus owner for BOTH Media3 fallback and the direct PCM engine.
 * Keeping focus with the session prevents Moka's engines from stealing it from each other.
 */
internal class MokaAudioFocusController(
    context: Context,
    private val onFocusChanged: (Int) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false
        Log.i(TAG, "Focus change=$change")
        onFocusChanged(change)
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()

    private var hasFocus = false

    fun request(): Boolean {
        if (hasFocus) return true
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "Focus request result=$result")
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        hasFocus = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        Log.i(TAG, "Focus abandoned")
    }

    private companion object {
        const val TAG = "MokaFocus"
    }
}
