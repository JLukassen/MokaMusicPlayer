package com.mokamusic.player.metadata

import android.content.Context
import android.util.AtomicFile
import com.mokamusic.player.data.UnicodeText
import com.mokamusic.player.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

/** Persistent, read-through cache for optional MusicBrainz album enrichment. */
data class AlbumEnrichment(
    val key: String,
    val releaseGroupId: String,
    val canonicalArtist: String? = null,
    val canonicalTitle: String? = null,
    val releaseDate: String? = null,
    val primaryType: String? = null,
    val genres: List<String> = emptyList(),
    val coverArtUrl: String? = null,
    val score: Int = 0,
    val updatedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L
)

class OnlineMetadataStore(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve("moka_online_metadata.json"))
    private val rows = LinkedHashMap<String, AlbumEnrichment>()

    init { loadFromDisk() }

    @Synchronized
    fun get(artist: String?, album: String?): AlbumEnrichment? = rows[keyFor(artist, album)]

    @Synchronized
    fun get(track: MusicTrack): AlbumEnrichment? = get(track.albumArtist ?: track.artist, track.album)

    @Synchronized
    fun put(value: AlbumEnrichment) {
        rows[value.key] = value
        saveLocked()
    }

    @Synchronized
    fun count(): Int = rows.size

    @Synchronized
    fun clear() {
        rows.clear()
        runCatching { file.delete() }
    }

    @Synchronized
    private fun loadFromDisk() {
        rows.clear()
        runCatching {
            if (!file.baseFile.exists()) return@runCatching
            val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (root.optInt("schema", 0) != SCHEMA) return@runCatching
            val a = root.optJSONArray("albums") ?: return@runCatching
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                val rgid = o.optString("releaseGroupId").takeIf { it.isNotBlank() } ?: continue
                val genres = buildList {
                    val g = o.optJSONArray("genres") ?: JSONArray()
                    for (j in 0 until g.length()) g.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                }
                rows[key] = AlbumEnrichment(
                    key = key,
                    releaseGroupId = rgid,
                    canonicalArtist = o.stringOrNull("canonicalArtist"),
                    canonicalTitle = o.stringOrNull("canonicalTitle"),
                    releaseDate = o.stringOrNull("releaseDate"),
                    primaryType = o.stringOrNull("primaryType"),
                    genres = genres,
                    coverArtUrl = o.stringOrNull("coverArtUrl"),
                    score = o.optInt("score", 0),
                    updatedAtEpochSeconds = o.optLong("updatedAtEpochSeconds", 0L)
                )
            }
        }
    }

    private fun saveLocked() {
        val root = JSONObject().put("schema", SCHEMA).put("albums", JSONArray().apply {
            rows.values.forEach { row ->
                put(JSONObject().apply {
                    put("key", row.key)
                    put("releaseGroupId", row.releaseGroupId)
                    putNullable("canonicalArtist", row.canonicalArtist)
                    putNullable("canonicalTitle", row.canonicalTitle)
                    putNullable("releaseDate", row.releaseDate)
                    putNullable("primaryType", row.primaryType)
                    put("genres", JSONArray(row.genres))
                    putNullable("coverArtUrl", row.coverArtUrl)
                    put("score", row.score)
                    put("updatedAtEpochSeconds", row.updatedAtEpochSeconds)
                })
            }
        })
        val out = file.startWrite()
        try {
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(root.toString()) }
            file.finishWrite(out)
        } catch (t: Throwable) {
            runCatching { file.failWrite(out) }
            throw t
        }
    }

    companion object {
        private const val SCHEMA = 1

        fun keyFor(artist: String?, album: String?): String =
            UnicodeText.key(artist) + "\u0000" + UnicodeText.key(album)
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
