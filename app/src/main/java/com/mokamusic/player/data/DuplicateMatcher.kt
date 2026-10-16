package com.mokamusic.player.data

import java.text.Normalizer
import java.util.Locale

/** Pure duplicate candidate policy. Metadata matches are clues, never grounds for deletion. */
data class DuplicateSignature(
    val id: Long,
    val title: String,
    val artist: String,
    val filename: String,
    val durationMs: Long,
    val sizeBytes: Long
)

object DuplicateMatcher {
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\.[a-z0-9]{2,5}$"), "")
            .replace(Regex("\\s*\\((?:copy|\\d+)\\)$"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    /** Keep version markers like 'live'/'remaster'; removing them would create false matches. */
    fun possibleGroups(songs: List<DuplicateSignature>): List<List<Long>> {
        val valid = songs.filter { it.durationMs > 0L }
        val titleGroups = valid.filter {
            normalize(it.title).length >= 3 && normalize(it.artist).length >= 2 &&
                !it.artist.startsWith("Unknown", true)
        }.groupBy { normalize(it.artist) + "|" + normalize(it.title) }.values
        val nameGroups = valid.filter { normalize(it.filename).length >= 3 }
            .groupBy { normalize(it.filename) }.values
        return (titleGroups + nameGroups)
            .filter { it.size > 1 }
            .flatMap { group ->
                // Close-duration matches only; unrelated songs sharing a generic name must not match.
                group.sortedBy { it.durationMs }.mapIndexedNotNull { index, song ->
                    val neighbours = group.filter { other ->
                        other.id != song.id &&
                            kotlin.math.abs(other.durationMs - song.durationMs) <= 2_500L
                    }
                    if (neighbours.isEmpty()) null
                    else (listOf(song.id) + neighbours.map { it.id }).distinct().sorted()
                }
            }.distinct().filter { it.size >= 2 }
    }

    /** Exact byte matches must have identical sizes, even when tags and filenames differ. */
    fun sameSizeGroups(songs: List<DuplicateSignature>): List<List<Long>> =
        songs.filter { it.sizeBytes > 0L }.groupBy { it.sizeBytes }.values
            .filter { it.size > 1 }.map { group -> group.map { it.id } }
}
