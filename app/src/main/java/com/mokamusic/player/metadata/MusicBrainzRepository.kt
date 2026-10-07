package com.mokamusic.player.metadata

import android.os.SystemClock
import android.util.Log
import com.mokamusic.player.data.UnicodeText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

/** Minimal MusicBrainz v2 client used only for optional local-library enrichment. */
class MusicBrainzRepository {
    fun findBestAlbum(artist: String, album: String): Result<AlbumEnrichment?> = runCatching {
        if (artist.isBlank() || album.isBlank()) return@runCatching null
        Log.i(TAG, "lookup start artist=${artist.take(80)} album=${album.take(120)}")
        respectRateLimit()
        val query = "releasegroup:\"${escapeQuery(album)}\" AND artist:\"${escapeQuery(artist)}\""
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = getText("https://musicbrainz.org/ws/2/release-group/?query=$encoded&fmt=json&limit=8")
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return@runCatching null
        Log.i(TAG, "lookup candidates=${groups.length()}")

        var best: AlbumEnrichment? = null
        var bestRank = Int.MIN_VALUE
        for (i in 0 until groups.length()) {
            val row = groups.optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = row.optString("title").takeIf { it.isNotBlank() }
            val canonicalArtist = parseArtistCredit(row)
            val serverScore = row.optInt("score", 0)
            val titleSimilarity = similarity(album, title.orEmpty())
            val artistSimilarity = similarity(artist, canonicalArtist.orEmpty())
            val rank = serverScore * 10 + titleSimilarity * 4 + artistSimilarity * 3
            if (rank <= bestRank) continue

            bestRank = rank
            best = AlbumEnrichment(
                key = OnlineMetadataStore.keyFor(artist, album),
                releaseGroupId = id,
                canonicalArtist = canonicalArtist,
                canonicalTitle = title,
                releaseDate = row.optString("first-release-date").takeIf { it.isNotBlank() },
                primaryType = row.optString("primary-type").takeIf { it.isNotBlank() },
                genres = emptyList(),
                coverArtUrl = "https://coverartarchive.org/release-group/$id/front-500",
                score = serverScore
            )
        }
        // MusicBrainz scores are 0..100. Refuse weak fuzzy guesses rather than attaching wrong art.
        best?.takeIf { it.score >= 80 }.also { selected ->
            if (selected == null) {
                Log.i(TAG, "lookup complete result=no-strong-match")
            } else {
                Log.i(TAG, "lookup complete result=match score=${selected.score} releaseGroup=${selected.releaseGroupId}")
            }
        }
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            Log.i(TAG, "HTTP $code")
            if (code !in 200..299) error("MusicBrainz HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseArtistCredit(row: JSONObject): String? {
        val credits = row.optJSONArray("artist-credit") ?: return null
        return buildList {
            for (i in 0 until credits.length()) {
                val c = credits.optJSONObject(i) ?: continue
                val name = c.optString("name").takeIf { it.isNotBlank() }
                    ?: c.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
                if (name != null) add(name)
                c.optString("joinphrase").takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.joinToString("").takeIf { it.isNotBlank() }
    }


    private fun similarity(a: String, b: String): Int {
        val x = UnicodeText.key(a)
        val y = UnicodeText.key(b)
        if (x.isBlank() || y.isBlank()) return 0
        if (x == y) return 100
        if (x.contains(y) || y.contains(x)) return 85
        val ax = x.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val by = y.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (ax.isEmpty() || by.isEmpty()) return 0
        return ((ax.intersect(by).size.toDouble() / max(ax.size, by.size).toDouble()) * 100.0).toInt()
    }

    private fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    @Synchronized
    private fun respectRateLimit() {
        val now = SystemClock.elapsedRealtime()
        val remaining = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAtMs)
        if (remaining > 0) Thread.sleep(remaining)
        lastRequestAtMs = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val TAG = "MokaMetadata"
        private const val USER_AGENT = "MokaMusicPlayer/4.0.0-beta01 (https://github.com/JLukassen/MokaMusicPlayer)"
        private const val MIN_REQUEST_INTERVAL_MS = 1100L
        @Volatile private var lastRequestAtMs = 0L
    }
}
