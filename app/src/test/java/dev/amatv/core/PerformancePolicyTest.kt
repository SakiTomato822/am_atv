package dev.amatv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePolicyTest {
    private val gibibyte = 1024L * 1024 * 1024

    @Test
    fun snapdragon625QualifiesThroughEitherHardwareThreshold() {
        // The fastest of its eight cores is 2.0 GHz.
        assertTrue(isLowPerformanceHardware(false, 3 * gibibyte, 2_000_000))
        assertTrue(isLowPerformanceHardware(false, 3 * gibibyte, null))
        assertTrue(isLowPerformanceHardware(false, 4 * gibibyte, 2_000_000))
    }

    @Test
    fun eightGibDeviceWithFastCoresDoesNotQualify() {
        assertFalse(isLowPerformanceHardware(false, 8 * gibibyte, 2_400_000))
        assertFalse(isLowPerformanceHardware(false, 8 * gibibyte, null))
    }

    @Test
    fun systemLowRamFlagQualifiesOnItsOwn() {
        assertTrue(isLowPerformanceHardware(true, 8 * gibibyte, 2_400_000))
    }

    @Test
    fun thirtyPercentSlowFramesAt60HzEnableLowPerformance() {
        val frames = List(7) { 16_666_667L } + List(3) { 25_000_001L }
        val stats = assessPlaybackFrames(frames, 60f)
        assertEquals(10, stats.frameCount)
        assertEquals(0.3, stats.slowFrameRatio, 0.0)
        assertTrue(stats.shouldEnableLowPerformance)
    }

    @Test
    fun tenPercentSlowFramesAt60HzKeepNormalPerformance() {
        val frames = List(9) { 16_666_667L } + 30_000_000L
        assertFalse(assessPlaybackFrames(frames, 60f).shouldEnableLowPerformance)
    }

    @Test
    fun at120HzThresholdIsTwelveAndAHalfMilliseconds() {
        val frames = listOf(8_333_333L, 12_500_000L, 12_500_001L)
        val stats = assessPlaybackFrames(frames, 120f)
        assertEquals(1, stats.slowFrameCount)
        assertTrue(stats.shouldEnableLowPerformance)
        assertFalse(assessPlaybackFrames(frames, 60f).shouldEnableLowPerformance)
    }

    @Test
    fun thresholdsAreStrictAndEmptySampleDoesNotSwitch() {
        val frames = listOf(16_666_667L, 25_000_000L, 25_000_000L, 30_000_000L)
        val stats = assessPlaybackFrames(frames, 60f)
        assertEquals(0.25, stats.slowFrameRatio, 0.0)
        assertFalse(stats.shouldEnableLowPerformance)
        assertFalse(assessPlaybackFrames(emptyList(), 60f).shouldEnableLowPerformance)
    }

    @Test
    fun legacyTrueMigratesToOnAndOthersMigrateToAuto() {
        assertEquals(LowPerformanceMode.On, migrateLowPerformanceMode(true))
        assertEquals(LowPerformanceMode.Auto, migrateLowPerformanceMode(false))
        assertEquals(LowPerformanceMode.Auto, migrateLowPerformanceMode(null))
    }
}
