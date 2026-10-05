package com.mokamusic.player

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mokamusic.player.audio.AudioOutputInspector
import com.mokamusic.player.audio.AudioOutputStatus
import com.mokamusic.player.audio.AudioPathMonitor
import com.mokamusic.player.data.AudioTechnicalMetadata
import com.mokamusic.player.data.AudioTechnicalMetadataReader
import com.mokamusic.player.data.MusicLibraryRepository
import com.mokamusic.player.model.MusicTrack
import com.mokamusic.player.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MokaUiState(
    val tracks: List<MusicTrack> = emptyList(),
    val isScanning: Boolean = false,
    val libraryInitialized: Boolean = false,
    val libraryCacheStale: Boolean = false,
    val libraryError: String? = null,
    val controllerReady: Boolean = false,
    val currentTrack: MusicTrack? = null,
    val technical: AudioTechnicalMetadata = AudioTechnicalMetadata(),
    val output: AudioOutputStatus = AudioOutputStatus(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

class MokaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicLibraryRepository(application)
    private val technicalReader = AudioTechnicalMetadataReader(application)
    private val outputInspector = AudioOutputInspector(application)

    private val _uiState = MutableStateFlow(MokaUiState())
    val uiState: StateFlow<MokaUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingTrackId: Long? = null
    private var pendingQueueIds: List<Long>? = null
    private var pendingShuffle: Boolean = false
    private var technicalJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlaybackState()
        override fun onPlaybackStateChanged(playbackState: Int) = syncPlaybackState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncPlaybackState(readTechnical = true)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPlaybackState()
        override fun onRepeatModeChanged(repeatMode: Int) = syncPlaybackState()
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = syncPlaybackState()
    }

    init {
        viewModelScope.launch {
            val cached = runCatching { repository.loadCached() }.getOrDefault(emptyList())
            val stale = runCatching { repository.isCacheStale() }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                tracks = cached,
                libraryInitialized = true,
                libraryCacheStale = stale
            )
            syncPlaybackState(readTechnical = true)
        }
        connectController()
        viewModelScope.launch {
            while (true) {
                syncPosition()
                delay(350)
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshOutputStatus()
                delay(2_000)
            }
        }
    }

    private fun connectController() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    _uiState.value = _uiState.value.copy(controllerReady = true)
                    syncPlaybackState(readTechnical = true)
                    pendingTrackId?.let { id ->
                        val allTracks = _uiState.value.tracks
                        val queue = pendingQueueIds
                            ?.mapNotNull { queueId -> allTracks.firstOrNull { it.id == queueId } }
                            ?.takeIf { it.isNotEmpty() }
                            ?: allTracks
                        allTracks.firstOrNull { it.id == id }?.let { track ->
                            playFromQueue(track, queue, pendingShuffle)
                        }
                    }
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    fun scanLibrary() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, libraryError = null)
            runCatching { repository.scan() }
                .onSuccess { tracks ->
                    _uiState.value = _uiState.value.copy(
                        tracks = tracks,
                        isScanning = false,
                        libraryInitialized = true,
                        libraryCacheStale = false,
                        libraryError = null
                    )
                    refreshOutputStatus()
                    syncPlaybackState(readTechnical = true)
                }
                .onFailure { error ->
                    CrashLogStore.nonFatal(getApplication(), "Library scan", error)
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        libraryInitialized = true,
                        libraryError = error.message ?: "Library scan failed"
                    )
                }
        }
    }

    fun play(track: MusicTrack) {
        playFromQueue(track, _uiState.value.tracks.ifEmpty { listOf(track) })
    }

    fun playFromQueue(track: MusicTrack, queue: List<MusicTrack>, shuffle: Boolean = false) {
        val cleanQueue = queue.distinctBy { it.id }.ifEmpty { listOf(track) }
        val mediaController = controller
        if (mediaController == null) {
            pendingTrackId = track.id
            pendingQueueIds = cleanQueue.map { it.id }
            pendingShuffle = shuffle
            return
        }

        pendingTrackId = null
        pendingQueueIds = null
        pendingShuffle = false

        val index = cleanQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = cleanQueue.map(::toMediaItem)

        mediaController.shuffleModeEnabled = shuffle
        mediaController.setMediaItems(items, index, 0L)
        mediaController.prepare()
        mediaController.play()
        syncPlaybackState(readTechnical = true)
    }

    fun playQueue(queue: List<MusicTrack>, shuffle: Boolean = false) {
        val cleanQueue = queue.distinctBy { it.id }
        if (cleanQueue.isEmpty()) return
        val first = if (shuffle) cleanQueue.random() else cleanQueue.first()
        playFromQueue(first, cleanQueue, shuffle)
    }

    fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun seekBy(deltaMs: Long) {
        controller?.let { player ->
            val duration = safeDuration(player.duration)
            val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
            player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeatMode() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun toMediaItem(track: MusicTrack): MediaItem {
        val extras = Bundle().apply {
            track.sampleRateHz?.let { putInt(AudioPathMonitor.EXTRA_SAMPLE_RATE_HZ, it) }
            track.bitDepth?.let { putInt(AudioPathMonitor.EXTRA_BIT_DEPTH, it) }
            track.channelCount?.let { putInt(AudioPathMonitor.EXTRA_CHANNEL_COUNT, it) }
            track.normalizationGainDb?.let { putFloat(AudioPathMonitor.EXTRA_NORMALIZATION_GAIN_DB, it) }
            putString(AudioPathMonitor.EXTRA_FORMAT_LABEL, track.formatLabel)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setExtras(extras)
            .apply {
                track.albumArtist?.let(::setAlbumArtist)
                track.trackNumber?.let(::setTrackNumber)
                track.discNumber?.let(::setDiscNumber)
                track.genre?.let(::setGenre)
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun syncPosition() {
        val player = controller ?: return
        val duration = safeDuration(player.duration)
        _uiState.value = _uiState.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration
        )
    }

    private fun refreshOutputStatus() {
        val status = outputInspector.inspect()
        if (status != _uiState.value.output) {
            _uiState.value = _uiState.value.copy(output = status)
        }
    }

    private fun syncPlaybackState(readTechnical: Boolean = false) {
        val player = controller ?: return
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val track = currentId?.let { id -> _uiState.value.tracks.firstOrNull { it.id == id } }
            ?: _uiState.value.currentTrack?.takeIf { it.id == currentId }

        val changedTrack = track?.id != _uiState.value.currentTrack?.id
        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = safeDuration(player.duration),
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode
        )

        if ((changedTrack || readTechnical) && track != null) {
            technicalJob?.cancel()
            technicalJob = viewModelScope.launch {
                val tech = technicalReader.read(track)
                if (_uiState.value.currentTrack?.id == track.id) {
                    _uiState.value = _uiState.value.copy(technical = tech)
                }
            }
        }
    }

    private fun safeDuration(duration: Long): Long =
        if (duration == C.TIME_UNSET || duration < 0L) 0L else duration

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onCleared()
    }
}
