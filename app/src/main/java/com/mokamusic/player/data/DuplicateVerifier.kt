package com.mokamusic.player.data

import android.content.Context
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class VerifiedDuplicateGroup(val tracks: List<MusicTrack>, val sha256: String)
data class DuplicateReview(
    val exact: List<VerifiedDuplicateGroup>,
    val possible: List<List<MusicTrack>>,
    val hashedFiles: Int,
    val unreadableFiles: Int
)

/**
 * Opt-in, non-destructive deep scan. SHA-256 is computed from the complete file contents,
 * only for files sharing a reported byte size. Filename/title/duration differences
 * are useful for review but are NEVER treated as permission to delete.
 */
object DuplicateVerifier {
    suspend fun scan(
        context: Context,
        tracks: List<MusicTrack>,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): DuplicateReview = withContext(Dispatchers.IO) {
        val local = tracks.filter { it.uri.scheme == "content" && it.uri.authority == "media" }
            .distinctBy { it.uri.toString() }
        val sig = local.map {
            DuplicateSignature(it.id, it.title, it.artist, it.displayName,
                it.durationMs, it.sizeBytes)
        }
        val lookup = local.associateBy { it.id }
        val fileIds = DuplicateMatcher.sameSizeGroups(sig).flatten().distinct()
        val hashes = mutableMapOf<Long, String>()
        var unreadable = 0
        fileIds.forEachIndexed { index, id ->
            currentCoroutineContext().ensureActive()
            val track = lookup[id]
            val hash = track?.let { runCatching { fullHash(context, it) }.getOrNull() }
            if (hash != null) hashes[id] = hash else unreadable++
            onProgress(index + 1, fileIds.size)
        }
        val exact = local.filter { hashes.containsKey(it.id) }
            .groupBy { it.sizeBytes.toString() + ":" + hashes[it.id] }
            .filterValues { it.size > 1 }
            .map { (key, group) -> VerifiedDuplicateGroup(group, key.substringAfter(":")) }
            .sortedByDescending { it.tracks.size }
        val verifiedSets = exact.map { group -> group.tracks.map { it.id }.toSet() }
        val possible = DuplicateMatcher.possibleGroups(sig).map { ids ->
            ids.mapNotNull(lookup::get)
        }.filter { group ->
            group.size > 1 && verifiedSets.none { exactIds ->
                group.all { it.id in exactIds }
            }
        }.distinctBy { group -> group.map { it.id }.sorted().joinToString(",") }

        DuplicateReview(exact, possible, hashes.size, unreadable)
    }

    private suspend fun fullHash(context: Context, track: MusicTrack): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val source = context.contentResolver.openInputStream(track.uri)
            ?: error("Cannot open media")
        source.buffered(64 * 1024).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
                count += n
            }
        }
        // Stale MediaStore sizes or changing files are never declared verified.
        check(count == track.sizeBytes) { "Media size changed during hashing" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
