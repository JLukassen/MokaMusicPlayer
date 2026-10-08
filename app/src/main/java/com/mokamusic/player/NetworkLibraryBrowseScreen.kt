package com.mokamusic.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mokamusic.player.network.NetworkAlbum
import com.mokamusic.player.network.NetworkSong
import com.mokamusic.player.network.NetworkArtist
import com.mokamusic.player.network.NetworkGenre

/** Network is a library source, not a place to type a password. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkLibraryScreen(
    state: NetworkLibraryState,
    onPlay: (NetworkSong, List<NetworkSong>, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    deviceTrackCount: Int,
    onShuffleMixed: () -> Unit
) {
    LaunchedEffect(state) { state.onScreenOpened() }
    var sortMode by remember { mutableStateOf("Name A-Z") }
    var sortMenu by remember { mutableStateOf(false) }

    if (state.client == null) {
        Column(Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Navidrome", style = MaterialTheme.typography.titleLarge)
            Text("Connect in Settings to browse your network music.")
            Button(onClick = onOpenSettings) { Text("Set up Navidrome in Settings") }
        }
        return
    }

    // These controls live inside each scrolling list: swiping up reveals more songs.
    val header: @Composable () -> Unit = {
        Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Navidrome", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(
                    if (state.client == null) "Not connected"
                    else "Private network music · " + state.albums.size + " albums loaded",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = state::refresh, enabled = !state.busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Navidrome tracks and albums")
            }
            TextButton(onClick = onOpenSettings) { Text("Account settings") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.status?.let {
            Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        NetworkMixedShuffleButton(state, deviceTrackCount, onShuffleMixed)
        PrimaryScrollableTabRow(selectedTabIndex = state.category, edgePadding = 0.dp) {
            listOf("Tracks", "Albums", "Artists", "Genres").forEachIndexed { index, label ->
                Tab(selected = state.category == index, text = { Text(label) },
                    onClick = { sortMode = "Name A-Z"; state.showCategory(index) })
            }
        }

        val choices = when (state.category) {
            0 -> listOf("Name A-Z", "Name Z-A", "Artist", "Album")
            1 -> listOf("Name A-Z", "Name Z-A", "Artist")
            2 -> listOf("Name A-Z", "Name Z-A", "Album count")
            else -> listOf("Name A-Z", "Name Z-A", "Track count")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.search, onValueChange = { state.search = it },
                label = { Text(if (state.category == 0) "Find server tracks" else "Filter loaded items") },
                modifier = Modifier.weight(1f), singleLine = true
            )
            Box {
                OutlinedButton(onClick = { sortMenu = true }) { Text(sortMode) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice) }, onClick = {
                            sortMode = choice
                            sortMenu = false
                        })
                    }
                }
            }
        }
        if (state.category == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = !state.busy && state.search.isNotBlank(),
                    onClick = { state.searchServerTracks(state.search) }
                ) { Text("Search server") }
                if (state.activeTrackSearch.isNotBlank()) {
                    TextButton(onClick = {
                        state.search = ""
                        state.searchServerTracks("")
                    }) { Text("Show browsed tracks") }
                }
            }
        }

        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.selectedAlbum != null) {
            val sorted = sortSongs(state.songs, sortMode)
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "controls") { header() }
                item(key = "back") {
                    NetworkBackHeader(state.selectedAlbum?.name.orEmpty(),
                        onBack = state::backToAlbums)
                }
                item(key = "queue-actions") { NetworkQueueActions(sorted, onPlay) }
                items(sorted, key = { it.id }) { song ->
                    NetworkSongRow(song, state, onPlay, sorted)
                }
            }
            return@Column
        }
        if (state.selectedArtist != null) {
            val selectedAlbums = sortAlbums(state.artistAlbums, sortMode)
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "controls") { header() }
                item(key = "back") {
                    NetworkBackHeader(state.selectedArtist?.name.orEmpty(),
                        onBack = state::backFromArtist)
                }
                items(selectedAlbums, key = { it.id }) { album ->
                    NetworkAlbumRow(album, state)
                }
            }
            return@Column
        }
        if (state.selectedGenre != null) {
            val sorted = sortSongs(state.genreSongs, sortMode)
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "controls") { header() }
                item(key = "back") {
                    NetworkBackHeader(state.selectedGenre?.name.orEmpty(),
                        onBack = state::backFromGenre)
                }
                item(key = "queue-actions") { NetworkQueueActions(sorted, onPlay) }
                items(sorted, key = { it.id }) { song ->
                    NetworkSongRow(song, state, onPlay, sorted)
                }
                if (state.hasMoreGenreSongs) {
                    item {
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = state::loadMoreGenreSongs,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) { Text("Load more genre tracks") }
                    }
                }
            }
            return@Column
        }

        when (state.category) {
            0 -> {
                val tracks = if (state.activeTrackSearch.isNotBlank())
                    state.searchedTracks else state.browsedTracks
                val sorted = sortSongs(tracks, sortMode)
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "controls") { header() }
                    item(key = "queue-actions") { NetworkQueueActions(sorted, onPlay) }
                    item(key = "count") {
                Text(
                    if (state.activeTrackSearch.isNotBlank())
                        "Matching tracks from Navidrome"
                    else "Loaded " + sorted.size + " tracks from " +
                        state.trackAlbumCursor + " albums" +
                        if (state.trackBrowsingComplete) " · All tracks loaded" else " · Scroll for more",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                    }
                    items(sorted, key = { it.id }) { song ->
                        NetworkSongRow(song, state, onPlay, sorted)
                    }
                    if (state.activeTrackSearch.isNotBlank() && state.hasMoreSearch) {
                        item {
                            OutlinedButton(
                                enabled = !state.busy,
                                onClick = {
                                    state.searchServerTracks(state.activeTrackSearch, more = true)
                                },
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) { Text("More matching tracks") }
                        }
                    } else if (state.activeTrackSearch.isBlank() && !state.trackBrowsingComplete) {
                        item(key = "next-track-batch") {
                            LaunchedEffect(state.trackAlbumCursor, state.busy,
                                state.trackBatchFailed, state.trackBrowsingComplete) {
                                if (!state.busy && !state.trackBatchFailed &&
                                    !state.trackBrowsingComplete) state.loadTrackBatch()
                            }
                            OutlinedButton(
                                enabled = !state.busy,
                                onClick = state::loadTrackBatch,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) { Text(if (state.trackBatchFailed) "Retry loading tracks" else "Load more tracks") }
                        }
                    }
                }
            }
            1 -> {
                val filtered = state.albums.filter {
                    state.search.isBlank() ||
                        it.name.contains(state.search, true) ||
                        it.artist.contains(state.search, true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "controls") { header() }
                    items(sortAlbums(filtered, sortMode), key = { it.id }) { album ->
                        NetworkAlbumRow(album, state)
                    }
                    if (state.hasMoreAlbums) {
                        item(key = "next-album-batch") {
                            LaunchedEffect(state.albums.size, state.busy, state.albumBatchFailed) {
                                if (!state.busy && !state.albumBatchFailed) state.loadMore()
                            }
                            OutlinedButton(
                                onClick = state::loadMore, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) { Text(if (state.albumBatchFailed) "Retry loading albums" else "Load more albums") }
                        }
                    }
                }
            }
            2 -> {
                val filtered = state.artists.filter {
                    state.search.isBlank() || it.name.contains(state.search, true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "controls") { header() }
                    items(sortArtists(filtered, sortMode), key = { it.id }) { artist ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                state.openArtist(artist)
                            },
                            headlineContent = { Text(artist.name) },
                            supportingContent = { Text(artist.albumCount.toString() + " albums") },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                        )
                        HorizontalDivider()
                    }
                    if (filtered.isEmpty()) {
                        item {
                            TextButton(onClick = state::loadArtists, enabled = !state.busy) {
                                Text("Load or refresh artists")
                            }
                        }
                    }
                }
            }
            3 -> {
                val filtered = state.genres.filter {
                    state.search.isBlank() || it.name.contains(state.search, true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "controls") { header() }
                    items(sortGenres(filtered, sortMode), key = { it.name }) { genre ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                state.openGenre(genre)
                            },
                            headlineContent = { Text(genre.name) },
                            supportingContent = {
                                Text(genre.songCount.toString() + " tracks · " +
                                    genre.albumCount + " albums")
                            },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                        )
                        HorizontalDivider()
                    }
                    if (filtered.isEmpty()) {
                        item {
                            TextButton(onClick = state::loadGenres, enabled = !state.busy) {
                                Text("Load or refresh genres")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Also shown from Device music, so users do not need to change sources first. */
