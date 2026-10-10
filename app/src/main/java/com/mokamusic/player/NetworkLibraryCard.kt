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
import com.mokamusic.player.network.NetworkStreamQuality
import com.mokamusic.player.network.SubsonicLibraryClient
import com.mokamusic.player.network.SubsonicHttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
    var streamQuality by mutableStateOf(
        runCatching {
            NetworkStreamQuality.valueOf(prefs.getString("stream_quality", "ORIGINAL") ?: "ORIGINAL")
        }.getOrDefault(NetworkStreamQuality.ORIGINAL)
    )
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
    var trackBatchFailed by mutableStateOf(false)
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
    var streamCheckBusy by mutableStateOf(false)
        private set
    var streamCheckStatus by mutableStateOf<String?>(null)
        private set
    var playbackStatus by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
        private set
    var hasMoreAlbums by mutableStateOf(false)
        private set
    var albumBatchFailed by mutableStateOf(false)
        private set
    private var nextAlbumOffset = 0
    private var autoReconnectAttempted = false

    /** Keep request URLs, credentials and token/salt out of failure messages. */
    private fun failureHint(e: Exception): String = when (e) {
        is SubsonicHttpException -> when (e.statusCode) {
            401, 403 -> "Navidrome denied access (HTTP ${e.statusCode}); check your account."
            429 -> "Navidrome is rate-limiting requests (HTTP 429)."
            else -> "Navidrome returned HTTP ${e.statusCode}."
        }
        is SocketTimeoutException -> "Navidrome timed out; check Tailscale and server load."
        is UnknownHostException -> "Can't resolve the server hostname; check Tailscale or DNS."
        is SSLException -> "HTTPS certificate/connection error."
        is IOException -> "Network connection failed; check Wi-Fi, cellular or Tailscale."
        else -> "Navidrome returned an unexpected API response."
    }

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

    fun updateStreamQuality(value: NetworkStreamQuality) {
        streamQuality = value
        prefs.edit().putString("stream_quality", value.name).apply()
        client?.streamQuality = value
        streamCheckStatus = null
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
            val candidate = SubsonicLibraryClient(target, account, secret).apply {
                streamQuality = this@NetworkLibraryState.streamQuality
            }
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
            trackBrowsingComplete = first.isEmpty()
            trackBatchFailed = false
            albumBatchFailed = false
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
        } catch (e: Exception) {
            status = "Connection failed: ${failureHint(e)}"
        } finally {
            busy = false
        }
        // Populate Tracks on connect, without requiring a separate button press.
        if (client != null && category == 0 && browsedTracks.isEmpty()) loadTrackBatch()
    }

    /** Check whether Navidrome can serve bytes for an indexed track. */
    fun testStream() {
        val active = client ?: run {
            streamCheckStatus = "Connect Navidrome first."
            return
        }
        if (streamCheckBusy) return
        streamCheckBusy = true
        streamCheckStatus = "Testing Navidrome audio access…"
        appScope.launch {
            try {
                val song = browsedTracks.firstOrNull() ?: songs.firstOrNull()
                    ?: genreSongs.firstOrNull() ?: searchedTracks.firstOrNull()
                    ?: withContext(Dispatchers.IO) { active.randomSongs(1).firstOrNull() }
                if (song == null) {
                    streamCheckStatus = "No server tracks found to test."
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { active.checkStream(song.id, song.mimeType) }
                if (client === active) {
                    streamCheckStatus = result.message +
                        if (result.playable) " Try playing a song in Moka."
                        else " Check Navidrome file permissions and proxy settings."
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (client === active) {
                    streamCheckStatus = "Stream check failed: ${failureHint(e)}"
                }
            } finally {
                streamCheckBusy = false
            }
        }
    }

    /** Explicit network refresh; only replace the currently displayed catalog on success. */
    fun refresh() {
        val active = client ?: return
        if (busy) return
        busy = true
        status = "Refreshing Navidrome library…"
        appScope.launch {
            var refreshed = false
            try {
                val first = withContext(Dispatchers.IO) { active.albums(0, PAGE_SIZE) }
                if (client !== active) return@launch
                albums = first.distinctBy { it.id }
                hasMoreAlbums = first.size == PAGE_SIZE
                nextAlbumOffset = first.size
                albumBatchFailed = false
                browsedTracks = emptyList()
                trackAlbumCursor = 0
                trackBrowsingComplete = first.isEmpty()
                trackBatchFailed = false
                searchedTracks = emptyList()
                activeTrackSearch = ""
                hasMoreSearch = false
                selectedAlbum = null
                selectedArtist = null
                selectedGenre = null
                songs = emptyList()
                artistAlbums = emptyList()
                genreSongs = emptyList()
                artists = emptyList()
                genres = emptyList()
                status = if (first.isEmpty()) "Connected, but no albums were returned."
                         else "Refreshed · ${albums.size} albums"
                refreshed = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "Refresh failed: ${failureHint(e)}" }
            finally {
                busy = false
            }
            if (refreshed && client === active && category == 0 && !trackBrowsingComplete) {
                loadTrackBatch()
            }
        }
    }

    fun loadMore() {
        val active = client ?: return
        if (busy || !hasMoreAlbums) return
        // Claim the request before dispatch so two scroll effects cannot request the same page.
        busy = true
        albumBatchFailed = false
        val offset = nextAlbumOffset
        status = "Loading more albums…"
        appScope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    active.albums(offset = offset, size = PAGE_SIZE)
                }
                if (client !== active) return@launch
                albums = (albums + next).distinctBy { it.id }
                nextAlbumOffset = offset + next.size
                hasMoreAlbums = next.size == PAGE_SIZE
                status = "${albums.size} albums loaded"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                albumBatchFailed = true
                status = "Couldn't load albums: ${failureHint(e)} Tap Load more to retry."
            } finally {
                busy = false
            }
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

    /** Incrementally browse the server, preserving completed albums if one request fails.
     * Four albums at a time keeps cellular requests short and leaves the UI responsive.
     */
    fun loadTrackBatch() {
        val active = client ?: return
        if (busy || trackBrowsingComplete) return
        busy = true
        trackBatchFailed = false
        status = "Loading tracks from Navidrome…"
        appScope.launch {
            try {
                var catalog = albums
                var offset = nextAlbumOffset
                var more = hasMoreAlbums
                var cursor = trackAlbumCursor
                var failure: Exception? = null
                val collected = withContext(Dispatchers.IO) {
                    val batch = mutableListOf<NetworkSong>()
                    var loaded = 0
                    while (loaded < 4) {
                        if (cursor >= catalog.size && more) {
                            val page = try {
                                active.albums(offset, PAGE_SIZE)
                            } catch (e: Exception) {
                                failure = e
                                break
                            }
                            offset += page.size
                            more = page.size == PAGE_SIZE
                            catalog = (catalog + page).distinctBy { it.id }
                        }
                        if (cursor >= catalog.size) break
                        try {
                            batch += active.songs(catalog[cursor].id)
                            cursor++
                            loaded++
                        } catch (e: Exception) {
                            // Keep earlier albums; retry the failed album in the next batch.
                            failure = e
                            break
                        }
                    }
                    batch
                }
                if (client !== active) return@launch
                albums = catalog
                hasMoreAlbums = more
                nextAlbumOffset = offset
                trackAlbumCursor = cursor
                browsedTracks = (browsedTracks + collected).distinctBy { it.id }
                trackBrowsingComplete = cursor >= catalog.size && !more
                trackBatchFailed = failure != null
                status = if (failure != null) {
                    "Loaded ${browsedTracks.size} tracks; stopped at album ${cursor + 1}: " +
                        failureHint(failure!!) + " Tap Load more to retry."
                } else if (browsedTracks.isEmpty()) {
                    "No server tracks found yet."
                } else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                trackBatchFailed = true
                status = "Couldn't load tracks: ${failureHint(e)} Tap Load more to retry."
            } finally {
                busy = false
            }
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
            catch (e: Exception) { status = "Couldn't search tracks: ${failureHint(e)}" }
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            status = "Couldn't load album: ${failureHint(e)}"
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
        streamCheckStatus = null
        playbackStatus = null
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
        trackBatchFailed = false
        albumBatchFailed = false
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

