package dev.amatv.flyme

import dev.amatv.model.LyricLine
import androidx.compose.ui.geometry.Rect
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
        assertEquals(listOf(1_000L, 1_700L), units.map { it.word.startMs })
        assertEquals(listOf(1_500L, 2_100L), units.map { it.word.endMs })
    }

    @Test
    fun latinSyllableGroupsKeepTheSeparateTimingsUsedByScrollingLyrics() {
        val line = LyricLine(words = listOf(
            LyricWord("Jun", 1_000, 1_200, emphasisGroupId = 1, hasTrailingWhitespace = false),
            LyricWord("gle", 1_200, 1_800, emphasisGroupId = 1),
        ))
        assertEquals(
            listOf(PosterSweepUnit(0, 3, line.words[0]), PosterSweepUnit(3, 6, line.words[1])),
            posterSweepUnits(line),
        )
    }

    @Test
    fun chineseAndJapaneseKeepTheTimingOfTheOriginalSpan() {
        val line = LyricLine(words = listOf(
            LyricWord("森林", 1_000, 1_400),
            LyricWord("か\u3099な", 1_400, 1_800),
        ), joinWithSpaces = false)
        val units = posterSweepUnits(line)
        assertEquals(
            listOf(PosterSweepUnit(0, 2, line.words[0]), PosterSweepUnit(2, 5, line.words[1])),
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
            listOf(PosterSweepUnit(0, 2, line.words[0]), PosterSweepUnit(2, 4, line.words[1])),
            posterSweepUnits(line),
        )
    }

    @Test
    fun sharedSweepAdvancesWithinWordsAndHoldsDuringARealPause() {
        val words = listOf(
            KaraokeSweepWord(LyricWord("one", 1_000, 1_100), 0f, 60f),
            KaraokeSweepWord(LyricWord("two", 1_400, 1_800), 100f, 160f),
        )
        val scrolling = KaraokeRowSweep(words, 260f, 30f, isRtl = false)
        val poster = PosterSweepRow(words, 200f, 30f, isRtl = false, nextRowStartMs = null)

        val settled = poster.highlightAt(1_100)
        for (time in 1_100L until 1_400L step 10) {
            assertEquals(settled, poster.highlightAt(time))
            assertEquals(scrolling.highlightAt(1_100), scrolling.highlightAt(time))
        }
        fun ramp(time: Long) = (poster.highlightAt(time) as KaraokeRowHighlight.Partial).ramp
        assertTrue(ramp(1_090).end > ramp(1_010).end)
        assertTrue(ramp(1_700).end > ramp(1_500).end)
        assertEquals(30f, ramp(1_700).end - ramp(1_700).start, 0.001f)
    }

    @Test
    fun rtlRowsUseTheSameFeatherInTheOppositeDirection() {
        val word = LyricWord("test", 1_000, 2_000)
        val row = PosterSweepRow(
            listOf(KaraokeSweepWord(word, 0f, 100f)), 100f, 30f,
            isRtl = true, nextRowStartMs = null,
        )
        val ramp = (row.highlightAt(1_500) as KaraokeRowHighlight.Partial).ramp
        assertEquals(30f, ramp.end - ramp.start, 0.001f)
        assertEquals(1f, ramp.fractionAt(100f), 0.001f)
        assertEquals(0f, ramp.fractionAt(0f), 0.001f)
    }

    @Test
    fun splitWordKeepsItsOriginalDurationAcrossTwoVisualRows() {
        val line = LyricLine(listOf(LyricWord("abcdefgh", 1_000, 1_800)))
        val units = posterSweepUnits(line)
        val first = posterSweepWordsOnRow(units, 0, 3) { glyphBounds(it) }.single()
        val second = posterSweepWordsOnRow(units, 3, 8) { glyphBounds(it - 3) }.single()
        assertEquals(first.word.startMs + 300, first.word.endMs)
        assertEquals(first.word.endMs, second.word.startMs)
        assertEquals(line.words.single().endMs, second.word.endMs)
    }

    @Test
    fun phonePortraitCompletesTheFirstOfThreeRows() {
        assertMiddleRowHighlight(widthPx = 280, expectedWordCounts = listOf(3, 3, 3))
    }

    @Test
    fun carLandscapeCompletesTheFirstOfThreeRows() {
        assertMiddleRowHighlight(widthPx = 400, expectedWordCounts = listOf(4, 4, 1))
    }

    private fun assertMiddleRowHighlight(widthPx: Int, expectedWordCounts: List<Int>) {
        val line = LyricLine(listOf(
            LyricWord("abcdefgh", 1_000, 1_300),
            LyricWord("ijklmnop", 1_300, 1_600),
            LyricWord("qrstuvwx", 1_900, 3_000),
            LyricWord("yzabcdef", 1_900, 3_000),
            LyricWord("ghijklmn", 1_900, 2_500),
            LyricWord("opqrstuv", 3_000, 3_300),
            LyricWord("wxyzabcd", 3_300, 3_600),
            LyricWord("efghijkl", 3_600, 3_900),
            LyricWord("mnopqrst", 3_900, 4_200),
        ))
        val units = posterSweepUnits(line)
        // Fixed advances make the two wrap patterns repeatable without Android fonts.
        val wordsByRow = buildList {
            var first = 0
            while (first < units.size) {
                val start = units[first].startOffset
                val last = units.indexOfLast { (it.endOffset - start) * GLYPH_PX <= widthPx }
                add(posterSweepWordsOnRow(units, start, units[last].endOffset) { glyphBounds(it - start) })
                first = last + 1
            }
        }
        assertEquals(expectedWordCounts, wordsByRow.map { it.size })
        val rows = wordsByRow.mapIndexed { index, words ->
            PosterSweepRow(
                words, widthPx.toFloat(), 30f, isRtl = false,
                nextRowStartMs = wordsByRow.getOrNull(index + 1)?.first()?.word?.startMs,
            )
        }
        assertTrue(rows[0].highlightAt(1_899) is KaraokeRowHighlight.Partial)
        val middleRow = wordsByRow[1]
        val midpoint = (middleRow.first().word.startMs + middleRow.last().word.endMs) / 2
        for (time in listOf(2_050L, midpoint)) {
            assertEquals(KaraokeRowHighlight.Lit, rows[0].highlightAt(time))
            assertTrue(rows[1].highlightAt(time) is KaraokeRowHighlight.Partial)
            assertEquals(KaraokeRowHighlight.Unlit, rows[2].highlightAt(time))
        }
    }

    private fun glyphBounds(offset: Int) =
        Rect(offset * GLYPH_PX, 0f, (offset + 1) * GLYPH_PX, 20f)

    private companion object {
        const val GLYPH_PX = 10f
    }
}
