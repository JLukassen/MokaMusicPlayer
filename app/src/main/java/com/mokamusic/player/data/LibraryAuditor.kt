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
    fun analyze(tracks: List<MusicTrack>): LibraryAuditReport {
        val issues = mutableListOf<AuditIssue>()
        val duplicates = tracks.filter { it.sizeBytes > 0 && it.durationMs > 0 }.groupBy {
            listOf(it.title.trim().lowercase(Locale.ROOT), it.artist.trim().lowercase(Locale.ROOT),
                (it.durationMs / 1000).toString(), it.sizeBytes.toString()).joinToString("|")
        }
        for (group in duplicates.values.filter { it.size > 1 }) {
            for (track in group) issues += AuditIssue(AuditCategory.DUPLICATE, track,
                "Same title/artist, approximate duration and size as ${group.size - 1} other entries")
        }
        for (track in tracks) {
            val unknown = buildList {
                if (track.title.isBlank() || track.title.startsWith("Unknown", true)) add("title")
                if (track.artist.isBlank() || track.artist.startsWith("Unknown", true)) add("artist")
                if (track.album.isBlank() || track.album.startsWith("Unknown", true)) add("album")
            }
            if (unknown.isNotEmpty()) issues += AuditIssue(AuditCategory.MISSING_TAGS, track,
                "Check ${unknown.joinToString(", ")} — names may have been inferred from the path")
            if (track.sizeBytes <= 0 || track.durationMs <= 0 || track.uri.scheme !in setOf("content", "file"))
                issues += AuditIssue(AuditCategory.INVALID_MEDIA, track,
                    "Missing size, duration or valid local URI; refresh the music library")
        }
        return LibraryAuditReport(tracks.size, issues.sortedWith(
            compareBy<AuditIssue>({ it.category.ordinal }, { it.track.artist.lowercase(Locale.ROOT) },
                { it.track.title.lowercase(Locale.ROOT) })
        ))
    }
}
