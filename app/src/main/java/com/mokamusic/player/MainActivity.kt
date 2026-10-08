package com.mokamusic.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mokamusic.player.audio.dsp.AutoEqCatalog
import com.mokamusic.player.audio.dsp.AutoEqMatch
import com.mokamusic.player.audio.dsp.UserEqCurveStore
import com.mokamusic.player.audio.dsp.BuiltInEqPresets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.mokamusic.player.data.ArtworkLoader
import com.mokamusic.player.data.SavedQueueStore
import com.mokamusic.player.data.UnicodeText
import com.mokamusic.player.audio.UsbDspSafetyPolicy
import com.mokamusic.player.audio.AudioOutputStatus
import com.mokamusic.player.audio.dsp.DspSettings
import com.mokamusic.player.audio.dsp.DspDeviceProfileStore
import com.mokamusic.player.audio.dsp.HeadphoneProfileStore
import com.mokamusic.player.audio.dsp.DspPresetId
import com.mokamusic.player.audio.dsp.DspPresets
import com.mokamusic.player.audio.dsp.RouteClass
import com.mokamusic.player.audio.dsp.DspSettingsStore
import com.mokamusic.player.audio.dsp.EqInterpolator
import com.mokamusic.player.audio.dsp.EqMode
import com.mokamusic.player.audio.dsp.NormalizationMode
import com.mokamusic.player.metadata.MetadataNamePreference
import com.mokamusic.player.model.MusicTrack
import com.mokamusic.player.ui.theme.MokaTheme

