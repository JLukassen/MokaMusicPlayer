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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.mokamusic.player.audio.dsp.DspSettings
import com.mokamusic.player.audio.dsp.DspDeviceProfileStore
import com.mokamusic.player.audio.dsp.DspPresetId
import com.mokamusic.player.audio.dsp.DspPresets
import com.mokamusic.player.audio.dsp.RouteClass
import com.mokamusic.player.audio.dsp.DspSettingsStore
import com.mokamusic.player.audio.dsp.EqInterpolator
import com.mokamusic.player.audio.dsp.EqMode
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
                    NavigationBarItem(page == 4, { page = 4 }, { Icon(Icons.Default.MoreHoriz, null) }, label = { Text("More") })
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
                page == 3 -> DspScreen()
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
            "Local lossless playback, source-rate USB audio, native DSP, ViPER-DDC and IRS convolution.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("• Local FLAC/WAV library — files stay on your device")
            Text("• Direct PCM and truthful bit-perfect reporting")
            Text("• Native C++ DSP with automatic headroom")
            Text("• Signal-path details and exportable diagnostics")
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
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
    val artistGroups = remember(tracks) { tracks.groupBy { it.artist.trim().ifBlank { "Unknown artist" } } }
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
            IconButton(onClick = onRescan) { Icon(Icons.Default.Refresh, "Rescan") }
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Tracks", "Albums", "Artists", "Genres").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        if (isScanning) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                groups = albumGroups.entries.sortedWith(
                    compareBy<Map.Entry<String, List<MusicTrack>>> { entry ->
                        val first = entry.value.firstOrNull()
                        (first?.albumArtist?.takeIf { it.isNotBlank() } ?: first?.artist ?: "Unknown artist").lowercase()
                    }.thenBy { it.value.firstOrNull()?.album?.lowercase().orEmpty() }
                ),
                icon = Icons.Default.Album,
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
                groups = artistGroups.entries.sortedBy { it.key.lowercase() },
                icon = Icons.Default.Person,
                title = { it.key },
                subtitle = { "${it.value.size} track${if (it.value.size == 1) "" else "s"}" },
                onClick = { entry ->
                    selectedCollection = encodeCollection(LibraryCollectionType.ARTIST, entry.key, entry.key)
                }
            )
            3 -> CollectionList(
                groups = genreGroups.entries.sortedBy { it.key.lowercase() },
                icon = Icons.Default.Category,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, null)
                        }
                    }
                },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }
            )
            HorizontalDivider()
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
                tracks.groupBy(::albumGroupKey).entries.sortedBy { it.value.firstOrNull()?.album?.lowercase().orEmpty() }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                albums.forEach { album ->
                    item(key = "header-${album.key}") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                            Text(album.value.firstOrNull()?.album ?: "Unknown album", fontWeight = FontWeight.Bold)
                            album.value.firstOrNull()?.year?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            it.title.contains(search, true) ||
                it.artist.contains(search, true) ||
                it.album.contains(search, true) ||
                it.genre.orEmpty().contains(search, true)
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
    var artwork by remember(track.albumId, track.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var menuExpanded by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(track.albumId, track.id) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Headphones, null, Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Moka Music Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(if (state.controllerReady) "Choose a track from your library" else "Connecting to playback service…")
            }
        }
        return
    }

    val context = LocalContext.current
    val loader = remember { ArtworkLoader(context.applicationContext) }
    var artwork by remember(track.albumId, track.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showSignal by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val dspSettings = remember(state.output.dspActive, state.dspMeters.updatedAtMs) {
        DspSettingsStore(context.applicationContext).load()
    }
    LaunchedEffect(track.albumId, track.id) { artwork = loader.load(track, 1200) }

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
            Column {
                Text("MOKA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("Lossless player", style = MaterialTheme.typography.labelMedium)
            }
            AssistChip(
                onClick = { showSignal = !showSignal },
                label = { Text(outputChipLabel(state)) },
                leadingIcon = {
                    Icon(
                        if (state.output.routeLabel.contains("USB", true)) Icons.Default.Usb else Icons.Default.Headphones,
                        null,
                        Modifier.size(18.dp)
                    )
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(36.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
            contentAlignment = Alignment.Center
        ) {
            artwork?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Default.GraphicEq, null, Modifier.size(110.dp))
        }

        Spacer(Modifier.height(22.dp))
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
        Text(track.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.album, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        track.genre?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().animateContentSize()
        ) {
            Column(Modifier.padding(18.dp)) {
                val tech = state.technical
                val detail = buildString {
                    append(track.formatLabel)
                    tech.bitDepth?.let { append(" · ${it}-bit") }
                    tech.sampleRateHz?.let { append(" · ${formatSampleRate(it)}") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(detail, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.output.routeLabel} · ${state.output.deviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(if (showSignal) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
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
                        SignalRow("Normalization", if (dspSettings.normalizationEnabled) "On" else "Off")
                        SignalRow("ViPER-DDC", if (dspSettings.ddcEnabled) (dspSettings.ddcName ?: "On") else "Off")
                        SignalRow("Equalizer", if (dspSettings.eqEnabled) dspSettings.eqMode.label else "Off")
                        SignalRow("Convolver", if (dspSettings.convolverEnabled) (dspSettings.convolverName ?: "On") else "Off")
                        SignalRow("Limiter", if (dspSettings.limiterEnabled) "${dspSettings.limiterThresholdDb} dB" else "Off")
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("LIVE DSP METERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SignalRow("Input peak", formatDbfs(state.dspMeters.inputPeakDbfs))
                        SignalRow("Output peak", formatDbfs(state.dspMeters.outputPeakDbfs))
                        SignalRow("Full-scale samples", state.dspMeters.clippedSamples.toString())
                        state.dspMeters.throughputX?.let { SignalRow("DSP throughput", "${"%.2f".format(it)}× realtime") }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.moka_brand_icon),
                    contentDescription = null,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(22.dp))
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Moka Music Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("v$versionName · early release", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Native hi-fi playback + DSP", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Export the current route, source format, DSP settings, live meters, last error and crash information.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { exportDiagnostics.launch("moka-diagnostics.txt") }) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export diagnostics")
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Current audio route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SignalRow("Output", state.output.routeLabel)
                    SignalRow("Device", state.output.deviceName)
                    SignalRow("Route class", state.activeRouteProfile?.label ?: "Unknown")
                    SignalRow("Engine", state.output.directEngineLabel ?: "Media3")
                    SignalRow("DSP", if (state.output.dspActive) "Active" else "Off")
                    state.dspMeters.throughputX?.let { SignalRow("DSP throughput", "${"%.2f".format(it)}×") }
                }
            }
        }
        if (savedQueues.isNotEmpty()) {
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
                                    Text("${saved.trackIds.size} tracks", style = MaterialTheme.typography.bodySmall)
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
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Project", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Moka keeps local music read-only and does not upload your library for playback or DSP.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/JLukassen/MokaMusicPlayer")))
                        }
                    }) {
                        Icon(Icons.Default.Code, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open GitHub")
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Playback status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Gapless Media3 playback is preserved where the decoder/container exposes gapless metadata. True seamless handoff between Moka's direct PCM/DSP tracks is still experimental and is not falsely labeled gapless yet.")
                    Spacer(Modifier.height(8.dp))
                    Text("Queue reordering/removal, favorites, recently added, device DSP profiles, and signal-path diagnostics are enabled in this iteration.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DspScreen() {
    val context = LocalContext.current
    val store = remember { DspSettingsStore(context.applicationContext) }
    val profileStore = remember { DspDeviceProfileStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.load()) }
    var autoProfilesEnabled by remember { mutableStateOf(profileStore.enabled) }

    fun save(next: DspSettings) {
        settings = next
        store.save(next)
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
            Text("JamesDSP-compatible tuning inside Moka's local playback path", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            modifier = Modifier.weight(1f)
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
                        Text(if (settings.masterEnabled) "High-resolution 32-bit float processing" else "Pure/direct path when possible", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.masterEnabled, onCheckedChange = { save(settings.copy(masterEnabled = it)) })
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
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Multimodal equalizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(settings.eqMode.label, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.eqEnabled, { save(settings.copy(eqEnabled = it)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    var modeMenu by remember { mutableStateOf(false) }
                    Box {
                        FilledTonalButton(onClick = { modeMenu = true }) { Text(settings.eqMode.label) }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            EqMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, onClick = { save(settings.copy(eqMode = mode)); modeMenu = false }) }
                        }
                    }
                    if (settings.eqMode == EqMode.FIR_MINIMUM_PHASE) {
                        Spacer(Modifier.height(8.dp))
                        var interpMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { interpMenu = true }) { Text(settings.eqInterpolator.label) }
                            DropdownMenu(expanded = interpMenu, onDismissRequest = { interpMenu = false }) {
                                EqInterpolator.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, onClick = { save(settings.copy(eqInterpolator = mode)); interpMenu = false }) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Your JamesDSP curve", fontWeight = FontWeight.SemiBold)
                    Text("25 · 40 · 63 · 100 · 160 · 250 · 400 · 630 · 1k · 1.6k · 2.5k · 4k · 6.3k · 10k · 16k", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        items(DspSettings.EQ_FREQUENCIES_HZ.indices.toList(), key = { it }) { i ->
            val freq = DspSettings.EQ_FREQUENCIES_HZ[i]
            val gain = settings.eqGainsDb[i]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (freq >= 1000) "${freq / 1000f}k" else "$freq", Modifier.width(52.dp), fontWeight = FontWeight.SemiBold)
                Slider(
                    value = gain,
                    onValueChange = { v ->
                        val g = settings.eqGainsDb.toMutableList(); g[i] = (v * 10f).toInt() / 10f
                        save(settings.copy(eqGainsDb = g))
                    },
                    valueRange = -15f..15f,
                    modifier = Modifier.weight(1f)
                )
                Text("${"%.1f".format(gain)}", Modifier.width(48.dp))
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
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
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
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
            )) }, label = { Text("Restore screenshot tuning") }, leadingIcon = { Icon(Icons.Default.Restore, null) })
            Spacer(Modifier.height(6.dp))
            Text("DSP changes hot-reload during playback after a short debounce. When DSP is active, Now Playing should show MOKA · HI-RES DSP. Source bit-perfect is intentionally false because the samples are being tuned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(80.dp))
        }
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
    if (track.albumId > 0L) "id:${track.albumId}" else "name:${track.album.lowercase()}|${track.albumArtist.orEmpty().lowercase()}"

private fun normalizedGenre(track: MusicTrack): String =
    track.genre?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown genre"

private val collectionTrackComparator = compareBy<MusicTrack> { it.discNumber ?: 0 }
    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
    .thenBy { it.album.lowercase() }
    .thenBy { it.title.lowercase() }

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
