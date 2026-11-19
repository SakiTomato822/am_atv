package dev.amatv.flyme

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterLyricSweepTest {
    @Test
    fun latinWordsKeepWholeTimingsAndExactDisplayOffsets() {
        val line = LyricLine(words = listOf(
            LyricWord("Welcome", 1_000, 1_500, displayText = "  Welcome  "),
            LyricWord("jungle", 1_700, 2_100),
        ))
        val units = posterSweepUnits(line)
        assertEquals(listOf("Welcome", "jungle"), units.map {
            posterLeadText(line).substring(it.startOffset, it.endOffset)
        })
        assertEquals(0.5f, units[0].progressAt(1_250), 0.001f)
        assertEquals(1f, units[0].progressAt(1_500), 0.001f)
        assertEquals(0f, units[1].progressAt(1_600), 0.001f)
    }

    @Test
    fun latinSyllableGroupsSweepAsOneWord() {
        val line = LyricLine(words = listOf(
            LyricWord("Jun", 1_000, 1_200, emphasisGroupId = 1, hasTrailingWhitespace = false),
            LyricWord("gle", 1_200, 1_800, emphasisGroupId = 1),
        ))
        assertEquals(listOf(PosterSweepUnit(0, 6, 1_000, 1_800)), posterSweepUnits(line))
    }

    @Test
    fun chineseAndJapaneseSplitIntoGraphemesWithinEachTimedWord() {
        val line = LyricLine(words = listOf(
            LyricWord("森林", 1_000, 1_400),
            LyricWord("か\u3099な", 1_400, 1_800),
        ), joinWithSpaces = false)
        val units = posterSweepUnits(line)
        assertEquals(listOf("森", "林", "か\u3099", "な"), units.map {
            posterLeadText(line).substring(it.startOffset, it.endOffset)
        })
        assertEquals(listOf(1_000L, 1_200L, 1_400L, 1_600L), units.map { it.startMs })
        assertEquals(0.5f, units[2].progressAt(1_500), 0.001f)
    }

    @Test
    fun supplementaryHanziAreSeparateWithoutSplittingSurrogatePairs() {
        val line = LyricLine(listOf(LyricWord("𠀀𠀁", 1_000, 1_400)), timing = LyricTiming.Word)
        assertEquals(
            listOf(PosterSweepUnit(0, 2, 1_000, 1_200), PosterSweepUnit(2, 4, 1_200, 1_400)),
            posterSweepUnits(line),
        )
    }

    @Test
    fun lineTimingHasNoSweepAndExplicitlyTimedSingleWordDoes() {
        val words = listOf(LyricWord("Jungle", 1_000, 2_000))
        assertTrue(posterSweepUnits(LyricLine(words, timing = LyricTiming.Line)).isEmpty())
        assertEquals(listOf(PosterSweepUnit(0, 6, 1_000, 2_000)), posterSweepUnits(LyricLine(words, timing = LyricTiming.Word)))
    }

    @Test
    fun featherMonotonicallyLightsTheFullWordInEitherDirection() {
        for (rtl in listOf(false, true)) {
            for (pixel in 0..100) {
                val x = pixel / 100f
                var last = 0f
                for (step in 0..100) {
                    val ramp = posterSweepRamp(step / 100f, feather = 0.25f, rtl = rtl)
                    val lit = if (rtl) ramp.fractionAt(x) else 1f - ramp.fractionAt(x)
                    if (step == 0) assertEquals(0f, lit, 0.001f)
                    assertTrue(lit + 0.001f >= last)
                    last = lit
                }
                assertEquals(1f, last, 0.001f)
            }
        }
    }
}
