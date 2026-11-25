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

        assertEquals(welcome.brightness * 0.6f, home.brightness, 0.001f)
        assertEquals(welcome.crestStrength * 0.6f, home.crestStrength, 0.001f)
        assertTrue(home.featherScale > welcome.featherScale)
        assertTrue(home.periodScale > welcome.periodScale)
        assertTrue(welcomeRibbons.all { it.period * home.periodScale in 20f..30f })
    }
}
