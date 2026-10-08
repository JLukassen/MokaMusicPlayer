package com.mokamusic.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mokamusic.player.network.NetworkAlbum
import com.mokamusic.player.network.NetworkSong
import com.mokamusic.player.network.SubsonicLibraryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opt-in network collection. The password/client are ephemeral Compose state;
 * credentials are neither saved nor sent anywhere except the selected HTTPS host.
 */
@Composable
internal fun NetworkLibraryCard(onPlay: (NetworkSong, android.net.Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<SubsonicLibraryClient?>(null) }
    var albums by remember { mutableStateOf<List<NetworkAlbum>>(emptyList()) }
    var songs by remember { mutableStateOf<List<NetworkSong>>(emptyList()) }
    var selected by remember { mutableStateOf<NetworkAlbum?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Network music · experimental", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Browse a Navidrome/Subsonic server over HTTPS. " +
                "Password stays in memory; this beta doesn't sync or download files for offline use.",
                style = MaterialTheme.typography.bodySmall)
            if (client == null) {
                OutlinedTextField(value = server, onValueChange = { server = it },
                    label = { Text("Server HTTPS URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        status = "Connecting…"
                        scope.launch {
                            val result = runCatching {
                                val candidate = SubsonicLibraryClient(server, username, password)
                                val list = withContext(Dispatchers.IO) {
                                    candidate.ping()
                                    candidate.albums()
                                }
                                candidate to list
                            }
                            result.onSuccess { (session, fetched) ->
                                client = session
                                albums = fetched
                                password = ""
                                status = "Connected · showing up to 100 albums"
                            }.onFailure { status = "Connection failed: ${it.message}" }
                            busy = false
                        }
                    }) { Text(if (busy) "Connecting…" else "Connect") }
            } else {
                Row {
                    OutlinedButton(onClick = {
                        client = null
                        albums = emptyList()
                        songs = emptyList()
                        selected = null
                        password = ""
                        status = "Disconnected"
                    }) { Text("Disconnect") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { client!!.albums() }
                            }
                            result.onSuccess { albums = it; status = "Album list refreshed" }
                                .onFailure { status = "Refresh failed: ${it.message}" }
                            busy = false
                        }
                    }) { Text("Refresh") }
                }
                if (selected != null) {
                    Text("Album: ${selected!!.name}", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selected = null; songs = emptyList() }) { Text("All albums") }
                    for (song in songs.take(100)) {
                        TextButton(
                            enabled = !busy,
                            onClick = { client?.let { session -> onPlay(song, session.streamUri(song.id)) } }
                        ) { Text("${song.title} · ${song.artist}") }
                    }
                } else {
                    Column(Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
                        for (album in albums) {
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                status = "Loading ${album.name}…"
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) { client!!.songs(album.id) }
                                    }
                                    result.onSuccess { songs = it; selected = album; status = null }
                                        .onFailure { status = "Album failed: ${it.message}" }
                                    busy = false
                                }
                            }) { Text("${album.name} — ${album.artist}") }
                        }
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Experimental: only a single page of server albums, no offline sync. " +
                "Streaming uses Media3; DSP parity and car playback still need testing.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
