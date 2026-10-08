package com.mokamusic.player.data

import org.junit.Assert.*
import org.junit.Test

class DuplicateSelectionPolicyTest {
    private val groups = listOf(listOf(1L, 2L, 3L), listOf(4L, 5L))

    @Test fun noDefaultSelectionAndOneCopyPerGroupMustRemain() {
        assertFalse(DuplicateSelectionPolicy.valid(groups, emptySet()))
        assertTrue(DuplicateSelectionPolicy.canSelect(groups, emptySet(), 1))
        assertTrue(DuplicateSelectionPolicy.canSelect(groups, setOf(1), 2))
        assertFalse(DuplicateSelectionPolicy.canSelect(groups, setOf(1, 2), 3))
        assertTrue(DuplicateSelectionPolicy.valid(groups, setOf(1, 2, 4)))
        assertFalse(DuplicateSelectionPolicy.valid(groups, setOf(1, 2, 3)))
        assertFalse(DuplicateSelectionPolicy.valid(groups, setOf(4, 5)))
    }

    @Test fun nonVerifiedTrackCannotBeSelectedOrDeleted() {
        assertFalse(DuplicateSelectionPolicy.canSelect(groups, emptySet(), 999))
        assertFalse(DuplicateSelectionPolicy.valid(groups, setOf(1L, 999L)))
    }

    @Test fun maximumBatchSizeIsEnforced() {
        val manyGroups = (1..101).map { i ->
            listOf(i.toLong(), (i + 1000).toLong())
        }
        val selected = (1L..100L).toSet()
        assertTrue(DuplicateSelectionPolicy.valid(manyGroups, selected))
        assertFalse(DuplicateSelectionPolicy.canSelect(manyGroups, selected, 101L))
        assertFalse(DuplicateSelectionPolicy.valid(manyGroups, selected + 101L))
    }

    @Test fun overlappingGroupsMustEachKeepOne() {
        val overlap = listOf(listOf(1L, 2L), listOf(2L, 3L))
        assertFalse(DuplicateSelectionPolicy.canSelect(overlap, setOf(1L), 2L))
        assertFalse(DuplicateSelectionPolicy.valid(overlap, setOf(1L, 2L)))
        assertTrue(DuplicateSelectionPolicy.valid(overlap, setOf(2L)))
    }
}
