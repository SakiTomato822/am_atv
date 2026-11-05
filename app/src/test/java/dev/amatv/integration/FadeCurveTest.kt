package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeCurveTest {
    @Test
    fun outFadeStartsGentlyAndStopsShortOfSilence() {
        assertEquals(0f, FadeCurve.outDb(FadeCurve.OUT_MS), 0f)
        // Halfway through, only a quarter of the depth.
        assertEquals(-FadeCurve.DEPTH_DB / 4, FadeCurve.outDb(FadeCurve.OUT_MS / 2), 0.01f)
        assertEquals(-FadeCurve.DEPTH_DB, FadeCurve.outDb(0L), 0.01f)
    }

    @Test
    fun inFadeRisesFastThenSettles() {
        assertEquals(-FadeCurve.DEPTH_DB, FadeCurve.inDb(0L), 0.01f)
        assertEquals(-FadeCurve.DEPTH_DB / 4, FadeCurve.inDb(FadeCurve.IN_MS / 2), 0.01f)
        assertEquals(0f, FadeCurve.inDb(FadeCurve.IN_MS), 0f)
    }

    private fun song(body: Float, tail: Float) = TailLoudness().apply {
        for (i in 0 until 20) add(body, 10_000L + i * 250L, 60_000L)
        for (i in 0 until 8) add(tail, 200_000L, 4_800L - i * 250L)
    }

    @Test
    fun loudEndingIsFaded() = assertFalse(song(-18f, -17f).alreadyQuiet())

    @Test
    fun selfFadingEndingIsLeftAlone() = assertTrue(song(-18f, -30f).alreadyQuiet())

    @Test
    fun silentMeasurementsMeanUnknown() = assertFalse(song(-96f, -96f).alreadyQuiet())
}
