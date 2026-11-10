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
