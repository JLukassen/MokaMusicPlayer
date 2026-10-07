package com.mokamusic.player.audio

import android.content.Context
import android.util.AtomicFile
import com.mokamusic.player.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

data class LoudnessRecord(
    val trackId: Long,
    val fingerprint: String,
    val integratedLufs: Float,
    val estimatedTruePeakDbtp: Float,
    val trackGainDb: Float,
    val albumGainDb: Float? = null,
    val analyzedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L
)

/** Persistent offline loudness results. Audio files are never modified. */
class LoudnessAnalysisStore(context: Context) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve("moka_loudness_analysis.json"))
    private val rows = LinkedHashMap<Long, LoudnessRecord>()

    init { load() }

    @Synchronized
    fun get(track: MusicTrack): LoudnessRecord? = rows[track.id]?.takeIf { it.fingerprint == fingerprint(track) }

    @Synchronized
    fun put(record: LoudnessRecord) {
        rows[record.trackId] = record
        saveLocked()
    }

    @Synchronized
    fun putAll(records: Collection<LoudnessRecord>) {
        records.forEach { rows[it.trackId] = it }
        saveLocked()
    }

    @Synchronized
    fun count(): Int = rows.size

    @Synchronized
    fun clear() {
        rows.clear()
        runCatching { file.delete() }
    }

    @Synchronized
    private fun load() {
        rows.clear()
        runCatching {
            if (!file.baseFile.exists()) return@runCatching
            val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (root.optInt("schema", 0) != SCHEMA) return@runCatching
            val a = root.optJSONArray("tracks") ?: return@runCatching
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optLong("trackId", Long.MIN_VALUE)
                val fp = o.optString("fingerprint").takeIf { it.isNotBlank() } ?: continue
                if (id == Long.MIN_VALUE) continue
                rows[id] = LoudnessRecord(
                    trackId = id,
                    fingerprint = fp,
                    integratedLufs = o.optDouble("integratedLufs", Double.NaN).toFloat(),
                    estimatedTruePeakDbtp = o.optDouble("estimatedTruePeakDbtp", Double.NaN).toFloat(),
                    trackGainDb = o.optDouble("trackGainDb", Double.NaN).toFloat(),
                    albumGainDb = if (o.has("albumGainDb") && !o.isNull("albumGainDb")) o.optDouble("albumGainDb").toFloat() else null,
                    analyzedAtEpochSeconds = o.optLong("analyzedAtEpochSeconds", 0L)
                )
            }
        }
    }

    private fun saveLocked() {
        val root = JSONObject().put("schema", SCHEMA).put("tracks", JSONArray().apply {
            rows.values.forEach { r ->
                put(JSONObject().apply {
                    put("trackId", r.trackId)
                    put("fingerprint", r.fingerprint)
                    put("integratedLufs", r.integratedLufs)
                    put("estimatedTruePeakDbtp", r.estimatedTruePeakDbtp)
                    put("trackGainDb", r.trackGainDb)
                    if (r.albumGainDb == null) put("albumGainDb", JSONObject.NULL) else put("albumGainDb", r.albumGainDb)
                    put("analyzedAtEpochSeconds", r.analyzedAtEpochSeconds)
                })
            }
        })
        val out = file.startWrite()
        try {
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(root.toString()) }
            file.finishWrite(out)
        } catch (t: Throwable) {
            runCatching { file.failWrite(out) }
            throw t
        }
    }

    companion object {
        private const val SCHEMA = 1
        fun fingerprint(track: MusicTrack): String = "${track.sizeBytes}:${track.durationMs}:${track.displayName}"
    }
}
