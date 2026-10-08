package com.mokamusic.player.data

import android.net.Uri
import com.mokamusic.player.model.MusicTrack
import org.junit.Assert.*
import org.junit.Test

class LibraryAuditorTest {
    private fun track(id: Long, size: Long = 1000, title: String = "Song") =
        MusicTrack(id, Uri.parse("content://media/external/audio/media/$id"), "$id.flac",
            title, "Artist", "Album", 1L, 190000L, "audio/flac", size, "Music/")

    @Test fun duplicatesAreCandidates() {
        val report = LibraryAuditor.analyze(listOf(track(1), track(2), track(3, 2000)))
        assertEquals(2, report.count(AuditCategory.DUPLICATE))
    }
    @Test fun incompleteMetadataAndSize() {
        val report = LibraryAuditor.analyze(listOf(track(1, 0, "Unknown track")))
        assertEquals(1, report.count(AuditCategory.MISSING_TAGS))
        assertEquals(1, report.count(AuditCategory.INVALID_MEDIA))
    }
}
