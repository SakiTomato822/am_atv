package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricViewportTest {
    @Test fun longSongFirstArrivalBuildsOnlyBoundedContextWithCurrentSingerFirst() {
        val tops = FloatArray(1_000) { 80f * it }
        val order = lyricViewportMountOrder(tops, FloatArray(1_000) { 60f },
            viewportHeight = 540f, fromScroll = 55_760f, toScroll = 55_760f,
            anchorIndex = 700, priorityIndices = listOf(700, 701))
        assertEquals(700, order.first())
        assertTrue(701 in order)
        assertTrue(order.size <= 18)
        assertTrue(order.size < 1_000)
        assertFalse(0 in order)
        val budget = LyricRowMountBudget()
        assertEquals(700, budget.next(order, emptySet(), 100L))
        assertNull(budget.next(order, setOf(700), 100L))
        assertNotNull(budget.next(order, setOf(700), 200L))
    }

    @Test fun seekRetargetsMissingRowsWithoutGrantingTwoTreesInOneFrame() {
        val tops = FloatArray(120) { it * 80f }
        val heights = FloatArray(120) { 60f }
        val before = lyricViewportMountOrder(tops, heights, 540f, 0f, 0f, 0)
        val after = lyricViewportMountOrder(tops, heights, 540f, 0f, 6_160f, 80)
        val budget = LyricRowMountBudget()
        assertEquals(0, budget.next(before, emptySet(), 1L))
        assertNull(budget.next(after, setOf(0), 1L))
        assertEquals(80, budget.next(after, setOf(0), 2L))
        assertTrue(after.size <= 18)
    }

    @Test fun refiningPrefixHeightKeepsAnchorFixedAtEveryScrollPhase() {
        val previous = floatArrayOf(200f, 280f, 360f, 440f, 520f)
        for (change in listOf(-60f, 45f, 200f)) {
            val corrected = previous.copyOf().also { for (index in 2 until it.size) it[index] += change }
            val correction = lyricAnchorHeightCorrection(previous, corrected, 3)
            for (fraction in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
                val oldScroll = 180f + (300f - 180f) * fraction
                val newScroll = (180f + correction) + ((300f + correction) - (180f + correction)) * fraction
                assertEquals(previous[3] - oldScroll, corrected[3] - newScroll, 0.001f)
            }
        }
    }

    @Test fun firstGeometryAndChangesBelowAnchorNeverCreateAScrollCorrection() {
        assertEquals(0f, lyricAnchorHeightCorrection(floatArrayOf(), floatArrayOf(200f, 300f), 1), 0f)
        assertEquals(0f, lyricAnchorHeightCorrection(floatArrayOf(200f, 300f, 400f),
            floatArrayOf(200f, 300f, 450f), 1), 0f)
    }

    @Test fun openingAtRestoredPositionUsesCurrentAnchorWithoutFirstLineScroll() {
        val tops = floatArrayOf(140f, 248f, 356f, 464f, 572f, 680f, 788f)
        // At a restored mid-song position, the current singer is row five. Its
        // first visible placement must already be at the focal anchor.
        assertEquals(500f, initialLyricScroll(tops, anchorIndex = 5, focalAnchorY = 180f, maxScroll = 500f), 0f)
        assertEquals(0f, initialLyricScroll(tops, anchorIndex = -1, focalAnchorY = 180f, maxScroll = 500f), 0f)
        assertEquals(0f, initialLyricScroll(tops, anchorIndex = 99, focalAnchorY = 180f, maxScroll = 500f), 0f)
    }

    @Test fun deferredOpeningReadsGeometryPublishedAfterComposition() {
        val layout = LyricsLayoutInfo()
        // The opening effect is created while composition has no measurements.
        val locateAfterLayout = {
            layout.targetScrollFor(itemIndex = 7, focalAnchorY = 140f, viewportHeightPx = 540f)
        }
        assertEquals(0f, locateAfterLayout(), 0f)
        // Layout runs before that effect. A captured maxScroll=0 used to undo
        // the correct initial placement and latch it until the next lyric cue.
        val tops = FloatArray(8) { 140f + it * 100f }
        layout.update(List(8) { "row-$it" }, tops, FloatArray(8) { 80f }, 1_200f)
        assertEquals(660f, locateAfterLayout(), 0f)
        layout.update(List(8) { "row-$it" }, FloatArray(8) { tops[it] + 40f },
            FloatArray(8) { 80f }, 1_240f)
        assertEquals(700f, locateAfterLayout(), 0f)
    }

    @Test fun emptySongAndOutOfRangeSelectionStayBounded() {
        assertTrue(lyricViewportMountOrder(floatArrayOf(), floatArrayOf(), 540f, 0f, 0f, 0).isEmpty())
        assertEquals(listOf(0), lyricViewportMountOrder(floatArrayOf(200f), floatArrayOf(60f),
            540f, 0f, 0f, 999))
    }

    @Test fun estimatesHandleCjkTranslationsAndPronunciationsWithoutCreatingTextNodes() {
        val words = listOf(LyricWord("長長長長長長長長長長長長", 500L, 3_000L, pronunciationText = "chang"))
        val line = LyricLine(words, translation = "A translated line", joinWithSpaces = false,
            backgroundWords = words, backgroundTranslation = "Background", isMultiAgentSong = true,
            timing = LyricTiming.Word)
        val narrow = estimateLyricRowHeight(line, 320f, 1f, 1f)
        val wide = estimateLyricRowHeight(line, 800f, 1f, 1f)
        assertTrue(narrow.isFinite() && narrow > 0f)
        assertTrue(narrow >= wide)
        assertEquals(500L, line.startMs)
        assertEquals(3_000L, line.mainEndMs)
    }

    @Test fun singleActiveLineFittingOnScreenKeepsBaseAnchor() {
        val tops = floatArrayOf(100f, 250f, 400f, 550f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(0, anchor)
    }

    @Test fun multipleActiveLinesWithinVisibleBoundsKeepBaseAnchor() {
        val tops = floatArrayOf(100f, 250f, 400f, 550f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(0, anchor)
    }

    @Test fun overflowingBottomLineUsesEarliestActiveAnchorThatFitsAllLines() {
        val tops = floatArrayOf(100f, 250f, 500f, 650f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        // With anchor 0, scroll = 0. Line 2 bottom is 500 + 120 = 620 > viewport bottom (600).
        // Moving only to line 1 makes every active line fit, so line 2 must not be moved to the top.
        val anchor = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1, 2),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(1, anchor)
    }

    @Test fun subsequentOverflowAdvancesAnchorFurther() {
        val tops = floatArrayOf(100f, 250f, 400f, 650f)
        val heights = floatArrayOf(120f, 120f, 120f, 120f)
        val maxScroll = 500f
        // Active lines are 1, 2, 3.
        // Under anchor 1, line 1 and 2 fit, but line 3 bottom is (650 - 100) + 120 = 670 > 600.
        // Anchor 2 is the earliest active line that lets line 3 fit; line 3 must not jump to the top.
        val anchor3 = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(1, 2, 3),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            maxScroll = maxScroll,
        )
        assertEquals(2, anchor3)

        // If lines 0, 1, 2 are active, and line 2 fits under anchor 0, keep anchor 0.
        val anchor2 = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = listOf(0, 1, 2),
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            maxScroll = maxScroll,
        )
        assertEquals(0, anchor2)
    }

    @Test fun emptyOrInvalidInputsSafelyReturnBase() {
        val tops = floatArrayOf(100f, 250f)
        val heights = floatArrayOf(120f, 120f)
        assertEquals(1, resolveActiveLyricAnchor(1, emptyList(), tops, heights, 150f, 600f))
        assertEquals(-1, resolveActiveLyricAnchor(-1, listOf(0, 1), tops, heights, 150f, 600f))
        assertEquals(0, resolveActiveLyricAnchor(0, listOf(0, 1), floatArrayOf(), floatArrayOf(), 150f, 600f))
    }

    @Test fun edgeFadeDoesNotMoveLinesThatStillFitOnScreen() {
        val tops = floatArrayOf(100f, 250f, 400f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        assertEquals(0, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
    }

    @Test fun stageOffsetUsesTheActualRenderedBottom() {
        val tops = floatArrayOf(100f, 250f, 500f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        assertEquals(1, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
        assertEquals(0, resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
            stageOffsetPx = -20f,
        ))
    }

    @Test fun overflowAnchorDoesNotMoveBackwardWithinCluster() {
        val tops = floatArrayOf(100f, 250f, 500f)
        val heights = floatArrayOf(115f, 115f, 115f)
        val active = listOf(0, 1, 2)
        val advanced = resolveActiveLyricAnchor(
            baseAnchorIndex = 0,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        )
        assertEquals(1, advanced)

        val retained = retainedLyricAnchorBase(
            clusterStartIndex = 0,
            previousAnchorIndex = advanced,
            nextClusterStartIndex = -1,
        )
        assertEquals(1, resolveActiveLyricAnchor(
            baseAnchorIndex = retained,
            activeLineIndices = active,
            tops = tops,
            heights = heights,
            focalAnchorY = 150f,
            viewportHeightPx = 600f,
        ))
    }

    @Test fun retainedOverflowAnchorResetsAcrossForwardAndBackwardSeeks() {
        assertEquals(4, retainedLyricAnchorBase(4, 2, 8))
        assertEquals(0, retainedLyricAnchorBase(0, 6, 4))
    }
}
