package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class KaraokeMaskRampTest {
    /** Original [0:A, p0:A, p1:B, 1:B], right-continuous at repeated stops. */
    private fun originalStops(progress: Float, feather: Float, rtl: Boolean): FloatArray = if (rtl) {
        val p0 = progress.coerceIn(0f, 1f)
        val p1 = (progress + feather).coerceIn(p0, 1f)
        floatArrayOf(0f, p0, p1, 1f)
    } else {
        val p1 = progress.coerceIn(0f, 1f)
        val p0 = (progress - feather).coerceIn(0f, p1)
        floatArrayOf(0f, p0, p1, 1f)
    }

    private fun originalAlpha(x: Float, stops: FloatArray, a: Float, b: Float): Float {
        for (index in 3 downTo 0) {
            if (x >= stops[index]) {
                if (index == 3) return b
                val fraction = (x - stops[index]) / (stops[index + 1] - stops[index])
                val left = if (index < 2) a else b
                val right = if (index + 1 < 2) a else b
                return left + (right - left) * fraction
            }
        }
        return a
    }

    @Test fun denseProgressRtlPresenceAndPixelSamplesMatchOriginalFourStopAlpha() {
        for (step in -20..220) for (feather in listOf(0.001f, 0.02f, 0.1f, 0.4f, 1f)) {
            val progress = step / 200f
            for (rtl in listOf(false, true)) {
                val ramp = karaokeMaskRamp(progress, feather, rtl)
                val originalStops = originalStops(progress, feather, rtl)
                for (unlit in listOf(0.18f, 0.45f)) for (lit in listOf(0.35f, 0.94f)) {
                    for (presence in listOf(0f, 0.001f, 0.25f, 0.75f, 1f)) {
                        val effectiveLit = unlit + (lit - unlit) * presence
                        val a = if (rtl) unlit else effectiveLit
                        val b = if (rtl) effectiveLit else unlit
                        for (pixel in 0..64) {
                            val x = pixel / 64f
                            val expected = originalAlpha(x, originalStops, a, b)
                            val actual = a + (b - a) * ramp.fractionAt(x)
                            assertTrue(actual.isFinite())
                            assertEquals(expected, actual, 0.00001f)
                            // DstIn uses only source alpha, including antialiased glyph coverage.
                            assertEquals(expected * 0.37f, actual * 0.37f, 0.00001f)
                        }
                    }
                }
            }
        }
    }

    @Test fun collapsedOuterEdgesMatchEveryRasterPixelWithoutZeroLengthGradient() {
        for (rtl in listOf(false, true)) for (progress in listOf(-1f, 0f, 1f, 2f)) {
            val ramp = karaokeMaskRamp(progress, 0.001f, rtl)
            val originalStops = originalStops(progress, 0.001f, rtl)
            if (ramp.start != ramp.end) continue
            assertTrue(ramp.start == 0f || ramp.start == 1f)
            for (width in listOf(1, 3, 64, 1_920)) for (pixel in 0 until width) {
                val x = (pixel + 0.5f) / width
                val paintedFraction = if (ramp.start == 0f) 1f else 0f
                assertEquals(originalAlpha(x, originalStops, 0f, 1f), paintedFraction, 0f)
            }
        }
    }

    @Test fun nonCollapsedFeatherKeepsIdenticalFlatRegionsAndLinearTransition() {
        for (rtl in listOf(false, true)) {
            val ramp = karaokeMaskRamp(0.5f, 0.125f, rtl)
            assertEquals(0f, ramp.fractionAt(0f), 0f)
            assertEquals(0f, ramp.fractionAt(ramp.start), 0f)
            assertEquals(0.5f, ramp.fractionAt((ramp.start + ramp.end) / 2f), 0.00001f)
            assertEquals(1f, ramp.fractionAt(ramp.end), 0f)
            assertEquals(1f, ramp.fractionAt(1f), 0f)
        }
    }
}
