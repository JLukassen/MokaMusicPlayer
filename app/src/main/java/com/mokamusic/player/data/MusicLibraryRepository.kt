package com.mokamusic.player.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mokamusic.player.audio.LoudnessAnalysisStore
import com.mokamusic.player.metadata.MetadataNamePreference
import com.mokamusic.player.metadata.MetadataNamePreferenceStore
import com.mokamusic.player.metadata.OnlineMetadataStore
import com.mokamusic.player.metadata.resolveAlbumName
import com.mokamusic.player.metadata.resolveArtistName
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryScanProgress(val completed: Int, val total: Int, val parsed: Int, val reused: Int, val current: String? = null)

class MusicLibraryRepository(private val context: Context) {
    private val embeddedReader = EmbeddedMetadataReader(context)
    private val cache = MusicLibraryCache(context)
    private val onlineMetadata = OnlineMetadataStore(context)
    private val loudnessStore = LoudnessAnalysisStore(context)
    private val namePreferenceStore = MetadataNamePreferenceStore(context)
    private val statePrefs = context.applicationContext.getSharedPreferences("moka_library_state", Context.MODE_PRIVATE)

    suspend fun loadCached(): List<MusicTrack> = cache.load()

    suspend fun isCacheStale(): Boolean = withContext(Dispatchers.IO) {
        val saved = statePrefs.getString(STORE_MARKER_KEY, null) ?: return@withContext true
        val current = currentStoreMarker() ?: return@withContext false
        saved != current
    }

    suspend fun scan(fullRescan: Boolean = false, onProgress: (LibraryScanProgress) -> Unit = {}): List<MusicTrack> = withContext(Dispatchers.IO) {
        val cachedById = if (fullRescan) emptyMap() else cache.load().associateBy { it.id }
        val preference = namePreferenceStore.load()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM); add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()

