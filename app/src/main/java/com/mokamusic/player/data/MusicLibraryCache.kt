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

/**
 * Crash-safe persistent library index.
 *
 * AtomicFile protects against process death, but it does not serialize concurrent writers. Beta 1
 * keeps one process-local mutex around load/save/clear so a scan, metadata refresh and loudness
 * refresh cannot race each other through the same .new/.bak files.
 */
class MusicLibraryCache(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve(FILE_NAME))

    suspend fun load(): List<MusicTrack> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val started = android.os.SystemClock.elapsedRealtime()
            runCatching {
                if (!file.baseFile.exists()) {
                    Log.i(TAG, "cache load: no existing index")
                    return@runCatching emptyList<MusicTrack>()
                }
                val text = file.openRead().bufferedReader().use { it.readText() }
                val root = JSONObject(text)
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
                        add(
                            MusicTrack(
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
                                onlineArtworkUrl = o.nullableString("onlineArtworkUrl")
                            )
                        )
                    }
                }
                Log.i(
                    TAG,
                    "cache load OK tracks=${tracks.size} schema=$SCHEMA " +
                        "time=${android.os.SystemClock.elapsedRealtime() - started}ms"
                )
                tracks
            }.getOrElse { error ->
                // Do not delete here. AtomicFile may still have a recoverable backup, and a transient
                // read problem should not force a destructive full-library rediscovery on next boot.
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
                        put("id", t.id)
                        put("uri", t.uri.toString())
                        put("displayName", t.displayName)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("albumId", t.albumId)
                        put("durationMs", t.durationMs)
                        putNullable("mimeType", t.mimeType)
                        put("sizeBytes", t.sizeBytes)
                        putNullable("relativePath", t.relativePath)
                        put("dateAddedEpochSeconds", t.dateAddedEpochSeconds)
                        putNullable("albumArtist", t.albumArtist)
                        putNullable("trackNumber", t.trackNumber)
                        putNullable("discNumber", t.discNumber)
                        putNullable("year", t.year)
                        putNullable("genre", t.genre)
                        putNullable("sampleRateHz", t.sampleRateHz)
                        putNullable("bitDepth", t.bitDepth)
                        putNullable("channelCount", t.channelCount)
                        putNullable("normalizationGainDb", t.normalizationGainDb)
                        putNullable("albumNormalizationGainDb", t.albumNormalizationGainDb)
                        putNullable("musicBrainzReleaseGroupId", t.musicBrainzReleaseGroupId)
                        putNullable("onlineArtworkUrl", t.onlineArtworkUrl)
                    })
                }
            })

            val out = file.startWrite()
            try {
                // Keep the writer scoped to the AtomicFile stream. finishWrite() performs the sync
                // and commit; no other coroutine can enter this section until it is complete.
                OutputStreamWriter(out, Charsets.UTF_8).apply {
                    write(root.toString())
                    flush()
                }
                file.finishWrite(out)
                Log.i(
                    TAG,
                    "cache commit OK tracks=${tracks.size} bytes=${file.baseFile.length()} " +
                        "time=${android.os.SystemClock.elapsedRealtime() - started}ms"
                )
            } catch (t: Throwable) {
                runCatching { file.failWrite(out) }
                Log.e(TAG, "cache commit FAILED tracks=${tracks.size}", t)
                throw t
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            file.delete()
            Log.i(TAG, "cache cleared")
        }
    }

    private companion object {
        // Process-wide: safe even if a second repository/cache instance is introduced later.
        val ioMutex = Mutex()
        const val TAG = "MokaLibrary"
        const val FILE_NAME = "moka_library_cache.json"
        const val SCHEMA = 4
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.nullableFloat(key: String): Float? =
    if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).toFloat().takeIf { it.isFinite() }
