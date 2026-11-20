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
}
