package com.mokamusic.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.mokamusic.player.MainActivity
import com.mokamusic.player.audio.AudioPathMonitor
import com.mokamusic.player.audio.MokaAudioOutputProvider

class PlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var browserCallback: CarLibraryCallback? = null
    private var hybridPlayer: HiFiHybridPlayer? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                hybridPlayer?.pauseForNoisyRoute()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val musicAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Media3 remains the high-quality fallback and handles every format that the direct
        // engine cannot preserve exactly. Float output preserves up to 24-bit integer precision.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)

        val audioOutputProvider = MokaAudioOutputProvider(this)
        val fallback = ExoPlayer.Builder(this, renderersFactory)
            .setAudioOutputProvider(audioOutputProvider)
            .setAudioAttributes(musicAttributes, true)
            .setHandleAudioBecomingNoisy(false) // handled for both engines by this service
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        fallback.skipSilenceEnabled = false
        fallback.volume = 1f

        val player = HiFiHybridPlayer(this, fallback)
        hybridPlayer = player

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val extras = mediaItem?.mediaMetadata?.extras
                AudioPathMonitor.setSource(
                    sampleRateHz = extras?.getInt(AudioPathMonitor.EXTRA_SAMPLE_RATE_HZ, 0)?.takeIf { it > 0 },
                    bitDepth = extras?.getInt(AudioPathMonitor.EXTRA_BIT_DEPTH, 0)?.takeIf { it > 0 },
                    channels = extras?.getInt(AudioPathMonitor.EXTRA_CHANNEL_COUNT, 0)?.takeIf { it > 0 }
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
                    AudioPathMonitor.clearAll()
                }
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            1001,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        browserCallback = CarLibraryCallback(this)
        mediaSession = MediaLibrarySession.Builder(this, player, browserCallback!!)
            .setSessionActivity(sessionActivity)
            .build()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        mediaSession?.run {
            player.release()
            release()
        }
        hybridPlayer = null
        browserCallback?.close()
        browserCallback = null
        AudioPathMonitor.clearAll()
        mediaSession = null
        super.onDestroy()
    }
}
