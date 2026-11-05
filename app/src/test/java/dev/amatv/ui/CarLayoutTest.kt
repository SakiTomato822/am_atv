package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLayoutTest {
    @Test fun fuelDesignViewport() {
        assertEquals(1.5f, carContentScale(1920f, 968f), 0.0001f)
    }

    @Test fun systemBarsReduceHeight() {
        assertEquals(1.5f * 896f / 968f, carContentScale(1920f, 896f), 0.0001f)
    }

    @Test fun splitWindowDoesNotShrinkTouchTargetsBelowNativeDensity() {
        assertEquals(1f, carContentScale(960f, 896f), 0.0001f)
    }

    @Test fun narrowWindowUsesWidthAndLargeWindowCapsScale() {
        assertEquals(1.25f, carContentScale(1600f, 968f), 0.0001f)
        assertEquals(1.5f, carContentScale(2560f, 1440f), 0.0001f)
    }

    // A landscape phone (about 727 x 312 dp after the status bar) fits the whole design.
    @Test fun phoneFitsTheWholeDesign() {
        assertEquals(1.5f * 312f / 968f, deviceContentScale(727f, 312f), 0.0001f)
    }

    @Test fun tabletScalesLikeTheCarAndTinyWindowsStopAtTheFloor() {
        assertEquals(1.5f * 1280f / 1920f, deviceContentScale(1280f, 800f), 0.0001f)
        assertEquals(0.4f, deviceContentScale(300f, 150f), 0.0001f)
    }
}
