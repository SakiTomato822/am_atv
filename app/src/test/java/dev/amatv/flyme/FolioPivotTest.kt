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
}
