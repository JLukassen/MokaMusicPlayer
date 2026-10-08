package com.mokamusic.player.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

class SubsonicLibraryClientTest {
    private class FakeHttps(url: URL, val body: String, val code: Int = 200) :
        HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }

    private val albumResponse = """
        {"subsonic-response":{"status":"ok","version":"1.16.1",
        "albumList2":{"album":[{"id":"album1","name":"Album One","artist":"Artist","songCount":2}]}}}
    """.trimIndent()

    private val songResponse = """
        {"subsonic-response":{"status":"ok","album":{"name":"Album One","artist":"Artist",
        "song":[{"id":"track1","title":"Song One","artist":"Artist","duration":180,
        "contentType":"audio/flac"}]}}}
    """.trimIndent()

    @Test fun pingAlbumListingAndTracksUseExpectedEndpoints() {
        val requested = mutableListOf<String>()
        val client = SubsonicLibraryClient("https://music.example.org", "tester", "secret") { url ->
            requested += url.toString()
            val body = when (url.path.substringAfterLast("/")) {
                "getAlbumList2.view" -> albumResponse
                "getAlbum.view" -> songResponse
                else -> """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
            }
            FakeHttps(url, body)
        }
        client.ping()
        val albums = client.albums(offset = 100, size = 20)
        assertEquals(1, albums.size)
        assertEquals("album1", albums.single().id)
        assertEquals(2, albums.single().songCount)
        val songs = client.songs("album1")
        assertEquals("Song One", songs.single().title)
        assertEquals("audio/flac", songs.single().mimeType)
        assertTrue(requested[0].contains("/rest/ping.view?"))
        assertTrue(requested[1].contains("offset=100"))
        assertTrue(requested[1].contains("size=20"))
        assertTrue(requested[2].contains("/rest/getAlbum.view?"))
        assertTrue(requested[2].contains("id=album1"))
        requested.forEach {
            assertTrue(it.startsWith("https://"))
            assertFalse(it.contains("secret"))
            val params = it.substringAfter('?').split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to
                    URLDecoder.decode(pair[1], "UTF-8")
            }
            val expected = MessageDigest.getInstance("MD5")
                .digest(("secret" + params.getValue("s")).toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) }
            assertEquals(expected, params["t"])
            assertEquals("tester", params["u"])
        }
    }

    @Test fun wmaRequestsMp3ButFlacAndWavStayRaw() {
        val client = SubsonicLibraryClient("https://music.example.org", "test", "secret")
        assertTrue(client.streamRequestUrl("song-wma", "audio/x-ms-wma").contains("format=mp3"))
        assertTrue(client.streamRequestUrl("song-asf", "audio/x-ms-asf").contains("format=mp3"))
        assertEquals("audio/mpeg", client.playbackMimeType("audio/x-ms-wma"))
        assertTrue(client.streamRequestUrl("song-flac", "audio/flac").contains("format=raw"))
        assertTrue(client.streamRequestUrl("song-wav", "audio/wav").contains("format=raw"))
        assertEquals("audio/flac", client.playbackMimeType("audio/flac"))
    }

    @Test fun streamProbeMatchesPlaybackTranscodeChoice() {
        val requested = mutableListOf<String>()
        val client = SubsonicLibraryClient("https://music.example.org", "test", "secret") { url ->
            requested += url.toString()
            FakeHttps(url, "ID3\\u0003\\u0000")
        }
        assertTrue(client.checkStream("song", "audio/x-ms-wma").playable)
        assertTrue(requested.single().contains("format=mp3"))
    }

    @Test fun streamUrlIsSignedAndHttps() {
        val client = SubsonicLibraryClient("https://music.example.org", "demo", "demo")
        val uri = client.streamRequestUrl("track 9")
        assertTrue(uri.startsWith("https://music.example.org/rest/stream.view?"))
        assertTrue(uri.contains("id=track+9"))
        assertTrue(uri.contains("format=raw"))
        assertFalse(uri.contains("p=demo"))
    }

    @Test fun emptyAlbumsAndServerFailuresAreHandled() {
        val empty = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""
        val c = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            FakeHttps(url, empty)
        }
        assertTrue(c.albums().isEmpty())
        val denied = """{"subsonic-response":{"status":"failed","error":{"message":"Bad login"}}}"""
        val reject = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            FakeHttps(url, denied)
        }
        assertTrue(assertThrows(IllegalStateException::class.java) { reject.ping() }
            .message.orEmpty().contains("Bad login"))
        val failure = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            FakeHttps(url, "", 503)
        }
        assertTrue(assertThrows(IllegalStateException::class.java) { failure.ping() }
            .message.orEmpty().contains("HTTP 503"))
    }

    @Test fun insecureAndCredentialBearingServerUrlsRejected() {
        listOf("http://music.example.org", "https://user:pass@music.example.org",
            "https://music.example.org?a=1", "https://music.example.org/#x")
            .forEach { input ->
                assertThrows(IllegalArgumentException::class.java) {
                    SubsonicLibraryClient.validateServerUrl(input)
                }
            }
        assertEquals("https://music.example.org/prefix",
            SubsonicLibraryClient.validateServerUrl("https://music.example.org/prefix/rest/"))
    }

    @Test fun categoriesUseServerIndexesAndGenrePaging() {
        val requested = mutableListOf<URL>()
        val client = SubsonicLibraryClient("https://fedora.example.ts.net", "u", "pw") { url ->
            requested += url
            val body = when (url.path.substringAfterLast("/")) {
                "getArtists.view" -> """{"subsonic-response":{"status":"ok","artists":{
                    "index":[{"name":"A","artist":[{"id":"ar1","name":"ILLIT","albumCount":3}]}]}}}"""
                "getArtist.view" -> """{"subsonic-response":{"status":"ok","artist":{
                    "name":"ILLIT","album":[{"id":"al1","name":"Bomb","songCount":5}]}}}"""
                "getGenres.view" -> """{"subsonic-response":{"status":"ok","genres":{
                    "genre":[{"value":"K-Pop","songCount":29,"albumCount":4}]}}}"""
                "getSongsByGenre.view" -> """{"subsonic-response":{"status":"ok","songsByGenre":{
                    "song":[{"id":"s1","title":"Magnetic","artist":"ILLIT","album":"Super Real Me",
                    "genre":"K-Pop","duration":170,"contentType":"audio/flac"}]}}}"""
                "search3.view" -> """{"subsonic-response":{"status":"ok","searchResult3":{
                    "song":[{"id":"s1","title":"Magnetic","artist":"ILLIT","album":"Super Real Me"}]}}}"""
                else -> error("Unexpected endpoint " + url.path)
            }
            FakeHttps(url, body)
        }
        assertEquals("ILLIT", client.artists().single().name)
        assertEquals(3, client.artists().single().albumCount)
        assertEquals("Bomb", client.artistAlbums("ar1").single().name)
        assertEquals("K-Pop", client.genres().single().name)
        assertEquals(29, client.genres().single().songCount)
        assertEquals("Magnetic", client.genreSongs("K-Pop", 100, 30).single().title)
        assertEquals("K-Pop", client.genreSongs("K-Pop", 0, 10).single().genre)
        assertEquals("Magnetic", client.searchSongs("Magnetic", 25, 50).single().title)
        assertTrue(requested.any { it.path.endsWith("/getArtist.view") &&
            it.query.contains("id=ar1") })
        assertTrue(requested.any { it.path.endsWith("/getSongsByGenre.view") &&
            it.query.contains("offset=100") && it.query.contains("count=30") &&
            it.query.contains("genre=K-Pop") })
        assertTrue(requested.any { it.path.endsWith("/search3.view") &&
            it.query.contains("songOffset=25") && it.query.contains("songCount=50") })
    }

    @Test fun randomSongsUsesSubsonicEndpointAndDeduplicatesResults() {
        val requested = mutableListOf<URL>()
        val response = """{"subsonic-response":{"status":"ok","randomSongs":{"song":[
            {"id":"101","title":"A","artist":"ILLIT","album":"Bomb","contentType":"audio/flac"},
            {"id":"102","title":"B","artist":"ILLIT","album":"Bomb"},
            {"id":"101","title":"A","artist":"ILLIT","album":"Bomb"}
        ]}}}"""
        val client = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            requested += url
            FakeHttps(url, response)
        }
        val songs = client.randomSongs(900)
        assertEquals(listOf("101", "102"), songs.map { it.id })
        assertEquals("ILLIT", songs.first().artist)
        assertEquals("audio/flac", songs.first().mimeType)
        assertTrue(requested.single().path.endsWith("/rest/getRandomSongs.view"))
        assertTrue(requested.single().query.contains("size=500"))
    }

    @Test fun catalogRetriesTransientHttpErrorsWithoutChangingApiParameters() {
        var calls = 0
        val client = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            calls++
            FakeHttps(url, albumResponse, if (calls == 1) 503 else 200)
        }
        assertEquals("album1", client.albums().single().id)
        assertEquals(2, calls)
    }

    @Test fun authenticationFailuresDoNotRetryOrExposeCredentials() {
        var calls = 0
        val client = SubsonicLibraryClient("https://music.example.org", "u", "secret") { url ->
            calls++
            FakeHttps(url, "", 401)
        }
        val failure = assertThrows(SubsonicHttpException::class.java) { client.albums() }
        assertEquals(401, failure.statusCode)
        assertEquals(1, calls)
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertFalse(failure.message.orEmpty().contains("/rest/"))
    }

    @Test fun streamedAudioProbeReadsOnlyFirstBytesUsingRange() {
        val requested = mutableListOf<URL>()
        val client = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            requested += url
            FakeHttps(url, "fLaC\\u0000example-audio", 206)
        }
        val result = client.checkStream("audio123")
        assertTrue(result.playable)
        assertTrue(result.message.contains("206"))
        assertEquals(1, requested.size)
        assertTrue(requested.single().path.endsWith("/stream.view"))
        assertTrue(requested.single().query.contains("id=audio123"))
    }

    @Test fun streamProbeRejectsHtmlAndReportsAudioPermissionFailure() {
        val client = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            FakeHttps(url, "<html>login</html>")
        }
        val result = client.checkStream("audio123")
        assertFalse(result.playable)
        assertTrue(result.message.contains("instead of audio"))
        val denied = SubsonicLibraryClient("https://music.example.org", "u", "pw") { url ->
            FakeHttps(url, "", 403)
        }
        val error = assertThrows(SubsonicHttpException::class.java) { denied.checkStream("audio123") }
        assertEquals(403, error.statusCode)
    }

    @Test fun blankSearchDoesNotCallServer() {
        val client = SubsonicLibraryClient("https://fedora.example.ts.net", "u", "pw") {
            error("Blank query should never call server")
        }
        assertTrue(client.searchSongs("   ").isEmpty())
    }
}
