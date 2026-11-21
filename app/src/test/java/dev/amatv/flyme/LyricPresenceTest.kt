package dev.amatv.flyme

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricPresenceTest {
    private val line = LyricLine(listOf(LyricWord("hold", 1_000, 2_000)))

    @Test fun backingFadesFromZeroAndLowPerformanceShowsItAtTheSameBoundary() {
        val interval = listOf(LyricWord("harmony", 1_400, 1_900)).backingVocalInterval()
        for ((position, expected) in listOf(1_399L to 0f, 1_400L to 0f, 1_401L to .005f,
            1_500L to .5f, 1_600L to 1f, 1_899L to 1f, 1_900L to 0f, 1_901L to 0f)
        ) {
            assertEquals(expected, backingVocalOpacity(position, interval, animate = true), 0f)
            assertEquals(if (position in interval) 1f else 0f, backingVocalOpacity(position, interval, animate = false), 0f)
        }
    }

    @Test fun backwardClockJitterCannotRestartTheBackingFadeButASeekCan() {
        val interval = 1_400L until 1_900L
        val clock = LyricPlaybackClock(1_399L)
        assertEquals(.005f, backingVocalOpacity(clock.sample(1_401), interval, animate = true), 0f)
        assertEquals(.005f, backingVocalOpacity(clock.sample(1_399), interval, animate = true), 0f)
        assertEquals(0f, backingVocalOpacity(clock.sample(1_399, seek = true), interval, animate = true), 0f)
    }

    @Test fun completedLineRemainsWhiteWhileItsRenderedPositionIsStationary() {
        val retained = LyricTimeline.completedLinePresence(line, 8_000, 200f, 200f, 64f)
        assertEquals(1f, retained, 0f)
        assertEquals(1f, advanceLyricPresence(1f, retained, 5_000), 0f)
    }

    @Test fun crossingTheWholeSpatialFadeRegionCannotTurnWhiteToGrayInOneFrame() {
        val retained = LyricTimeline.completedLinePresence(line, 2_001, 120f, 200f, 64f)
        assertEquals(0f, retained, 0f)
        val firstFrame = advanceLyricPresence(1f, retained, 16)
        assertTrue(firstFrame > .94f)
        var current = firstFrame
        repeat(19) {
            val next = advanceLyricPresence(current, retained, 16)
            assertTrue(next <= current)
            assertTrue(current - next <= 16f / 300f + .00001f)
            current = next
        }
        assertEquals(0f, current, 0f)
    }

    @Test fun retargetingAnotherShortPhraseContinuesFromCurrentTint() {
        var current = 1f
        repeat(5) { current = advanceLyricPresence(current, .5f, 16) }
        val next = advanceLyricPresence(current, 0f, 16)
        assertEquals(current - 16f / 300f, next, .00001f)
        assertTrue(next > 0f)
    }

    @Test fun liveActivationKeepsItsExistingTimingAndSeekBackCanRelight() {
        assertEquals(.4f, advanceLyricPresence(.1f, .4f, 16), 0f)
        assertEquals(1f, advanceLyricPresence(.4f, 1f, 16), 0f)
    }

    @Test fun staleOrLongFrameCannotEraseTheWholeFade() {
        assertEquals(1f, advanceLyricPresence(1f, 0f, -1), 0f)
        assertTrue(advanceLyricPresence(1f, 0f, 10_000) > .78f)
    }
}
