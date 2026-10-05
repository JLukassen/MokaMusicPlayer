package com.mokamusic.player.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mokamusic.player.model.MusicTrack

class MokaPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    fun play(track: MusicTrack) {
        val item = MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.id.toString())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun release() = player.release()
}
