package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioGlassTest {

    @Test
    fun resolveGlassTierMatchesOsCapabilitiesAndCapsUnsafeOverrides() {
        // Auto mode (0)
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 28, overrideCode = 0))
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 30, overrideCode = 0))
        assertEquals(FolioGlassTier.BlurB, resolveFolioGlassTier(sdkInt = 31, overrideCode = 0))
        assertEquals(FolioGlassTier.BlurB, resolveFolioGlassTier(sdkInt = 32, overrideCode = 0))
        assertEquals(FolioGlassTier.FullA, resolveFolioGlassTier(sdkInt = 33, overrideCode = 0))
        assertEquals(FolioGlassTier.FullA, resolveFolioGlassTier(sdkInt = 35, overrideCode = 0))

        // API 35 allows forcing any of A / B / C for benchmarking
        assertEquals(FolioGlassTier.FullA, resolveFolioGlassTier(sdkInt = 35, overrideCode = 1))
        assertEquals(FolioGlassTier.BlurB, resolveFolioGlassTier(sdkInt = 35, overrideCode = 2))
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 35, overrideCode = 3))
        assertEquals(FolioGlassTier.FullA, resolveFolioGlassTier(sdkInt = 35, overrideCode = 99))

        // Forced tier higher than system capability falls back to auto
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 30, overrideCode = 1))
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 30, overrideCode = 2))
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 30, overrideCode = 3))
        assertEquals(FolioGlassTier.BlurB, resolveFolioGlassTier(sdkInt = 32, overrideCode = 1))
        assertEquals(FolioGlassTier.BlurB, resolveFolioGlassTier(sdkInt = 32, overrideCode = 2))
        assertEquals(FolioGlassTier.FallbackC, resolveFolioGlassTier(sdkInt = 32, overrideCode = 3))
    }

    @Test
    fun glassColorsShareBordersAndHighlightsAcrossTiersWithExpectedTintAlphas() {
        val aDark = resolveFolioGlassColors(FolioGlassTier.FullA, isLight = false)
        val bDark = resolveFolioGlassColors(FolioGlassTier.BlurB, isLight = false)
        val cDark = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight = false)

        assertEquals(0.35f, aDark.fill.alpha, 0.01f)
        assertEquals(0.35f, bDark.fill.alpha, 0.01f)
        assertEquals(0.45f, cDark.fill.alpha, 0.01f)
        assertEquals(aDark.border, bDark.border)
        assertEquals(bDark.border, cDark.border)
        assertEquals(aDark.highlightTop, cDark.highlightTop)

        val aLight = resolveFolioGlassColors(FolioGlassTier.FullA, isLight = true)
        val bLight = resolveFolioGlassColors(FolioGlassTier.BlurB, isLight = true)
        val cLight = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight = true)

        assertEquals(0.24f, aLight.fill.alpha, 0.01f)
        assertEquals(0.24f, bLight.fill.alpha, 0.01f)
        assertEquals(0.55f, cLight.fill.alpha, 0.01f)
        assertEquals(aLight.border, bLight.border)
        assertEquals(aLight.highlightTop, bLight.highlightTop)
        assertTrue(aLight.highlightTop.alpha > cLight.highlightTop.alpha)
    }

    @Test
    fun centeredCropRectFillsDestinationProportionally() {
        val wide = computeCenteredCropRect(srcWidth = 64f, srcHeight = 64f, dstWidth = 640f, dstHeight = 400f)
        assertEquals(0f, wide[0], 0.01f)
        assertEquals(-120f, wide[1], 0.01f)
        assertEquals(640f, wide[2], 0.01f)
        assertEquals(640f, wide[3], 0.01f)

        val tall = computeCenteredCropRect(srcWidth = 64f, srcHeight = 64f, dstWidth = 400f, dstHeight = 800f)
        assertEquals(-200f, tall[0], 0.01f)
        assertEquals(0f, tall[1], 0.01f)
        assertEquals(800f, tall[2], 0.01f)
        assertEquals(800f, tall[3], 0.01f)
    }

    @Test
    fun stackBlurSoftensStepEdgeWhilePreservingUniformColor() {
        val uniformColor = 0xFF336699.toInt()
        val uniform = IntArray(16 * 16) { uniformColor }
        stackBlurArgb(uniform, width = 16, height = 16, radius = 3)
        for (px in uniform) {
            assertEquals(uniformColor, px)
        }

        // Left half black, right half white -> center column softens to intermediate gray.
        val split = IntArray(16 * 16) { idx ->
            if ((idx % 16) < 8) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        stackBlurArgb(split, width = 16, height = 16, radius = 3)
        val centerLeftRed = (split[8 * 16 + 7] ushr 16) and 0xFF
        val centerRightRed = (split[8 * 16 + 8] ushr 16) and 0xFF
        assertTrue(centerLeftRed in 40..140)
        assertTrue(centerRightRed in 115..215)
    }

    @Test
    fun directionalEdgeSpecRespectsDarkLightAndAlbumTint() {
        val albumTint = Color(0xFFB488A0)
        val darkTinted = resolveFolioGlassEdgeSpec(isLight = false, albumTint = albumTint)
        val darkNeutral = resolveFolioGlassEdgeSpec(isLight = false, albumTint = null)
        val lightTinted = resolveFolioGlassEdgeSpec(isLight = true, albumTint = albumTint)
        val lightNeutral = resolveFolioGlassEdgeSpec(isLight = true, albumTint = null)

        assertEquals(0.72f, darkTinted.strokeStart.alpha, 0.003f)
        assertEquals(0.55f, darkTinted.strokeEnd.alpha, 0.003f)
        assertEquals(albumTint.red, darkTinted.strokeEnd.red, 0.003f)
        assertEquals(Color.White.red, darkNeutral.strokeEnd.red, 0.003f)
        assertEquals(0.10f, darkNeutral.strokeEnd.alpha, 0.003f)
        assertEquals(0.22f, darkTinted.bottomLine.alpha, 0.003f)

        assertTrue(lightTinted.strokeStart.alpha > darkTinted.strokeStart.alpha)
        assertTrue(lightTinted.strokeMid1.alpha > darkTinted.strokeMid1.alpha)
        assertEquals(0.48f, lightTinted.strokeEnd.alpha, 0.003f)
        assertEquals(Color.White.red, lightNeutral.strokeEnd.red, 0.003f)
        assertEquals(0.10f, lightNeutral.strokeEnd.alpha, 0.003f)
        assertEquals(0.08f, lightTinted.bottomLine.alpha, 0.003f)

        val boosted = resolveFolioGlassEdgeSpec(
            isLight = false,
            albumTint = albumTint,
            edgeBrightness = FolioGatherMotion.TRACK_EDGE_GATHER_MULTIPLIER,
        )
        assertEquals(1.0f, boosted.strokeStart.alpha, 0.003f)
        assertEquals(0.24f * FolioGatherMotion.TRACK_EDGE_GATHER_MULTIPLIER, boosted.strokeMid1.alpha, 0.003f)
        assertEquals(0.55f * FolioGatherMotion.TRACK_EDGE_GATHER_MULTIPLIER, boosted.strokeEnd.alpha, 0.003f)
        assertEquals(0.05f, boosted.strokeMid2.alpha, 0.003f)
        assertEquals(0.22f, boosted.bottomLine.alpha, 0.003f)
    }

    @Test
    fun shouldTriggerTrackGatherRequiresOpenAndChangedTrackId() {
        assertFalse(shouldTriggerTrackGather(open = false, previousOpenTrackId = "a", nextTrackId = "b"))
        assertFalse(shouldTriggerTrackGather(open = true, previousOpenTrackId = null, nextTrackId = "a"))
        assertFalse(shouldTriggerTrackGather(open = true, previousOpenTrackId = "a", nextTrackId = "a"))
        assertTrue(shouldTriggerTrackGather(open = true, previousOpenTrackId = "a", nextTrackId = "b"))
    }
}
