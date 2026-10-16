package com.mokamusic.player.data

import org.junit.Assert.*
import org.junit.Test

class DuplicateMatcherTest {
    private fun song(
        id: Long, title: String = "Song", artist: String = "Artist",
        filename: String = "song.flac", duration: Long = 180_000L, size: Long = 100L
    ) = DuplicateSignature(id, title, artist, filename, duration, size)

    @Test fun findsMatchingFilenamesDespiteCopySuffixAndDifferentTags() {
        val a = song(1, "Audio Track 01", "First Artist", "Moka Track.flac", 190_000L, 50L)
        val b = song(2, "Completely Different Tag", "Unknown Artist", "Moka Track (1).mp3",
            190_500L, 30L)
        assertTrue(DuplicateMatcher.possibleGroups(listOf(a, b))
            .any { it.containsAll(listOf(1L, 2L)) })
    }

    @Test fun findsNormalizedTitleAndArtistDespiteDifferentFileSizesAndNames() {
        val a = song(1, "My--Song!", "Björk", "abc.flac", 200_000L, 90L)
        val b = song(2, "my song", "BJÖRK", "def.mp3", 200_500L, 25L)
        assertTrue(DuplicateMatcher.possibleGroups(listOf(a, b))
            .any { it.containsAll(listOf(1L, 2L)) })
    }

    @Test fun rejectsDistantDurationsAndPreservesVersionLabels() {
        val a = song(1, "Song", "Artist", "alpha.flac", 180_000L)
        val b = song(2, "Song", "Artist", "beta.flac", 210_000L)
        val live = song(3, "Song (Live)", "Artist", "live.mp3", 180_100L)
        assertTrue(DuplicateMatcher.possibleGroups(listOf(a, b, live)).isEmpty())
    }

    @Test fun equalByteSizeMakesContentHashCandidatesRegardlessOfTags() {
        val a = song(1, "A", "X", "a.flac", 200_000L, 123456L)
        val b = song(2, "B", "Y", "b.flac", 210_000L, 123456L)
        val c = song(3, "C", "Z", "c.flac", 210_000L, 654321L)
        assertEquals(listOf(listOf(1L, 2L)), DuplicateMatcher.sameSizeGroups(listOf(a, b, c)))
    }

    @Test fun zeroSizesCannotProduceVerifiedCandidate() {
        assertTrue(DuplicateMatcher.sameSizeGroups(
            listOf(song(1, size = 0L), song(2, size = 0L))
        ).isEmpty())
    }
}
