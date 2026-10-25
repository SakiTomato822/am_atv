package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class TerminalLyricReleaseTest {
    private fun release(lines: List<LyricLine>): TerminalLyricRelease = requireNotNull(
        terminalLyricRelease(lines, LyricTimeline.Schedule(lines).finalClusterStartIndex, 500L),
    )

    private fun presence(line: LyricLine, positionMs: Long, release: TerminalLyricRelease): Float =
        LyricTimeline.completedLinePresence(
            line, positionMs, 200f, 200f, 64f, terminalReleaseMs = release.releaseMs,
        )

    @Test fun finalPhraseReleasesWithoutScrollingAfterItsLastHighlightSweep() {
        val line = LyricLine(
            listOf(LyricWord("last", 1_000, 1_300), LyricWord("phrase", 1_300, 1_600)),
        )
        val release = release(listOf(line))
        assertEquals(1_600L, release.vocalsEndMs)
        assertEquals(2_100L, release.releaseMs)
        assertEquals(1f, presence(line, 2_099, release), 0f)
        assertEquals(0f, presence(line, 2_100, release), 0f)
        // The existing tint envelope still fades gently instead of flashing gray.
        assertTrue(advanceLyricPresence(1f, presence(line, 2_100, release), 16) > .94f)
        // Earlier completed lines retain their spatial handoff rule.
        assertEquals(1f, LyricTimeline.completedLinePresence(line, 8_000, 200f, 200f, 64f), 0f)
    }

    @Test fun finalLongNoteCompletesItsEntireCharacterReturnBeforeDimming() {
        val line = LyricLine(
            listOf(LyricWord("stay", 1_000, 1_500), LyricWord("go", 1_500, 3_500)),
        )
        val release = release(listOf(line))
        val lastCharacter = LyricTimeline.characterEmphasisTimings(line, 1).last()
        assertEquals(5_900L, release.releaseMs)
        assertEquals(1f, presence(line, release.releaseMs - 1, release), 0f)
        assertTrue(LyricTimeline.characterEmphasisAmountAt(
            lastCharacter, release.releaseMs - 1, line.mainEndMs, 1f, { it },
        ) > 0f)
        assertEquals(0f, LyricTimeline.characterEmphasisAmountAt(
            lastCharacter, release.releaseMs, line.mainEndMs, 1f, { it },
        ), 0f)
        assertEquals(0f, presence(line, release.releaseMs, release), 0f)
    }

    @Test fun finalDuetWaitsForItsTrailingBackgroundSinger() {
        val first = LyricLine(listOf(LyricWord("earlier", 100, 500)))
        val lead = LyricLine(listOf(LyricWord("lead", 1_000, 2_000)), timing = LyricTiming.Word)
        val answer = LyricLine(
            listOf(LyricWord("answer", 1_500, 2_500)),
            backgroundWords = listOf(LyricWord("echo", 2_600, 4_200, isBackgroundVocal = true)),
        )
        val release = release(listOf(first, lead, answer))
        assertEquals(1, release.firstLineIndex)
        assertEquals(4_200L, release.vocalsEndMs)
        assertEquals(4_700L, release.releaseMs)
        assertEquals(1f, presence(lead, 4_699, release), 0f)
        assertEquals(1f, presence(answer, 4_699, release), 0f)
        assertEquals(0f, presence(lead, 4_700, release), 0f)
        assertEquals(0f, LyricTimeline.completedLinePresence(
            answer, 4_700, 200f, 200f, 64f,
            completionMs = 4_200, terminalReleaseMs = release.releaseMs,
        ), 0f)
    }

    @Test fun cjkFinalWordHasNoInventedLatinSweepAndStaticFooterIsIgnored() {
        val line = LyricLine(listOf(LyricWord("愛", 1_000, 2_000)), timing = LyricTiming.Word)
        val footer = LyricLine(listOf(LyricWord("credits", 0, 0)), isStatic = true)
        val release = release(listOf(line, footer))
        assertEquals(0, release.firstLineIndex)
        assertEquals(2_000L, release.releaseMs)
        assertEquals(0f, presence(line, 2_000, release), 0f)
        assertNull(terminalLyricRelease(listOf(footer), -1, 500))
    }

    @Test fun paragraphEndCannotKeepCompletedVocalsBrightThroughAnOutro() {
        val line = LyricLine(
            listOf(LyricWord("愛", 1_000, 2_000)),
            lineEndMs = 20_000, timing = LyricTiming.Word,
        )
        val release = release(listOf(line))
        assertEquals(2_000L, release.vocalsEndMs)
        assertEquals(2_000L, release.releaseMs)
        assertEquals(0f, presence(line, 2_000, release), 0f)
    }

    @Test fun finalVisualTailFinishesWhenMediaPositionStopsAtSongDuration() {
        val line = LyricLine(listOf(LyricWord("go", 1_500, 3_500)), timing = LyricTiming.Word)
        val release = release(listOf(line))
        assertEquals(3_500L, terminalLyricTailPosition(3_500, 3_500, 0, release))
        assertEquals(4_500L, terminalLyricTailPosition(3_500, 3_500, 1_000, release))
        val finishedPosition = terminalLyricTailPosition(3_500, 3_500, 60_000, release)
        assertEquals(release.releaseMs, finishedPosition)
        assertEquals(0f, presence(line, finishedPosition, release), 0f)
        assertEquals(8_000L, terminalLyricTailPosition(8_000, 3_500, 0, release))
    }

    @Test fun pausingOrSeekingBeforeTheFinalVocalEndDoesNotAdvanceSinging() {
        val release = TerminalLyricRelease(0, 3_500, 5_900)
        assertEquals(3_499L, terminalLyricTailPosition(3_499, 3_500, 60_000, release))
        assertEquals(1_500L, terminalLyricTailPosition(1_500, 5_900, 60_000, release))
    }
}
