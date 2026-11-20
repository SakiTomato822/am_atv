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
    fun overlappingLeadsAdvanceAtStartAndKeepTheSweepOnThePreviousLine() {
        val lines = listOf(
            LyricLine(
                words = listOf(word("still", 1_000, 1_500), word("singing", 1_500, 3_700)),
                agentId = "second",
                isOppositeAgent = true,
            ),
            line("Next", 1_800, 2_200),
            line("Last", 4_000, 4_100),
        )
        val timeline = PosterLyricsTimeline(lines)
        assertMonotonicFocus(timeline, 4_300)
        assertEquals(0, timeline.at(1_790).focusLeadIndex)
        val overlap = timeline.at(1_800)
        assertEquals(1, overlap.focusLeadIndex)
        assertEquals(0, overlap.previousLeadIndex)
        assertTrue(overlap.smallLines.isEmpty())
        assertEquals(3_700L, overlap.previousLeadSweepEndMs)
        val sweep = PosterSweepRow(
            listOf(KaraokeSweepWord(lines[0].words[0], 0f, 40f), KaraokeSweepWord(lines[0].words[1], 48f, 120f)),
            120f, 30f, isRtl = false, nextRowStartMs = null,
        )
        val handoffPx = sweep.leadingEdgeAt(1_800)
        assertTrue(handoffPx > 48f && handoffPx < 120f)
        assertTrue(sweep.leadingEdgeAt(2_000) > handoffPx)
        assertTrue(timeline.at(2_000).smallLines.isEmpty())
        assertEquals(1, timeline.at(2_210).focusLeadIndex)
        assertTrue(sweep.leadingEdgeAt(3_700) >= 150f - 0.001f)
        assertTrue(timeline.at(3_700).smallLines.isEmpty())
        assertEquals(3_700L, timeline.at(3_899).previousLeadSweepEndMs)
        assertNull(timeline.at(3_900).previousLeadSweepEndMs)
        assertEquals(0, timeline.at(3_900).previousLeadIndex)
        assertEquals(2, timeline.at(4_000).focusLeadIndex)
    }

    @Test
    fun overlappingLineTimingKeepsThePreviousSweepUntilTheExplicitEndAndFade() {
        val previous = line("Previous", 1_000, 1_700).copy(lineEndMs = 2_500)
        val timeline = PosterLyricsTimeline(listOf(previous, line("Focus", 1_800, 3_000)))
        assertEquals(2_500L, timeline.at(1_800).previousLeadSweepEndMs)
        assertEquals(2_500L, timeline.at(2_699).previousLeadSweepEndMs)
        assertNull(timeline.at(2_700).previousLeadSweepEndMs)
    }

    @Test
    fun onlyAnIndependentOverlappingSingerAppearsBelowAsAWholeLine() {
        val otherSinger = LyricLine(
            words = listOf(word("still", 1_000, 1_400), word("here", 1_400, 3_000)),
            agentId = "second",
            isOppositeAgent = true,
        )
        val timeline = PosterLyricsTimeline(
            listOf(
                otherSinger,
                line("Earlier solo", 1_100, 3_000),
                line("Previous", 1_500, 2_600),
                line("Focus", 2_000, 3_500),
            ),
        )
        val overlap = timeline.at(2_100)
        assertEquals(3, overlap.focusLeadIndex)
        assertEquals(2, overlap.previousLeadIndex)
        assertEquals(listOf(PosterTextLine("still here", true)), overlap.smallLines)
        assertTrue(timeline.at(3_000).smallLines.isEmpty())
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
    fun groupChorusTakesFocusThroughTheBugaiSoloAndChorusHandoffs() {
        // L9–L15 timings and agents, with synthetic lyric text.
        val lines = listOf(
            line("L9", 76_922, 82_422).copy(agentId = "v1", agentType = LyricAgentType.Person),
            line("L10", 83_358, 88_800).copy(agentId = "v3", agentType = LyricAgentType.Group),
            line("L11", 88_969, 91_951).copy(agentId = "v3", agentType = LyricAgentType.Group),
            line("L12", 92_190, 95_589).copy(agentId = "v3", agentType = LyricAgentType.Group),
            line("L13", 95_998, 101_080).copy(agentId = "v3", agentType = LyricAgentType.Group),
            line("L14", 101_372, 108_339).copy(agentId = "v2", agentType = LyricAgentType.Person),
            line("L15", 107_761, 114_096).copy(agentId = "v1", agentType = LyricAgentType.Person),
        )
        val timeline = PosterLyricsTimeline(lines)
        val shortGap = timeline.at(83_357)
        assertNull(shortGap.gap)
        assertEquals(0, shortGap.focusLeadIndex)
        assertEquals(listOf(1, 2, 3), shortGap.upcomingLeadIndices)
        for (index in 1..5) {
            val start = lines[index].startMs
            assertEquals(index - 1, timeline.at(start - 1).focusLeadIndex)
            for (position in listOf(start, start + 100)) {
                val projection = timeline.at(position)
                assertEquals(index, projection.focusLeadIndex)
                assertEquals(index - 1, projection.previousLeadIndex)
                assertTrue(projection.smallLines.isEmpty())
            }
        }
        val overlap = timeline.at(107_761)
        assertEquals(6, overlap.focusLeadIndex)
        assertEquals(5, overlap.previousLeadIndex)
        assertEquals(108_339L, overlap.previousLeadSweepEndMs)
        assertTrue(overlap.smallLines.isEmpty())
    }

    @Test
    fun groupUsesForegroundInkAndOnlyMarkedBackingAppearsAsSmallText() {
        val lead = line("Lead", 1_000, 1_700).copy(
            agentId = "second",
            isOppositeAgent = true,
            backgroundWords = listOf(word("echo", 1_200, 1_600)),
        )
        val group = LyricLine(
            words = listOf(word("Group", 1_300, 1_650), word("chorus", 1_650, 2_300)),
            agentId = "group",
            agentType = LyricAgentType.Group,
            isOppositeAgent = true,
        )
        val backing = LyricLine(
            backgroundWords = listOf(word("harmony", 1_400, 2_200)),
            words = emptyList(),
            agentType = LyricAgentType.Group,
        )
        val markedBacking = LyricLine(words = listOf(word("hum", 1_500, 2_000).copy(isBackgroundVocal = true)))
        val last = line("Last", 2_100, 2_900).copy(agentId = "solo")
        val timeline = PosterLyricsTimeline(
            listOf(lead, group, backing, markedBacking, line("Next", 1_750, 2_100), last),
        )
        val projection = timeline.at(1_550)
        assertEquals(1, projection.focusLeadIndex)
        assertTrue(isOppositeSingerLine(lead))
        assertFalse(isOppositeSingerLine(group))
        assertEquals(
            listOf(PosterTextLine("(echo)", true), PosterTextLine("(harmony)", false), PosterTextLine("(hum)", false)),
            projection.smallLines,
        )
        assertEquals(listOf(4, 5), projection.upcomingLeadIndices)
        val handoff = timeline.at(1_750)
        assertEquals(4, handoff.focusLeadIndex)
        assertEquals(1, handoff.previousLeadIndex)
        assertEquals(2_300L, handoff.previousLeadSweepEndMs)
        val laterOverlap = timeline.at(2_150)
        assertEquals(5, laterOverlap.focusLeadIndex)
        assertEquals(4, laterOverlap.previousLeadIndex)
        assertEquals(listOf(PosterTextLine("(harmony)", false)), laterOverlap.smallLines)
        assertEquals(0, PosterLyricsTimeline(listOf(group)).at(1_550).focusLeadIndex)
    }

    @Test
    fun clockHoldsFocusAndSweepThroughJitterThenResetsForASeek() {
        val lyric = LyricLine(words = listOf(word("one", 1_500, 1_800), word("two", 1_800, 2_200)))
        val timeline = PosterLyricsTimeline(listOf(line("Before", 200, 1_500), lyric))
        val clock = PosterPlaybackClock(1_470)
        val sweep = PosterSweepRow(
            listOf(KaraokeSweepWord(lyric.words[0], 0f, 30f), KaraokeSweepWord(lyric.words[1], 35f, 70f)),
            70f, 30f, isRtl = false, nextRowStartMs = null,
        )
        var previousPx = Float.NEGATIVE_INFINITY
        for (sample in (1_500L..1_900L step 10L).toList() + listOf(1_510L, 1_490L, 1_990L, 990L)) {
            val position = clock.sample(sample)
            assertEquals(1, timeline.at(position).focusLeadIndex)
            val px = sweep.leadingEdgeAt(position)
            assertTrue(px >= previousPx)
            previousPx = px
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

    private fun PosterSweepRow.leadingEdgeAt(position: Long): Float =
        (highlightAt(position) as KaraokeRowHighlight.Partial).ramp.end

    private fun line(text: String, start: Long, end: Long) =
        LyricLine(words = listOf(word(text, start, end)), timing = LyricTiming.Line)

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start, end)
}
