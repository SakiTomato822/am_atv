package dev.amatv.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNowPlayingLayoutTest {
    @Test
    fun wideViewportUsesFullHeightArtworkAndBalancedLyricsColumn() {
        val geometry = carNowPlayingGeometry(1_280.dp, 645.dp)

        assertEquals(CarNowPlayingLayoutMode.Wide, geometry.mode)
        assertEquals(0.45f, geometry.leftFraction, 0f)
        assertEquals(24.dp, geometry.columnGap)
        assertEquals(645.dp, geometry.lyricsPaneHeight)
        assertEquals(20.dp, geometry.transportCardBottomInset)
        assertEquals(geometry.leftPaneWidth - 40.dp, geometry.transportCardWidth)
        assertTrue(geometry.lyricsPaneWidth > geometry.leftPaneWidth)
    }

    @Test
    fun actualScaledDpWidthSelectsCompactSplit() {
        // CarLayout may map a 1920 px device viewport to roughly 1280 dp; geometry uses the
        // measured Compose width, not the physical reference resolution.
        val geometry = carNowPlayingGeometry(960.dp, 597.dp)

        assertEquals(CarNowPlayingLayoutMode.Compact, geometry.mode)
        assertEquals(0.44f, geometry.leftFraction, 0f)
        assertEquals(20.dp, geometry.columnGap)
        assertTrue(geometry.lyricsPaneWidth > geometry.leftPaneWidth)
    }

    @Test
    fun narrowViewportStacksCoverCardAndKeepsLyricsInRemainingHeight() {
        val geometry = carNowPlayingGeometry(680.dp, 500.dp)

        assertEquals(CarNowPlayingLayoutMode.Stacked, geometry.mode)
        assertEquals(680.dp, geometry.leftPaneWidth)
        assertEquals(680.dp, geometry.lyricsPaneWidth)
        assertEquals(176.dp, geometry.transportCardHeight)
        assertEquals(0.dp, geometry.transportCardBottomInset)
        assertEquals(648.dp, geometry.transportCardWidth)
        assertTrue(geometry.lyricsPaneHeight > 0.dp)
        assertEquals(
            geometry.stackedArtworkHeight + geometry.transportCardHeight + 8.dp,
            geometry.stackedHeaderHeight,
        )
    }

    @Test
    fun veryNarrowButTallViewportUsesSmallCoverAndFullWidthTransportCard() {
        val geometry = carNowPlayingGeometry(480.dp, 597.dp)

        assertEquals(CarNowPlayingLayoutMode.Stacked, geometry.mode)
        assertTrue(geometry.artworkMaxSize <= 128.dp)
        assertEquals(448.dp, geometry.transportCardWidth)
        assertTrue(geometry.lyricsPaneHeight >= 250.dp)
    }

    @Test
    fun shortStackedViewportBoundsTransportCardWithoutAFullScreenControlInset() {
        val geometry = carNowPlayingGeometry(480.dp, 300.dp)

        assertEquals(CarNowPlayingLayoutMode.Stacked, geometry.mode)
        assertTrue(geometry.compactTransport)
        assertEquals(144.dp, geometry.transportCardHeight)
        assertEquals(0.dp, geometry.transportCardBottomInset)
        assertTrue(geometry.lyricsPaneHeight > 0.dp)
    }

    @Test
    fun widthBreakpointsUseTheAvailableComposeViewport() {
        assertEquals(CarNowPlayingLayoutMode.Stacked, carNowPlayingGeometry(759.dp, 600.dp).mode)
        assertEquals(CarNowPlayingLayoutMode.Compact, carNowPlayingGeometry(760.dp, 600.dp).mode)
        assertEquals(CarNowPlayingLayoutMode.Compact, carNowPlayingGeometry(1_119.dp, 600.dp).mode)
        assertEquals(CarNowPlayingLayoutMode.Wide, carNowPlayingGeometry(1_120.dp, 600.dp).mode)
    }
}
