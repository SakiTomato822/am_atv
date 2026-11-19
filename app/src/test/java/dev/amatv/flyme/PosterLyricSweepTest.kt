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
        assertEquals(listOf(1_000L, 1_700L), units.map { it.startMs })
        assertEquals(listOf(1_500L, 2_100L), units.map { it.endMs })
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
    fun chineseAndJapaneseKeepTheTimingOfTheOriginalSpan() {
        val line = LyricLine(words = listOf(
            LyricWord("森林", 1_000, 1_400),
            LyricWord("か\u3099な", 1_400, 1_800),
        ), joinWithSpaces = false)
        val units = posterSweepUnits(line)
        assertEquals(
            listOf(PosterSweepUnit(0, 2, 1_000, 1_400), PosterSweepUnit(2, 5, 1_400, 1_800)),
            units,
        )
    }

    @Test
    fun timedSupplementaryHanziUseFullSurrogatePairOffsets() {
        val line = LyricLine(
            listOf(LyricWord("𠀀", 1_000, 1_200), LyricWord("𠀁", 1_200, 1_400)),
            joinWithSpaces = false,
        )
        assertEquals(
            listOf(PosterSweepUnit(0, 2, 1_000, 1_200), PosterSweepUnit(2, 4, 1_200, 1_400)),
            posterSweepUnits(line),
        )
    }

    @Test
    fun lineTimingSweepsAtAConstantRateForTheExplicitLineDuration() {
        val words = listOf(LyricWord("Jungle", 1_000, 2_000))
        val line = LyricLine(words, lineStartMs = 900, lineEndMs = 2_400, timing = LyricTiming.Line)
        assertTrue(posterSweepUnits(line).isEmpty())
        assertEquals(listOf(PosterSweepUnit(0, 6, 1_000, 2_000)), posterSweepUnits(LyricLine(words, timing = LyricTiming.Word)))
        val sweep = PosterSweepAxis(line.startMs, posterLeadEndMs(line), 150f, emptyList())
        assertEquals(0f, sweep.positionAt(890), 0f)
        for (time in 900L..2_400L step 10L) {
            assertEquals((time - 900) / 10f, sweep.positionAt(time), 0.001f)
        }
        assertEquals(150f, sweep.positionAt(2_410), 0f)
    }

    @Test
    fun featherClearsTheFirstRowBeforeStartingTheNextRow() {
        val width = 80f
        val feather = 24f
        for (pixel in 0..80) {
            var last = 0f
            for (step in 0..80) {
                val ramp = posterSweepRamp(step.toFloat(), width, feather)
                val lit = 1f - ramp.fractionAt(pixel.toFloat())
                if (step == 0) assertEquals(0f, lit, 0.001f)
                assertTrue(lit >= last)
                last = lit
            }
            assertEquals(1f, last, 0.001f)
        }
        val firstRowEnd = posterSweepRamp(width, width, feather)
        val secondRowStart = posterSweepRamp(0f, 50f, feather)
        assertEquals(width, firstRowEnd.start, 0f)
        assertEquals(0f, secondRowStart.end, 0f)
    }

    @Test
    fun sweepPixelsKeepIncreasingEveryTenMillisecondsIncludingBetweenWords() {
        val sweep = PosterSweepAxis(1_000, 2_100, 150f, listOf(
            PosterSweepSpan(PosterSweepUnit(0, 7, 1_000, 1_500), 0f, 80f),
            PosterSweepSpan(PosterSweepUnit(9, 15, 1_700, 2_100), 96f, 150f),
        ))
        var previousPx = 0f
        for (time in 1_010L..2_100L step 10L) {
            val px = sweep.positionAt(time)
            assertTrue("Sweep stopped or reversed at $time ms", px > previousPx)
            previousPx = px
        }
        for (time in 1_500L..1_700L step 10L) {
            assertEquals(80f + (time - 1_500) * 0.08f, sweep.positionAt(time), 0.001f)
        }
    }

    @Test
    fun touchingWordBoundsAndOverlappingTimesKeepTheSweepMoving() {
        val sweep = PosterSweepAxis(1_000, 2_000, 160f, listOf(
            PosterSweepSpan(PosterSweepUnit(0, 2, 1_000, 1_350), 0f, 40f),
            PosterSweepSpan(PosterSweepUnit(2, 4, 1_500, 1_800), 40f, 80f),
            PosterSweepSpan(PosterSweepUnit(5, 9, 1_700, 2_000), 85f, 160f),
        ))
        for (time in 1_010L..2_000L step 10L) {
            assertTrue(sweep.positionAt(time) > sweep.positionAt(time - 10))
        }
        assertEquals(40f, sweep.positionAt(1_350), 0f)
        assertEquals(80f, sweep.positionAt(1_700), 0f)
    }
}
