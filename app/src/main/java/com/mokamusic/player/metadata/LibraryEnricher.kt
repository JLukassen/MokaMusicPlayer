package com.mokamusic.player.metadata

import android.content.Context
import android.util.Log
import com.mokamusic.player.data.UnicodeText
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class EnrichmentProgress(
    val completed: Int,
    val total: Int,
    val matched: Int,
    val failed: Int,
    val current: String? = null
)

class LibraryEnricher(context: Context) {
    private val store = OnlineMetadataStore(context)
    private val repository = MusicBrainzRepository()

    suspend fun enrichAlbums(
        tracks: List<MusicTrack>,
        forceRefresh: Boolean = false,
        onProgress: (EnrichmentProgress) -> Unit = {}
    ): EnrichmentProgress = withContext(Dispatchers.IO) {
        val albums = tracks
            .filter { it.album.isNotBlank() && !it.album.equals("Unknown album", true) }
            .groupBy {
                OnlineMetadataStore.keyFor(
                    it.albumArtist?.takeIf(String::isNotBlank) ?: it.artist,
                    it.album
                )
            }
            .values
            .mapNotNull { group -> group.firstOrNull() }
            .filter { !it.artist.equals("Unknown artist", true) }
            .sortedWith(compareBy({ UnicodeText.key(it.artist) }, { UnicodeText.key(it.album) }))

        var completed = 0
        var matched = 0
        var failed = 0
        onProgress(EnrichmentProgress(0, albums.size, 0, 0))

        for (track in albums) {
            coroutineContext.ensureActive()
            val artist = track.albumArtist?.takeIf(String::isNotBlank) ?: track.artist
            val existing = store.get(artist, track.album)
            if (existing != null && !forceRefresh) {
                completed++
                matched++
                onProgress(EnrichmentProgress(completed, albums.size, matched, failed, track.album))
                continue
            }

            val result = repository.findBestAlbum(artist, track.album)
            result.fold(
                onSuccess = { value ->
                    if (value != null) {
                        store.put(value)
                        matched++
                    }
                },
                onFailure = { error ->
                    failed++
                    Log.w(TAG, "album enrichment failed artist=${artist.take(80)} album=${track.album.take(120)}", error)
                }
            )
            completed++
            onProgress(EnrichmentProgress(completed, albums.size, matched, failed, track.album))
        }
        Log.i(TAG, "enrichment complete total=${albums.size} matched=$matched failed=$failed forceRefresh=$forceRefresh")
        EnrichmentProgress(completed, albums.size, matched, failed)
    }

    private companion object {
        const val TAG = "MokaMetadata"
    }
}
