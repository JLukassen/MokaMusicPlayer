package com.mokamusic.player

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mokamusic.player.audio.AudioOutputInspector
import com.mokamusic.player.audio.AudioOutputStatus
import com.mokamusic.player.audio.AudioPathMonitor
import com.mokamusic.player.audio.Bs1770LoudnessAnalyzer
import com.mokamusic.player.audio.LoudnessAnalysisStore
import com.mokamusic.player.audio.LoudnessRecord
import com.mokamusic.player.audio.dsp.DspDeviceProfileStore
import com.mokamusic.player.audio.dsp.HeadphoneProfileStore
import com.mokamusic.player.audio.dsp.DspMeterSnapshot
import com.mokamusic.player.audio.dsp.DspPresetId
import com.mokamusic.player.audio.dsp.DspPresets
import com.mokamusic.player.audio.dsp.DspRuntimeMonitor
import com.mokamusic.player.audio.dsp.DspSettingsStore
import com.mokamusic.player.audio.dsp.RouteClass
import com.mokamusic.player.audio.dsp.classifyRoute
import com.mokamusic.player.data.AudioTechnicalMetadata
import com.mokamusic.player.data.AudioTechnicalMetadataReader
import com.mokamusic.player.data.LibraryScanProgress
import com.mokamusic.player.data.MusicLibraryRepository
import com.mokamusic.player.network.NetworkSong
import com.mokamusic.player.data.ArtworkLoader
import com.mokamusic.player.metadata.LibraryEnricher
import com.mokamusic.player.metadata.MetadataNamePreference
import com.mokamusic.player.metadata.MetadataNamePreferenceStore
import com.mokamusic.player.metadata.OnlineMetadataStore
import com.mokamusic.player.model.MusicTrack
import com.mokamusic.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

data class MokaUiState(
    val tracks: List<MusicTrack> = emptyList(),
    val isScanning: Boolean = false,
    val libraryInitialized: Boolean = false,
    val libraryCacheStale: Boolean = false,
    val libraryError: String? = null,
    val libraryScanCompleted: Int = 0,
    val libraryScanTotal: Int = 0,
    val libraryScanParsed: Int = 0,
    val libraryScanReused: Int = 0,
    val libraryScanStatus: String? = null,
    val metadataNamePreference: MetadataNamePreference = MetadataNamePreference.FILE_TAGS,
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
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<MusicTrack> = emptyList(),
    val currentQueueIndex: Int = -1,
    val favoriteIds: Set<Long> = emptySet(),
    val dspMeters: DspMeterSnapshot = DspMeterSnapshot(),
    val activeRouteProfile: RouteClass? = null,
    val metadataEnrichmentRunning: Boolean = false,
    val metadataEnrichmentCompleted: Int = 0,
    val metadataEnrichmentTotal: Int = 0,
    val metadataEnrichmentMatched: Int = 0,
    val metadataEnrichmentFailed: Int = 0,
    val metadataEnrichmentStatus: String? = null,
    val loudnessAnalysisRunning: Boolean = false,
    val loudnessAnalysisCompleted: Int = 0,
    val loudnessAnalysisTotal: Int = 0,
    val loudnessAnalysisFailed: Int = 0,
    val loudnessAnalysisStatus: String? = null
)

