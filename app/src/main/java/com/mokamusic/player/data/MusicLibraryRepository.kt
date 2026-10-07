package com.mokamusic.player.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mokamusic.player.model.MusicTrack
import com.mokamusic.player.audio.LoudnessAnalysisStore
import com.mokamusic.player.metadata.OnlineMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicLibraryRepository(private val context: Context) {
    private val embeddedReader = EmbeddedMetadataReader(context)
    private val cache = MusicLibraryCache(context)
    private val onlineMetadata = OnlineMetadataStore(context)
    private val loudnessStore = LoudnessAnalysisStore(context)
    private val statePrefs = context.applicationContext.getSharedPreferences("moka_library_state", Context.MODE_PRIVATE)

    suspend fun loadCached(): List<MusicTrack> = cache.load()

    suspend fun isCacheStale(): Boolean = withContext(Dispatchers.IO) {
        val saved = statePrefs.getString("media_store_version", null) ?: return@withContext true
        val current = runCatching { MediaStore.getVersion(context) }.getOrNull() ?: return@withContext false
        saved != current
    }

    suspend fun scan(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()

        val tracks = mutableListOf<MusicTrack>()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val displayName = cursor.getString(nameColumn).orEmpty()
                val mediaTitle = cursor.getString(titleColumn).usable()
                val mediaArtist = cursor.getString(artistColumn).usable()
                val mediaAlbum = cursor.getString(albumColumn).usable()
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null

                // Direct file tags are authoritative. MediaStore is discovery + fallback only.
                val direct = embeddedReader.read(uri, displayName, includeArtwork = false)
                val inferred = inferFromPath(displayName, relativePath)

                val title = UnicodeText.display(
                    direct.title.usable()
                        ?: mediaTitle
                        ?: inferred.title
                        ?: displayName.substringBeforeLast('.').ifBlank { "Unknown track" }
                ) ?: "Unknown track"
                val artist = UnicodeText.display(
                    direct.artist.usable()
                        ?: mediaArtist
                        ?: inferred.artist
                        ?: "Unknown artist"
                ) ?: "Unknown artist"
                val album = UnicodeText.display(
                    direct.album.usable()
                        ?: mediaAlbum
                        ?: inferred.album
                        ?: "Unknown album"
                ) ?: "Unknown album"

                val enrichment = onlineMetadata.get(
                    direct.albumArtist.usable() ?: artist,
                    album
                )
                val analysisProbe = MusicTrack(
                    id = id,
                    uri = uri,
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = cursor.getLong(albumIdColumn),
                    durationMs = cursor.getLong(durationColumn),
                    mimeType = cursor.getString(mimeColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                    relativePath = relativePath,
                    dateAddedEpochSeconds = cursor.getLong(dateAddedColumn)
                )
                val loudness = loudnessStore.get(analysisProbe)

                tracks += MusicTrack(
                    id = id,
                    uri = uri,
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = cursor.getLong(albumIdColumn),
                    durationMs = cursor.getLong(durationColumn),
                    mimeType = cursor.getString(mimeColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                    relativePath = relativePath,
                    dateAddedEpochSeconds = cursor.getLong(dateAddedColumn),
                    albumArtist = direct.albumArtist.usable(),
                    trackNumber = direct.trackNumber ?: inferred.trackNumber,
                    discNumber = direct.discNumber,
                    year = direct.year.usable() ?: enrichment?.releaseDate?.substringBefore('-')?.takeIf { it.isNotBlank() },
                    genre = direct.genre.usable() ?: enrichment?.genres?.firstOrNull(),
                    sampleRateHz = direct.sampleRateHz,
                    bitDepth = direct.bitDepth,
                    channelCount = direct.channelCount,
                    normalizationGainDb = direct.normalizationGainDb ?: loudness?.trackGainDb,
                    albumNormalizationGainDb = direct.albumNormalizationGainDb ?: loudness?.albumGainDb,
                    musicBrainzReleaseGroupId = enrichment?.releaseGroupId,
                    onlineArtworkUrl = enrichment?.coverArtUrl
                )
            }
        }

        val textComparator = UnicodeText.comparator()
        val sorted = tracks.sortedWith(Comparator { a, b ->
            val aArtist = a.albumArtist?.takeIf(String::isNotBlank) ?: a.artist
            val bArtist = b.albumArtist?.takeIf(String::isNotBlank) ?: b.artist
            textComparator.compare(aArtist, bArtist)
                .takeIf { it != 0 }
                ?: textComparator.compare(a.album, b.album).takeIf { it != 0 }
                ?: compareValues(a.discNumber ?: 0, b.discNumber ?: 0).takeIf { it != 0 }
                ?: compareValues(a.trackNumber ?: Int.MAX_VALUE, b.trackNumber ?: Int.MAX_VALUE).takeIf { it != 0 }
                ?: textComparator.compare(a.title, b.title)
        })
        val cacheSaved = runCatching { cache.save(sorted) }.isSuccess
        if (cacheSaved) {
            runCatching { MediaStore.getVersion(context) }.getOrNull()?.let { version ->
                statePrefs.edit().putString("media_store_version", version).apply()
            }
        } else {
            // Never advertise a cache generation as current when its AtomicFile commit failed.
            // Keeping this marker stale guarantees the next launch offers/retries reconciliation.
            statePrefs.edit().remove("media_store_version").apply()
        }
        sorted
    }
}

private data class PathMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null
)

/** Conservative inference only; it fills blanks but never overrides real embedded tags. */
private fun inferFromPath(displayName: String, relativePath: String?): PathMetadata {
    val base = displayName.substringBeforeLast('.').trim()
    val trackMatch = Regex("""^\s*(\d{1,3})\s*[-._ ]+\s*(.+)$""").matchEntire(base)
    val track = trackMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val stripped = trackMatch?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { base }

    val parts = stripped.split(Regex("\\s+-\\s+"), limit = 3).map { it.trim() }.filter { it.isNotBlank() }
    val pathParts = relativePath.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
    val albumFolder = pathParts.lastOrNull()?.takeUnless { it.isGenericFolder() }
    val artistFolder = pathParts.dropLast(1).lastOrNull()?.takeUnless { it.isGenericFolder() }

    return when {
        parts.size >= 2 -> PathMetadata(
            title = parts.last(),
            artist = parts.dropLast(1).joinToString(" - ").takeIf { artistFolder == null },
            album = albumFolder,
            trackNumber = track
        ).let { it.copy(artist = it.artist ?: artistFolder) }
        else -> PathMetadata(title = stripped, artist = artistFolder, album = albumFolder, trackNumber = track)
    }
}

private fun String?.usable(): String? = UnicodeText.display(this)?.takeUnless {
    it.equals("<unknown>", true) || it.equals("unknown", true) || it.equals("unknown artist", true) || it.equals("unknown album", true)
}

private fun String.isGenericFolder(): Boolean = lowercase() in setOf(
    "music", "audio", "downloads", "download", "media", "internal storage", "sdcard"
)
