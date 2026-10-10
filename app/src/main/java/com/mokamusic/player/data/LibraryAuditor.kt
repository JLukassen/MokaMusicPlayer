package com.mokamusic.player.data

import com.mokamusic.player.model.MusicTrack
import java.util.Locale

enum class AuditCategory(val label: String) {
    DUPLICATE("Possible duplicates"),
    MISSING_TAGS("Incomplete metadata"),
    INVALID_MEDIA("Invalid media entries")
}
data class AuditIssue(val category: AuditCategory, val track: MusicTrack, val description: String)
data class LibraryAuditReport(val total: Int, val issues: List<AuditIssue>) {
    fun count(category: AuditCategory) = issues.count { it.category == category }
    fun examples(category: AuditCategory) = issues.filter { it.category == category }.take(12)
}
/** Read-only metadata audit; no file reads, deletion or modification. */
object LibraryAuditor {
    fun duplicateKey(title: String, artist: String, durationMs: Long, sizeBytes: Long): String? {
        if (sizeBytes <= 0 || durationMs <= 0) return null
        return listOf(title.trim().lowercase(Locale.ROOT), artist.trim().lowercase(Locale.ROOT),
            (durationMs / 1000).toString(), sizeBytes.toString()).joinToString("|")
    }
    fun incompleteMetadata(title: String, artist: String, album: String): List<String> = buildList {
        if (title.isBlank() || title.startsWith("Unknown", true)) add("title")
        if (artist.isBlank() || artist.startsWith("Unknown", true)) add("artist")
        if (album.isBlank() || album.startsWith("Unknown", true)) add("album")
    }
    fun suspicious(sizeBytes: Long, durationMs: Long, scheme: String?): Boolean =
        sizeBytes <= 0 || durationMs <= 0 || scheme !in setOf("content", "file")

    fun analyze(tracks: List<MusicTrack>): LibraryAuditReport {
        val issues = mutableListOf<AuditIssue>()
        val duplicates = tracks.filter { it.sizeBytes > 0 && it.durationMs > 0 }.groupBy {
            duplicateKey(it.title, it.artist, it.durationMs, it.sizeBytes)
        }
        for (group in duplicates.values.filter { it.size > 1 }) {
            for (track in group) issues += AuditIssue(AuditCategory.DUPLICATE, track,
                "Same title/artist, approximate duration and size as ${group.size - 1} other entries")
        }
        for (track in tracks) {
            val unknown = incompleteMetadata(track.title, track.artist, track.album)
            if (unknown.isNotEmpty()) issues += AuditIssue(AuditCategory.MISSING_TAGS, track,
                "Check ${unknown.joinToString(", ")} — names may have been inferred from the path")
            if (suspicious(track.sizeBytes, track.durationMs, track.uri.scheme))
                issues += AuditIssue(AuditCategory.INVALID_MEDIA, track,
                    "Missing size, duration or valid local URI; refresh the music library")
        }
        return LibraryAuditReport(tracks.size, issues.sortedWith(
            compareBy<AuditIssue>({ it.category.ordinal }, { it.track.artist.lowercase(Locale.ROOT) },
                { it.track.title.lowercase(Locale.ROOT) })
        ))
    }
}
