package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class FlymeCarLyricsTest {
    @Test fun lineTimingDividesTheCueAmongVisibleCharactersWithoutSplittingSurrogates() {
        val line = LyricLine(listOf(LyricWord("夜🌙风", 1_000, 4_000)))
        assertEquals(listOf(
            CarLyricHighlight(0, 1, 1_000, 2_000),
            CarLyricHighlight(1, 3, 2_000, 3_000),
            CarLyricHighlight(3, 4, 3_000, 4_000),
        ), carLyricHighlights(line))
    }

    @Test fun wordTimingPreservesAppleCuesAndTextOffsets() {
        val line = LyricLine(listOf(LyricWord("night", 800, 1_100), LyricWord("drive", 1_450, 2_200)))
        assertEquals(listOf(
            CarLyricHighlight(0, 5, 800, 1_100),
            CarLyricHighlight(6, 11, 1_450, 2_200),
        ), carLyricHighlights(line))
    }
}
