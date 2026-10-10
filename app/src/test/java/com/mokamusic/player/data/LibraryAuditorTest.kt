package com.mokamusic.player.data

import org.junit.Assert.*
import org.junit.Test

/** Pure metadata policy tests; Android Uri methods are not present in local JVM tests. */
class LibraryAuditorTest {
    @Test fun probableDuplicatesMustMatchDurationAndSize() {
        val original = LibraryAuditor.duplicateKey("Song", "Artist", 190_000, 1000)
        assertEquals(original,
            LibraryAuditor.duplicateKey("song", "ARTIST", 190_200, 1000))
        assertNotEquals(original, LibraryAuditor.duplicateKey("Song", "Artist", 190_000, 2000))
    }
    @Test fun flagsIncompleteTagsAndSuspiciousEntries() {
        assertEquals(listOf("title", "album"),
            LibraryAuditor.incompleteMetadata("Unknown track", "Artist", ""))
        assertTrue(LibraryAuditor.suspicious(0, 200_000, "content"))
        assertTrue(LibraryAuditor.suspicious(1000, 200_000, "https"))
        assertFalse(LibraryAuditor.suspicious(1000, 200_000, "content"))
    }
}
