package dev.amatv.flyme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioPivotTest {

    @Test
    fun firstPageAlignsAtLeftInsetWithoutPeeking() {
        val widths = listOf(80f, 160f, 160f, 120f, 80f)
        val placement = computePivotPlacementForPage(
            pageIndex = 0,
            tabWidthsPx = widths,
            pxPx = 16f,
            gapPx = 24f,
            pivotWidthPx = 390f,
        )
        assertEquals(16f, placement.translationX, 0.01f)
        assertFalse(placement.isPeekingLeft)
    }

    @Test
    fun shortLeftNeighborFitsCompletelyWithoutExceedingOneThirdWidth() {
        // 390dp phone on "Listen Now" (page 1): prev "Settings" (76) + px (16) + gap (24) = 116 <= 130 (390 / 3)
        val widths = listOf(76f, 152f, 152f, 114f, 76f)
        val placement = computePivotPlacementForPage(
            pageIndex = 1,
            tabWidthsPx = widths,
            pxPx = 16f,
            gapPx = 24f,
            pivotWidthPx = 390f,
        )
        // Expected: at = 116, offsetLeft[1] = 76 + 24 = 100 -> row translationX = 16f
        assertEquals(16f, placement.translationX, 0.01f)
        assertFalse(placement.isPeekingLeft)
    }

    @Test
    fun wideLeftNeighborCapsAtOneThirdScreenAndEnablesPeekMask() {
        // 390dp phone on "New" (page 2): prev "Listen Now" (152) + px (16) + gap (24) = 192 > 130 (390 / 3)
        val widths = listOf(76f, 152f, 152f, 114f, 76f)
        val placement = computePivotPlacementForPage(
            pageIndex = 2,
            tabWidthsPx = widths,
            pxPx = 16f,
            gapPx = 24f,
            pivotWidthPx = 390f,
        )
        // at = 130, offsetLeft[2] = (76+24) + (152+24) = 276 -> row translationX = 130 - 276 = -146f
        assertEquals(-146f, placement.translationX, 0.01f)
        assertTrue(placement.isPeekingLeft)
    }

    @Test
    fun coldStartDefaultsToListenNowAndInProcessSwitchesPreservePage() {
        FolioUiSession.reset()
        assertEquals(1, FolioUiSession.HOME_PAGE_INDEX)
        assertEquals(FolioUiSession.HOME_PAGE_INDEX, FolioUiSession.initialPage())

        FolioUiSession.onPageChanged(3)
        assertEquals(3, FolioUiSession.initialPage())

        FolioUiSession.reset()
        assertEquals(1, FolioUiSession.initialPage())
    }

    @Test
    fun topRightMiniPlayerCapsuleFitsCarAndTabletAndFallsBackToDotWhenTooNarrow() {
        // 1. Car head unit (1280x720 dp): "Search" ends ~850dp -> 1280 - 40 - 20 - 850 = 370dp -> capped at 360dp
        val carLayout = computeTopRightMiniPlayerLayout(
            windowWidthDp = 1280f,
            pivotRightEdgeDp = 850f,
            endPaddingDp = 40f,
        )
        assertTrue(carLayout.showCapsule)
        assertEquals(360f, carLayout.widthDp, 0.01f)

        // 2. Tablet landscape (1280x800 dp): "Search" ends ~920dp -> 1280 - 40 - 20 - 920 = 300dp capsule
        val tabletLayout = computeTopRightMiniPlayerLayout(
            windowWidthDp = 1280f,
            pivotRightEdgeDp = 920f,
            endPaddingDp = 40f,
        )
        assertTrue(tabletLayout.showCapsule)
        assertEquals(300f, tabletLayout.widthDp, 0.01f)

        // 3. Narrow landscape (e.g. 880dp wide, available = 880 - 40 - 20 - 660 = 160dp < 220dp) -> Dot fallback
        val narrowFallback = computeTopRightMiniPlayerLayout(
            windowWidthDp = 880f,
            pivotRightEdgeDp = 660f,
            endPaddingDp = 40f,
        )
        assertFalse(narrowFallback.showCapsule)
        assertEquals(0f, narrowFallback.widthDp, 0.01f)
    }

    @Test
    fun trailingPagesKeepTrailingTitlesInScreenAcrossWidthsAndPreserveEarlyPages() {
        val widths = listOf(76f, 152f, 152f, 114f, 76f)
        val px = 16f
        val pr = 16f
        val gap = 24f

        for (screenWidth in listOf(360f, 390f, 412f, 480f, 1280f)) {
            val page0 = computePivotPlacementForPage(0, widths, px, gap, screenWidth, pr)
            val page1 = computePivotPlacementForPage(1, widths, px, gap, screenWidth, pr)
            val page4 = computePivotPlacementForPage(4, widths, px, gap, screenWidth, pr)

            // Pages 0 & 1 retain previous placement (page 1 full left neighbor fits inside width/3? 116 <= 120..426 -> translationX == px)
            assertEquals(px, page0.translationX, 0.01f)
            assertFalse(page0.isPeekingLeft)
            assertEquals(px, page1.translationX, 0.01f)
            assertFalse(page1.isPeekingLeft)

            // Compute positions on last page (page 4)
            val starts = FloatArray(widths.size)
            var cursor = page4.translationX
            for (i in widths.indices) {
                starts[i] = cursor
                cursor += widths[i] + gap
            }
            val lastRight = starts[4] + widths[4]
            if (screenWidth >= 700f) {
                // Wide screen fits all tabs: pinned cleanly at px without unnecessary shift
                assertEquals(px, page4.translationX, 0.01f)
                assertFalse(page4.isPeekingLeft)
            } else {
                // Phone widths align right edge of last tab at screenWidth - pr
                assertEquals(screenWidth - pr, lastRight, 0.01f)
                // Last 2 tabs completely visible on all phone widths >= 360dp
                assertTrue(starts[3] >= 0f && starts[3] + widths[3] <= screenWidth)
                assertTrue(starts[4] >= 0f && starts[4] + widths[4] <= screenWidth)
            }
        }

        // Slightly wider 5-tab bar on 480dp keeps last 3 tabs completely in screen on page 4
        val p4WidePhone = computePivotPlacementForPage(4, widths, px, gap, 480f, pr)
        val newTabLeft = p4WidePhone.translationX + (76f + gap + 152f + gap)
        assertTrue(newTabLeft >= 0f)
        assertTrue(newTabLeft + widths[2] <= 480f)
    }

    @Test
    fun visibleInactiveTabAlphaAndScrubLayoutFitAndNearestTabSelection() {
        assertEquals(1f, tabAlphaForPage(2, 2, isFullyVisible = true), 0.001f)
        assertEquals(0.32f, tabAlphaForPage(3, 2, isFullyVisible = true), 0.001f)
        assertEquals(0.24f, tabAlphaForPage(1, 2, isFullyVisible = false), 0.001f)

        val widths = listOf(80f, 160f, 160f, 120f, 80f) // sum = 600, gaps = 4*24 = 96 -> 696
        val scrubPortrait = computeScrubPivotLayout(widths, pxPx = 16f, prPx = 16f, gapPx = 24f, pivotWidthPx = 390f)
        assertTrue(scrubPortrait.fitScale < 1f)
        val lastCenter = scrubPortrait.tabCentersPx.last()
        assertTrue(lastCenter < 390f)

        val scrubWide = computeScrubPivotLayout(widths, pxPx = 40f, prPx = 40f, gapPx = 24f, pivotWidthPx = 1280f)
        assertEquals(1f, scrubWide.fitScale, 0.001f)

        assertEquals(0, nearestScrubTabIndex(0f, scrubPortrait.tabCentersPx))
        assertEquals(2, nearestScrubTabIndex(scrubPortrait.tabCentersPx[2] + 3f, scrubPortrait.tabCentersPx))
        assertEquals(4, nearestScrubTabIndex(389f, scrubPortrait.tabCentersPx))
    }

    @Test
    fun resolveScrollToTopActionHandlesTopAnimateAndJumpThresholds() {
        assertEquals(FolioScrollToTopAction.AlreadyAtTop, resolveScrollToTopAction(0, 0))
        assertEquals(FolioScrollToTopAction.AnimateFromCurrent, resolveScrollToTopAction(0, 24))
        assertEquals(FolioScrollToTopAction.AnimateFromCurrent, resolveScrollToTopAction(8, 0))
        assertEquals(FolioScrollToTopAction.JumpThenAnimate, resolveScrollToTopAction(9, 0))
        assertEquals(FolioScrollToTopAction.JumpThenAnimate, resolveScrollToTopAction(25, 120))
    }
}
