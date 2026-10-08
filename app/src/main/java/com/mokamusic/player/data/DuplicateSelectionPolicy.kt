package com.mokamusic.player.data

/**
 * Explicit selection policy for deletion from SHA-256 verified duplicate groups.
 * No file is auto-selected. A batch must leave at least one copy in EVERY group.
 */
object DuplicateSelectionPolicy {
    const val MAX_FILES_PER_REQUEST = 100

    fun canSelect(
        groups: List<List<Long>>, selected: Set<Long>, id: Long
    ): Boolean {
        if (id in selected || selected.size >= MAX_FILES_PER_REQUEST) return false
        val matches = groups.filter { id in it }
        return matches.isNotEmpty() && matches.all { group ->
            group.size > 1 && group.count { it in selected } < group.size - 1
        }
    }

    fun valid(
        groups: List<List<Long>>, selected: Set<Long>
    ): Boolean {
        if (selected.isEmpty() || selected.size > MAX_FILES_PER_REQUEST) return false
        val allVerified = groups.flatten().toSet()
        if (!allVerified.containsAll(selected)) return false
        return groups.all { group ->
            group.any { it !in selected }
        }
    }
}
