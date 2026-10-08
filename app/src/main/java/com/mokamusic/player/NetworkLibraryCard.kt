package com.mokamusic.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mokamusic.player.network.NetworkAlbum
import com.mokamusic.player.network.NetworkSong
import com.mokamusic.player.network.SubsonicLibraryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kept at MokaApp scope so switching Library tabs or opening Now Playing
 * does not discard the active Subsonic session and force another login.
 * Only server URL and username are persisted. Password stays in memory
 * inside the active client and is dropped on disconnect/process death.
 */
@Stable
internal class NetworkLibraryState(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("moka_network_library", Context.MODE_PRIVATE)

    var server by mutableStateOf(prefs.getString("server_url", "").orEmpty())
    var username by mutableStateOf(prefs.getString("username", "").orEmpty())
    var password by mutableStateOf("")
    var search by mutableStateOf("")

    var client by mutableStateOf<SubsonicLibraryClient?>(null)
        private set
    var albums by mutableStateOf<List<NetworkAlbum>>(emptyList())
        private set
    var songs by mutableStateOf<List<NetworkSong>>(emptyList())
        private set
    var selectedAlbum by mutableStateOf<NetworkAlbum?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var hasMoreAlbums by mutableStateOf(false)
        private set
    private var nextAlbumOffset = 0

    suspend fun connect() {
        if (busy || server.isBlank() || username.isBlank() || password.isBlank()) return
        busy = true
        status = "Connecting…"
        try {
            val candidate = SubsonicLibraryClient(server, username, password)
            val first = withContext(Dispatchers.IO) {
                candidate.ping()
                candidate.albums(offset = 0, size = PAGE_SIZE)
            }
            client = candidate
            albums = first.distinctBy { it.id }
            selectedAlbum = null
            songs = emptyList()
            hasMoreAlbums = first.size == PAGE_SIZE
            nextAlbumOffset = first.size
            password = ""
            prefs.edit().putString("server_url", server.trim())
                .putString("username", username.trim()).apply()
            status = if (first.isEmpty()) "Connected, but the server returned no albums."
                     else "Connected · ${albums.size} albums"
        } catch (e: Exception) {
            // Avoid displaying raw request URLs containing Subsonic authentication tokens.
            status = "Connection failed. Check the HTTPS address, login and server access."
        } finally {
            busy = false
        }
    }

    suspend fun refresh() {
        val active = client ?: return
        if (busy) return
        busy = true
        status = "Refreshing albums…"
        try {
            val first = withContext(Dispatchers.IO) { active.albums(0, PAGE_SIZE) }
            albums = first.distinctBy { it.id }
            hasMoreAlbums = first.size == PAGE_SIZE
            nextAlbumOffset = first.size
            status = "Refreshed · ${albums.size} albums"
        } catch (e: Exception) {
            status = "Couldn't refresh albums. Check the server connection."
        } finally {
            busy = false
        }
    }

    suspend fun loadMore() {
        val active = client ?: return
        if (busy || !hasMoreAlbums) return
        busy = true
        val offset = nextAlbumOffset
        status = "Loading more albums…"
        try {
            val next = withContext(Dispatchers.IO) {
                active.albums(offset = offset, size = PAGE_SIZE)
            }
            albums = (albums + next).distinctBy { it.id }
            nextAlbumOffset += next.size
            hasMoreAlbums = next.size == PAGE_SIZE
            status = "${albums.size} albums loaded"
        } catch (e: Exception) {
            status = "Couldn't load more albums. Try again."
        } finally {
            busy = false
        }
    }

    suspend fun openAlbum(album: NetworkAlbum) {
        val active = client ?: return
        if (busy) return
        busy = true
        status = "Loading ${album.name}…"
        try {
            val tracks = withContext(Dispatchers.IO) { active.songs(album.id) }
            selectedAlbum = album
            songs = tracks
            status = if (tracks.isEmpty()) "This album has no playable tracks." else null
        } catch (e: Exception) {
            status = "Couldn't load this album. Try again."
        } finally {
            busy = false
        }
    }

    fun backToAlbums() {
        selectedAlbum = null
        songs = emptyList()
        status = null
    }

    fun disconnect() {
        client = null
        password = ""
        selectedAlbum = null
        albums = emptyList()
        songs = emptyList()
        hasMoreAlbums = false
        nextAlbumOffset = 0
        search = ""
        status = "Disconnected"
    }

    companion object {
        const val PAGE_SIZE = 100
    }
}

/** Full-height network library browser, alongside Tracks/Albums/Artists/Genres. */
@Composable
internal fun NetworkLibraryScreen(
    state: NetworkLibraryState,
    onPlay: (NetworkSong, Uri) -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Network Music",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (state.client == null) "Navidrome / Subsonic over HTTPS"
                    else "${state.albums.size} albums loaded · streaming",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.client != null) {
                TextButton(onClick = state::disconnect, enabled = !state.busy) {
                    Text("Disconnect")
                }
            }
        }

        state.status?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.client == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Connect to your music server. The server address and username " +
                        "are remembered; the password is not saved.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = state.server, onValueChange = { state.server = it },
                    label = { Text("Server HTTPS URL") },
                    placeholder = { Text("https://your-server.ts.net") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.username, onValueChange = { state.username = it },
                    label = { Text("Username") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.password, onValueChange = { state.password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !state.busy && state.server.isNotBlank() &&
                        state.username.isNotBlank() && state.password.isNotBlank(),
                    onClick = { scope.launch { state.connect() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.busy) "Connecting…" else "Connect to library")
                }
                Text(
                    "Streaming is experimental. Offline downloads and DSP parity " +
                        "still need device testing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (state.selectedAlbum != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = state::backToAlbums) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to albums")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.selectedAlbum?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${state.selectedAlbum?.artist.orEmpty()} · ${state.songs.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.songs, key = { it.id }) { song ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.busy) {
                            state.client?.let { onPlay(song, it.streamUri(song.id)) }
                        },
                        headlineContent = {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        trailingContent = { Text(song.durationSeconds.takeIf { it > 0 }?.let {
                            "${it / 60}:${(it % 60).toString().padStart(2, '0')}"
                        }.orEmpty()) }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            OutlinedTextField(
                value = state.search,
                onValueChange = { state.search = it },
                placeholder = { Text("Search loaded albums or artists") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                trailingIcon = {
                    TextButton(
                        onClick = { scope.launch { state.refresh() } },
                        enabled = !state.busy
                    ) { Text("Refresh") }
                }
            )
            val filtered = remember(state.albums, state.search) {
                if (state.search.isBlank()) state.albums
                else state.albums.filter {
                    it.name.contains(state.search, ignoreCase = true) ||
                        it.artist.contains(state.search, ignoreCase = true)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { album ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.busy) {
                            scope.launch { state.openAlbum(album) }
                        },
                        headlineContent = {
                            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text("${album.artist} · ${album.songCount} songs",
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Default.Album, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                    )
                    HorizontalDivider()
                }
                if (state.hasMoreAlbums) {
                    item(key = "more") {
                        OutlinedButton(
                            onClick = { scope.launch { state.loadMore() } },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Text(if (state.busy) "Loading…" else "Load more albums")
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (state.search.isNotBlank()) "No matching albums loaded."
                            else "No albums found. Check that Navidrome has scanned your music.",
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }
        }
    }
}
