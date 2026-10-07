package com.mokamusic.player.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

/** Crash-safe persistent library index used by the incremental scanner. */
class MusicLibraryCache(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve(FILE_NAME))

    suspend fun load(): List<MusicTrack> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val started = android.os.SystemClock.elapsedRealtime()
            runCatching {
                if (!file.baseFile.exists()) return@runCatching emptyList<MusicTrack>()
                val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                val schema = root.optInt("schema", 0)
                if (schema != SCHEMA) {
                    Log.i(TAG, "cache load: schema mismatch stored=$schema expected=$SCHEMA")
                    return@runCatching emptyList<MusicTrack>()
                }
                val rows = root.optJSONArray("tracks") ?: return@runCatching emptyList<MusicTrack>()
                val tracks = buildList {
                    for (i in 0 until rows.length()) {
                        val o = rows.optJSONObject(i) ?: continue
                        val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: continue
                        add(MusicTrack(
                            id = o.optLong("id"),
                            uri = Uri.parse(uri),
                            displayName = o.optString("displayName"),
                            title = o.optString("title", "Unknown track"),
                            artist = o.optString("artist", "Unknown artist"),
                            album = o.optString("album", "Unknown album"),
                            albumId = o.optLong("albumId"),
                            durationMs = o.optLong("durationMs"),
                            mimeType = o.nullableString("mimeType"),
                            sizeBytes = o.optLong("sizeBytes"),
                            relativePath = o.nullableString("relativePath"),
                            dateAddedEpochSeconds = o.optLong("dateAddedEpochSeconds", 0L),
                            dateModifiedEpochSeconds = o.optLong("dateModifiedEpochSeconds", 0L),
                            albumArtist = o.nullableString("albumArtist"),
                            trackNumber = o.nullableInt("trackNumber"),
                            discNumber = o.nullableInt("discNumber"),
                            year = o.nullableString("year"),
                            genre = o.nullableString("genre"),
                            sampleRateHz = o.nullableInt("sampleRateHz"),
                            bitDepth = o.nullableInt("bitDepth"),
                            channelCount = o.nullableInt("channelCount"),
                            normalizationGainDb = o.nullableFloat("normalizationGainDb"),
                            albumNormalizationGainDb = o.nullableFloat("albumNormalizationGainDb"),
                            musicBrainzReleaseGroupId = o.nullableString("musicBrainzReleaseGroupId"),
                            onlineArtworkUrl = o.nullableString("onlineArtworkUrl"),
                            sourceArtist = o.nullableString("sourceArtist"),
                            sourceAlbum = o.nullableString("sourceAlbum"),
                            sourceAlbumArtist = o.nullableString("sourceAlbumArtist"),
                            sourceYear = o.nullableString("sourceYear"),
                            sourceGenre = o.nullableString("sourceGenre"),
                            sourceNormalizationGainDb = o.nullableFloat("sourceNormalizationGainDb"),
                            sourceAlbumNormalizationGainDb = o.nullableFloat("sourceAlbumNormalizationGainDb")
                        ))
                    }
                }
                Log.i(TAG, "cache load OK tracks=${tracks.size} schema=$SCHEMA time=${android.os.SystemClock.elapsedRealtime() - started}ms")
                tracks
            }.getOrElse { error ->
                Log.e(TAG, "cache load FAILED; preserving cache for recovery", error)
                emptyList()
            }
        }
    }

    suspend fun save(tracks: List<MusicTrack>) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val started = android.os.SystemClock.elapsedRealtime()
            val root = JSONObject().put("schema", SCHEMA).put("tracks", JSONArray().apply {
                tracks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id); put("uri", t.uri.toString()); put("displayName", t.displayName)
                        put("title", t.title); put("artist", t.artist); put("album", t.album)
                        put("albumId", t.albumId); put("durationMs", t.durationMs)
                        putNullable("mimeType", t.mimeType); put("sizeBytes", t.sizeBytes)
                        putNullable("relativePath", t.relativePath)
                        put("dateAddedEpochSeconds", t.dateAddedEpochSeconds)
                        put("dateModifiedEpochSeconds", t.dateModifiedEpochSeconds)
                        putNullable("albumArtist", t.albumArtist); putNullable("trackNumber", t.trackNumber)
                        putNullable("discNumber", t.discNumber); putNullable("year", t.year)
                        putNullable("genre", t.genre); putNullable("sampleRateHz", t.sampleRateHz)
                        putNullable("bitDepth", t.bitDepth); putNullable("channelCount", t.channelCount)
                        putNullable("normalizationGainDb", t.normalizationGainDb)
                        putNullable("albumNormalizationGainDb", t.albumNormalizationGainDb)
                        putNullable("musicBrainzReleaseGroupId", t.musicBrainzReleaseGroupId)
                        putNullable("onlineArtworkUrl", t.onlineArtworkUrl)
                        putNullable("sourceArtist", t.sourceArtist); putNullable("sourceAlbum", t.sourceAlbum)
                        putNullable("sourceAlbumArtist", t.sourceAlbumArtist); putNullable("sourceYear", t.sourceYear)
                        putNullable("sourceGenre", t.sourceGenre)
                        putNullable("sourceNormalizationGainDb", t.sourceNormalizationGainDb)
                        putNullable("sourceAlbumNormalizationGainDb", t.sourceAlbumNormalizationGainDb)
                    })
                }
            })
            val out = file.startWrite()
            try {
                OutputStreamWriter(out, Charsets.UTF_8).apply { write(root.toString()); flush() }
                file.finishWrite(out)
                Log.i(TAG, "cache commit OK tracks=${tracks.size} bytes=${file.baseFile.length()} time=${android.os.SystemClock.elapsedRealtime() - started}ms")
            } catch (t: Throwable) {
                runCatching { file.failWrite(out) }
                Log.e(TAG, "cache commit FAILED tracks=${tracks.size}", t)
                throw t
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { ioMutex.withLock { file.delete() } }

    private companion object {
        val ioMutex = Mutex()
        const val TAG = "MokaLibrary"
        const val FILE_NAME = "moka_library_cache.json"
        const val SCHEMA = 5
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) { if (value == null) put(key, JSONObject.NULL) else put(key, value) }
private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
private fun JSONObject.nullableFloat(key: String): Float? = if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).toFloat().takeIf { it.isFinite() }
