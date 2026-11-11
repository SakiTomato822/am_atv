package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLayoutTest {
    @Test fun portraitUsesTheAvailableWidthInsteadOfTheLandscapeFloor() {
        assertEquals(0.9f, carContentScale(360f, 800f), 0.0001f)
        assertEquals(1.5f, carContentScale(800f, 1_440f), 0.0001f)
    }
    @Test fun fuelDesignViewport() {
        assertEquals(1.5f, carContentScale(1920f, 968f), 0.0001f)
    }

    @Test fun systemBarsReduceHeight() {
        assertEquals(1.5f * 896f / 968f, carContentScale(1920f, 896f), 0.0001f)
    }

    @Test fun splitWindowDoesNotShrinkTouchTargetsBelowNativeDensity() {
        assertEquals(1f, carContentScale(960f, 896f), 0.0001f)
    }

    @Test fun highDensityPhoneLandscapeShrinksToFitTheDesign() {
        // Xiaomi 2772x1280 at 393dpi: about 1128x521dp.
        assertEquals(1.5f * 521f / 968f, carContentScale(1128f, 521f, 2.456f), 0.0001f)
    }

    @Test fun narrowWindowUsesWidthAndLargeWindowCapsScale() {
        assertEquals(1.25f, carContentScale(1600f, 968f), 0.0001f)
        assertEquals(1.5f, carContentScale(2560f, 1440f), 0.0001f)
    }
}
