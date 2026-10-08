package com.mokamusic.player.audio

import java.nio.ByteBuffer

/** JNI bridge for Beta 3's offline loudness analyzer. */
internal object NativeLoudnessBridge {
    val available: Boolean = runCatching {
        System.loadLibrary("moka_dsp")
        true
    }.getOrDefault(false)

    external fun nativeCreate(sampleRate: Int, channels: Int): Long
    external fun nativeProcessPcm(handle: Long, pcm: ByteBuffer, encoding: Int, sampleCount: Int): Boolean
    external fun nativeFinish(handle: Long): FloatArray?
    external fun nativeRelease(handle: Long)
}
