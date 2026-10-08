package com.mokamusic.player.audio

import android.content.Context
import android.util.AtomicFile
import com.mokamusic.player.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject

data class LoudnessFailureRecord(
    val trackId: Long,
    val fingerprint: String,
    val reasonCode: String,
    val reason: String,
    val attempts: Int,
    val lastAttemptEpochSeconds: Long,
    val retryAfterEpochSeconds: Long
)

/**
 * Failure history is stored separately from successful LUFS measurements.
 * A failed song is never treated as having valid normalization data.
 */
class LoudnessFailureStore(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve("moka_loudness_failures.json"))
    private val rows = LinkedHashMap<Long, LoudnessFailureRecord>()

    init { load() }

    @Synchronized
    fun skipped(track: MusicTrack, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): LoudnessFailureRecord? =
        rows[track.id]?.takeIf { record ->
            LoudnessRetryPolicy.shouldSkip(
                record.fingerprint == LoudnessAnalysisStore.fingerprint(track),
                record.retryAfterEpochSeconds,
                nowEpochSeconds
            )
        }

    @Synchronized
    fun recordFailure(
        track: MusicTrack,
        error: Throwable,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): LoudnessFailureRecord {
        val fingerprint = LoudnessAnalysisStore.fingerprint(track)
        val previous = rows[track.id]?.takeIf { it.fingerprint == fingerprint }
        val attempts = ((previous?.attempts ?: 0) + 1).coerceAtMost(1000)
        val typed = error as? LoudnessAnalysisException
        val code = typed?.failureCode ?: error.javaClass.simpleName
        val permanent = typed?.permanent ?: false
        val record = LoudnessFailureRecord(
            trackId = track.id,
            fingerprint = fingerprint,
            reasonCode = code.take(80),
            reason = (error.message ?: error.javaClass.simpleName).take(300),
            attempts = attempts,
            lastAttemptEpochSeconds = nowEpochSeconds,
            retryAfterEpochSeconds = LoudnessRetryPolicy.retryAfter(nowEpochSeconds, attempts, permanent)
        )
        rows[track.id] = record
        saveLocked()
        return record
    }

    @Synchronized
    fun remove(track: MusicTrack) {
        if (rows.remove(track.id) != null) saveLocked()
    }

    @Synchronized
    fun count(): Int = rows.size

    @Synchronized
    fun clear() {
        rows.clear()
        file.delete()
    }

    @Synchronized
    private fun load() {
        rows.clear()
        runCatching {
            if (!file.baseFile.exists()) return@runCatching
            val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (root.optInt("schema", 0) != SCHEMA) return@runCatching
            val items = root.optJSONArray("failures") ?: return@runCatching
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optLong("trackId", Long.MIN_VALUE)
                val fp = o.optString("fingerprint").takeIf { it.isNotBlank() } ?: continue
                if (id == Long.MIN_VALUE) continue
                rows[id] = LoudnessFailureRecord(
                    trackId = id,
                    fingerprint = fp,
                    reasonCode = o.optString("reasonCode", "UNKNOWN"),
                    reason = o.optString("reason", ""),
                    attempts = o.optInt("attempts", 1).coerceAtLeast(1),
                    lastAttemptEpochSeconds = o.optLong("lastAttemptEpochSeconds", 0L),
                    retryAfterEpochSeconds = o.optLong("retryAfterEpochSeconds", 0L)
                )
            }
        }
    }

    @Synchronized
    private fun saveLocked() {
        val root = JSONObject().put("schema", SCHEMA).put("failures", JSONArray().apply {
            rows.values.forEach { r ->
                put(JSONObject().apply {
                    put("trackId", r.trackId)
                    put("fingerprint", r.fingerprint)
                    put("reasonCode", r.reasonCode)
                    put("reason", r.reason)
                    put("attempts", r.attempts)
                    put("lastAttemptEpochSeconds", r.lastAttemptEpochSeconds)
                    put("retryAfterEpochSeconds", r.retryAfterEpochSeconds)
                })
            }
        })
        val out = file.startWrite()
        try {
            // AtomicFile owns closing/syncing out: never close it before finishWrite().
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (t: Throwable) {
            runCatching { file.failWrite(out) }
            throw t
        }
    }

    private companion object {
        const val SCHEMA = 1
    }
}
