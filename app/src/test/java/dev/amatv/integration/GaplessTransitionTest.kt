package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessTransitionTest {
    @Test
    fun nativeContinuationRetainsLookaheadAndBufferingStages() {
        val timing = GaplessTransition(7, 12, "test-1", 1_000)
        timing.mark(GaplessStage.Resolved, 1_550)
        timing.mark(GaplessStage.Append, 1_560)
        timing.mark(GaplessStage.Added, 1_600)
        timing.ended(GaplessEnd(7, "old", 8_000, "onItemEnded"))
        timing.mark(GaplessStage.ItemChanged, 8_015)
        timing.buffering(true, 8_020)
        timing.buffering(true, 8_025)
        timing.buffering(false, 8_060)
        timing.start(GaplessPath.Native, 8_065)

        val line = timing.finish(8_080, "new", "media-router:3:0", 21, "attached")

        assertTrue(line.startsWith("id=test-1 path=native catalog=new endSource=onItemEnded"))
        assertTrue(line.contains("gapMs=80 resolveMs=550 appendMs=40 rebuildMs=- bufferMs=40"))
        assertTrue(line.contains("onCurrentItemChanged=7015"))
        assertTrue(line.contains("firstAdvance=7080"))
        assertTrue(line.contains("route=media-router:3:0 session=21 fade=attached"))
    }

    @Test
    fun fallbackIncludesTimeBeforePrepareEvenWhenEndPrecedesSelection() {
        val times = mapOf(
            GaplessStage.Ended to 500L,
            GaplessStage.Selected to 650L,
            GaplessStage.Requested to 650L,
            GaplessStage.Rebuild to 750L,
            GaplessStage.Rebuilt to 810L,
            GaplessStage.Prepare to 850L,
            GaplessStage.Play to 900L,
            GaplessStage.Advanced to 1_050L,
        )

        val summary = summarizeGaplessTiming(GaplessPath.Fallback, times)

        assertTrue(summary.startsWith("gapMs=550 resolveMs=- appendMs=- rebuildMs=60 bufferMs=-"))
        assertTrue(summary.contains("ended=-150 requested=0 rebuild=100 rebuilt=160 prepare=200 play=250"))
    }

    @Test
    fun manualGapUsesRequestAndMissingNativeEndStaysUnknown() {
        val times = mapOf(
            GaplessStage.Selected to 100L,
            GaplessStage.Requested to 130L,
            GaplessStage.Advanced to 220L,
        )
        assertEquals("gapMs=90", summarizeGaplessTiming(GaplessPath.Manual, times).substringBefore(' '))
        assertEquals("gapMs=90", summarizeGaplessTiming(GaplessPath.ManualNative, times).substringBefore(' '))
        assertEquals("gapMs=-", summarizeGaplessTiming(GaplessPath.Native, times).substringBefore(' '))
    }

    @Test
    fun lateEndCallbackCanReplaceAnInferenceWithoutANegativeGap() {
        val timing = GaplessTransition(7, 12, "test-2", 100)
        timing.start(GaplessPath.Native, 490, GaplessEnd(7, "old", 480, "inferred-item-change"))
        timing.ended(GaplessEnd(7, "old", 510, "onItemEnded"))

        val line = timing.finish(500, "new", "unknown", 0, "session-unavailable")

        assertTrue(line.contains("endSource=onItemEnded reason=- gapMs=0"))
        assertTrue(line.contains("session=unknown fade=session-unavailable"))
    }

    @Test
    fun retriedFallbackKeepsOriginalEndAndFirstPrepare() {
        val timing = GaplessTransition(7, 12, "test-3", 300)
        timing.start(GaplessPath.Fallback, 300, GaplessEnd(7, "old", 250, "inferred-completion", "no-lookahead"))
        timing.mark(GaplessStage.Prepare, 400)
        timing.mark(GaplessStage.Retry, 3_500)
        timing.mark(GaplessStage.Prepare, 3_550)
        timing.mark(GaplessStage.Play, 3_700)

        val result = timing.finish(3_850, "new", "unknown", 0, "disabled")

        assertTrue(result.startsWith("id=test-3 path=fallback catalog=new endSource=inferred-completion"))
        assertTrue(result.contains("reason=no-lookahead gapMs=3600"))
        assertTrue(result.contains("prepare=100"))
        assertTrue(result.contains("retry=3200"))
    }
}
