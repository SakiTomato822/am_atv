package dev.amatv.flyme

import dev.amatv.core.HomeBackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioHomeBackdropTest {
    @Test
    fun selectsBackgroundForEveryStyleTrackAndPerformanceCombination() {
        for (style in HomeBackgroundStyle.entries) {
            for (useGl in listOf(false, true)) {
                assertEquals(HomeBackdropMode.Ambient, homeBackdropMode(style, hasTrack = false, useGl))
            }
            assertEquals(HomeBackdropMode.Animated, homeBackdropMode(style, hasTrack = true, useGl = true))
        }
        assertEquals(HomeBackdropMode.StaticLava, homeBackdropMode(HomeBackgroundStyle.Lava, true, false))
        assertEquals(HomeBackdropMode.StaticRibbon, homeBackdropMode(HomeBackgroundStyle.Ribbon, true, false))
    }

    @Test
    fun homeRibbonsAreDimmerSofterAndSlowerThanWelcomeRibbons() {
        val welcome = RibbonPreset.Welcome
        val home = RibbonPreset.Home

        assertEquals(0.85f, home.brightness, 0.001f)
        assertEquals(0.80f, home.crestStrength, 0.001f)
        assertTrue(home.featherScale > welcome.featherScale)
        assertTrue(home.periodScale > welcome.periodScale)
        assertTrue(welcomeRibbons.all { it.period * home.periodScale in 20f..30f })
    }

    @Test
    fun lightHomeUsesTintedBaseAndMediumLightnessBands() {
        val colors = LavaPalette.from(intArrayOf(0xff2e896b.toInt()), isLight = true, homeRibbon = true)
        val frame = homeRibbonFrame(colors, isLight = true)

        assertTrue(frame.base.green in 0.92f..0.99f)
        assertTrue(frame.base.green > frame.base.red)
        assertTrue(frame.primary.green - frame.primary.red > 0.20f)
        assertTrue(frame.primary.green < frame.base.green)
        assertTrue(welcomeRibbons.maxOf { it.opacity } * frame.bandStrength in 0.35f..0.51f)
    }

    @Test
    fun lightHomeRibbonKeepsMutedCoverHueVisible() {
        // A muted brown cover: the bands must still read as a color, not as gray.
        val colors = LavaPalette.from(intArrayOf(0xff6b5f55.toInt()), isLight = true, homeRibbon = true)
        val primary = homeRibbonFrame(colors, isLight = true).primary
        assertTrue(maxOf(primary.red, primary.green, primary.blue) - minOf(primary.red, primary.green, primary.blue) > 0.25f)
        assertTrue(primary.red > primary.blue)

        val gray = LavaPalette.from(intArrayOf(0xff777777.toInt()), isLight = true, homeRibbon = true)
        val grayPrimary = homeRibbonFrame(gray, isLight = true).primary
        assertEquals(grayPrimary.red, grayPrimary.blue, 0.01f)
    }

    @Test
    fun darkHomeRibbonKeepsMutedCoverHueVisible() {
        val colors = LavaPalette.from(intArrayOf(0xff6b5f55.toInt()), isLight = false, homeRibbon = true)
        val frame = homeRibbonFrame(colors, isLight = false)
        val primary = frame.primary
        assertTrue(maxOf(primary.red, primary.green, primary.blue) - minOf(primary.red, primary.green, primary.blue) > 0.25f)
        assertTrue(primary.red > primary.blue)
        assertTrue(maxOf(frame.base.red, frame.base.green, frame.base.blue) < 0.25f)
        assertTrue(frame.base.red > frame.base.blue)
    }
}
