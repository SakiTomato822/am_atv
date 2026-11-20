package dev.amatv.flyme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLyricFlowTest {
    @Test
    fun liveBlurStartsAtAndroid12() {
        assertFalse(flowUsesLiveBlur(sdkInt = 28))
        assertFalse(flowUsesLiveBlur(sdkInt = 30))
        assertTrue(flowUsesLiveBlur(sdkInt = 31))
        assertTrue(flowUsesLiveBlur(sdkInt = 35))
    }

    @Test
    fun liveBlurKeepsTheExistingOpacityLadder() {
        assertEquals(listOf(1f, 0.95f, 0.80f, 0.65f, 0.50f), lineOpacityLadder(liveBlur = true))
    }

    @Test
    fun withoutBlurTheCurrentLineStaysOpaqueAndNeighborsDimMore() {
        assertEquals(listOf(1f, 0.55f, 0.45f, 0.38f, 0.32f), lineOpacityLadder(liveBlur = false))
    }
}
