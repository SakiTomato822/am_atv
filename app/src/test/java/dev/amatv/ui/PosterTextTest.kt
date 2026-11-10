package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterTextTest {
    @Test fun cjkPhrasesGetTheirOwnLines() {
        assertEquals("童年的\n荡秋千", posterText("童年的 荡秋千"))
        assertEquals("夏日\n乾いた雲", posterText(" 夏日  乾いた雲 "))
    }

    @Test fun latinLinesKeepTheirSpaces() {
        assertEquals("No way to stop, no way to fight", posterText("No way to stop, no way to fight"))
    }
}

class LyricDisplayTextTest {
    private fun line(vararg words: String) = dev.amatv.model.LyricLine(
        words.mapIndexed { i, w -> dev.amatv.model.LyricWord(w, i * 100L, i * 100L + 90) })

    @Test fun cjkUnitsJoinWithoutSpacesButKeepTheirOwnGaps() {
        assertEquals("還有多少回憶 藏著", lyricDisplayText(line("還", "有", "多少", "回憶 ", "藏", "著")))
    }

    @Test fun latinWordsKeepSingleSpaces() {
        assertEquals("night drive home", lyricDisplayText(line("night", "drive", "home")))
    }
}
