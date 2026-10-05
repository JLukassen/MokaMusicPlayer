package com.mokamusic.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedQueue(val name: String, val trackIds: List<Long>)

/** Small local playlist store. It stores only MediaStore IDs; audio files remain untouched. */
class SavedQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moka_saved_queues", Context.MODE_PRIVATE)

    fun list(): List<SavedQueue> = runCatching {
        val rows = JSONArray(prefs.getString(KEY_QUEUES, "[]") ?: "[]")
        buildList {
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val name = o.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                val idsArray = o.optJSONArray("ids") ?: JSONArray()
                val ids = buildList {
                    for (j in 0 until idsArray.length()) {
                        val id = idsArray.optLong(j, Long.MIN_VALUE)
                        if (id != Long.MIN_VALUE) add(id)
                    }
                }
                if (ids.isNotEmpty()) add(SavedQueue(name, ids))
            }
        }
    }.getOrDefault(emptyList())

    fun save(name: String, trackIds: List<Long>) {
        val cleanName = name.trim().ifBlank { "Saved queue" }
        val cleanIds = trackIds.distinct()
        if (cleanIds.isEmpty()) return
        val next = list().filterNot { it.name.equals(cleanName, ignoreCase = true) }.toMutableList()
        next += SavedQueue(cleanName, cleanIds)
        write(next.sortedBy { it.name.lowercase() })
    }

    fun delete(name: String) {
        write(list().filterNot { it.name == name })
    }

    private fun write(queues: List<SavedQueue>) {
        val rows = JSONArray()
        queues.forEach { queue ->
            rows.put(JSONObject().apply {
                put("name", queue.name)
                put("ids", JSONArray().apply { queue.trackIds.forEach { id -> put(id) } })
            })
        }
        prefs.edit().putString(KEY_QUEUES, rows.toString()).apply()
    }

    private companion object { const val KEY_QUEUES = "queues" }
}
