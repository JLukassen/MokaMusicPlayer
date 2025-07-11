package com.yooastan.mokamusicplayer.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class ExoPlayerManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null

    fun initPlayer(uri: Uri): ExoPlayer {
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
        return exoPlayer!!
    }

    fun play() { exoPlayer?.playWhenReady = true }
    fun pause() { exoPlayer?.pause() }
    fun release() { exoPlayer?.release() }
    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false
    fun getPlayer(): ExoPlayer? = exoPlayer
}