class MokaViewModel(application: Application) : AndroidViewModel(application) {
    // Connection work is tied to the ViewModel, not to the Composable Network tab.
    // Navigating away cannot cancel an in-flight request or discard the session.
    internal val networkLibrary = NetworkLibraryState(application, viewModelScope)
    private val repository = MusicLibraryRepository(application)
    private val technicalReader = AudioTechnicalMetadataReader(application)
    private val outputInspector = AudioOutputInspector(application)
    private val dspStore = DspSettingsStore(application)
    private val deviceProfiles = DspDeviceProfileStore(application)
    private val headphoneProfiles = HeadphoneProfileStore(application)
    private val favoritePrefs = application.getSharedPreferences("moka_favorites", android.content.Context.MODE_PRIVATE)
    private val libraryEnricher = LibraryEnricher(application)
    private val onlineMetadataStore = OnlineMetadataStore(application)
    private val metadataNamePreferenceStore = MetadataNamePreferenceStore(application)
    private val loudnessStore = LoudnessAnalysisStore(application)
    private val loudnessFailureStore = com.mokamusic.player.audio.LoudnessFailureStore(application)
    private val loudnessAnalyzer = Bs1770LoudnessAnalyzer(application)
    private val loudnessSessionPrefs = application.getSharedPreferences("moka_loudness_session", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MokaUiState())
    val uiState: StateFlow<MokaUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingTrackId: Long? = null
    private var pendingQueueIds: List<Long>? = null
    private var pendingShuffle: Boolean = false
    private var technicalJob: Job? = null
    private var mediaStoreRefreshJob: Job? = null
    private var lastProfileRoute: RouteClass? = null
    private var lastHeadphoneIdentity: String? = null
    private var networkCurrentTrack: MusicTrack? = null
    // Retain every streamed track in Media3's remote queue for next/previous and UI metadata.
    private val networkQueueTracks = mutableMapOf<Long, MusicTrack>()

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { scheduleAutomaticLibraryRefresh() }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlaybackState()
        override fun onPlaybackStateChanged(playbackState: Int) = syncPlaybackState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncPlaybackState(readTechnical = true)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPlaybackState()
        override fun onRepeatModeChanged(repeatMode: Int) = syncPlaybackState()
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = syncPlaybackState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = syncPlaybackState()
    }

    init {
        _uiState.value = _uiState.value.copy(metadataNamePreference = metadataNamePreferenceStore.load())
        runCatching {
            application.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        }
        viewModelScope.launch {
            val cached = runCatching { repository.loadCached() }.getOrDefault(emptyList())
            val stale = runCatching { repository.isCacheStale() }.getOrDefault(false)
            val favorites = favoritePrefs.getStringSet("ids", emptySet())
                .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            _uiState.value = _uiState.value.copy(
                tracks = cached,
                libraryInitialized = true,
                libraryCacheStale = stale,
                favoriteIds = favorites
            )
            syncPlaybackState(readTechnical = true)

            if (cached.isNotEmpty() && loudnessSessionPrefs.getBoolean(LOUDNESS_SESSION_ACTIVE, false)) {
                android.util.Log.i(LOUDNESS_LOG_TAG, "restoring interrupted loudness-analysis session")
                analyzeLoudnessForNormalization(resumeInterrupted = true)
            }
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
                        val allTracks = _uiState.value.tracks + networkQueueTracks.values
                        val byId = allTracks.associateBy { it.id }
                        val queue = pendingQueueIds
                            ?.mapNotNull(byId::get)
                            ?.takeIf { it.isNotEmpty() }
                            ?: allTracks
                        byId[id]?.let { track ->
                            playFromQueue(track, queue, pendingShuffle)
                        }
                    }
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    fun scanLibrary() = requestLibraryScan(fullRescan = false, automatic = false)

    fun fullRescanLibrary() = requestLibraryScan(fullRescan = true, automatic = false)

    fun setMetadataNamePreference(preference: MetadataNamePreference) {
        if (_uiState.value.metadataNamePreference == preference) return
        metadataNamePreferenceStore.save(preference)
        _uiState.value = _uiState.value.copy(metadataNamePreference = preference)
        requestLibraryScan(fullRescan = false, automatic = false)
    }

    private fun scheduleAutomaticLibraryRefresh() {
        mediaStoreRefreshJob?.cancel()
        mediaStoreRefreshJob = viewModelScope.launch {
            delay(2_500)
            if (!_uiState.value.libraryInitialized) return@launch
            if (_uiState.value.isScanning) delay(3_000)
            if (!_uiState.value.isScanning) requestLibraryScan(fullRescan = false, automatic = true)
        }
    }

    private fun requestLibraryScan(fullRescan: Boolean, automatic: Boolean) {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isScanning = true, libraryError = null,
                libraryScanCompleted = 0, libraryScanTotal = 0,
                libraryScanParsed = 0, libraryScanReused = 0,
                libraryScanStatus = when {
                    fullRescan -> "Full metadata rescan…"
                    automatic -> "Library change detected…"
                    else -> "Checking library…"
                }
            )
            runCatching {
                repository.scan(fullRescan = fullRescan) { progress: LibraryScanProgress ->
                    _uiState.value = _uiState.value.copy(
                        libraryScanCompleted = progress.completed,
                        libraryScanTotal = progress.total,
                        libraryScanParsed = progress.parsed,
                        libraryScanReused = progress.reused,
                        libraryScanStatus = progress.current?.let { "Checking ${it.take(70)}" }
                    )
                }
            }.onSuccess { tracks ->
                val parsed = _uiState.value.libraryScanParsed
                val reused = _uiState.value.libraryScanReused
                _uiState.value = _uiState.value.copy(
                    tracks = tracks, isScanning = false, libraryInitialized = true,
                    libraryCacheStale = false, libraryError = null,
                    libraryScanStatus = when {
                        fullRescan -> "Full rescan complete · $parsed parsed"
                        automatic && parsed > 0 -> "Library updated automatically · $parsed new/changed"
                        automatic -> "Library already up to date"
                        else -> "Library refreshed · $parsed new/changed · $reused reused"
                    }
                )
                refreshOutputStatus()
                syncPlaybackState(readTechnical = true)
            }.onFailure { error ->
                CrashLogStore.nonFatal(getApplication(), "Library scan", error)
                _uiState.value = _uiState.value.copy(
                    isScanning = false, libraryInitialized = true,
                    libraryError = error.message ?: "Library scan failed", libraryScanStatus = null
                )
            }
        }
    }

    private fun networkMusicTrack(song: NetworkSong, url: Uri): MusicTrack {
        // Keep remote IDs in the negative range to avoid colliding with MediaStore IDs.
        val remoteId = -(song.id.hashCode().toLong() and 0x7fffffffL) - 1L
        return MusicTrack(
            id = remoteId, uri = url, displayName = song.title,
            title = song.title, artist = song.artist, album = song.album,
            albumId = -1L, durationMs = song.durationSeconds.coerceAtLeast(0) * 1000L,
            mimeType = song.mimeType, sizeBytes = 0L, relativePath = null
        )
    }

    fun playNetworkTrack(song: NetworkSong, url: Uri) {
        val track = networkMusicTrack(song, url)
        networkQueueTracks.clear()
        networkQueueTracks[track.id] = track
        networkCurrentTrack = track
        playFromQueue(track, listOf(track))
    }

    /** A complete Media3 queue allows automatic next track, previous, and shuffle. */
    fun playNetworkQueue(
        selectedSong: NetworkSong, songs: List<NetworkSong>, shuffle: Boolean = false
    ) {
        val client = networkLibrary.client ?: return
        val entries = com.mokamusic.player.network.NetworkQueuePlan.build(selectedSong, songs)
        if (entries.isEmpty()) return
        val queue = entries.map { networkMusicTrack(it, client.streamUri(it.id)) }
            .distinctBy { it.id }
        val selectedId = -(selectedSong.id.hashCode().toLong() and 0x7fffffffL) - 1L
        val selected = queue.firstOrNull { it.id == selectedId } ?: return
        networkQueueTracks.clear()
        queue.forEach { networkQueueTracks[it.id] = it }
        networkCurrentTrack = selected
        playFromQueue(selected, queue, shuffle)
    }

    /** One Media3 playlist can alternate local files and Navidrome streams.
     * Fetch a bounded random sample from the server rather than walking every album.
     * Local files stay local; network URLs are generated only for selected songs.
     */
    fun shuffleDeviceAndNavidrome() {
        val library = networkLibrary
        val client = library.client ?: run {
            library.mixedShuffleStatus = "Connect Navidrome in Settings first."
            return
        }
        val localTracks = _uiState.value.tracks
        if (localTracks.isEmpty()) {
            library.mixedShuffleStatus = "No device tracks loaded. Scan your device library first."
            return
        }
        if (library.mixedShuffleBusy) return
        viewModelScope.launch {
            library.mixedShuffleBusy = true
            library.mixedShuffleStatus = "Choosing music from Navidrome…"
            try {
                // Balanced when possible; Subsonic caps random songs at 500.
                val remoteSongs = withContext(Dispatchers.IO) {
                    client.randomSongs(localTracks.size.coerceIn(1, 500))
                }
                if (library.client !== client) {
                    library.mixedShuffleStatus = "Navidrome account changed. Try again."
                    return@launch
                }
                if (remoteSongs.isEmpty()) {
                    library.mixedShuffleStatus = "Navidrome returned no songs; mixed shuffle wasn't started."
                    return@launch
                }
                val remoteTracks = remoteSongs.map { networkMusicTrack(it, client.streamUri(it.id)) }
                    .distinctBy { it.id }
                val mixedQueue = (localTracks + remoteTracks).distinctBy { it.id }
                networkQueueTracks.clear()
                remoteTracks.forEach { networkQueueTracks[it.id] = it }
                networkCurrentTrack = remoteTracks.firstOrNull()
                playQueue(mixedQueue, shuffle = true)
                library.mixedShuffleStatus =
                    "Shuffling ${localTracks.size} device + ${remoteTracks.size} Navidrome tracks."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Exception text might contain a signed Subsonic URL. Never display or log it.
                library.mixedShuffleStatus =
                    "Couldn't load Navidrome songs. Check Tailscale or your server connection."
            } finally {
                library.mixedShuffleBusy = false
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

        // Tapping another song within the same library/album should not replace the entire
        // Media3 queue. Replacing it tears down the direct PCM/DSP path and loses a warm
        // next-track source even when the user only wants to seek to another queue item.
        val existingQueueMatches = mediaController.mediaItemCount == cleanQueue.size &&
            cleanQueue.indices.all { i ->
                val current = mediaController.getMediaItemAt(i)
                current.mediaId == cleanQueue[i].id.toString() &&
                    current.localConfiguration?.uri == cleanQueue[i].uri
            }
        mediaController.shuffleModeEnabled = shuffle
        if (existingQueueMatches) {
            mediaController.seekTo(index, 0L)
            if (mediaController.playbackState == Player.STATE_IDLE) mediaController.prepare()
        } else {
            mediaController.setMediaItems(cleanQueue.map(::toMediaItem), index, 0L)
            mediaController.prepare()
        }
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

    fun playNext(track: MusicTrack) {
        val player = controller ?: return
        val insertAt = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(insertAt, toMediaItem(track))
        syncPlaybackState()
    }

    fun addToQueue(track: MusicTrack) {
        val player = controller ?: return
        player.addMediaItem(toMediaItem(track))
        syncPlaybackState()
    }

    fun playQueueIndex(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        player.play()
        syncPlaybackState(readTechnical = true)
    }

    fun removeQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
        syncPlaybackState()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
        if (fromIndex == toIndex) return
        player.moveMediaItem(fromIndex, toIndex)
        syncPlaybackState()
    }

    fun clearQueueAfterCurrent() {
        val player = controller ?: return
        val current = player.currentMediaItemIndex
        if (current < 0) return
        while (player.mediaItemCount > current + 1) player.removeMediaItem(current + 1)
        syncPlaybackState()
    }

    fun playSavedQueue(trackIds: List<Long>) {
        val byId = _uiState.value.tracks.associateBy { it.id }
        val tracks = trackIds.mapNotNull(byId::get)
        if (tracks.isNotEmpty()) playQueue(tracks, false)
    }

    fun toggleFavorite(track: MusicTrack) {
        val next = _uiState.value.favoriteIds.toMutableSet().apply {
            if (!add(track.id)) remove(track.id)
        }.toSet()
        favoritePrefs.edit().putStringSet("ids", next.map(Long::toString).toSet()).apply()
        _uiState.value = _uiState.value.copy(favoriteIds = next)
    }

    fun applyDspPreset(preset: DspPresetId) {
        val current = dspStore.load()
        dspStore.save(DspPresets.apply(preset, current))
    }

    fun enrichOnlineMetadata(forceRefresh: Boolean = false) {
        if (_uiState.value.metadataEnrichmentRunning) return
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                metadataEnrichmentRunning = true,
                metadataEnrichmentCompleted = 0,
                metadataEnrichmentTotal = 0,
                metadataEnrichmentMatched = 0,
                metadataEnrichmentFailed = 0,
                metadataEnrichmentStatus = "Preparing MusicBrainz album matching…"
            )
            runCatching {
                libraryEnricher.enrichAlbums(tracks, forceRefresh) { progress ->
                    _uiState.value = _uiState.value.copy(
                        metadataEnrichmentCompleted = progress.completed,
                        metadataEnrichmentTotal = progress.total,
                        metadataEnrichmentMatched = progress.matched,
                        metadataEnrichmentFailed = progress.failed,
                        metadataEnrichmentStatus = progress.current?.let { "Matching $it" }
                    )
                }
            }.onSuccess { final ->
                ArtworkLoader.clearMemoryCache()
                val refreshed = runCatching { repository.scan(fullRescan = false) }.getOrDefault(_uiState.value.tracks)
                _uiState.value = _uiState.value.copy(
                    tracks = refreshed,
                    metadataEnrichmentRunning = false,
                    metadataEnrichmentCompleted = final.completed,
                    metadataEnrichmentTotal = final.total,
                    metadataEnrichmentMatched = final.matched,
                    metadataEnrichmentFailed = final.failed,
                    metadataEnrichmentStatus = "Matched ${final.matched}/${final.total} albums · ${final.failed} request errors"
                )
            }.onFailure { error ->
                CrashLogStore.nonFatal(getApplication(), "MusicBrainz enrichment", error)
                _uiState.value = _uiState.value.copy(
                    metadataEnrichmentRunning = false,
                    metadataEnrichmentStatus = "Enrichment stopped: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    fun clearOnlineMetadata() {
        onlineMetadataStore.clear()
        ArtworkLoader.clearMemoryCache()
        viewModelScope.launch {
            val refreshed = runCatching { repository.scan(fullRescan = false) }.getOrDefault(_uiState.value.tracks)
            _uiState.value = _uiState.value.copy(
                tracks = refreshed,
                metadataEnrichmentStatus = "Online metadata cache cleared"
            )
        }
    }

    fun analyzeLoudnessForNormalization() = analyzeLoudnessForNormalization(resumeInterrupted = false)

    private fun analyzeLoudnessForNormalization(resumeInterrupted: Boolean) {
        if (_uiState.value.loudnessAnalysisRunning) return
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        controller?.pause()

        viewModelScope.launch {
            val albumGroups = tracks.groupBy { track ->
                val artist = track.albumArtist?.takeIf(String::isNotBlank) ?: track.artist
                com.mokamusic.player.data.UnicodeText.key(artist) + "\u0000" +
                    com.mokamusic.player.data.UnicodeText.key(track.album)
            }

            // Skip an album only when every track already has trusted embedded Track + Album gain.
            // If even one album gain is missing, analyze the whole album so the offline album gain
            // is based on the complete album rather than a partial subset.
            val targetAlbums = albumGroups.values.filter { albumTracks ->
                albumTracks.any {
                    it.sourceNormalizationGainDb == null ||
                        it.sourceAlbumNormalizationGainDb == null
                }
            }
            val targets = targetAlbums.flatten()
            val trustedTagSkipped = tracks.size - targets.size

            if (targets.isEmpty()) {
                loudnessSessionPrefs.edit().putBoolean(LOUDNESS_SESSION_ACTIVE, false).apply()
                _uiState.value = _uiState.value.copy(
                    loudnessAnalysisRunning = false,
                    loudnessAnalysisCompleted = 0,
                    loudnessAnalysisTotal = 0,
                    loudnessAnalysisFailed = 0,
                    loudnessAnalysisStatus = "All ${tracks.size} tracks already have trusted ReplayGain/R128 Track + Album gain"
                )
                return@launch
            }

            val analyzed = LinkedHashMap<Long, LoudnessRecord>()
            targets.forEach { track ->
                loudnessStore.get(track)?.let { analyzed[track.id] = it }
            }

            val previouslyFailed = targets.count { track ->
                !analyzed.containsKey(track.id) && loudnessFailureStore.skipped(track) != null
            }
            var completed = analyzed.size + previouslyFailed
            var failed = previouslyFailed
            var reused = analyzed.size
            var newlyAnalyzed = 0
            val checkpoint = ArrayList<LoudnessRecord>(LOUDNESS_CHECKPOINT_TRACKS)

            loudnessSessionPrefs.edit()
                .putBoolean(LOUDNESS_SESSION_ACTIVE, true)
                .putInt(LOUDNESS_SESSION_COMPLETED, completed)
                .putInt(LOUDNESS_SESSION_TOTAL, targets.size)
                .apply()

            _uiState.value = _uiState.value.copy(
                loudnessAnalysisRunning = true,
                loudnessAnalysisCompleted = completed,
                loudnessAnalysisTotal = targets.size,
                loudnessAnalysisFailed = 0,
                loudnessAnalysisStatus = if (resumeInterrupted && completed > 0) {
                    "Resumed loudness analysis · $completed/${targets.size} already saved · $trustedTagSkipped fully tagged skipped"
                } else {
                    "Offline loudness analysis started · $completed/${targets.size} cached · $trustedTagSkipped fully tagged skipped"
                }
            )

            withContext(Dispatchers.IO) {
                for (track in targets) {
                    if (analyzed.containsKey(track.id)) continue
                    if (loudnessFailureStore.skipped(track) != null) continue
                    var timingText = "analyzing"

                    runCatching { loudnessAnalyzer.analyze(track) }
                            .onSuccess { result ->
                                val record = LoudnessRecord(
                                    trackId = track.id,
                                    fingerprint = LoudnessAnalysisStore.fingerprint(track),
                                    integratedLufs = result.integratedLufs,
                                    estimatedTruePeakDbtp = result.estimatedTruePeakDbtp,
                                    trackGainDb = result.trackGainDb
                                )
                                analyzed[track.id] = record
                                runCatching { loudnessFailureStore.remove(track) }
                                checkpoint += record
                                newlyAnalyzed++
                                val speed = if (result.analysisTimeMs > 0 && track.durationMs > 0) {
                                    track.durationMs.toDouble() / result.analysisTimeMs.toDouble()
                                } else 0.0
                                timingText = "${result.decoderPath} · ${result.analysisTimeMs} ms · ${"%.1f".format(java.util.Locale.US, speed)}× realtime"
                                android.util.Log.i(
                                    LOUDNESS_LOG_TAG,
                                    "track=$completed/${targets.size} name=${track.displayName} " +
                                        "durationMs=${track.durationMs} analysisMs=${result.analysisTimeMs} " +
                                        "speed=${"%.2f".format(java.util.Locale.US, speed)}x path=${result.decoderPath}"
                                )
                            }
                            .onFailure { error ->
                                failed++
                                val failedRecord = runCatching {
                                    loudnessFailureStore.recordFailure(track, error)
                                }.onFailure { ioError ->
                                    android.util.Log.e(LOUDNESS_LOG_TAG, "Failed to checkpoint analysis error", ioError)
                                }.getOrNull()
                                timingText = "failed · ${failedRecord?.reasonCode ?: error.javaClass.simpleName}"
                                CrashLogStore.nonFatal(getApplication(), "Loudness analysis: ${track.displayName}", error)
                                android.util.Log.w(LOUDNESS_LOG_TAG, "analysis failed name=${track.displayName}", error)
                            }

                    completed++

                    if (checkpoint.size >= LOUDNESS_CHECKPOINT_TRACKS) {
                        loudnessStore.putAll(checkpoint)
                        android.util.Log.i(
                            LOUDNESS_LOG_TAG,
                            "checkpoint saved records=${checkpoint.size} completed=$completed/${targets.size}"
                        )
                        checkpoint.clear()
                    }

                    loudnessSessionPrefs.edit()
                        .putBoolean(LOUDNESS_SESSION_ACTIVE, true)
                        .putInt(LOUDNESS_SESSION_COMPLETED, completed)
                        .putInt(LOUDNESS_SESSION_TOTAL, targets.size)
                        .apply()

                    _uiState.value = _uiState.value.copy(
                        loudnessAnalysisCompleted = completed,
                        loudnessAnalysisFailed = failed,
                        loudnessAnalysisStatus =
                            "Analyzing ${track.artist} — ${track.title} · $timingText · " +
                                "$newlyAnalyzed new · $reused cached"
                    )
                }

                if (checkpoint.isNotEmpty()) {
                    loudnessStore.putAll(checkpoint)
                    android.util.Log.i(
                        LOUDNESS_LOG_TAG,
                        "checkpoint saved records=${checkpoint.size} completed=$completed/${targets.size}"
                    )
                    checkpoint.clear()
                }

                val withAlbumGain = ArrayList<LoudnessRecord>()
                targetAlbums.forEach { albumTracks ->
                    val usable = albumTracks.mapNotNull { t -> analyzed[t.id]?.let { t to it } }
                    if (usable.size != albumTracks.size) return@forEach

                    var weightedEnergy = 0.0
                    var weight = 0.0
                    usable.forEach { (track, record) ->
                        val durationWeight = track.durationMs.coerceAtLeast(1L).toDouble()
                        val energy = 10.0.pow((record.integratedLufs + 0.691) / 10.0)
                        weightedEnergy += energy * durationWeight
                        weight += durationWeight
                    }
                    val albumLufs = if (weight > 0.0 && weightedEnergy > 0.0) {
                        (-0.691 + 10.0 * log10(weightedEnergy / weight)).toFloat()
                    } else null
                    val albumGain = albumLufs?.let { (-18f - it).coerceIn(-30f, 20f) }
                    usable.forEach { (_, record) ->
                        withAlbumGain += record.copy(albumGainDb = albumGain)
                    }
                }
                if (withAlbumGain.isNotEmpty()) loudnessStore.putAll(withAlbumGain)
            }

            val refreshed = runCatching {
                repository.scan(fullRescan = false)
            }.getOrDefault(_uiState.value.tracks)

            loudnessSessionPrefs.edit()
                .putBoolean(LOUDNESS_SESSION_ACTIVE, false)
                .putInt(LOUDNESS_SESSION_COMPLETED, completed)
                .putInt(LOUDNESS_SESSION_TOTAL, targets.size)
                .apply()

            _uiState.value = _uiState.value.copy(
                tracks = refreshed,
                loudnessAnalysisRunning = false,
                loudnessAnalysisCompleted = completed,
                loudnessAnalysisTotal = targets.size,
                loudnessAnalysisFailed = failed,
                loudnessAnalysisStatus =
                    "Loudness analysis complete · $newlyAnalyzed new · $reused cached · " +
                        "$trustedTagSkipped fully tagged skipped · $failed failures"
            )
        }
    }

    fun clearLoudnessAnalysis() {
        loudnessStore.clear()
        loudnessFailureStore.clear()
        loudnessSessionPrefs.edit().clear().apply()
        viewModelScope.launch {
            val refreshed = runCatching { repository.scan(fullRescan = false) }.getOrDefault(_uiState.value.tracks)
            _uiState.value = _uiState.value.copy(
                tracks = refreshed,
                loudnessAnalysisStatus = "Offline loudness cache cleared"
            )
        }
    }

    fun buildDiagnostics(): String {
        val app = getApplication<Application>()
        val state = _uiState.value
        val settings = dspStore.load()
        val meters = DspRuntimeMonitor.snapshot()
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("Moka Music Player diagnostics")
            appendLine("Version: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Route: ${state.output.routeLabel} / ${state.output.deviceName}")
            appendLine("Engine: ${state.output.directEngineLabel ?: "Media3"}")
            appendLine("DSP active: ${state.output.dspActive}")
            appendLine("Source bit-perfect verified: ${state.output.sourceBitPerfectVerified}")
            appendLine("USB transport verified: ${state.output.usbTransportBitPerfectVerified}")
            state.currentTrack?.let { t ->
                appendLine("Track: ${t.artist} — ${t.title}")
                appendLine("Album: ${t.album}")
                appendLine("Format: ${t.formatLabel} ${t.bitDepth ?: "?"}-bit ${t.sampleRateHz ?: "?"} Hz")
                appendLine("Track normalization gain: ${t.normalizationGainDb?.let { "%.2f dB".format(it) } ?: "none"}")
                appendLine("Album normalization gain: ${t.albumNormalizationGainDb?.let { "%.2f dB".format(it) } ?: "none"}")
                t.musicBrainzReleaseGroupId?.let { appendLine("MusicBrainz release-group: $it") }
                loudnessStore.get(t)?.let { r ->
                    appendLine("Offline integrated loudness: ${"%.2f".format(r.integratedLufs)} LUFS")
                    appendLine("Offline true-peak estimate: ${"%.2f".format(r.estimatedTruePeakDbtp)} dBTP est.")
                }
            }
            appendLine()
            appendLine("DSP settings")
            appendLine("Master: ${settings.masterEnabled}")
            appendLine("Auto headroom: ${settings.autoHeadroomEnabled}")
            appendLine("Normalization: ${settings.normalizationEnabled} / ${settings.normalizationMode.label}")
            appendLine("EQ: ${settings.eqEnabled} / ${settings.eqMode.label}")
            appendLine("DDC: ${settings.ddcEnabled} / ${settings.ddcName ?: "none"}")
            appendLine("Convolver: ${settings.convolverEnabled} / ${settings.convolverName ?: "none"}")
            appendLine("Limiter: ${settings.limiterEnabled} threshold=${settings.limiterThresholdDb} dB release=${settings.limiterReleaseMs} ms")
            appendLine()
            appendLine("DSP telemetry")
            appendLine("Engine: ${meters.engine}")
            appendLine("Automatic headroom: ${meters.automaticHeadroomDb} dB")
            appendLine("Input peak: ${meters.inputPeakDbfs} dBFS")
            appendLine("Output peak: ${meters.outputPeakDbfs} dBFS")
            appendLine("Inter-sample peak estimate: ${meters.intersamplePeakDbfs} dBFS")
            appendLine("Near/full-scale samples: ${meters.clippedSamples}")
            appendLine("DSP throughput: ${meters.throughputX?.let { "%.2fx".format(it) } ?: "n/a"}")
            appendLine("AudioTrack underruns: ${meters.audioTrackUnderruns}")
            appendLine("DSP queue: ${meters.dspQueueDepth?.let { depth -> "$depth/${meters.dspQueueCapacity ?: "?"}" } ?: "n/a"}")
            appendLine("DSP primed frames: ${meters.primedFrames ?: 0}")
            appendLine("DSP in-place reloads: ${meters.hotReloadCount}")
            appendLine()
            appendLine("Library enrichment")
            appendLine("MusicBrainz album matches cached: ${onlineMetadataStore.count()}")
            appendLine("Offline loudness records cached: ${loudnessStore.count()}")
            appendLine("Offline loudness failures cached: ${loudnessFailureStore.count()}")
            CrashLogStore.lastError(app)?.let {
                appendLine()
                appendLine("Last non-fatal error")
                appendLine(it)
            }
            CrashLogStore.lastCrash(app)?.let {
                appendLine()
                appendLine("Last crash")
                appendLine(it)
            }
        }
    }

    private fun toMediaItem(track: MusicTrack): MediaItem {
        val extras = Bundle().apply {
            track.sampleRateHz?.let { putInt(AudioPathMonitor.EXTRA_SAMPLE_RATE_HZ, it) }
            track.bitDepth?.let { putInt(AudioPathMonitor.EXTRA_BIT_DEPTH, it) }
            track.channelCount?.let { putInt(AudioPathMonitor.EXTRA_CHANNEL_COUNT, it) }
            track.normalizationGainDb?.let { putFloat(AudioPathMonitor.EXTRA_NORMALIZATION_GAIN_DB, it) }
            track.albumNormalizationGainDb?.let { putFloat(AudioPathMonitor.EXTRA_ALBUM_NORMALIZATION_GAIN_DB, it) }
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
        val route = classifyRoute(status.routeLabel)
        val headphoneIdentity = HeadphoneProfileStore.identity(status.routeLabel, status.deviceName)
        // Named output profiles win over broad route presets only when the user opted in.
        // Never guess a headset from a generic "Bluetooth headphones" label.
        val selected = headphoneIdentity?.takeIf { headphoneProfiles.enabled && headphoneProfiles.has(it) }
        if (selected != null && selected != lastHeadphoneIdentity) {
            lastHeadphoneIdentity = selected
            lastProfileRoute = route
            headphoneProfiles.get(selected)?.let { dspStore.save(it) }
        } else if (selected == null) {
            lastHeadphoneIdentity = null
            if (deviceProfiles.enabled && route != lastProfileRoute) {
                val preset = deviceProfiles.get(route)
                if (preset != DspPresetId.KEEP) {
                    dspStore.save(DspPresets.apply(preset, dspStore.load()))
                }
            }
            lastProfileRoute = route
        }
        val meters = DspRuntimeMonitor.snapshot()
        if (status != _uiState.value.output || meters != _uiState.value.dspMeters || route != _uiState.value.activeRouteProfile) {
            _uiState.value = _uiState.value.copy(
                output = status,
                dspMeters = meters,
                activeRouteProfile = route
            )
        }
    }

    private fun syncPlaybackState(readTechnical: Boolean = false) {
        val player = controller ?: return
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val track = currentId?.let { id ->
            _uiState.value.tracks.firstOrNull { it.id == id }
                ?: networkQueueTracks[id]
                ?: networkCurrentTrack?.takeIf { it.id == id }
        } ?: _uiState.value.currentTrack?.takeIf { it.id == currentId }

        val changedTrack = track?.id != _uiState.value.currentTrack?.id
        val trackById = _uiState.value.tracks.associateBy { it.id } +
            networkQueueTracks + listOfNotNull(networkCurrentTrack).associateBy { it.id }
        val queue = buildList {
            for (i in 0 until player.mediaItemCount) {
                val id = player.getMediaItemAt(i).mediaId.toLongOrNull() ?: continue
                val queuedTrack = trackById[id] ?: continue
                add(queuedTrack)
            }
        }
        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = safeDuration(player.duration),
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = queue,
            currentQueueIndex = player.currentMediaItemIndex,
            dspMeters = DspRuntimeMonitor.snapshot()
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
        mediaStoreRefreshJob?.cancel()
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(mediaStoreObserver) }
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onCleared()
    }
    private companion object {
        const val LOUDNESS_LOG_TAG = "MokaLoudness"
        const val LOUDNESS_CHECKPOINT_TRACKS = 1
        const val LOUDNESS_SESSION_ACTIVE = "active"
        const val LOUDNESS_SESSION_COMPLETED = "completed"
        const val LOUDNESS_SESSION_TOTAL = "total"
    }

}
