package com.mokamusic.player.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkQueuePlanTest {
    private fun song(id: String) = NetworkSong(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        durationSeconds = 180, mimeType = "audio/flac"
    )

    @Test fun selectingAnyVisibleTrackRetainsEntireQueueForNextAndPrevious() {
        val visible = listOf(song("a"), song("b"), song("c"))
        val queue = NetworkQueuePlan.build(visible[1], visible)
        assertEquals(listOf("a", "b", "c"), queue.map { it.id })
        assertEquals(1, queue.indexOfFirst { it.id == "b" })
    }

    @Test fun duplicatesAreNotRepeatedInPlaylist() {
        val a = song("a")
        assertEquals(listOf("a", "b"),
            NetworkQueuePlan.build(a, listOf(a, song("b"), a)).map { it.id })
    }

    @Test fun selectedTrackOutsideVisiblePageRemainsPlayable() {
        val queue = NetworkQueuePlan.build(song("selected"), listOf(song("a"), song("b")))
        assertEquals(listOf("a", "b", "selected"), queue.map { it.id })
    }
}
