package com.mokamusic.player.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

/** Crash-safe persistent library index. The original audio files remain read-only. */
class MusicLibraryCache(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve("moka_library_cache.json"))

    suspend fun load(): List<MusicTrack> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.baseFile.exists()) return@runCatching emptyList<MusicTrack>()
            val text = file.openRead().bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            if (root.optInt("schema", 0) != SCHEMA) return@runCatching emptyList<MusicTrack>()
            val rows = root.optJSONArray("tracks") ?: return@runCatching emptyList<MusicTrack>()
            buildList {
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
                            normalizationGainDb = o.nullableFloat("normalizationGainDb")
                        )
                    )
                }
            }
        }.getOrElse {
            runCatching { file.delete() }
            emptyList()
        }
    }

    suspend fun save(tracks: List<MusicTrack>) = withContext(Dispatchers.IO) {
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
                })
            }
        })
        val out = file.startWrite()
        try {
            val writer = OutputStreamWriter(out, Charsets.UTF_8)
            writer.write(root.toString())
            writer.flush()
            file.finishWrite(out)
        } catch (t: Throwable) {
            runCatching { file.failWrite(out) }
            throw t
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    private companion object { const val SCHEMA = 2 }
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
