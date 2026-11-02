package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChineseTranslationFilterTest {
    private fun line(text: String, translation: String) = LyricLine(
        words = listOf(LyricWord(text, 0L, 1_000L)), translation = translation, joinWithSpaces = false,
    )

    @Test fun traditionalLyricsLoseTheirSimplifiedTranslation() {
        val result = listOf(line("你給我多少時間", "你给我多少时间"))
            .withoutChineseToChineseTranslations(Locale.SIMPLIFIED_CHINESE)
        assertNull(result.single().translation)
    }

    @Test fun englishAndJapaneseLinesKeepTheirTranslation() {
        val lines = listOf(line("I like you, I do", "我喜欢你"), line("夜に駆ける", "奔向夜晚"))
        val result = lines.withoutChineseToChineseTranslations(Locale.SIMPLIFIED_CHINESE)
        assertEquals(listOf("我喜欢你", "奔向夜晚"), result.map { it.translation })
    }

    @Test fun nonChineseTargetsAreUntouched() {
        val lines = listOf(line("你給我多少時間", "How much time will you give me"))
        assertEquals(lines, lines.withoutChineseToChineseTranslations(Locale.ENGLISH))
    }

    @Test fun detectsChineseLines() {
        assertTrue(isChineseLyric("讓愛再繼續"))
        assertFalse(isChineseLyric("ヤーヤー唱タイム"))
        assertFalse(isChineseLyric("Hell yeah"))
    }
}
