package com.mokamusic.player.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mokamusic.player.audio.AudioPathMonitor
import com.mokamusic.player.audio.DirectPcmEngine
import com.mokamusic.player.audio.dsp.DspSettingsStore

/**
 * Keeps ExoPlayer/Media3 as Moka's playlist, session and fallback engine while routing eligible
 * local WAV/FLAC files through DirectPcmEngine.
 *
 * Because this object itself is the Player attached to MediaSession, lock-screen controls,
 * notification controls, Bluetooth media buttons and the in-app MediaController all keep using
 * the same queue regardless of which audio engine is currently rendering the track.
 */
@OptIn(UnstableApi::class)
class HiFiHybridPlayer(
    context: Context,
    private val fallback: ExoPlayer
) : ForwardingSimpleBasePlayer(fallback), DirectPcmEngine.Callback {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val direct = DirectPcmEngine(appContext, this, manageAudioFocus = false)
    private val focus = MokaAudioFocusController(appContext, ::onSystemAudioFocusChanged)
    private val transition = PlaybackTransitionTrace()
    private var focusSuspended = false
    private var resumeOnFocusGain = false
    private var focusDucked = false
    private val dspStore = DspSettingsStore(appContext)
    private val dspReloadRunnable = Runnable { reloadCurrentItemForDspSettings() }
    private val dspPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // EQ sliders can save dozens of values while dragging. Debounce rebuild requests so the
        // background DSP builder can coalesce edits without thrashing the live audio path.
        mainHandler.removeCallbacks(dspReloadRunnable)
        mainHandler.postDelayed(dspReloadRunnable, 350L)
    }

    @Volatile private var directActive = false
    @Volatile private var desiredPlayWhenReady = false
    @Volatile private var directState = DirectPcmEngine.State.IDLE
    @Volatile private var directDurationMs = 0L
    @Volatile private var directPositionMs = 0L
    private var switchingInternally = false

    private val fallbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!directActive && playbackState == Player.STATE_ENDED) {
                restoreDucking()
                desiredPlayWhenReady = false
                focus.abandon()
            }
            if (!directActive && playbackState == Player.STATE_READY) {
                transition.ready("media3")
                if (fallback.isPlaying) transition.audible("media3-ready")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!directActive && isPlaying) transition.audible("media3-playing")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !directActive) {
                transition.begin("media3-auto", mediaItem?.mediaId.orEmpty())
            }
            if (switchingInternally || directActive) return
            val item = mediaItem ?: return
            val wasPlaying = fallback.playWhenReady
            if (wasPlaying && direct.canAttempt(item)) {
                mainHandler.post {
                    if (!directActive && fallback.currentMediaItem == item) {
                        desiredPlayWhenReady = true
                        fallback.pause()
                        activateDirect(item, fallback.currentPosition.coerceAtLeast(0L), true)
                    }
                }
            }
        }
    }

    init {
        fallback.addListener(fallbackListener)
        dspStore.registerListener(dspPreferenceListener)
    }

    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        if (!directActive) return state

        // SimpleBasePlayer validates every State combination. During a direct->fallback handoff,
        // ExoPlayer can briefly still be loading while the direct engine has already gone idle.
        // Reusing that isLoading/playerError data with a different playbackState can throw from
        // State.Builder.build(), which is what the crash logs showed.
        if (state.timeline.isEmpty) {
            return state
        }

        val directStateSnapshot = directState
        val playbackState = when (directStateSnapshot) {
            DirectPcmEngine.State.PREPARING -> Player.STATE_BUFFERING
            DirectPcmEngine.State.READY,
            DirectPcmEngine.State.PLAYING,
            DirectPcmEngine.State.PAUSED -> Player.STATE_READY
            DirectPcmEngine.State.ENDED -> Player.STATE_ENDED
            DirectPcmEngine.State.ERROR,
            DirectPcmEngine.State.IDLE -> Player.STATE_IDLE
        }
        val position = direct.currentPositionMs.coerceAtLeast(0L)

        return state.buildUpon()
            .setPlayWhenReady(
                desiredPlayWhenReady && !focusSuspended && playbackState != Player.STATE_ENDED,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(playbackState)
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlayerError(null)
            .setContentPositionMs(position)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        transition.begin(
            "queue-replacement",
            mediaItems.getOrNull(startIndex.takeIf { it >= 0 } ?: 0)?.mediaId.orEmpty()
        )
        Log.i(TAG, "Queue replaced items=${mediaItems.size} startIndex=$startIndex")
        deactivateDirect(clearMonitor = true)
        restoreDucking()
        desiredPlayWhenReady = false
        resumeOnFocusGain = false
        focusSuspended = false
        focus.abandon()
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = fallback.currentMediaItem
        if (item != null && direct.canAttempt(item)) {
            fallback.playWhenReady = false
            AudioPathMonitor.beginDirectPath()
            // Keep Media3 idle while direct PCM prepares. Starting both renderer pipelines
            // wastes I/O and can initialize a second AudioTrack during transitions.
            activateDirect(item, fallback.currentPosition.coerceAtLeast(0L), desiredPlayWhenReady)
            return Futures.immediateVoidFuture()
        }
        return super.handlePrepare()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !focus.request()) {
            Log.w(TAG, "Playback deferred: audio focus not granted")
            desiredPlayWhenReady = false
            focusSuspended = true
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        desiredPlayWhenReady = playWhenReady
        if (!playWhenReady) {
            restoreDucking()
            resumeOnFocusGain = false
            focusSuspended = false
            focus.abandon()
        } else {
            focusSuspended = false
        }
        val item = fallback.currentMediaItem
        if (directActive) {
            if (playWhenReady) direct.play() else direct.pause()
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady && item != null && direct.canAttempt(item)) {
            fallback.pause()
            activateDirect(item, fallback.currentPosition.coerceAtLeast(0L), true)
            return Futures.immediateVoidFuture()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val currentIndex = fallback.currentMediaItemIndex
        val targetIndex = mediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: currentIndex
        val targetPosition = positionMs.coerceAtLeast(0L)

        if (targetIndex != currentIndex && targetIndex in 0 until fallback.mediaItemCount) {
            transition.begin("manual-seek", fallback.getMediaItemAt(targetIndex).mediaId)
        }
        if (directActive && targetIndex == currentIndex) {
            direct.seekTo(targetPosition)
            directPositionMs = targetPosition
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        if (targetIndex in 0 until fallback.mediaItemCount) {
            val target = fallback.getMediaItemAt(targetIndex)
            if (direct.canAttempt(target)) {
                switchingInternally = true
                try {
                    direct.stop()
                    directActive = false
                    fallback.pause()
                    fallback.seekTo(targetIndex, targetPosition)
                    activateDirect(target, targetPosition, desiredPlayWhenReady)
                } finally {
                    switchingInternally = false
                }
                return Futures.immediateVoidFuture()
            }
        }

        if (directActive) {
            switchingInternally = true
            try {
                direct.stop()
                directActive = false
                AudioPathMonitor.endDirectPath()
                fallback.stop()
                fallback.seekTo(targetIndex, targetPosition)
                fallback.prepare()
                if (desiredPlayWhenReady) fallback.play() else fallback.pause()
            } finally {
                switchingInternally = false
            }
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleRelease(): ListenableFuture<*> {
        mainHandler.removeCallbacks(dspReloadRunnable)
        dspStore.unregisterListener(dspPreferenceListener)
        focus.abandon()
        restoreDucking()
        direct.release()
        fallback.removeListener(fallbackListener)
        AudioPathMonitor.endDirectPath()
        return super.handleRelease()
    }

    override fun onStateChanged(state: DirectPcmEngine.State) {
        mainHandler.post {
            directState = state
            directPositionMs = direct.currentPositionMs
            directDurationMs = direct.durationMs
            invalidateState()
        }
    }

    override fun onReady(durationMs: Long) {
        mainHandler.post {
            transition.ready("direct")
            directDurationMs = durationMs
            directPositionMs = direct.currentPositionMs
            invalidateState()
        }
    }

    override fun onAudioStarted() {
        mainHandler.post { transition.audible("direct-audiotrack") }
    }

    override fun onEnded() {
        mainHandler.post {
            if (!directActive) return@post
            if (fallback.repeatMode == Player.REPEAT_MODE_ONE) {
                val repeating = fallback.currentMediaItem
                if (repeating != null) {
                    transition.begin("repeat-one", repeating.mediaId)
                    activateDirect(repeating, 0L, desiredPlayWhenReady)
                }
                return@post
            }

            val before = fallback.currentMediaItemIndex
            switchingInternally = true
            try {
                // The natural EOS path has already parked the output for same-format reuse.
                // Do NOT call direct.stop() before the next direct prepare.
                directActive = false
                fallback.seekToNextMediaItem()
                var after = fallback.currentMediaItemIndex
                if (after == before && fallback.repeatMode == Player.REPEAT_MODE_ALL && fallback.mediaItemCount > 0) {
                    fallback.seekToDefaultPosition(0)
                    after = fallback.currentMediaItemIndex
                }

                if (after == before || after == C.INDEX_UNSET) {
                    direct.stop()
                    restoreDucking()
                    desiredPlayWhenReady = false
                    focus.abandon()
                    directState = DirectPcmEngine.State.ENDED
                    AudioPathMonitor.endDirectPath()
                    invalidateState()
                    return@post
                }

                val next = fallback.currentMediaItem
                transition.begin("direct-next", next?.mediaId.orEmpty())
                if (next != null && direct.canAttempt(next)) {
                    activateDirect(next, 0L, desiredPlayWhenReady)
                } else {
                    direct.stop() // discard parked direct output before Media3 renders
                    AudioPathMonitor.endDirectPath()
                    fallback.stop()
                    if (after != C.INDEX_UNSET) fallback.seekTo(after, 0L)
                    fallback.prepare()
                    if (desiredPlayWhenReady) fallback.play()
                }
            } finally {
                switchingInternally = false
            }
            invalidateState()
        }
    }

    override fun onFallbackRequired(positionMs: Long, reason: String) {
        mainHandler.post {
            if (!directActive) return@post
            val index = fallback.currentMediaItemIndex
            directActive = false
            directState = DirectPcmEngine.State.IDLE
            AudioPathMonitor.endDirectPath()
            AudioPathMonitor.clearOutput()

            switchingInternally = true
            try {
                fallback.stop()
                if (index != C.INDEX_UNSET) fallback.seekTo(index, positionMs.coerceAtLeast(0L))
                fallback.prepare()
                if (desiredPlayWhenReady) fallback.play() else fallback.pause()
            } finally {
                switchingInternally = false
            }
            invalidateState()
        }
    }


    private fun reloadCurrentItemForDspSettings() {
        val item = fallback.currentMediaItem ?: return
        val settings = dspStore.load()
        val dspRequested = settings.anyProcessingEnabled

        // Once direct playback is already on Moka's float output path, DSP parameters can be
        // rebuilt between PCM blocks. Do not tear down MediaCodec, AudioTrack or audio focus for
        // EQ/DDC/convolver/limiter changes. Turning DSP off becomes float bypass for the remainder
        // of the current track; turning it back on reuses that same output stream.
        if (directActive && direct.isFloatDspPath && direct.requestDspHotReload()) {
            Log.i(TAG, "DSP preference change scheduled as in-place hot reload")
            invalidateState()
            return
        }

        // A pure integer direct stream only needs rebuilding when DSP transitions from off -> on,
        // because that is the one case where the AudioTrack encoding must change to float. Saving
        // EQ values while the DSP master is off should be silent and should not restart playback.
        if (directActive && !direct.isFloatDspPath && !dspRequested) {
            return
        }

        // If Media3 is rendering and DSP is still off, there is no DSP route to reload. This also
        // avoids repeatedly retrying a direct path that previously fell back for codec/route reasons.
        if (!directActive && !dspRequested) {
            return
        }

        val position = if (directActive) direct.currentPositionMs else fallback.currentPosition.coerceAtLeast(0L)
        val shouldPlay = desiredPlayWhenReady || fallback.playWhenReady
        val shouldUseDirect = direct.canAttempt(item)

        transition.begin("dsp-route-rebuild", item.mediaId)
        switchingInternally = true
        try {
            if (shouldUseDirect) {
                direct.stop()
                directActive = false
                fallback.pause()
                val index = fallback.currentMediaItemIndex
                if (index != C.INDEX_UNSET) fallback.seekTo(index, position)
                activateDirect(item, position, shouldPlay)
            } else if (directActive) {
                direct.stop()
                directActive = false
                AudioPathMonitor.endDirectPath()
                AudioPathMonitor.clearOutput()
                val index = fallback.currentMediaItemIndex
                fallback.stop()
                if (index != C.INDEX_UNSET) fallback.seekTo(index, position)
                fallback.prepare()
                if (shouldPlay) fallback.play() else fallback.pause()
            }
        } finally {
            switchingInternally = false
        }
        invalidateState()
    }

    fun pauseForNoisyRoute() {
        focus.abandon()
        restoreDucking()
        focusSuspended = false
        resumeOnFocusGain = false
        direct.invalidateOutputCache()
        desiredPlayWhenReady = false
        if (directActive) direct.pause() else fallback.pause()
        invalidateState()
    }

    private fun activateDirect(item: MediaItem, positionMs: Long, playWhenReady: Boolean) {
        directActive = true
        desiredPlayWhenReady = playWhenReady
        directState = DirectPcmEngine.State.PREPARING
        directPositionMs = positionMs.coerceAtLeast(0L)
        directDurationMs = 0L

        val extras = item.mediaMetadata.extras
        AudioPathMonitor.setSource(
            sampleRateHz = extras?.getInt(AudioPathMonitor.EXTRA_SAMPLE_RATE_HZ, 0)?.takeIf { it > 0 },
            bitDepth = extras?.getInt(AudioPathMonitor.EXTRA_BIT_DEPTH, 0)?.takeIf { it > 0 },
            channels = extras?.getInt(AudioPathMonitor.EXTRA_CHANNEL_COUNT, 0)?.takeIf { it > 0 }
        )
        AudioPathMonitor.beginDirectPath()
        direct.prepare(item, positionMs, playWhenReady)
        // Prepare only the next queue entry (respecting shuffle/repeat), never the whole library.
        val nextIndex = fallback.nextMediaItemIndex
        direct.prefetch(
            if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until fallback.mediaItemCount)
                fallback.getMediaItemAt(nextIndex).takeIf(direct::canAttempt)
            else null
        )
        invalidateState()
    }

    private fun onSystemAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Permanent focus loss: stop playback, no automatic resume")
                resumeOnFocusGain = false
                focusSuspended = true
                desiredPlayWhenReady = false
                restoreDucking()
                if (directActive) direct.pause() else fallback.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = desiredPlayWhenReady && !focusSuspended
                focusSuspended = true
                restoreDucking()
                if (directActive) direct.pause() else fallback.pause()
                Log.i(TAG, "Transient focus loss; eligibleToResume=$resumeOnFocusGain")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusDucked = true
                direct.setOutputVolume(0.2f)
                fallback.volume = 0.2f
                Log.i(TAG, "Ducking for navigation or notification")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreDucking()
                val shouldResume = resumeOnFocusGain && desiredPlayWhenReady
                resumeOnFocusGain = false
                focusSuspended = false
                if (shouldResume) {
                    Log.i(TAG, "Restoring playback after transient focus interruption")
                    if (directActive) direct.play() else fallback.play()
                }
            }
        }
        invalidateState()
    }

    private fun restoreDucking() {
        if (!focusDucked) return
        focusDucked = false
        direct.setOutputVolume(1f)
        fallback.volume = 1f
    }

    private fun deactivateDirect(clearMonitor: Boolean) {
        if (directActive || direct.state != DirectPcmEngine.State.IDLE) direct.stop()
        directActive = false
        directState = DirectPcmEngine.State.IDLE
        directDurationMs = 0L
        directPositionMs = 0L
        if (clearMonitor) {
            AudioPathMonitor.endDirectPath()
            AudioPathMonitor.clearOutput()
        }
    }

    private companion object {
        const val TAG = "MokaHybrid"
    }
}