        val tracks = mutableListOf<MusicTrack>()
        val seenIds = HashSet<Long>()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        var completed = 0; var parsed = 0; var reused = 0
        var lastCheckpointAtMs = android.os.SystemClock.elapsedRealtime()
        var parsedAtLastCheckpoint = 0

        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val total = cursor.count
            onProgress(LibraryScanProgress(0, total, 0, 0))
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
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val displayName = cursor.getString(nameColumn).orEmpty()
                val durationMs = cursor.getLong(durationColumn)
                val sizeBytes = cursor.getLong(sizeColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                val mimeType = cursor.getString(mimeColumn)
                val albumId = cursor.getLong(albumIdColumn)

                seenIds += id
                val previous = cachedById[id]
                val reusable = previous != null &&
                    previous.displayName == displayName &&
                    previous.sizeBytes == sizeBytes &&
                    previous.durationMs == durationMs &&
                    previous.dateModifiedEpochSeconds == dateModified &&
                    previous.relativePath == relativePath

                val base = if (reusable) {
                    reused++
                    previous!!.copy(
                        uri = uri, albumId = albumId, durationMs = durationMs, mimeType = mimeType,
                        sizeBytes = sizeBytes, relativePath = relativePath,
                        dateAddedEpochSeconds = dateAdded, dateModifiedEpochSeconds = dateModified
                    )
                } else {
                    parsed++
                    val mediaTitle = cursor.getString(titleColumn).usable()
                    val mediaArtist = cursor.getString(artistColumn).usable()
                    val mediaAlbum = cursor.getString(albumColumn).usable()
                    val direct = embeddedReader.read(uri, displayName, includeArtwork = false)
                    val inferred = inferFromPath(displayName, relativePath)
                    val title = UnicodeText.display(direct.title.usable() ?: mediaTitle ?: inferred.title ?: displayName.substringBeforeLast('.').ifBlank { "Unknown track" }) ?: "Unknown track"
                    val localArtist = UnicodeText.display(direct.artist.usable() ?: mediaArtist ?: inferred.artist ?: "Unknown artist") ?: "Unknown artist"
                    val localAlbum = UnicodeText.display(direct.album.usable() ?: mediaAlbum ?: inferred.album ?: "Unknown album") ?: "Unknown album"
                    val localAlbumArtist = direct.albumArtist.usable()
                    val localYear = direct.year.usable()
                    val localGenre = direct.genre.usable()

                    MusicTrack(
                        id = id, uri = uri, displayName = displayName, title = title,
                        artist = localArtist, album = localAlbum, albumId = albumId,
                        durationMs = durationMs, mimeType = mimeType, sizeBytes = sizeBytes,
                        relativePath = relativePath, dateAddedEpochSeconds = dateAdded,
                        dateModifiedEpochSeconds = dateModified, albumArtist = localAlbumArtist,
                        trackNumber = direct.trackNumber ?: inferred.trackNumber, discNumber = direct.discNumber,
                        year = localYear, genre = localGenre, sampleRateHz = direct.sampleRateHz,
                        bitDepth = direct.bitDepth, channelCount = direct.channelCount,
                        normalizationGainDb = direct.normalizationGainDb,
                        albumNormalizationGainDb = direct.albumNormalizationGainDb,
                        sourceArtist = localArtist, sourceAlbum = localAlbum,
                        sourceAlbumArtist = localAlbumArtist, sourceYear = localYear, sourceGenre = localGenre,
                        sourceNormalizationGainDb = direct.normalizationGainDb,
                        sourceAlbumNormalizationGainDb = direct.albumNormalizationGainDb
                    )
                }

                tracks += decorate(base, preference)
                completed++

                val nowMs = android.os.SystemClock.elapsedRealtime()
                val checkpointDue =
                    !fullRescan &&
                    completed < total &&
                    (
                        parsed - parsedAtLastCheckpoint >= CHECKPOINT_PARSED_TRACKS ||
                        nowMs - lastCheckpointAtMs >= CHECKPOINT_INTERVAL_MS
                    )

                if (checkpointDue) {
                    // Preserve cached entries that have not been visited yet. This keeps the visible
                    // library from shrinking if Android kills Moka mid-scan. Do not advance the
                    // MediaStore marker here; the next launch must still reconcile the library.
                    val checkpoint = ArrayList<MusicTrack>(tracks.size + cachedById.size)
                    checkpoint.addAll(tracks)
                    cachedById.values.asSequence()
                        .filter { it.id !in seenIds }
                        .forEach(checkpoint::add)
                    runCatching { cache.save(checkpoint.distinctBy { it.id }) }
                    lastCheckpointAtMs = nowMs
                    parsedAtLastCheckpoint = parsed
                }

                if (completed == total || completed % 8 == 0) {
                    onProgress(LibraryScanProgress(completed, total, parsed, reused, displayName))
                }
            }
        }

        val textComparator = UnicodeText.comparator()
        val sorted = tracks.sortedWith(Comparator { a, b ->
            val aArtist = a.albumArtist?.takeIf(String::isNotBlank) ?: a.artist
            val bArtist = b.albumArtist?.takeIf(String::isNotBlank) ?: b.artist
            textComparator.compare(aArtist, bArtist).takeIf { it != 0 }
                ?: textComparator.compare(a.album, b.album).takeIf { it != 0 }
                ?: compareValues(a.discNumber ?: 0, b.discNumber ?: 0).takeIf { it != 0 }
                ?: compareValues(a.trackNumber ?: Int.MAX_VALUE, b.trackNumber ?: Int.MAX_VALUE).takeIf { it != 0 }
                ?: textComparator.compare(a.title, b.title)
        })

        val cacheSaved = runCatching { cache.save(sorted) }.isSuccess
        if (cacheSaved) {
            currentStoreMarker()?.let { marker ->
                statePrefs.edit()
                    .putString(STORE_MARKER_KEY, marker)
                    .remove(LEGACY_VERSION_KEY)
                    .apply()
            }
        } else {
            // A partial checkpoint may exist, but never advertise it as fully synchronized.
            statePrefs.edit().remove(STORE_MARKER_KEY).apply()
        }
        sorted
    }

    private fun currentStoreMarker(): String? = runCatching {
        val version = MediaStore.getVersion(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching "v:$version"

        val generations = MediaStore.getExternalVolumeNames(context)
            .sorted()
            .joinToString("|") { volume ->
                "$volume:${MediaStore.getGeneration(context, volume)}"
            }
        "v:$version|g:$generations"
    }.getOrNull()

    private companion object {
        const val STORE_MARKER_KEY = "media_store_marker"
        const val LEGACY_VERSION_KEY = "media_store_version"
        const val CHECKPOINT_PARSED_TRACKS = 12
        const val CHECKPOINT_INTERVAL_MS = 4_000L
    }

    private fun decorate(track: MusicTrack, preference: MetadataNamePreference): MusicTrack {
        val localArtist = track.sourceArtist ?: track.artist
        val localAlbum = track.sourceAlbum ?: track.album
        val localAlbumArtist = track.sourceAlbumArtist
        val enrichment = onlineMetadata.get(localAlbumArtist?.takeIf(String::isNotBlank) ?: localArtist, localAlbum)
        val artist = resolveArtistName(localArtist, enrichment?.canonicalArtist, enrichment?.englishArtist, preference)
        val album = resolveAlbumName(localAlbum, enrichment?.canonicalTitle, preference)
        val albumArtist = localAlbumArtist?.let { resolveArtistName(it, enrichment?.canonicalArtist, enrichment?.englishArtist, preference) }
        val loudness = loudnessStore.get(track.copy(artist = localArtist, album = localAlbum, albumArtist = localAlbumArtist))

        return track.copy(
            artist = artist, album = album, albumArtist = albumArtist,
            year = track.sourceYear ?: enrichment?.releaseDate?.substringBefore('-')?.takeIf { it.isNotBlank() },
            genre = track.sourceGenre ?: enrichment?.genres?.firstOrNull(),
            normalizationGainDb = track.sourceNormalizationGainDb ?: loudness?.trackGainDb,
            albumNormalizationGainDb = track.sourceAlbumNormalizationGainDb ?: loudness?.albumGainDb,
            musicBrainzReleaseGroupId = enrichment?.releaseGroupId,
            onlineArtworkUrl = enrichment?.coverArtUrl
        )
    }
}

private data class PathMetadata(val title: String? = null, val artist: String? = null, val album: String? = null, val trackNumber: Int? = null)

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
private fun String.isGenericFolder(): Boolean = lowercase() in setOf("music", "audio", "downloads", "download", "media", "internal storage", "sdcard")