class MainActivity : ComponentActivity() {
    private var openNowPlayingRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        enableEdgeToEdge()
        setContent {
            MokaTheme {
                val vm: MokaViewModel = viewModel()
                MokaApp(vm, openNowPlayingRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            openNowPlayingRequest++
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.mokamusic.player.OPEN_NOW_PLAYING"
    }
}

@Composable
private fun MokaApp(viewModel: MokaViewModel, openNowPlayingRequest: Int) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    val uiPrefs = remember { context.getSharedPreferences("moka_ui", Context.MODE_PRIVATE) }
    var onboardingSeen by rememberSaveable { mutableStateOf(uiPrefs.getBoolean("onboarding_seen", false)) }

    LaunchedEffect(openNowPlayingRequest) {
        if (openNowPlayingRequest > 0) page = 0
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.scanLibrary()
    }

    LaunchedEffect(hasPermission, state.libraryInitialized, state.libraryCacheStale) {
        if (hasPermission && state.libraryInitialized && (state.tracks.isEmpty() || state.libraryCacheStale)) {
            viewModel.scanLibrary()
        }
    }

    if (!onboardingSeen) {
        OnboardingScreen {
            uiPrefs.edit().putBoolean("onboarding_seen", true).apply()
            onboardingSeen = true
        }
        return
    }

    Scaffold(
        bottomBar = {
            Column {
                state.currentTrack?.let { track ->
                    MiniPlayer(
                        track = track,
                        state = state,
                        onClick = { page = 0 },
                        onPrevious = viewModel::previous,
                        onToggle = viewModel::togglePlayback,
                        onNext = viewModel::next
                    )
                }
                NavigationBar {
                    NavigationBarItem(page == 0, { page = 0 }, { Icon(Icons.Default.Headphones, null) }, label = { Text("Now") })
                    NavigationBarItem(page == 1, { page = 1 }, { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                    NavigationBarItem(page == 2, { page = 2 }, { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                    NavigationBarItem(page == 3, { page = 3 }, { Icon(Icons.Default.Tune, null) }, label = { Text("DSP") })
                    NavigationBarItem(page == 4, { page = 4 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !hasPermission -> PermissionScreen { launcher.launch(permission) }
                page == 0 -> NowPlayingScreen(state, viewModel)
                page == 1 -> LibraryScreen(
                    tracks = state.tracks,
                    favoriteIds = state.favoriteIds,
                    isScanning = state.isScanning,
                    libraryError = state.libraryError,
                    scanCompleted = state.libraryScanCompleted,
                    scanTotal = state.libraryScanTotal,
                    scanParsed = state.libraryScanParsed,
                    scanReused = state.libraryScanReused,
                    scanStatus = state.libraryScanStatus,
                    onRescan = viewModel::scanLibrary,
                    onPlay = viewModel::play,
                    onPlayFromQueue = viewModel::playFromQueue,
                    onPlayQueue = viewModel::playQueue,
                    onPlayNext = viewModel::playNext,
                    onAddToQueue = viewModel::addToQueue,
                    onToggleFavorite = viewModel::toggleFavorite
                )
                page == 2 -> SearchScreen(
                    state.tracks,
                    state.favoriteIds,
                    search,
                    { search = it },
                    viewModel::play,
                    viewModel::playNext,
                    viewModel::addToQueue,
                    viewModel::toggleFavorite
                )
                page == 3 -> DspScreen(state.output)
                else -> MoreScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.moka_brand_icon),
            contentDescription = "Moka Music Player",
            modifier = Modifier.size(176.dp).clip(RoundedCornerShape(40.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text("Moka Music Player", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text(
            "Local-first hi-fi playback with a transparent signal path and a native real-time DSP engine.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WelcomeFeature(Icons.Default.LibraryMusic, "Your library stays local", "Moka reads your music without rewriting the files.")
                WelcomeFeature(Icons.Default.GraphicEq, "Tune without guessing", "EQ, ViPER-DDC and convolution remain visible in the signal path.")
                WelcomeFeature(Icons.Default.Usb, "Know what reaches the output", "Source format, route and DSP state are reported instead of hidden behind a badge.")
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
            Text("Open Moka")
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.LibraryMusic, null, Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text("Let Moka find your music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Moka only reads your local audio library. It does not rename, move, or modify your music files.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow music access") }
    }
}

private enum class LibraryCollectionType { ALBUM, ARTIST, GENRE }

private data class LibraryCollection(
    val type: LibraryCollectionType,
    val key: String,
    val title: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    tracks: List<MusicTrack>,
    favoriteIds: Set<Long>,
    isScanning: Boolean,
    libraryError: String?,
    scanCompleted: Int,
    scanTotal: Int,
    scanParsed: Int,
    scanReused: Int,
    scanStatus: String?,
    onRescan: () -> Unit,
    onPlay: (MusicTrack) -> Unit,
    onPlayFromQueue: (MusicTrack, List<MusicTrack>, Boolean) -> Unit,
    onPlayQueue: (List<MusicTrack>, Boolean) -> Unit,
    onPlayNext: (MusicTrack) -> Unit,
    onAddToQueue: (MusicTrack) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedCollection by rememberSaveable { mutableStateOf<String?>(null) }
    var trackMode by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 recent, 2 favorites

    val albumGroups = remember(tracks) { tracks.groupBy(::albumGroupKey) }
    val artistGroups = remember(tracks) { tracks.groupBy { UnicodeText.key(it.artist.ifBlank { "Unknown artist" }) } }
    val genreGroups = remember(tracks) { tracks.groupBy { normalizedGenre(it) } }

    val selected = selectedCollection?.let(::decodeCollection)
    if (selected != null) {
        val collectionTracks = when (selected.type) {
            LibraryCollectionType.ALBUM -> albumGroups[selected.key].orEmpty()
            LibraryCollectionType.ARTIST -> artistGroups[selected.key].orEmpty()
            LibraryCollectionType.GENRE -> genreGroups[selected.key].orEmpty()
        }.sortedWith(collectionTrackComparator)

        CollectionDetailScreen(
            collection = selected,
            tracks = collectionTracks,
            onBack = { selectedCollection = null },
            onPlayTrack = { track -> onPlayFromQueue(track, collectionTracks, false) },
            onPlayAll = { onPlayQueue(collectionTracks, false) },
            onShuffle = { onPlayQueue(collectionTracks, true) },
            favoriteIds = favoriteIds,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onToggleFavorite = onToggleFavorite
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Moka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("${tracks.size} tracks · ${albumGroups.size} albums · ${artistGroups.size} artists · ${genreGroups.size} genres")
            }
            IconButton(onClick = onRescan, enabled = !isScanning) {
                if (isScanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh library", tint = MaterialTheme.colorScheme.primary)
            }
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Tracks", "Albums", "Artists", "Genres").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        if (isScanning) {
            val progress = if (scanTotal > 0) scanCompleted.toFloat() / scanTotal.toFloat() else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                "$scanCompleted/$scanTotal tracks · $scanParsed parsed · $scanReused reused",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
            )
        }
        scanStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp))
        }
        libraryError?.let {
            Text(
                text = "Library refresh failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        when (tab) {
            0 -> {
                val recent = remember(tracks) {
                    tracks.filter { it.dateAddedEpochSeconds > 0L }
                        .sortedByDescending { it.dateAddedEpochSeconds }
                }
                val favorites = remember(tracks, favoriteIds) { tracks.filter { it.id in favoriteIds } }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = trackMode == 0, onClick = { trackMode = 0 }, label = { Text("All") })
                        FilterChip(
                            selected = trackMode == 1,
                            onClick = { trackMode = 1 },
                            label = { Text("Recent") },
                            enabled = recent.isNotEmpty()
                        )
                        FilterChip(
                            selected = trackMode == 2,
                            onClick = { trackMode = 2 },
                            label = { Text("Favorites") },
                            enabled = favorites.isNotEmpty()
                        )
                    }
                    TrackList(
                        when (trackMode) {
                            1 -> recent
                            2 -> favorites
                            else -> tracks
                        },
                        onPlay,
                        favoriteIds = favoriteIds,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }
            1 -> CollectionList(
                groups = albumGroups.entries.sortedWith(albumEntryComparator),
                type = LibraryCollectionType.ALBUM,
                title = { it.value.firstOrNull()?.album ?: "Unknown album" },
                subtitle = { entry ->
                    val first = entry.value.firstOrNull()
                    val artist = first?.albumArtist?.takeIf { it.isNotBlank() } ?: first?.artist ?: "Unknown artist"
                    "$artist · ${entry.value.size} track${if (entry.value.size == 1) "" else "s"}"
                },
                onClick = { entry ->
                    val title = entry.value.firstOrNull()?.album ?: "Unknown album"
                    selectedCollection = encodeCollection(LibraryCollectionType.ALBUM, entry.key, title)
                }
            )
            2 -> CollectionList(
                groups = artistGroups.entries.sortedWith(artistEntryComparator),
                type = LibraryCollectionType.ARTIST,
                title = { it.value.firstOrNull()?.artist ?: "Unknown artist" },
                subtitle = { entry ->
                    val albums = entry.value.map(::albumGroupKey).distinct().size
                    "${albums} album${if (albums == 1) "" else "s"} · ${entry.value.size} track${if (entry.value.size == 1) "" else "s"}"
                },
                onClick = { entry ->
                    val artist = entry.value.firstOrNull()?.artist ?: "Unknown artist"
                    selectedCollection = encodeCollection(LibraryCollectionType.ARTIST, entry.key, artist)
                }
            )
            3 -> CollectionList(
                groups = genreGroups.entries.sortedWith(genreEntryComparator),
                type = LibraryCollectionType.GENRE,
                title = { it.key },
                subtitle = { "${it.value.size} track${if (it.value.size == 1) "" else "s"}" },
                onClick = { entry ->
                    selectedCollection = encodeCollection(LibraryCollectionType.GENRE, entry.key, entry.key)
                }
            )
        }
    }
}

@Composable
private fun CollectionList(
    groups: List<Map.Entry<String, List<MusicTrack>>>,
    type: LibraryCollectionType,
    title: (Map.Entry<String, List<MusicTrack>>) -> String,
    subtitle: (Map.Entry<String, List<MusicTrack>>) -> String,
    onClick: (Map.Entry<String, List<MusicTrack>>) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.key }) { entry ->
            ListItem(
                modifier = Modifier.clickable { onClick(entry) },
                headlineContent = { Text(title(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(subtitle(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    CollectionArtwork(entry.value, type, Modifier.size(58.dp))
                },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CollectionArtwork(
    tracks: List<MusicTrack>,
    type: LibraryCollectionType,
    modifier: Modifier = Modifier.size(58.dp)
) {
    val context = LocalContext.current
    val loader = remember { ArtworkLoader(context.applicationContext) }
    val representatives = remember(tracks, type) {
        when (type) {
            LibraryCollectionType.ALBUM -> tracks.firstOrNull()?.let(::listOf).orEmpty()
            LibraryCollectionType.ARTIST -> tracks
                .groupBy(::albumGroupKey)
                .values
                .mapNotNull { it.firstOrNull() }
                .sortedWith(Comparator { a, b -> libraryTextComparator.compare(a.album, b.album) })
                .take(4)
            LibraryCollectionType.GENRE -> tracks
                .groupBy(::albumGroupKey)
                .values
                .mapNotNull { it.firstOrNull() }
                .take(4)
        }
    }
    var artwork by remember(representatives.map { Triple(it.id, it.albumId, it.onlineArtworkUrl) }) {
        mutableStateOf<List<android.graphics.Bitmap>>(emptyList())
    }

    LaunchedEffect(representatives.map { Triple(it.id, it.albumId, it.onlineArtworkUrl) }) {
        artwork = representatives.mapNotNull { loader.load(it, 256) }
    }

    val fallbackIcon = when (type) {
        LibraryCollectionType.ALBUM -> Icons.Default.Album
        LibraryCollectionType.ARTIST -> Icons.Default.Person
        LibraryCollectionType.GENRE -> Icons.Default.Category
    }

    Surface(modifier = modifier.clip(RoundedCornerShape(15.dp)), tonalElevation = 2.dp) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when {
                artwork.isEmpty() -> Icon(fallbackIcon, null)
                artwork.size == 1 || type == LibraryCollectionType.ALBUM -> {
                    Image(
                        artwork.first().asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        repeat(2) { row ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                repeat(2) { col ->
                                    val index = row * 2 + col
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        artwork.getOrNull(index)?.let { bitmap ->
                                            Image(
                                                bitmap.asImageBitmap(),
                                                null,
                                                Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } ?: Icon(fallbackIcon, null, Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionDetailScreen(
    collection: LibraryCollection,
    tracks: List<MusicTrack>,
    onBack: () -> Unit,
    onPlayTrack: (MusicTrack) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    favoriteIds: Set<Long>,
    onPlayNext: (MusicTrack) -> Unit,
    onAddToQueue: (MusicTrack) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            CollectionArtwork(tracks, collection.type, Modifier.size(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(collection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (collection.type) {
                        LibraryCollectionType.ALBUM -> "Album"
                        LibraryCollectionType.ARTIST -> "Artist"
                        LibraryCollectionType.GENRE -> "Genre"
                    } + " · ${tracks.size} track${if (tracks.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onPlayAll, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            FilledTonalButton(onClick = onShuffle, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }

        if (collection.type == LibraryCollectionType.ARTIST) {
            val albums = remember(tracks) {
                tracks.groupBy(::albumGroupKey).entries.sortedWith(albumOnlyEntryComparator)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                albums.forEach { album ->
                    item(key = "header-${album.key}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CollectionArtwork(album.value, LibraryCollectionType.ALBUM, Modifier.size(52.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(album.value.firstOrNull()?.album ?: "Unknown album", fontWeight = FontWeight.Bold)
                                album.value.firstOrNull()?.year?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    items(album.value.sortedWith(collectionTrackComparator), key = { "artist-${it.id}" }) { track ->
                        TrackRow(
                            track,
                            track.trackNumber?.let { "Track $it" } ?: track.formatLabel,
                            onClick = { onPlayTrack(track) },
                            isFavorite = track.id in favoriteIds,
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) },
                            onFavorite = { onToggleFavorite(track) }
                        )
                    }
                }
            }
        } else {
            TrackList(
                tracks = tracks,
                onPlay = onPlayTrack,
                subtitle = { track ->
                    when (collection.type) {
                        LibraryCollectionType.ALBUM -> track.artist
                        LibraryCollectionType.ARTIST -> track.album
                        LibraryCollectionType.GENRE -> "${track.artist} · ${track.album}"
                    }
                },
                favoriteIds = favoriteIds,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun SearchScreen(
    tracks: List<MusicTrack>,
    favoriteIds: Set<Long>,
    search: String,
    onSearchChange: (String) -> Unit,
    onPlay: (MusicTrack) -> Unit,
    onPlayNext: (MusicTrack) -> Unit,
    onAddToQueue: (MusicTrack) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit
) {
    val filtered = remember(tracks, search) {
        if (search.isBlank()) tracks else tracks.filter {
            UnicodeText.contains(it.title, search) ||
                UnicodeText.contains(it.artist, search) ||
                UnicodeText.contains(it.album, search) ||
                UnicodeText.contains(it.albumArtist, search) ||
                UnicodeText.contains(it.genre, search)
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search tracks, artists, albums, genres") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        TrackList(
            filtered,
            onPlay,
            favoriteIds = favoriteIds,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onToggleFavorite = onToggleFavorite
        )
    }
}

@Composable
private fun TrackList(
    tracks: List<MusicTrack>,
    onPlay: (MusicTrack) -> Unit,
    subtitle: (MusicTrack) -> String = { "${it.artist} · ${it.album}" },
    favoriteIds: Set<Long> = emptySet(),
    onPlayNext: ((MusicTrack) -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    onToggleFavorite: ((MusicTrack) -> Unit)? = null
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                subtitle = subtitle(track),
                onClick = { onPlay(track) },
                isFavorite = track.id in favoriteIds,
                onPlayNext = onPlayNext?.let { action -> { action(track) } },
                onAddToQueue = onAddToQueue?.let { action -> { action(track) } },
                onFavorite = onToggleFavorite?.let { action -> { action(track) } }
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    subtitle: String,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val loader = remember { ArtworkLoader(context.applicationContext) }
    var artwork by remember(track.albumId, track.id, track.onlineArtworkUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var menuExpanded by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(track.albumId, track.id, track.onlineArtworkUrl) {
        artwork = loader.load(track, 180)
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                artwork?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Icon(Icons.Default.MusicNote, null)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuggestionChip(onClick = onClick, label = { Text(track.formatLabel) })
                if (onPlayNext != null || onAddToQueue != null || onFavorite != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Track actions") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            onPlayNext?.let { action ->
                                DropdownMenuItem(
                                    text = { Text("Play next") },
                                    leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                                    onClick = { menuExpanded = false; action() }
                                )
                            }
                            onAddToQueue?.let { action ->
                                DropdownMenuItem(
                                    text = { Text("Add to queue") },
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                    onClick = { menuExpanded = false; action() }
                                )
                            }
                            onFavorite?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(if (isFavorite) "Remove favorite" else "Favorite") },
                                    leadingIcon = { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                                    onClick = { menuExpanded = false; action() }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun NowPlayingScreen(state: MokaUiState, viewModel: MokaViewModel) {
    val track = state.currentTrack
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.moka_brand_icon),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(30.dp))
                )
                Spacer(Modifier.height(20.dp))
                Text("Nothing playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.controllerReady) "Choose something from your library to start listening." else "Connecting to the Moka playback service…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val loader = remember { ArtworkLoader(context.applicationContext) }
    var artwork by remember(track.albumId, track.id, track.onlineArtworkUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showSignal by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val dspSettings = remember(state.output.dspActive, state.dspMeters.updatedAtMs) {
        DspSettingsStore(context.applicationContext).load()
    }
    LaunchedEffect(track.albumId, track.id, track.onlineArtworkUrl) { artwork = loader.load(track, 1200) }

    if (showQueue) {
        QueueSheet(state = state, viewModel = viewModel, onDismiss = { showQueue = false })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.moka_brand_icon),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("MOKA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Local hi-fi player", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PlaybackStatusPill(state = state, onClick = { showSignal = !showSignal })
        }

        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 12.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))),
                contentAlignment = Alignment.Center
            ) {
                artwork?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(Icons.Default.GraphicEq, null, Modifier.size(110.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                Icon(
                    if (track.id in state.favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (track.id in state.favoriteIds) "Remove favorite" else "Favorite",
                    tint = if (track.id in state.favoriteIds) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
        Text(
            track.artist,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track.album,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AudioBadge(track.formatLabel.uppercase())
            state.technical.bitDepth?.let {
                Spacer(Modifier.width(8.dp))
                AudioBadge("${it}-BIT")
            }
            state.technical.sampleRateHz?.let {
                Spacer(Modifier.width(8.dp))
                AudioBadge(formatSampleRate(it).uppercase())
            }
        }
        Spacer(Modifier.height(14.dp))

        val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
        val fraction = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { viewModel.seekTo((it * duration).toLong()) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text("-${formatTime((duration - state.positionMs).coerceAtLeast(0L))}", style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::toggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    "Shuffle",
                    tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            FilledTonalIconButton(onClick = viewModel::previous, enabled = state.hasPrevious) {
                Icon(Icons.Default.SkipPrevious, "Previous")
            }
            FilledIconButton(onClick = viewModel::togglePlayback, modifier = Modifier.size(76.dp)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", Modifier.size(40.dp))
            }
            FilledTonalIconButton(onClick = viewModel::next, enabled = state.hasNext) {
                Icon(Icons.Default.SkipNext, "Next")
            }
            IconButton(onClick = viewModel::cycleRepeatMode) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.seekBy(-10_000L) }) {
                Icon(Icons.Default.Replay10, "Back 10 seconds")
                Spacer(Modifier.width(6.dp))
                Text("10 sec")
            }
            Spacer(Modifier.width(24.dp))
            TextButton(onClick = { viewModel.seekBy(10_000L) }) {
                Text("10 sec")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Forward10, "Forward 10 seconds")
            }
        }

        Spacer(Modifier.height(6.dp))
        FilledTonalButton(onClick = { showQueue = true }, enabled = state.queue.isNotEmpty()) {
            Icon(Icons.Default.QueueMusic, null)
            Spacer(Modifier.width(8.dp))
            Text("Queue · ${state.queue.size}")
        }

        Spacer(Modifier.height(10.dp))
        Surface(
            onClick = { showSignal = !showSignal },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().animateContentSize()
        ) {
            Column(Modifier.padding(18.dp)) {
                val tech = state.technical
                val detail = buildString {
                    append(track.formatLabel)
                    tech.bitDepth?.let { append(" · ${it}-bit") }
                    tech.sampleRateHz?.let { append(" · ${formatSampleRate(it)}") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Signal path", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(detail, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.output.routeLabel} · ${state.output.deviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(if (showSignal) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showSignal) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SignalRow("Source", track.formatLabel)
                    SignalRow("Sample rate", tech.sampleRateHz?.let(::formatSampleRate) ?: "Reading…")
                    SignalRow("Bit depth", tech.bitDepth?.let { "$it-bit" } ?: "Unknown")
                    SignalRow("Bitrate", tech.bitrate?.let { "${it / 1000} kbps" } ?: "Unknown")
                    SignalRow("Output", state.output.routeLabel)
                    SignalRow("Device", state.output.deviceName)
                    SignalRow("Playback engine", state.output.directEngineLabel ?: "Media3 Hi-Res")
                    SignalRow("Direct path", if (state.output.directPlaybackAvailable) "Supported" else "System mixer")
                    state.output.maxDirectSampleRateHz?.let { SignalRow("Direct max", formatSampleRate(it)) }
                    if (state.output.audioTrackCreated) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("ACTUAL AUDIOTRACK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        state.output.actualAudioTrackSampleRateHz?.let { SignalRow("Track rate", formatSampleRate(it)) }
                        state.output.actualAudioTrackEncodingLabel?.let { SignalRow("Track format", it) }
                        state.output.actualAudioTrackChannels?.let { SignalRow("Track channels", it.toString()) }
                        SignalRow("Source = AudioTrack", if (state.output.sourceMatchesAudioTrack) "Exact" else "No")
                        if (state.output.highResFloatPrecisionPreserved) {
                            SignalRow("High-res handling", "32-bit float")
                        }
                    }
                    if (state.output.routeLabel.contains("USB", true)) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("USB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SignalRow("Bit-perfect mixer", if (state.output.usbBitPerfectAvailable) "Available" else "Not exposed")
                        state.output.maxUsbMixerSampleRateHz?.let { SignalRow("USB mixer max", formatSampleRate(it)) }
                        if (state.output.preferredUsbMixerSet) {
                            state.output.preferredUsbMixerSampleRateHz?.let { SignalRow("Preferred rate", formatSampleRate(it)) }
                            state.output.preferredUsbMixerBitDepth?.let { SignalRow("Preferred depth", "$it-bit") }
                            state.output.preferredUsbMixerChannels?.let { SignalRow("Preferred channels", it.toString()) }
                            SignalRow("Preferred behavior", if (state.output.preferredUsbMixerBitPerfect) "BIT_PERFECT" else "DEFAULT")
                        }
                        SignalRow("USB transport", if (state.output.usbTransportBitPerfectVerified) "VERIFIED" else "Not verified")
                        SignalRow("Source bit-perfect", if (state.output.sourceBitPerfectVerified) "VERIFIED" else "No")
                    }
                    SignalRow("Moka DSP", if (state.output.dspActive) "Active · 32-bit float" else "Off")
                    if (state.output.dspActive) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("DSP SIGNAL PATH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SignalRow("Auto headroom", if (dspSettings.autoHeadroomEnabled) formatDb(state.dspMeters.automaticHeadroomDb) else "Off")
                        SignalRow(
                            "Normalization",
                            if (dspSettings.normalizationEnabled) {
                                val gain = if (dspSettings.normalizationMode == NormalizationMode.ALBUM) {
                                    track.albumNormalizationGainDb ?: track.normalizationGainDb
                                } else track.normalizationGainDb
                                "${dspSettings.normalizationMode.label}${gain?.let { " · ${"%.1f".format(it)} dB" } ?: " · adaptive"}"
                            } else "Off"
                        )
                        SignalRow("ViPER-DDC", if (dspSettings.ddcEnabled) (dspSettings.ddcName ?: "On") else "Off")
                        SignalRow("Equalizer", if (dspSettings.eqEnabled) dspSettings.eqMode.label else "Off")
                        SignalRow("Convolver", if (dspSettings.convolverEnabled) (dspSettings.convolverName ?: "On") else "Off")
                        SignalRow("Limiter", if (dspSettings.limiterEnabled) "${dspSettings.limiterThresholdDb} dB" else "Off")
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("LIVE DSP METERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SignalRow("Input peak", formatDbfs(state.dspMeters.inputPeakDbfs))
                        SignalRow("Output peak", formatDbfs(state.dspMeters.outputPeakDbfs))
                        SignalRow("True-peak estimate", formatDbfs(state.dspMeters.intersamplePeakDbfs).replace("dBFS", "dBTP est."))
                        SignalRow("Full-scale samples", state.dspMeters.clippedSamples.toString())
                        state.dspMeters.throughputX?.let { SignalRow("DSP throughput", "${"%.2f".format(it)}× realtime") }
                        SignalRow("Audio underruns", state.dspMeters.audioTrackUnderruns.toString())
                        state.dspMeters.dspQueueDepth?.let { depth ->
                            SignalRow("DSP queue", "$depth/${state.dspMeters.dspQueueCapacity ?: "?"}")
                        }
                        state.dspMeters.primedFrames?.let { SignalRow("Primed frames", it.toString()) }
                        SignalRow("In-place reloads", state.dspMeters.hotReloadCount.toString())
                    }
                    SignalRow("Silence skipping", "Off")
                    SignalRow("Direct PCM", if (state.output.directEngineActive) "Active" else "Not active")
                    SignalRow("Fallback precision", "32-bit float when compatible")
                    SignalRow("Hardware offload", "Off for deterministic PCM path")
                    Text(
                        state.output.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: MokaUiState, viewModel: MokaViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val queueStore = remember { SavedQueueStore(context.applicationContext) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save queue as playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    queueStore.save(playlistName.ifBlank { "Saved queue" }, state.queue.map { it.id })
                    showSaveDialog = false
                    playlistName = ""
                }, enabled = state.queue.isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Up next", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${state.queue.size} tracks", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showSaveDialog = true }, enabled = state.queue.isNotEmpty()) {
                    Icon(Icons.Default.PlaylistAdd, "Save playlist")
                }
                TextButton(onClick = viewModel::clearQueueAfterCurrent, enabled = state.currentQueueIndex >= 0 && state.currentQueueIndex < state.queue.lastIndex) {
                    Text("Clear upcoming")
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(state.queue, key = { _, track -> track.id }) { index, item ->
                    val current = index == state.currentQueueIndex
                    ListItem(
                        modifier = Modifier.clickable { viewModel.playQueueIndex(index) },
                        headlineContent = {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        },
                        supportingContent = { Text("${item.artist} · ${item.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            if (current) Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary)
                            else Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.moveQueueItem(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Default.KeyboardArrowUp, "Move up")
                                }
                                IconButton(onClick = { viewModel.moveQueueItem(index, index + 1) }, enabled = index < state.queue.lastIndex) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Move down")
                                }
                                IconButton(onClick = { viewModel.removeQueueItem(index) }) {
                                    Icon(Icons.Default.Close, "Remove")
                                }
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MiniPlayer(
    track: MusicTrack,
    state: MokaUiState,
    onClick: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
    val progress = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = onToggle) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                IconButton(onClick = onNext, enabled = state.hasNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun AudioBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PlaybackStatusPill(state: MokaUiState, onClick: () -> Unit) {
    val context = LocalContext.current
    val pixelProtected = UsbDspSafetyPolicy.requiresDsp(context)
    val label = when {
        pixelProtected -> "USB · DSP PROTECTED"
        state.output.dspActive -> "DSP ACTIVE"
        state.output.sourceBitPerfectVerified -> "BIT PERFECT"
        state.output.directEngineActive -> "DIRECT PCM"
        else -> outputChipLabel(state)
    }
    val emphasized = pixelProtected || state.output.dspActive || state.output.sourceBitPerfectVerified
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (state.output.routeLabel.contains("USB", true)) Icons.Default.Usb else Icons.Default.Headphones,
                null,
                Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DspStatusBanner(settings: DspSettings, pixelUsbDspGuard: Boolean) {
    val stageCount = listOf(
        settings.eqEnabled,
        settings.ddcEnabled,
        settings.convolverEnabled,
        settings.normalizationEnabled,
        settings.limiterEnabled
    ).count { it }
    val title = when {
        pixelUsbDspGuard -> "DSP protected on Pixel USB"
        settings.masterEnabled -> "DSP active"
        else -> "Pure/direct mode"
    }
    val detail = when {
        pixelUsbDspGuard -> "Your saved tuning stays active on this route to prevent the unsafe Pixel USB level jump."
        settings.masterEnabled -> "$stageCount active stage${if (stageCount == 1) "" else "s"} · 32-bit float processing"
        else -> "Moka leaves the samples untouched when the output path supports it."
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (settings.masterEnabled || pixelUsbDspGuard) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (settings.masterEnabled || pixelUsbDspGuard) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            ) {
                Icon(Icons.Default.GraphicEq, null, Modifier.padding(10.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EqualizerPreview(gains: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val center = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().height(132.dp)
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            val maxDb = 15f
            val midY = size.height / 2f
            listOf(0f, size.height / 4f, midY, size.height * 3f / 4f, size.height).forEachIndexed { index, y ->
                drawLine(
                    color = if (index == 2) center else grid,
                    start = androidx.compose.ui.geometry.Offset(0f, y.coerceIn(1f, size.height - 1f)),
                    end = androidx.compose.ui.geometry.Offset(size.width, y.coerceIn(1f, size.height - 1f)),
                    strokeWidth = if (index == 2) 1.5f else 1f
                )
            }
            if (gains.size >= 2) {
                val path = Path()
                gains.forEachIndexed { i, gain ->
                    val x = i.toFloat() / (gains.lastIndex.toFloat()) * size.width
                    val y = midY - (gain.coerceIn(-maxDb, maxDb) / maxDb) * midY * 0.88f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = primary, style = Stroke(width = 4f, cap = StrokeCap.Round))
                gains.forEachIndexed { i, gain ->
                    val x = i.toFloat() / (gains.lastIndex.toFloat()) * size.width
                    val y = midY - (gain.coerceIn(-maxDb, maxDb) / maxDb) * midY * 0.88f
                    drawCircle(primary, radius = 4.5f, center = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
        }
    }
}

@Composable
private fun SignalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}


@Composable
private fun MoreScreen(state: MokaUiState, viewModel: MokaViewModel) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
    val savedQueueStore = remember { SavedQueueStore(context.applicationContext) }
    var savedQueues by remember { mutableStateOf(savedQueueStore.list()) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(viewModel.buildDiagnostics())
                }
            }.onFailure { CrashLogStore.nonFatal(context, "Export diagnostics", it) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.moka_brand_icon),
                    contentDescription = null,
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp))
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Moka 4.0 Beta 5", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("v$versionName · Local-first hi-fi", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("Music first. DSP when you want it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (savedQueues.isNotEmpty()) {
            item { SectionLabel("PLAYLISTS") }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Saved playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Queues saved inside Moka", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        savedQueues.forEach { saved ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(saved.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${saved.trackIds.size} tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.playSavedQueue(saved.trackIds) }) {
                                    Icon(Icons.Default.PlayArrow, "Play ${saved.name}")
                                }
                                IconButton(onClick = {
                                    savedQueueStore.delete(saved.name)
                                    savedQueues = savedQueueStore.list()
                                }) {
                                    Icon(Icons.Default.DeleteOutline, "Delete ${saved.name}")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("LIBRARY & METADATA") }
        item { LibraryMaintenanceCard(state, viewModel) }
        item { LibraryAuditCard(state.tracks) }
        item { MetadataDisplayPreferenceCard(state, viewModel) }
        item { MetadataEnrichmentCard(state, viewModel) }
        item { LoudnessAnalysisCard(state, viewModel) }

        item { SectionLabel("ADVANCED") }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Advanced & diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "Technical route details stay out of the way until you need them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showAdvanced = !showAdvanced }) {
                            Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (showAdvanced) "Hide advanced" else "Show advanced")
                        }
                    }
                    if (showAdvanced) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        SignalRow("Output", state.output.routeLabel)
                        SignalRow("Device", state.output.deviceName)
                        SignalRow("Route class", state.activeRouteProfile?.label ?: "Unknown")
                        SignalRow("Engine", state.output.directEngineLabel ?: "Media3")
                        SignalRow("DSP", if (state.output.dspActive) "Active" else "Off")
                        state.dspMeters.throughputX?.let { SignalRow("DSP throughput", "${"%.2f".format(it)}×") }
                        SignalRow("Audio underruns", state.dspMeters.audioTrackUnderruns.toString())
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { exportDiagnostics.launch("moka-diagnostics.txt") }) {
                            Icon(Icons.Default.Description, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export diagnostics")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("About Moka", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Local music remains read-only. Moka does not upload your library for playback or DSP.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Beta 4 retains the validated playback/DSP engine while adding Samsung FLAC fallback extraction, explicit unsupported-format errors, persistent failure backoff and safer loudness cache writes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/JLukassen/MokaMusicPlayer")))
                        }
                    }) {
                        Icon(Icons.Default.Code, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Source on GitHub")
                    }
                }
            }
        }

        item {
            TextButton(onClick = {
                context.getSharedPreferences("moka_ui", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", false).apply()
            }) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Show welcome screen next launch")
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun LibraryMaintenanceCard(state: MokaUiState, viewModel: MokaViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Library refresh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Normal refresh is incremental: unchanged tracks reuse cached metadata. Moka also watches MediaStore and refreshes automatically after file-copy/indexing bursts settle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.libraryScanStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.isScanning, onClick = viewModel::scanLibrary) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Refresh")
                }
                OutlinedButton(enabled = !state.isScanning, onClick = viewModel::fullRescanLibrary) { Text("Full rescan") }
            }
            Text(
                "Use Full rescan only when tags changed without Android updating the file modified time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetadataDisplayPreferenceCard(state: MokaUiState, viewModel: MokaViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Metadata names", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Choose how enriched artist and album names are displayed. Track titles remain your local file tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MetadataNamePreference.values().forEach { preference ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setMetadataNamePreference(preference) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.metadataNamePreference == preference,
                        onClick = { viewModel.setMetadataNamePreference(preference) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(preference.label, fontWeight = FontWeight.SemiBold)
                        Text(preference.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataEnrichmentCard(state: MokaUiState, viewModel: MokaViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Optional MusicBrainz enrichment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Moka stays local-first. This optional pass matches unique albums against MusicBrainz and caches Cover Art Archive artwork plus core release date/type/identifier data. Embedded tags always win.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.metadataEnrichmentRunning) {
                val progress = if (state.metadataEnrichmentTotal > 0) {
                    state.metadataEnrichmentCompleted.toFloat() / state.metadataEnrichmentTotal.toFloat()
                } else 0f
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.metadataEnrichmentCompleted}/${state.metadataEnrichmentTotal} albums · ${state.metadataEnrichmentMatched} matched",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.metadataEnrichmentStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.metadataEnrichmentRunning,
                    onClick = { viewModel.enrichOnlineMetadata(false) }
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enrich library")
                }
                OutlinedButton(
                    enabled = !state.metadataEnrichmentRunning,
                    onClick = viewModel::clearOnlineMetadata
                ) { Text("Clear cache") }
            }
            Text(
                "Online enrichment is never required for playback and is not run automatically. MusicBrainz requests are rate-limited and cached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoudnessAnalysisCard(state: MokaUiState, viewModel: MokaViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Offline loudness analysis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Analyze local PCM for BS.1770-style K-weighted loudness when ReplayGain/R128 coverage is incomplete. WAV uses a direct PCM fast path; results checkpoint as the scan progresses and your audio files are never modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.loudnessAnalysisRunning) {
                val progress = if (state.loudnessAnalysisTotal > 0) {
                    state.loudnessAnalysisCompleted.toFloat() / state.loudnessAnalysisTotal.toFloat()
                } else 0f
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.loudnessAnalysisCompleted}/${state.loudnessAnalysisTotal} tracks · ${state.loudnessAnalysisFailed} failures",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.loudnessAnalysisStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.loudnessAnalysisRunning,
                    onClick = viewModel::analyzeLoudnessForNormalization
                ) {
                    Icon(Icons.Default.GraphicEq, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Analyze library")
                }
                OutlinedButton(
                    enabled = !state.loudnessAnalysisRunning,
                    onClick = viewModel::clearLoudnessAnalysis
                ) { Text("Clear analysis") }
            }
            Text(
                "The scanner pauses playback to avoid competing with playback. Fully tagged albums are skipped, cached results resume instantly, and Track/Album gains target -18 LUFS. Inter-sample peak remains an estimate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DspScreen(outputStatus: AudioOutputStatus) {
    val context = LocalContext.current
    val store = remember { DspSettingsStore(context.applicationContext) }
    val profileStore = remember { DspDeviceProfileStore(context.applicationContext) }
    val headphoneStore = remember { HeadphoneProfileStore(context.applicationContext) }
    val headphoneKey = HeadphoneProfileStore.identity(outputStatus.routeLabel, outputStatus.deviceName)
    var headphoneAutoEnabled by remember { mutableStateOf(headphoneStore.enabled) }
    var hasHeadphoneProfile by remember(headphoneKey) {
        mutableStateOf(headphoneKey?.let(headphoneStore::has) ?: false)
    }
    var settings by remember { mutableStateOf(store.load()) }
    var autoProfilesEnabled by remember { mutableStateOf(profileStore.enabled) }
    val pixelUsbDspGuard = UsbDspSafetyPolicy.requiresDsp(context)
    val autoEqScope=rememberCoroutineScope()
    var autoEqQuery by remember { mutableStateOf("") }
    var autoEqList by remember { mutableStateOf<List<AutoEqMatch>>(emptyList()) }
    var autoEqBusy by remember { mutableStateOf(false) }
    var autoEqStatus by remember { mutableStateOf<String?>(null) }
    var autoEqDialog by remember { mutableStateOf<String?>(null) }
    var autoEqRequest by remember { mutableIntStateOf(0) }
    val savedEqStore = remember { UserEqCurveStore(context.applicationContext) }
    var savedCurves by remember { mutableStateOf(savedEqStore.list()) }
    var eqPresetName by remember { mutableStateOf("") }
    var eqPresetMessage by remember { mutableStateOf<String?>(null) }
    var eqPresetMenu by remember { mutableStateOf(false) }

    fun clearAutoEq() {
        autoEqRequest++
        autoEqQuery = ""
        autoEqList = emptyList()
        autoEqStatus = null
        autoEqBusy = false
    }

    fun save(next: DspSettings) {
        store.save(next)
        // Reload effective settings so the Pixel USB safety guard is reflected immediately
        // without permanently overwriting the user's stored master preference.
        settings = store.load()
    }

    fun displayName(uri: android.net.Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    val ddcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            save(settings.copy(ddcUri = uri.toString(), ddcName = displayName(uri), ddcEnabled = true))
        }
    }
    val irsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            save(settings.copy(convolverUri = uri.toString(), convolverName = displayName(uri), convolverEnabled = true))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Moka DSP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Shape the sound. Moka keeps every active stage visible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            DspStatusBanner(settings = settings, pixelUsbDspGuard = pixelUsbDspGuard)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic headphone profiles",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Remember your current VDC, convolver, EQ and output settings for this exact " +
                            "named playback device. Profiles apply automatically when the same " +
                            "device is connected again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Output: ${outputStatus.deviceName} · ${outputStatus.routeLabel}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-restore saved device profiles", modifier = Modifier.weight(1f))
                        Switch(checked = headphoneAutoEnabled, onCheckedChange = {
                            headphoneAutoEnabled = it
                            headphoneStore.enabled = it
                        })
                    }
                    if (headphoneKey == null) {
                        Text(
                            "This output doesn't provide a unique headphone identity. " +
                                "Use the manual EQ/VDC presets instead of guessing which headphones are connected.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(if (hasHeadphoneProfile) "Saved profile available for this output"
                            else "No profile saved for this output")
                        Button(onClick = {
                            headphoneStore.save(headphoneKey, store.load())
                            hasHeadphoneProfile = true
                        }) { Text("Save current DSP for this device") }
                        if (hasHeadphoneProfile) {
                            OutlinedButton(onClick = {
                                headphoneStore.remove(headphoneKey)
                                hasHeadphoneProfile = false
                            }) { Text("Forget this device profile") }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Apply the whole signal path with one tap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { save(DspPresets.apply(DspPresetId.REFERENCE, settings)) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reference") }
                        OutlinedButton(
                            onClick = { save(DspPresets.apply(DspPresetId.SAFE, settings)) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Safe") }
                        OutlinedButton(
                            onClick = { save(DspPresets.apply(DspPresetId.OFF, settings)) },
                            modifier = Modifier.weight(1f),
                            enabled = !pixelUsbDspGuard
                        ) { Text("Pure") }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DSP engine", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                pixelUsbDspGuard -> "Required for safe Pixel 8a USB playback · your saved DSP profile stays active"
                                settings.masterEnabled -> "High-resolution 32-bit float processing"
                                else -> "Pure/direct path when possible"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.masterEnabled,
                        enabled = !pixelUsbDspGuard,
                        onCheckedChange = { save(settings.copy(masterEnabled = it)) }
                    )
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Output control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic headroom", fontWeight = FontWeight.SemiBold)
                            Text("Estimates EQ/DDC/IRS gain before the limiter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.autoHeadroomEnabled, { save(settings.copy(autoHeadroomEnabled = it)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Limiter", Modifier.weight(1f)); Switch(settings.limiterEnabled, { save(settings.copy(limiterEnabled = it)) })
                    }
                    Text("Limiter threshold  ${"%.2f".format(settings.limiterThresholdDb)} dB")
                    Slider(settings.limiterThresholdDb, { save(settings.copy(limiterThresholdDb = it)) }, valueRange = -24f..0f)
                    Text("Limiter release  ${settings.limiterReleaseMs.toInt()} ms")
                    Slider(settings.limiterReleaseMs, { save(settings.copy(limiterReleaseMs = it)) }, valueRange = 20f..500f)
                    Text("Post gain  ${"%.2f".format(settings.postGainDb)} dB")
                    Slider(settings.postGainDb, { save(settings.copy(postGainDb = it)) }, valueRange = -12f..12f)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Volume normalization", fontWeight = FontWeight.SemiBold)
                            Text(
                                "ReplayGain/R128 when available; slow adaptive leveling otherwise",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.normalizationEnabled,
                            onCheckedChange = { save(settings.copy(normalizationEnabled = it)) }
                        )
                    }
                    if (settings.normalizationEnabled) {
                        Text("Normalization mode", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NormalizationMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.normalizationMode == mode,
                                    onClick = { save(settings.copy(normalizationMode = mode)) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        Text(
                            if (settings.normalizationMode == NormalizationMode.ALBUM)
                                "Preserves loudness relationships within an album when album ReplayGain/R128 tags exist."
                            else "Normalizes each track independently when track ReplayGain/R128 tags exist.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Adaptive fallback")
                                Text(
                                    "For songs without loudness tags",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.normalizationAdaptiveFallback,
                                onCheckedChange = { save(settings.copy(normalizationAdaptiveFallback = it)) }
                            )
                        }
                        Text("Normalization preamp  ${"%.1f".format(settings.normalizationPreampDb)} dB")
                        Slider(
                            settings.normalizationPreampDb,
                            { save(settings.copy(normalizationPreampDb = it)) },
                            valueRange = -6f..6f
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (settings.eqEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Multimodal equalizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (settings.eqEnabled) settings.eqMode.label else "Off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(settings.eqEnabled, { save(settings.copy(eqEnabled = it)) })
                    }
                    Spacer(Modifier.height(12.dp))
                    EqualizerPreview(settings.eqGainsDb)
                    Spacer(Modifier.height(14.dp))
                    Text("EQ presets", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick a sound style or a saved curve. Built-ins use your current FIR/IIR mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        FilledTonalButton(
                            onClick = { eqPresetMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Presets… · " +
                                    (BuiltInEqPresets.matching(settings.eqGainsDb)?.name ?: "Custom"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = eqPresetMenu,
                            onDismissRequest = { eqPresetMenu = false }
                        ) {
                            Text(
                                "BUILT-IN",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BuiltInEqPresets.all.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        save(preset.applyTo(settings))
                                        eqPresetMessage = "Applied " + preset.name + " EQ"
                                        eqPresetMenu = false
                                    }
                                )
                            }
                            if (savedCurves.isNotEmpty()) {
                                HorizontalDivider()
                                Text(
                                    "MY SAVED PRESETS",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                savedCurves.forEach { curve ->
                                    DropdownMenuItem(
                                        text = { Text(curve.name) },
                                        onClick = {
                                            save(curve.applyTo(settings))
                                            eqPresetName = curve.name
                                            eqPresetMessage = "Loaded " + curve.name
                                            eqPresetMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = eqPresetName,
                        onValueChange = { eqPresetName = it; eqPresetMessage = null },
                        label = { Text("EQ preset name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = eqPresetName.trim().isNotEmpty() && eqPresetName.trim().length <= 40,
                            onClick = {
                                runCatching { savedEqStore.save(eqPresetName, settings) }
                                    .onSuccess { savedCurves = it; eqPresetMessage = "Saved EQ preset" }
                                    .onFailure { eqPresetMessage = it.message ?: "Unable to save preset" }
                            }
                        ) { Text("Save EQ") }
                        OutlinedButton(
                            enabled = savedCurves.any { it.name == eqPresetName.trim() },
                            onClick = {
                                savedCurves = savedEqStore.delete(eqPresetName.trim())
                                eqPresetMessage = "Deleted EQ preset"
                            }
                        ) { Text("Delete") }
                    }
                    eqPresetMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var modeMenu by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(settings.eqMode.label) }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                EqMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = { save(settings.copy(eqMode = mode)); modeMenu = false }
                                    )
                                }
                            }
                        }
                        if (settings.eqMode == EqMode.FIR_MINIMUM_PHASE) {
                            var interpMenu by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { interpMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(settings.eqInterpolator.label) }
                                DropdownMenu(expanded = interpMenu, onDismissRequest = { interpMenu = false }) {
                                    EqInterpolator.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = { save(settings.copy(eqInterpolator = mode)); interpMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    DspSettings.EQ_FREQUENCIES_HZ.indices.forEach { i ->
                        val freq = DspSettings.EQ_FREQUENCIES_HZ[i]
                        val gain = settings.eqGainsDb[i]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (freq >= 1000) "${freq / 1000f}k" else "$freq",
                                Modifier.width(52.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = gain,
                                onValueChange = { v ->
                                    val g = settings.eqGainsDb.toMutableList()
                                    g[i] = (v * 10f).toInt() / 10f
                                    save(settings.copy(eqGainsDb = g))
                                },
                                valueRange = -15f..15f,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${"%.1f".format(gain)}", Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (settings.ddcEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ViPER-DDC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(settings.ddcName ?: "No .vdc selected", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.ddcEnabled, { save(settings.copy(ddcEnabled = it)) }, enabled = settings.ddcUri != null)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { ddcPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose .vdc")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { clearAutoEq(); autoEqDialog = "VDC" }) {
                        Text("Find headphone correction (AutoEq)")
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (settings.convolverEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Convolver", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(settings.convolverName ?: "No .irs / .wav / .flac selected", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.convolverEnabled, { save(settings.copy(convolverEnabled = it)) }, enabled = settings.convolverUri != null)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { irsPicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(8.dp)); Text("Choose impulse response")
                    }
                    OutlinedButton(onClick = { clearAutoEq(); autoEqDialog = "IR" }) {
                        Text("Find headphone impulse (AutoEq)")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Convolver gain  ${"%.1f".format(settings.convolverGainDb)} dB")
                    Slider(settings.convolverGainDb, { save(settings.copy(convolverGainDb = it)) }, valueRange = -24f..12f)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Output-device profiles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Automatically choose a DSP preset when the audio route changes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoProfilesEnabled,
                            onCheckedChange = { enabled ->
                                autoProfilesEnabled = enabled
                                profileStore.enabled = enabled
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    RouteClass.entries.forEach { route ->
                        DeviceProfileRow(route = route, store = profileStore)
                    }
                    Text(
                        "Keep current is the safe default. Route profiles only change DSP settings; they never change your music files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        item {
            AssistChip(onClick = { save(settings.copy(
                masterEnabled = true,
                autoHeadroomEnabled = true,
                limiterEnabled = true,
                limiterThresholdDb = -12f,
                limiterReleaseMs = 120f,
                postGainDb = 0f,
                eqEnabled = true,
                eqMode = EqMode.FIR_MINIMUM_PHASE,
                eqInterpolator = EqInterpolator.MAKIMA,
                eqGainsDb = DspSettings.FAVORITE_EQ_GAINS
            )) }, label = { Text("Restore Moka reference") }, leadingIcon = { Icon(Icons.Default.Restore, null) })
            Spacer(Modifier.height(6.dp))
            Text("Changes apply live during playback. Now Playing shows the active route and DSP state; source bit-perfect is intentionally false whenever Moka is tuning the samples.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(80.dp))
        }
    }

    if (autoEqDialog != null) {
        val mode = autoEqDialog ?: "VDC"
        AlertDialog(
            onDismissRequest = { clearAutoEq(); autoEqDialog = null },
            title = { Text(if (mode == "IR") "AutoEq convolver search" else "AutoEq headphone correction") },
            text = {
                Column {
                    Text("Choose a matching headphone model and measurement source.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = autoEqQuery,
                        onValueChange = {
                            autoEqQuery = it
                            autoEqRequest++
                            autoEqList = emptyList()
                            autoEqStatus = null
                            autoEqBusy = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Headphone model") },
                        singleLine = true,
                        trailingIcon = {
                            if (autoEqQuery.isNotEmpty()) {
                                IconButton(onClick = { clearAutoEq() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear query")
                                }
                            }
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !autoEqBusy && autoEqQuery.trim().length >= 3,
                            onClick = {
                                val query = autoEqQuery.trim()
                                val request = ++autoEqRequest
                                autoEqList = emptyList()
                                autoEqBusy = true
                                autoEqStatus = null
                                autoEqScope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            AutoEqCatalog.search(context.applicationContext, query)
                                        }
                                    }
                                    if (request == autoEqRequest && autoEqDialog == mode) {
                                        result.onSuccess {
                                            autoEqList = it
                                            if (it.isEmpty()) autoEqStatus = "No matching profiles"
                                        }.onFailure { autoEqStatus = it.message ?: "Search failed" }
                                        autoEqBusy = false
                                    }
                                }
                            }
                        ) { Text(if (autoEqBusy) "Loading…" else "Search") }
                        TextButton(onClick = { clearAutoEq() }) { Text("Clear") }
                    }
                    autoEqStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        autoEqList.forEach { match ->
                            TextButton(enabled = !autoEqBusy, onClick = {
                                val request = ++autoEqRequest
                                autoEqBusy = true
                                autoEqStatus = "Downloading ${match.model}…"
                                autoEqScope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            if (mode == "IR") {
                                                AutoEqCatalog.importImpulse(context.applicationContext, match)
                                            } else {
                                                AutoEqCatalog.importVdc(context.applicationContext, match)
                                            }
                                        }
                                    }
                                    if (request == autoEqRequest && autoEqDialog == mode) {
                                        result.onSuccess { uri ->
                                            if (mode == "IR") {
                                                save(settings.copy(
                                                    masterEnabled = true,
                                                    autoHeadroomEnabled = true,
                                                    limiterEnabled = true,
                                                    eqEnabled = false,
                                                    ddcEnabled = false,
                                                    convolverEnabled = true,
                                                    convolverUri = uri.toString(),
                                                    convolverName = "${match.model} · ${match.source}"
                                                ))
                                            } else {
                                                save(settings.copy(
                                                    masterEnabled = true,
                                                    autoHeadroomEnabled = true,
                                                    limiterEnabled = true,
                                                    eqEnabled = false,
                                                    convolverEnabled = false,
                                                    ddcEnabled = true,
                                                    ddcUri = uri.toString(),
                                                    ddcName = "${match.model} · ${match.source}"
                                                ))
                                            }
                                            clearAutoEq()
                                            autoEqDialog = null
                                        }.onFailure {
                                            autoEqStatus = "Import failed: ${it.message}"
                                            autoEqBusy = false
                                        }
                                    }
                                }
                            }) { Text("${match.model} · ${match.source}") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { clearAutoEq(); autoEqDialog = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun DeviceProfileRow(route: RouteClass, store: DspDeviceProfileStore) {
    var selected by remember(route) { mutableStateOf(store.get(route)) }
    var expanded by remember(route) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(route.label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selected.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DspPresetId.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            selected = preset
                            store.set(route, preset)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun albumGroupKey(track: MusicTrack): String =
    if (track.albumId > 0L) "id:${track.albumId}" else
        "name:${UnicodeText.key(track.album)}|${UnicodeText.key(track.albumArtist ?: track.artist)}"

private fun normalizedGenre(track: MusicTrack): String =
    UnicodeText.display(track.genre)?.takeIf { it.isNotBlank() } ?: "Unknown genre"

private val libraryTextComparator = UnicodeText.comparator()

private val collectionTrackComparator = Comparator<MusicTrack> { a, b ->
    compareValues(a.discNumber ?: 0, b.discNumber ?: 0).takeIf { it != 0 }
        ?: compareValues(a.trackNumber ?: Int.MAX_VALUE, b.trackNumber ?: Int.MAX_VALUE).takeIf { it != 0 }
        ?: libraryTextComparator.compare(a.album, b.album).takeIf { it != 0 }
        ?: libraryTextComparator.compare(a.title, b.title)
}

private val albumEntryComparator = Comparator<Map.Entry<String, List<MusicTrack>>> { a, b ->
    val af = a.value.firstOrNull()
    val bf = b.value.firstOrNull()
    val aa = af?.albumArtist?.takeIf { it.isNotBlank() } ?: af?.artist ?: "Unknown artist"
    val ba = bf?.albumArtist?.takeIf { it.isNotBlank() } ?: bf?.artist ?: "Unknown artist"
    libraryTextComparator.compare(aa, ba).takeIf { it != 0 }
        ?: libraryTextComparator.compare(af?.album.orEmpty(), bf?.album.orEmpty())
}

private val albumOnlyEntryComparator = Comparator<Map.Entry<String, List<MusicTrack>>> { a, b ->
    libraryTextComparator.compare(a.value.firstOrNull()?.album.orEmpty(), b.value.firstOrNull()?.album.orEmpty())
}

private val artistEntryComparator = Comparator<Map.Entry<String, List<MusicTrack>>> { a, b ->
    libraryTextComparator.compare(
        a.value.firstOrNull()?.artist ?: "Unknown artist",
        b.value.firstOrNull()?.artist ?: "Unknown artist"
    )
}

private val genreEntryComparator = Comparator<Map.Entry<String, List<MusicTrack>>> { a, b ->
    libraryTextComparator.compare(a.key, b.key)
}

private fun encodeCollection(type: LibraryCollectionType, key: String, title: String): String =
    "${type.name}\u001F${key}\u001F${title}"

private fun decodeCollection(encoded: String): LibraryCollection? {
    val parts = encoded.split('\u001F', limit = 3)
    if (parts.size != 3) return null
    val type = runCatching { LibraryCollectionType.valueOf(parts[0]) }.getOrNull() ?: return null
    return LibraryCollection(type, parts[1], parts[2])
}

private fun outputChipLabel(state: MokaUiState): String = when {
    state.output.sourceBitPerfectVerified -> "USB · BIT PERFECT"
    state.output.dspActive -> "MOKA · HI-RES DSP"
    state.output.directEngineActive -> "MOKA · DIRECT PCM"
    state.output.usbTransportBitPerfectVerified -> "USB · TRANSPORT VERIFIED"
    state.output.highResFloatPrecisionPreserved -> "HI-RES · 32-BIT FLOAT"
    state.output.routeLabel.contains("USB", true) && state.output.usbBitPerfectAvailable -> "USB · BIT-PERFECT READY"
    state.output.directPlaybackAvailable -> "HI-FI · DIRECT READY"
    state.output.routeLabel.contains("Bluetooth", true) -> "BLUETOOTH"
    state.output.routeLabel.contains("head", true) -> "HEADPHONES · HI-FI"
    else -> "HI-FI"
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun formatSampleRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"

private fun formatDb(value: Float): String = "${"%.1f".format(value)} dB"

private fun formatDbfs(value: Float): String =
    if (!value.isFinite() || value <= -119f) "< -119 dBFS" else "${"%.1f".format(value)} dBFS"
