package dev.amatv.flyme

import dev.amatv.model.LyricAgentType
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterLyricsTimelineTest {
    @Test
    fun overlappingLeadsAdvanceAtStartAndKeepOnlyTheUnfinishedWordsBelow() {
        val lines = listOf(
            LyricLine(
                words = listOf(word("first", 1_000, 1_500), word("still", 1_500, 2_000), word("singing", 2_000, 3_700)),
                agentId = "second",
                isOppositeAgent = true,
            ),
            line("Next", 1_800, 2_200),
            line("Last", 3_900, 4_100),
        )
        val timeline = PosterLyricsTimeline(lines)
        assertMonotonicFocus(timeline, 4_300)
        assertEquals(0, timeline.at(1_790).focusLeadIndex)
        assertEquals(1, timeline.at(1_800).focusLeadIndex)
        assertEquals(0, timeline.at(1_800).previousLeadIndex)
        assertEquals(listOf(PosterTextLine("still singing", true)), timeline.at(1_800).smallLines)
        assertEquals(listOf(PosterTextLine("singing", true)), timeline.at(2_000).smallLines)
        assertEquals(1, timeline.at(2_210).focusLeadIndex)
        assertTrue(timeline.at(3_700).smallLines.isEmpty())
        assertEquals(2, timeline.at(3_900).focusLeadIndex)
    }

    @Test
    fun repeatedChorusTextUsesLineIdentityThroughOverlaps() {
        val timeline = PosterLyricsTimeline(
            listOf(line("Repeat", 1_000, 3_100), line("Repeat", 2_500, 2_900), line("After", 3_500, 4_000)),
        )
        assertMonotonicFocus(timeline, 4_000)
        assertEquals(0, timeline.at(2_490).focusLeadIndex)
        assertEquals(1, timeline.at(2_500).focusLeadIndex)
        assertEquals(1, timeline.at(3_000).focusLeadIndex)
        assertEquals(listOf(2), timeline.at(3_000).upcomingLeadIndices)
    }

    @Test
    fun groupsAndBackingVocalsStaySmallAndKeepTheSingersInkRule() {
        val lead = line("Lead", 1_000, 1_700).copy(
            agentId = "second",
            isOppositeAgent = true,
            backgroundWords = listOf(word("echo", 1_200, 1_600)),
        )
        val group = line("Group", 1_300, 2_300).copy(
            agentId = "group",
            agentType = LyricAgentType.Group,
            isOppositeAgent = true,
        )
        val backing = LyricLine(backgroundWords = listOf(word("harmony", 1_400, 2_200)), words = emptyList())
        val markedBacking = LyricLine(words = listOf(word("hum", 1_500, 2_000).copy(isBackgroundVocal = true)))
        val timeline = PosterLyricsTimeline(listOf(lead, group, backing, markedBacking, line("Next", 2_400, 2_900)))
        val projection = timeline.at(1_550)
        assertEquals(0, projection.focusLeadIndex)
        assertTrue(isOppositeSingerLine(lead))
        assertEquals(
            listOf(PosterTextLine("(echo)", true), PosterTextLine("Group", false),
                PosterTextLine("(harmony)", false), PosterTextLine("(hum)", false)),
            projection.smallLines,
        )
        assertEquals(listOf(4), projection.upcomingLeadIndices)
        assertEquals(0, timeline.at(2_250).focusLeadIndex)
        assertNull(PosterLyricsTimeline(listOf(group)).at(1_550).focusLeadIndex)
    }

    @Test
    fun clockHoldsFocusAndSweepThroughJitterThenResetsForASeek() {
        val lyric = LyricLine(words = listOf(word("one", 1_500, 1_800), word("two", 1_800, 2_200)))
        val timeline = PosterLyricsTimeline(listOf(line("Before", 200, 1_500), lyric))
        val clock = PosterPlaybackClock(1_470)
        val sweep = posterSweepUnits(lyric).first()
        var previousProgress = 0f
        for (sample in (1_500L..1_900L step 10L).toList() + listOf(1_510L, 1_490L, 1_990L, 990L)) {
            val position = clock.sample(sample)
            assertEquals(1, timeline.at(position).focusLeadIndex)
            val progress = sweep.progressAt(position)
            assertTrue(progress >= previousProgress)
            previousProgress = progress
        }
        assertEquals(0, timeline.at(clock.sample(1_400, seek = true)).focusLeadIndex)
        assertEquals(1L, clock.seekGeneration)
        clock.sample(2_600)
        assertEquals(0, timeline.at(clock.sample(500)).focusLeadIndex)
        assertEquals(2L, clock.seekGeneration)
    }

    @Test
    fun gapHoldsItsDotsUntilTheNextLeadActuallyStarts() {
        val timeline = PosterLyricsTimeline(listOf(line("First", 1_000, 2_000), line("Next", 10_000, 12_000)))
        assertNull(timeline.at(1_990).gap)
        assertEquals(1, timeline.at(2_000).gap?.beforeLineIndex)
        assertEquals(timeline.boundaryAt(2_000), timeline.boundaryAt(9_990))
        assertEquals(listOf(1), timeline.at(9_990).upcomingLeadIndices)
        assertNull(timeline.at(10_000).gap)
        assertEquals(1, timeline.at(10_000).focusLeadIndex)
        assertTrue(timeline.at(10_000).hasStartedLead)
        assertFalse(timeline.at(900).hasStartedLead)
    }

    private fun assertMonotonicFocus(timeline: PosterLyricsTimeline, endMs: Long) {
        var previous = -1
        for (position in 0L..endMs step 10) {
            val focus = checkNotNull(timeline.at(position).focusLeadIndex)
            assertTrue("Focus went from $previous to $focus at $position ms", focus >= previous)
            previous = focus
        }
    }

    private fun PosterLyricsTimeline.at(position: Long) = projectionAt(boundaryAt(position))

    private fun line(text: String, start: Long, end: Long) =
        LyricLine(words = listOf(word(text, start, end)), timing = LyricTiming.Line)

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start, end)
}
