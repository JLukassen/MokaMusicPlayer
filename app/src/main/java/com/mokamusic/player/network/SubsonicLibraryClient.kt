package com.mokamusic.player.network

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class NetworkAlbum(val id: String, val name: String, val artist: String, val songCount: Int)
data class NetworkArtist(val id: String, val name: String, val albumCount: Int)
data class NetworkGenre(val name: String, val songCount: Int, val albumCount: Int)
data class NetworkSong(
    val id: String, val title: String, val artist: String, val album: String,
    val durationSeconds: Int, val mimeType: String?, val genre: String = ""
)

/**
 * Explicit HTTPS-only Navidrome/Subsonic connection.
 * Username and password stay in memory; nothing is written to preferences or logs.
 * Subsonic token/salt hashes are only passed to the caller-selected HTTPS server.
 */
/** Sanitized HTTP failure: never put the token-bearing request URL into UI or logs. */
class SubsonicHttpException(val statusCode: Int) :
    IllegalStateException("Navidrome HTTP $statusCode")

data class StreamProbeResult(val playable: Boolean, val message: String)

class SubsonicLibraryClient(
    server: String,
    private val user: String,
    private val password: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val root: String = validateServerUrl(server)

    fun ping() { request("ping") }

    fun albums(offset: Int = 0, size: Int = 100): List<NetworkAlbum> {
        val root = request("getAlbumList2", mapOf("type" to "alphabeticalByName",
            "size" to size.coerceIn(1, 100).toString(), "offset" to offset.coerceAtLeast(0).toString()))
        val array = root.optJSONObject("albumList2")?.optJSONArray("album")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            NetworkAlbum(id, item.optString("name", "Unknown album"),
                item.optString("artist", "Unknown artist"), item.optInt("songCount", 0))
        }
    }

    fun songs(albumId: String): List<NetworkSong> {
        val root = request("getAlbum", mapOf("id" to albumId))
        val album = root.optJSONObject("album") ?: return emptyList()
        val title = album.optString("name", "Unknown album")
        val artist = album.optString("artist", "Unknown artist")
        val array = album.optJSONArray("song") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            parseSong(item, title, artist)
        }
    }

    /** Subsonic ID3 artists are grouped into initial-letter indexes. */
    fun artists(): List<NetworkArtist> {
        val indexes = request("getArtists").optJSONObject("artists")
            ?.optJSONArray("index") ?: return emptyList()
        return (0 until indexes.length()).flatMap { i ->
            val entries = indexes.optJSONObject(i)?.optJSONArray("artist")
            if (entries == null) emptyList() else (0 until entries.length()).mapNotNull { j ->
                val entry = entries.optJSONObject(j) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                NetworkArtist(id, entry.optString("name", "Unknown artist"),
                    entry.optInt("albumCount", 0))
            }
        }.distinctBy { it.id }
    }

    fun artistAlbums(artistId: String): List<NetworkAlbum> {
        val entry = request("getArtist", mapOf("id" to artistId))
            .optJSONObject("artist") ?: return emptyList()
        val artist = entry.optString("name", "Unknown artist")
        val rows = entry.optJSONArray("album") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val album = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = album.optString("id").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            NetworkAlbum(id, album.optString("name", "Unknown album"),
                album.optString("artist", artist), album.optInt("songCount", 0))
        }
    }

    fun genres(): List<NetworkGenre> {
        val rows = request("getGenres").optJSONObject("genres")
            ?.optJSONArray("genre") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("value").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            NetworkGenre(name, item.optInt("songCount", 0), item.optInt("albumCount", 0))
        }.distinctBy { it.name }
    }

    /** A server-chosen sample of music for mixed device + Navidrome shuffle.
     * This avoids crawling every album over a cellular/Tailscale connection.
     * Subsonic getRandomSongs accepts up to 500 songs per request.
     */
    fun randomSongs(count: Int = 200): List<NetworkSong> {
        val rows = request("getRandomSongs", mapOf(
            "size" to count.coerceIn(1, 500).toString()
        )).optJSONObject("randomSongs")?.optJSONArray("song") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { parseSong(it) }
        }.distinctBy { it.id }
    }

    /** Server-side genre paging: never download an entire genre just to open it. */
    fun genreSongs(genre: String, offset: Int = 0, size: Int = 100): List<NetworkSong> {
        val rows = request("getSongsByGenre", mapOf(
            "genre" to genre, "count" to size.coerceIn(1, 500).toString(),
            "offset" to offset.coerceAtLeast(0).toString()
        )).optJSONObject("songsByGenre")?.optJSONArray("song") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { parseSong(it) }
        }
    }

    /** Search returns matching server tracks; blank queries use album-based browsing instead. */
    fun searchSongs(query: String, offset: Int = 0, size: Int = 100): List<NetworkSong> {
        if (query.isBlank()) return emptyList()
        val rows = request("search3", mapOf(
            "query" to query.trim(), "artistCount" to "0", "albumCount" to "0",
            "songCount" to size.coerceIn(1, 500).toString(),
            "songOffset" to offset.coerceAtLeast(0).toString()
        )).optJSONObject("searchResult3")?.optJSONArray("song") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { parseSong(it) }
        }
    }

    private fun parseSong(
        item: JSONObject, fallbackAlbum: String = "Unknown album",
        fallbackArtist: String = "Unknown artist"
    ): NetworkSong? {
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return null
        return NetworkSong(id, item.optString("title", "Unknown track"),
            item.optString("artist", fallbackArtist),
            item.optString("album", fallbackAlbum), item.optInt("duration", 0),
            item.optString("contentType").takeIf(String::isNotBlank),
            item.optString("genre"))
    }

    fun streamUri(id: String): Uri = Uri.parse(streamRequestUrl(id))

    /** Builds a signed streaming endpoint; exposed for JVM tests without android.net.Uri. */
    internal fun streamRequestUrl(id: String): String = endpoint(
        "stream", mapOf("id" to id, "format" to "raw")
    )

    /**
     * Validate the *audio response*, not only ping/catalog permissions. Some Navidrome
     * installations can list indexed songs even when the container cannot read the
     * underlying file. Read only a small range; never download an entire song here.
     * The signed URL is kept private and must never be logged.
     */
    fun checkStream(songId: String): StreamProbeResult {
        val connection = connectionFactory(URL(streamRequestUrl(songId))).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            setRequestProperty("Range", "bytes=0-255")
            setRequestProperty("Accept", "audio/*, application/octet-stream")
            setRequestProperty("User-Agent", "MokaMusicPlayer/4.0")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw SubsonicHttpException(status)
            val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            val firstBytes = connection.inputStream.use { input ->
                val sample = ByteArray(32)
                val count = input.read(sample)
                if (count > 0) sample.copyOf(count) else byteArrayOf()
            }
            val text = firstBytes.toString(Charsets.US_ASCII).trimStart()
            val looksLikeJsonOrHtml = type.contains("json") || type.contains("html") ||
                text.startsWith("{") || text.startsWith("<")
            if (looksLikeJsonOrHtml) {
                return StreamProbeResult(false, "Navidrome returned a web/API response instead of audio.")
            }
            val hasAudioHeader = type.startsWith("audio/") ||
                text.startsWith("fLaC") || text.startsWith("RIFF") ||
                text.startsWith("ID3") || text.startsWith("OggS") ||
                (firstBytes.size >= 2 &&
                    (firstBytes[0].toInt() and 0xff) == 0xff &&
                    (firstBytes[1].toInt() and 0xe0) == 0xe0) ||
                (firstBytes.size >= 8 && text.substring(4).startsWith("ftyp"))
            return if (hasAudioHeader) {
                StreamProbeResult(true, "Audio stream accessible (HTTP $status).")
            } else {
                StreamProbeResult(false, "Server responded (HTTP $status), but no recognizable audio header was returned.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        // Safe to retry metadata GETs, never any mutating API request.
        val url = URL(endpoint(method, params))
        for (attempt in 0..1) {
            var connection: HttpURLConnection? = null
            try {
                connection = connectionFactory(url).apply {
                    connectTimeout = 15_000
                    readTimeout = 40_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "MokaMusicPlayer/4.0")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw SubsonicHttpException(code)
                val body = connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val n = input.read(chunk)
                        if (n == -1) break
                        check(buffer.size() + n <= 2_000_000) {
                            "Network response too large"
                        }
                        buffer.write(chunk, 0, n)
                    }
                    buffer.toString("UTF-8")
                }
                val response = JSONObject(body).getJSONObject("subsonic-response")
                check(response.optString("status") == "ok") {
                    response.optJSONObject("error")?.optString("message") ?: "Subsonic server error"
                }
                return response
            } catch (e: SubsonicHttpException) {
                if (attempt == 0 && (e.statusCode == 429 || e.statusCode in 500..599)) {
                    Thread.sleep(350)
                    continue
                }
                throw e
            } catch (e: IOException) {
                // Covers Tailscale reconnects, transient socket failures and read timeouts.
                if (attempt == 0) {
                    Thread.sleep(350)
                    continue
                }
                throw e
            } finally {
                connection?.disconnect()
            }
        }
        error("Navidrome request failed after retry")
    }

    private fun endpoint(method: String, params: Map<String, String>): String {
        val salt = ByteArray(12).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val digest = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val query = linkedMapOf(
            "u" to user, "t" to digest, "s" to salt,
            "v" to "1.16.1", "c" to "Moka", "f" to "json"
        ) + params
        return "$root/rest/$method.view?" + query.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    companion object {
        fun validateServerUrl(input: String): String {
            val url = URL(input.trim().trimEnd('/'))
            require(url.protocol.equals("https", true)) {
                "HTTPS is required. Set up an HTTPS reverse proxy or a private TLS endpoint."
            }
            require(url.host.isNotBlank() && url.userInfo == null && url.query == null &&
                url.ref == null) { "Use a server URL without credentials, query or fragment" }
            val path = url.path.trimEnd('/').removeSuffix("/rest")
            return "https://${url.authority}$path"
        }
    }
}
