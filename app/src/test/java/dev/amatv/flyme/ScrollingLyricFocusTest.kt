package dev.amatv.flyme

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollingLyricFocusTest {
    @Test
    fun detachedBackingIsReservedBelowTheLeadWithoutChangingFocusAtEitherBoundary() {
        val lead = LyricLine(listOf(LyricWord("lead", 1_000, 3_000)))
        val harmony = LyricLine(listOf(LyricWord("harmony", 1_900, 2_400, isBackgroundVocal = true)))
        val content = scrollingLyricContent(listOf(lead, harmony))
        assertEquals(listOf(lead), content.leads)
        val backing = content.attachedBacking.getValue(0).single()
        assertTrue(backing.words.isEmpty())
        assertEquals(harmony.words, backing.backgroundWords)
        val schedule = LyricTimeline.Schedule(content.leads)
        val before = scrollingLyricFocusAt(content.leads, schedule, 1_899)
        for (ms in listOf(1_900L, 1_901L, 2_399L, 2_400L, 2_401L)) {
            assertEquals(before, scrollingLyricFocusAt(content.leads, schedule, ms))
        }
    }
    @Test
    fun localizationFollowsHalfOfTheMainTextScaleChange() {
        assertEquals(0.875f, scrollingLyricLocalizationScale(0.75f), 0f)
        assertEquals(1f, scrollingLyricLocalizationScale(1f), 0f)
        assertEquals(1.25f, scrollingLyricLocalizationScale(1.5f), 0f)
    }

    @Test
    fun wholeLineInkKeepsTheNormalScrollingAnchorAndNeighbours() {
        val lines = listOf(line("First", 8_000, 9_200), line("Next", 9_000, 10_000), line("Final", 18_000, 19_000))
        val schedule = LyricTimeline.Schedule(lines)
        val intro = scrollingLyricFocusAt(lines, schedule, 7_999)
        assertEquals(0, intro.waiting?.beforeLineIndex)
        assertEquals(KaraokeRowHighlight.Unlit, intro.wholeLineHighlight(0, isStartedFinalLead = false))

        val overlap = scrollingLyricFocusAt(lines, schedule, 9_050)
        assertEquals(1, overlap.anchorIndex)
        assertTrue(overlap.isCurrent(0))
        assertTrue(overlap.isCurrent(1))
        assertFalse(overlap.isCurrent(2))
        assertEquals(KaraokeRowHighlight.Lit, overlap.wholeLineHighlight(0, isStartedFinalLead = false))
        assertEquals(KaraokeRowHighlight.Lit, overlap.wholeLineHighlight(1, isStartedFinalLead = false))
        assertEquals(KaraokeRowHighlight.Unlit, overlap.wholeLineHighlight(2, isStartedFinalLead = false))

        val handoff = scrollingLyricFocusAt(lines, schedule, 9_200)
        assertEquals(1, handoff.anchorIndex)
        assertEquals(KaraokeRowHighlight.Unlit, handoff.wholeLineHighlight(0, isStartedFinalLead = false))
        assertEquals(KaraokeRowHighlight.Lit, handoff.wholeLineHighlight(1, isStartedFinalLead = false))

        val gap = scrollingLyricFocusAt(lines, schedule, 17_999)
        assertEquals(2, gap.waiting?.beforeLineIndex)
        assertEquals(KaraokeRowHighlight.Unlit, gap.wholeLineHighlight(2, isStartedFinalLead = false))
        val finished = scrollingLyricFocusAt(lines, schedule, 20_000)
        assertEquals(2, finished.anchorIndex)
        assertEquals(KaraokeRowHighlight.Lit, finished.wholeLineHighlight(2, isStartedFinalLead = true))
    }

    @Test
    fun introKeepsFirstLineUnlitUntilItsStart() {
        val lines = listOf(line("first", 9_000, 10_000), line("second", 10_000, 11_000))
        val schedule = LyricTimeline.Schedule(lines)

        assertEquals(listOf(0), schedule.snapshotAt(8_500).activeLineIndices)
        for (positionMs in 0L until 9_000L) {
            val focus = scrollingLyricFocusAt(lines, schedule, positionMs)
            assertEquals(0, focus.waiting?.beforeLineIndex)
            assertEquals(0, focus.anchorIndex)
            assertFalse(focus.isCurrent(0))
            assertEquals(1, focus.styleDistance(0))
        }
        val first = scrollingLyricFocusAt(lines, schedule, 9_000)
        assertNull(first.waiting)
        assertEquals(0, first.anchorIndex)
        assertTrue(first.isCurrent(0))
        assertEquals(0, first.styleDistance(0))
    }

    @Test
    fun sevenSecondInterludeKeepsNextLineUnlitUntilItsStart() {
        val lines = listOf(line("previous", 1_000, 2_000), line("next", 9_000, 10_000))
        val schedule = LyricTimeline.Schedule(lines)

        assertTrue(scrollingLyricFocusAt(lines, schedule, 1_999).isCurrent(0))
        assertEquals(listOf(1), schedule.snapshotAt(8_500).activeLineIndices)
        for (positionMs in 2_000L until 9_000L) {
            val focus = scrollingLyricFocusAt(lines, schedule, positionMs)
            assertEquals(1, focus.waiting?.beforeLineIndex)
            assertEquals(1, focus.anchorIndex)
            assertFalse(focus.isCurrent(0))
            assertFalse(focus.isCurrent(1))
            assertEquals(1, focus.styleDistance(1))
        }
        val next = scrollingLyricFocusAt(lines, schedule, 9_000)
        assertNull(next.waiting)
        assertEquals(1, next.anchorIndex)
        assertTrue(next.isCurrent(1))
        assertEquals(0, next.styleDistance(1))
        assertFalse(scrollingLyricFocusAt(lines, schedule, 8_999).isCurrent(1))
    }

    @Test
    fun shortIntroCanPositionFirstLineWithoutLightingIt() {
        val lines = listOf(line("first", 1_000, 2_000))
        val schedule = LyricTimeline.Schedule(lines)

        for (positionMs in 0L until 1_000L) {
            val focus = scrollingLyricFocusAt(lines, schedule, positionMs)
            assertNull(focus.waiting)
            assertEquals(0, focus.anchorIndex)
            assertFalse(focus.isCurrent(0))
        }
        assertTrue(scrollingLyricFocusAt(lines, schedule, 1_000).isCurrent(0))
    }

    private fun line(text: String, startMs: Long, endMs: Long) =
        LyricLine(listOf(LyricWord(text, startMs, endMs)))
}
