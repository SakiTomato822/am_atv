package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLyricEffectsTest {
    @Test
    fun currentLineStaysSharpAndDistanceAddsProgressiveBlur() {
        assertEquals(0f, carLyricSoftFocusRadiusDp(0), 0f)
        assertEquals(CAR_LYRIC_SOFT_FOCUS_STEP_DP, carLyricSoftFocusRadiusDp(1), 0f)
        assertEquals(CAR_LYRIC_SOFT_FOCUS_STEP_DP * 2, carLyricSoftFocusRadiusDp(2), 0f)
    }

    @Test
    fun blurRadiusIsMonotonicAndCapped() {
        var previous = carLyricSoftFocusRadiusDp(0)
        for (distance in 1..20) {
            val radius = carLyricSoftFocusRadiusDp(distance)
            assertTrue("radius should not decrease at distance $distance", radius >= previous)
            assertTrue("radius exceeded its cap at distance $distance", radius <= CAR_LYRIC_SOFT_FOCUS_MAX_DP)
            previous = radius
        }
        assertEquals(CAR_LYRIC_SOFT_FOCUS_MAX_DP, previous, 0f)
    }

    @Test
    fun negativeDistanceIsTreatedAsTheCurrentLine() {
        assertEquals(0f, carLyricSoftFocusRadiusDp(-1), 0f)
    }

    @Test
    fun apiLevelSelectsARealBlurPathOnlyWhenRadiusIsPositive() {
        assertEquals(CarLyricSoftFocusPath.None, carLyricSoftFocusPath(31, 0f))
        assertEquals(CarLyricSoftFocusPath.RasterizedCpuBlur, carLyricSoftFocusPath(28, 2f))
        assertEquals(CarLyricSoftFocusPath.RasterizedCpuBlur, carLyricSoftFocusPath(30, 2f))
        assertEquals(CarLyricSoftFocusPath.GraphicsLayerBlur, carLyricSoftFocusPath(31, 2f))
        assertEquals(CarLyricSoftFocusPath.Unsupported, carLyricSoftFocusPath(27, 2f))
    }

    @Test
    fun legacyBlurSpreadsPixelsThroughABoxConvolution() {
        val source = IntArray(5 * 5)
        source[2 * 5 + 2] = 0xffffffff.toInt()

        val result = boxBlurArgb8888(source, width = 5, height = 5, radius = 1)

        assertTrue("the original glyph pixel should soften", alpha(result[2 * 5 + 2]) in 1..254)
        assertTrue("blur should spread into adjacent pixels", alpha(result[1 * 5 + 2]) > 0)
        assertEquals("transparent blur keeps the glyph's white color", 255, result[1 * 5 + 2] ushr 16 and 0xff)
        assertEquals("pixels outside the kernel stay untouched", 0, alpha(result[0]))
    }

    @Test
    fun zeroRadiusReturnsTheOriginalRaster() {
        val source = intArrayOf(0x10203040, 0x7fabcdef.toInt())

        assertTrue(source.contentEquals(boxBlurArgb8888(source, width = 2, height = 1, radius = 0)))
    }

    @Test
    fun downsampledCaptureStaysWithinItsPerLinePixelAndSideBudgets() {
        val geometry = carLyricRasterGeometry(widthPx = 1920, heightPx = 160, density = 3f)

        assertTrue(geometry.widthPx.toLong() * geometry.heightPx <= CAR_LYRIC_RASTER_MAX_PIXELS)
        assertTrue(geometry.widthPx <= CAR_LYRIC_RASTER_MAX_SIDE)
        assertTrue(geometry.heightPx <= CAR_LYRIC_RASTER_MAX_SIDE)
        assertTrue(geometry.scaleX < 1f)
        assertTrue(geometry.scaleY < 1f)
    }

    @Test
    fun overlappingActiveLinesUseTheNearestLineForFocusDistance() {
        val activeLines = setOf(4, 6)
        assertEquals(0, carLyricDistanceFromActiveLines(4, activeLines))
        assertEquals(1, carLyricDistanceFromActiveLines(5, activeLines))
        assertEquals(4, carLyricDistanceFromActiveLines(0, activeLines))
        assertEquals(0, carLyricDistanceFromActiveLines(5, emptySet()))
    }

    private fun alpha(color: Int): Int = color ushr 24 and 0xff
}