@Composable
internal fun NetworkMixedShuffleButton(
    state: NetworkLibraryState,
    deviceTrackCount: Int,
    onShuffleMixed: () -> Unit
) {
    if (state.client == null || deviceTrackCount == 0) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        FilledTonalButton(
            onClick = onShuffleMixed,
            enabled = !state.mixedShuffleBusy
        ) {
            if (state.mixedShuffleBusy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Shuffle, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state.mixedShuffleBusy) "Loading mixed shuffle…"
                 else "Shuffle device + Navidrome")
        }
        state.mixedShuffleStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NetworkBackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to network library")
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NetworkAlbumRow(album: NetworkAlbum, state: NetworkLibraryState) {
    ListItem(
        modifier = Modifier.clickable(enabled = !state.busy) { state.openAlbum(album) },
        headlineContent = {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(album.artist + " · " + album.songCount + " songs")
        },
        leadingContent = { Icon(Icons.Default.Album, null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
    )
    HorizontalDivider()
}

@Composable
private fun NetworkQueueActions(
    songs: List<NetworkSong>,
    onPlay: (NetworkSong, List<NetworkSong>, Boolean) -> Unit
) {
    if (songs.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { onPlay(songs.first(), songs, false) }) {
            Text("Play all · ${songs.size}")
        }
        Button(onClick = { onPlay(songs.random(), songs, true) }) {
            Text("Shuffle · ${songs.size}")
        }
    }
}

@Composable
private fun NetworkSongRow(
    song: NetworkSong, state: NetworkLibraryState,
    onPlay: (NetworkSong, List<NetworkSong>, Boolean) -> Unit,
    queue: List<NetworkSong>
) {
    ListItem(
        modifier = Modifier.clickable(enabled = !state.busy) {
            onPlay(song, queue, false)
        },
        headlineContent = {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(song.artist + " · " + song.album,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(Icons.Default.PlayArrow, null) }
    )
    HorizontalDivider()
}

private fun sortSongs(rows: List<NetworkSong>, mode: String): List<NetworkSong> =
    when (mode) {
        "Name Z-A" -> rows.sortedByDescending { it.title.lowercase() }
        "Artist" -> rows.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        "Album" -> rows.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
        else -> rows.sortedBy { it.title.lowercase() }
    }

private fun sortAlbums(rows: List<NetworkAlbum>, mode: String): List<NetworkAlbum> =
    when (mode) {
        "Name Z-A" -> rows.sortedByDescending { it.name.lowercase() }
        "Artist" -> rows.sortedWith(compareBy({ it.artist.lowercase() }, { it.name.lowercase() }))
        else -> rows.sortedBy { it.name.lowercase() }
    }

private fun sortArtists(rows: List<NetworkArtist>, mode: String): List<NetworkArtist> =
    when (mode) {
        "Name Z-A" -> rows.sortedByDescending { it.name.lowercase() }
        "Album count" -> rows.sortedByDescending { it.albumCount }
        else -> rows.sortedBy { it.name.lowercase() }
    }

private fun sortGenres(rows: List<NetworkGenre>, mode: String): List<NetworkGenre> =
    when (mode) {
        "Name Z-A" -> rows.sortedByDescending { it.name.lowercase() }
        "Track count" -> rows.sortedByDescending { it.songCount }
        else -> rows.sortedBy { it.name.lowercase() }
    }
