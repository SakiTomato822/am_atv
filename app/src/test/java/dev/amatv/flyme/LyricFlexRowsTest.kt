package dev.amatv.flyme

import org.junit.Assert.*
import org.junit.Test

class LyricFlexRowsTest {
    @Test fun oversizedNonBreakingGroupFallsBackToBoundedWordWrapping() {
        val rows = lyricFlexRows(
            groupIds = listOf(7, 7, 7, 7),
            wordWidths = intArrayOf(58, 42, 55, 45),
            maxWidth = 100,
        )

        assertEquals(2, rows.size)
        assertArrayEquals(intArrayOf(0, 1), rows[0])
        assertArrayEquals(intArrayOf(2, 3), rows[1])
        assertTrue(rows.all { row -> row.sumOf { intArrayOf(58, 42, 55, 45)[it] } <= 100 })
    }

    @Test fun ordinaryNonBreakingGroupRemainsAtomicAtTheWrapBoundary() {
        val rows = lyricFlexRows(
            groupIds = listOf(null, 9, 9, null),
            wordWidths = intArrayOf(35, 30, 30, 35),
            maxWidth = 100,
        )

        assertEquals(2, rows.size)
        assertArrayEquals(intArrayOf(0, 1, 2), rows[0])
        assertArrayEquals(intArrayOf(3), rows[1])
    }
}
