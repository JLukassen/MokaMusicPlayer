package com.mokamusic.player.network

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class NetworkAlbum(val id: String, val name: String, val artist: String, val songCount: Int)
data class NetworkSong(
    val id: String, val title: String, val artist: String, val album: String,
    val durationSeconds: Int, val mimeType: String?
)

/**
 * Explicit HTTPS-only Navidrome/Subsonic connection.
 * Username and password stay in memory; nothing is written to preferences or logs.
 * Subsonic token/salt hashes are only passed to the caller-selected HTTPS server.
 */
class SubsonicLibraryClient(server: String, private val user: String, private val password: String) {
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
            NetworkSong(id, item.optString("title", "Unknown track"),
                item.optString("artist", artist), title, item.optInt("duration", 0),
                item.optString("contentType").takeIf(String::isNotBlank))
        }
    }

    fun streamUri(id: String): Uri = Uri.parse(endpoint("stream", mapOf(
        "id" to id, "format" to "raw"
    )))

    private fun request(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = URL(endpoint(method, params))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MokaMusicPlayer/4.0")
        }
        try {
            check(connection.responseCode in 200..299) {
                "Server returned HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    check(buffer.size() + n <= 2_000_000) { "Network response too large" }
                    buffer.write(chunk, 0, n)
                }
                buffer.toString("UTF-8")
            }
            val response = JSONObject(body).getJSONObject("subsonic-response")
            check(response.optString("status") == "ok") {
                response.optJSONObject("error")?.optString("message") ?: "Subsonic server error"
            }
            return response
        } finally { connection.disconnect() }
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
