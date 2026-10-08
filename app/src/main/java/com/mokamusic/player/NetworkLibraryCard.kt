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
 * Lives in MokaViewModel instead of transient Composable state.
 * Server URL and username persist as typed; an optional encrypted password
 * is kept with Android Keystore outside of backup files.
 */
@Stable
internal class NetworkLibraryState(
    context: Context,
    private val appScope: kotlinx.coroutines.CoroutineScope
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("moka_network_library", Context.MODE_PRIVATE)

    private val credentialStore = com.mokamusic.player.network.NetworkCredentialStore(context)
    var server by mutableStateOf(prefs.getString("server_url", "").orEmpty())
        private set
    var username by mutableStateOf(prefs.getString("username", "").orEmpty())
        private set
    var password by mutableStateOf("")
    var rememberLogin by mutableStateOf(prefs.getBoolean("remember_login", true))
        private set
    var search by mutableStateOf("")
    var category by mutableIntStateOf(0) // 0 Tracks, 1 Albums, 2 Artists, 3 Genres
        private set
    var artists by mutableStateOf<List<com.mokamusic.player.network.NetworkArtist>>(emptyList())
        private set
    var genres by mutableStateOf<List<com.mokamusic.player.network.NetworkGenre>>(emptyList())
        private set
    var selectedArtist by mutableStateOf<com.mokamusic.player.network.NetworkArtist?>(null)
        private set
    var artistAlbums by mutableStateOf<List<NetworkAlbum>>(emptyList())
        private set
    var selectedGenre by mutableStateOf<com.mokamusic.player.network.NetworkGenre?>(null)
        private set
    var genreSongs by mutableStateOf<List<NetworkSong>>(emptyList())
        private set
    var hasMoreGenreSongs by mutableStateOf(false)
        private set
    var browsedTracks by mutableStateOf<List<NetworkSong>>(emptyList())
        private set
    var trackAlbumCursor by mutableIntStateOf(0)
        private set
    var trackBrowsingComplete by mutableStateOf(false)
        private set
    var searchedTracks by mutableStateOf<List<NetworkSong>>(emptyList())
        private set
    var activeTrackSearch by mutableStateOf("")
        private set
    var hasMoreSearch by mutableStateOf(false)
        private set

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
    // Mixed shuffle is independent of the library browsing/paging spinner.
    var mixedShuffleBusy by mutableStateOf(false)
    var mixedShuffleStatus by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
        private set
    var hasMoreAlbums by mutableStateOf(false)
        private set
    private var nextAlbumOffset = 0
    private var autoReconnectAttempted = false

    val savedLoginAvailable: Boolean
        get() = credentialStore.isSaved() &&
            prefs.getString("saved_server_url", null) == server.trim().trimEnd('/') &&
            prefs.getString("saved_username", null) == username.trim()

    fun updateServer(value: String) {
        server = value
        prefs.edit().putString("server_url", value).apply()
    }

    fun updateUsername(value: String) {
        username = value
        prefs.edit().putString("username", value).apply()
    }

    fun updateRememberLogin(value: Boolean) {
        rememberLogin = value
        prefs.edit().putBoolean("remember_login", value).apply()
        if (!value) clearSavedLogin()
    }

    fun onScreenOpened() {
        if (autoReconnectAttempted) return
        autoReconnectAttempted = true
        if (client == null && savedLoginAvailable) connect()
    }

    fun connect() {
        if (busy || client != null) return
        appScope.launch { connectInternal() }
    }

    private suspend fun connectInternal() {
        if (busy) return
        busy = true
        status = "Connecting…"
        try {
            val target = server.trim().trimEnd('/')
            val account = username.trim()
            val secret = if (password.isNotBlank()) password else {
                withContext(Dispatchers.IO) {
                    if (!savedLoginAvailable) null
                    else credentialStore.load()?.takeIf {
                        it.server == target && it.username == account
                    }?.password
                }
            }
            if (target.isBlank() || account.isBlank() || secret.isNullOrBlank()) {
                status = "Enter your password. The saved login may no longer be available."
                return
            }
            val candidate = SubsonicLibraryClient(target, account, secret)
            val first = withContext(Dispatchers.IO) {
                candidate.ping()
                candidate.albums(offset = 0, size = PAGE_SIZE)
            }
            client = candidate
            albums = first.distinctBy { it.id }
            selectedAlbum = null
            songs = emptyList()
            artists = emptyList()
            genres = emptyList()
            artistAlbums = emptyList()
            selectedArtist = null
            selectedGenre = null
            genreSongs = emptyList()
            browsedTracks = emptyList()
            trackAlbumCursor = 0
            trackBrowsingComplete = false
            searchedTracks = emptyList()
            activeTrackSearch = ""
            hasMoreSearch = false
            hasMoreAlbums = first.size == PAGE_SIZE
            nextAlbumOffset = first.size
            updateServer(target)
            updateUsername(account)
            val saved = if (rememberLogin) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        credentialStore.save(
                            com.mokamusic.player.network.SavedNetworkLogin(target, account, secret)
                        )
                    }
                    prefs.edit().putString("saved_server_url", target)
                        .putString("saved_username", account).apply()
                }.isSuccess
            } else false
            password = ""
            val message = if (first.isEmpty()) "Connected, but the server returned no albums."
                     else "Connected · ${albums.size} albums"
            status = if (rememberLogin && !saved) {
                "$message · Login wasn't saved (device keystore unavailable)."
            } else message
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Do not expose Subsonic token/salt URLs or passwords in the UI.
            status = "Connection failed. Check HTTPS, login and server access."
        } finally {
            busy = false
        }
        // Populate Tracks on connect, without requiring a separate button press.
        if (client != null && category == 0 && browsedTracks.isEmpty()) loadTrackBatch()
    }

    fun refresh() {
        if (busy || client == null) return
        appScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
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

    fun loadMore() {
        if (busy || client == null || !hasMoreAlbums) return
        appScope.launch { loadMoreInternal() }
    }

    private suspend fun loadMoreInternal() {
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

    fun showCategory(next: Int) {
        if (next !in 0..3) return
        category = next
        selectedAlbum = null
        selectedArtist = null
        selectedGenre = null
        songs = emptyList()
        search = ""
        when (next) {
            0 -> if (browsedTracks.isEmpty() && !trackBrowsingComplete) loadTrackBatch()
            2 -> if (artists.isEmpty()) loadArtists()
            3 -> if (genres.isEmpty()) loadGenres()
        }
    }

    fun loadArtists() {
        val active = client ?: return
        if (busy) return
        appScope.launch {
            busy = true
            status = "Loading artists…"
            try {
                artists = withContext(Dispatchers.IO) { active.artists() }
                status = if (artists.isEmpty()) "No artists found." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't load artists. Try again." }
            finally { busy = false }
        }
    }

    fun loadGenres() {
        val active = client ?: return
        if (busy) return
        appScope.launch {
            busy = true
            status = "Loading genres…"
            try {
                genres = withContext(Dispatchers.IO) { active.genres() }
                status = if (genres.isEmpty()) "No genres found." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't load genres. Try again." }
            finally { busy = false }
        }
    }

    fun openArtist(artist: com.mokamusic.player.network.NetworkArtist) {
        val active = client ?: return
        if (busy) return
        appScope.launch {
            busy = true
            status = "Loading artist albums…"
            try {
                val found = withContext(Dispatchers.IO) { active.artistAlbums(artist.id) }
                artistAlbums = found
                selectedArtist = artist
                selectedAlbum = null
                songs = emptyList()
                status = if (found.isEmpty()) "No albums for this artist." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't load artist albums." }
            finally { busy = false }
        }
    }

    fun backFromArtist() {
        selectedArtist = null
        selectedAlbum = null
        songs = emptyList()
        status = null
    }

    fun openGenre(genre: com.mokamusic.player.network.NetworkGenre) {
        selectedGenre = genre
        genreSongs = emptyList()
        hasMoreGenreSongs = true
        loadMoreGenreSongs()
    }

    fun backFromGenre() {
        selectedGenre = null
        genreSongs = emptyList()
        hasMoreGenreSongs = false
        status = null
    }

    fun loadMoreGenreSongs() {
        val active = client ?: return
        val genre = selectedGenre ?: return
        if (busy || !hasMoreGenreSongs) return
        appScope.launch {
            busy = true
            status = "Loading genre tracks…"
            try {
                val page = withContext(Dispatchers.IO) {
                    active.genreSongs(genre.name, genreSongs.size, PAGE_SIZE)
                }
                genreSongs = (genreSongs + page).distinctBy { it.id }
                hasMoreGenreSongs = page.size == PAGE_SIZE
                status = if (genreSongs.isEmpty()) "No tracks in this genre." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't load genre tracks." }
            finally { busy = false }
        }
    }

    /** Incrementally browse all tracks, eight album requests per explicit batch. */
    fun loadTrackBatch() {
        val active = client ?: return
        if (busy || trackBrowsingComplete) return
        appScope.launch {
            busy = true
            status = "Loading tracks from the server…"
            try {
                var catalog = albums
                var offset = nextAlbumOffset
                var more = hasMoreAlbums
                var cursor = trackAlbumCursor
                val collected = withContext(Dispatchers.IO) {
                    val batch = mutableListOf<NetworkSong>()
                    var loaded = 0
                    while (loaded < 8) {
                        if (cursor >= catalog.size && more) {
                            val page = active.albums(offset, PAGE_SIZE)
                            offset += page.size
                            more = page.size == PAGE_SIZE
                            catalog = (catalog + page).distinctBy { it.id }
                        }
                        if (cursor >= catalog.size) break
                        batch += active.songs(catalog[cursor].id)
                        cursor++
                        loaded++
                    }
                    batch
                }
                albums = catalog
                hasMoreAlbums = more
                nextAlbumOffset = offset
                trackAlbumCursor = cursor
                browsedTracks = (browsedTracks + collected).distinctBy { it.id }
                trackBrowsingComplete = cursor >= catalog.size && !more
                status = if (browsedTracks.isEmpty()) "No server tracks found yet." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't load tracks. Retry this batch." }
            finally { busy = false }
        }
    }

    fun searchServerTracks(query: String, more: Boolean = false) {
        val active = client ?: return
        val trimmed = query.trim()
        if (busy) return
        if (trimmed.isEmpty()) {
            activeTrackSearch = ""
            searchedTracks = emptyList()
            hasMoreSearch = false
            return
        }
        if (more && (!hasMoreSearch || trimmed != activeTrackSearch)) return
        appScope.launch {
            busy = true
            status = "Searching server tracks…"
            try {
                val page = withContext(Dispatchers.IO) {
                    active.searchSongs(trimmed, if (more) searchedTracks.size else 0, PAGE_SIZE)
                }
                searchedTracks = (if (more) searchedTracks + page else page).distinctBy { it.id }
                activeTrackSearch = trimmed
                hasMoreSearch = page.size == PAGE_SIZE
                status = if (searchedTracks.isEmpty()) "No matching server tracks." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Couldn't search server tracks." }
            finally { busy = false }
        }
    }

    fun openAlbum(album: NetworkAlbum) {
        if (busy || client == null) return
        appScope.launch { openAlbumInternal(album) }
    }

    private suspend fun openAlbumInternal(album: NetworkAlbum) {
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
        autoReconnectAttempted = true
        client = null
        password = ""
        selectedAlbum = null
        albums = emptyList()
        songs = emptyList()
        artists = emptyList()
        genres = emptyList()
        selectedArtist = null
        artistAlbums = emptyList()
        selectedGenre = null
        genreSongs = emptyList()
        hasMoreGenreSongs = false
        browsedTracks = emptyList()
        trackAlbumCursor = 0
        trackBrowsingComplete = false
        searchedTracks = emptyList()
        activeTrackSearch = ""
        hasMoreSearch = false
        hasMoreAlbums = false
        nextAlbumOffset = 0
        search = ""
        status = "Disconnected. Saved login is retained."
    }

    fun forgetServer() {
        disconnect()
        clearSavedLogin()
        server = ""
        username = ""
        password = ""
        prefs.edit().remove("server_url").remove("username").apply()
        status = "Server address and saved login forgotten."
    }

    private fun clearSavedLogin() {
        credentialStore.clear()
        prefs.edit().remove("saved_server_url").remove("saved_username").apply()
    }

    companion object {
        const val PAGE_SIZE = 100
    }
}

