package dev.amatv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedLyricTextTest {
    private fun unit(text: String, space: Boolean) =
        dev.amatv.model.LyricWord(text, 0L, 90L, hasTrailingWhitespace = space)

    @Test fun characterTimedCjkKeepsOnlyRealPhraseGaps() {
        val line = dev.amatv.model.LyricLine(listOf(
            unit("我", false), unit("一", false), unit("大早", false), unit("起", false), unit("不", false),
            unit("来", true), unit("我的", false), unit("眼", false), unit("睛", false),
        ))
        assertEquals("我一大早起不来 我的眼睛", line.renderedText)
    }

    @Test fun latinWordsKeepTheirSpaces() {
        val line = dev.amatv.model.LyricLine(listOf(unit("night", true), unit("drive", true), unit("home", true)))
        assertEquals("night drive home", line.renderedText)
    }
}

class LyricFocusTest {
    @Test fun overlappingLinesMoveTheFocusToTheNewestOneBeingSung() {
        // A lead line (5) anchors the group while answering lines 6 and 7 are sung over it.
        assertEquals(7, dev.amatv.model.LyricTimeline.Snapshot(listOf(5, 7), 5, null).focusLineIndex)
        assertEquals(5, dev.amatv.model.LyricTimeline.Snapshot(emptyList(), 5, null).focusLineIndex)
        assertEquals(-1, dev.amatv.model.LyricTimeline.Snapshot(emptyList(), -1, null).focusLineIndex)
    }
}
