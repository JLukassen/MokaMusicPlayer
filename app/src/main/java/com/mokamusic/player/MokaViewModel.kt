package com.mokamusic.player

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
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
import com.mokamusic.player.audio.dsp.DspMeterSnapshot
import com.mokamusic.player.audio.dsp.DspPresetId
import com.mokamusic.player.audio.dsp.DspPresets
import com.mokamusic.player.audio.dsp.DspRuntimeMonitor
import com.mokamusic.player.audio.dsp.DspSettingsStore
import com.mokamusic.player.audio.dsp.RouteClass
import com.mokamusic.player.audio.dsp.classifyRoute
import com.mokamusic.player.data.AudioTechnicalMetadata
import com.mokamusic.player.data.AudioTechnicalMetadataReader
import com.mokamusic.player.data.MusicLibraryRepository
import com.mokamusic.player.data.ArtworkLoader
import com.mokamusic.player.metadata.LibraryEnricher
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
    private val repository = MusicLibraryRepository(application)
    private val technicalReader = AudioTechnicalMetadataReader(application)
    private val outputInspector = AudioOutputInspector(application)
    private val dspStore = DspSettingsStore(application)
    private val deviceProfiles = DspDeviceProfileStore(application)
    private val favoritePrefs = application.getSharedPreferences("moka_favorites", android.content.Context.MODE_PRIVATE)
    private val libraryEnricher = LibraryEnricher(application)
    private val onlineMetadataStore = OnlineMetadataStore(application)
    private val loudnessStore = LoudnessAnalysisStore(application)
    private val loudnessAnalyzer = Bs1770LoudnessAnalyzer(application)

    private val _uiState = MutableStateFlow(MokaUiState())
    val uiState: StateFlow<MokaUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingTrackId: Long? = null
    private var pendingQueueIds: List<Long>? = null
    private var pendingShuffle: Boolean = false
    private var technicalJob: Job? = null
    private var lastProfileRoute: RouteClass? = null

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
                val refreshed = runCatching { repository.scan() }.getOrDefault(_uiState.value.tracks)
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
            val refreshed = runCatching { repository.scan() }.getOrDefault(_uiState.value.tracks)
            _uiState.value = _uiState.value.copy(
                tracks = refreshed,
                metadataEnrichmentStatus = "Online metadata cache cleared"
            )
        }
    }

    fun analyzeLoudnessForNormalization() {
        if (_uiState.value.loudnessAnalysisRunning) return
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        controller?.pause()
        viewModelScope.launch {
            val albumGroups = tracks.groupBy { track ->
                val artist = track.albumArtist?.takeIf(String::isNotBlank) ?: track.artist
                com.mokamusic.player.data.UnicodeText.key(artist) + "\u0000" + com.mokamusic.player.data.UnicodeText.key(track.album)
            }
            val targets = albumGroups.values.flatten()
            _uiState.value = _uiState.value.copy(
                loudnessAnalysisRunning = true,
                loudnessAnalysisCompleted = 0,
                loudnessAnalysisTotal = targets.size,
                loudnessAnalysisFailed = 0,
                loudnessAnalysisStatus = "Offline loudness analysis started · playback paused"
            )

            var completed = 0
            var failed = 0
            val analyzed = LinkedHashMap<Long, LoudnessRecord>()

            withContext(Dispatchers.IO) {
                for (track in targets) {
                    val existing = loudnessStore.get(track)
                    if (existing != null) {
                        analyzed[track.id] = existing
                    } else {
                        runCatching { loudnessAnalyzer.analyze(track) }
                            .onSuccess { result ->
                                analyzed[track.id] = LoudnessRecord(
                                    trackId = track.id,
                                    fingerprint = LoudnessAnalysisStore.fingerprint(track),
                                    integratedLufs = result.integratedLufs,
                                    estimatedTruePeakDbtp = result.estimatedTruePeakDbtp,
                                    trackGainDb = result.trackGainDb
                                )
                            }
                            .onFailure {
                                failed++
                                CrashLogStore.nonFatal(getApplication(), "Loudness analysis: ${track.displayName}", it)
                            }
                    }
                    completed++
                    _uiState.value = _uiState.value.copy(
                        loudnessAnalysisCompleted = completed,
                        loudnessAnalysisFailed = failed,
                        loudnessAnalysisStatus = "Analyzing ${track.artist} — ${track.title}"
                    )
                }

                val withAlbumGain = ArrayList<LoudnessRecord>()
                albumGroups.values.forEach { albumTracks ->
                    val usable = albumTracks.mapNotNull { t -> analyzed[t.id]?.let { t to it } }
                    if (usable.isEmpty()) return@forEach
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
                    usable.forEach { (_, record) -> withAlbumGain += record.copy(albumGainDb = albumGain) }
                }
                loudnessStore.putAll(withAlbumGain)
            }

            val refreshed = runCatching { repository.scan() }.getOrDefault(_uiState.value.tracks)
            _uiState.value = _uiState.value.copy(
                tracks = refreshed,
                loudnessAnalysisRunning = false,
                loudnessAnalysisCompleted = completed,
                loudnessAnalysisTotal = targets.size,
                loudnessAnalysisFailed = failed,
                loudnessAnalysisStatus = "Analyzed ${completed - failed}/${targets.size} tracks · $failed failures"
            )
        }
    }

    fun clearLoudnessAnalysis() {
        loudnessStore.clear()
        viewModelScope.launch {
            val refreshed = runCatching { repository.scan() }.getOrDefault(_uiState.value.tracks)
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
        if (deviceProfiles.enabled && route != lastProfileRoute) {
            lastProfileRoute = route
            val preset = deviceProfiles.get(route)
            if (preset != DspPresetId.KEEP) {
                dspStore.save(DspPresets.apply(preset, dspStore.load()))
            }
        } else if (lastProfileRoute == null) {
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
        val track = currentId?.let { id -> _uiState.value.tracks.firstOrNull { it.id == id } }
            ?: _uiState.value.currentTrack?.takeIf { it.id == currentId }

        val changedTrack = track?.id != _uiState.value.currentTrack?.id
        val trackById = _uiState.value.tracks.associateBy { it.id }
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
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onCleared()
    }
}
