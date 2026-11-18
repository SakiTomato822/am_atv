package dev.amatv.flyme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioTierTest {

    @Test
    fun classifiesAllReferenceDevicesFromH5Prototype() {
        // Phone portrait: 390x844 -> Narrow (c)
        assertEquals(FolioTier.Narrow, tierOf(390f, 844f))
        // Phone landscape: 844x390 -> Short (m)
        assertEquals(FolioTier.Short, tierOf(844f, 390f))
        // Tablet / foldable inner: 768x1024 -> Medium (t)
        assertEquals(FolioTier.Medium, tierOf(768f, 1024f))
        // Car head unit 1920x1080 @ 1.5 density (1280x720dp) -> Wide (e)
        assertEquals(FolioTier.Wide, tierOf(1280f, 720f))
        // Car head unit 1920x1080 @ 1.0 density -> Wide (e)
        assertEquals(FolioTier.Wide, tierOf(1920f, 1080f))
        // Car small window: 360x600 -> Narrow (c)
        assertEquals(FolioTier.Narrow, tierOf(360f, 600f))
        // Ultrawide: 3840x1080 @ 2.0 density (1920x540dp) or 2560x720dp -> Ultrawide (u)
        assertEquals(FolioTier.Ultrawide, tierOf(1920f, 540f))
        assertEquals(FolioTier.Ultrawide, tierOf(2560f, 720f))
    }

    @Test
    fun respectsExactBoundaryThresholdsAndPrecedence() {
        // Aspect ratio 2.6 takes precedence over Short height and Wide width
        assertEquals(FolioTier.Ultrawide, tierOf(1040f, 400f)) // ratio = 2.6
        assertEquals(FolioTier.Short, tierOf(1039f, 400f))     // ratio < 2.6, w >= 600, h < 480

        // Narrow (w < 600) takes precedence over Short (h < 480)
        assertEquals(FolioTier.Narrow, tierOf(599.9f, 400f))
        assertEquals(FolioTier.Short, tierOf(600f, 479.9f))

        // Height 480 exits Short into Medium (if w < 840) or Wide (if w >= 840)
        assertEquals(FolioTier.Medium, tierOf(600f, 480f))
        assertEquals(FolioTier.Medium, tierOf(839.9f, 480f))
        assertEquals(FolioTier.Wide, tierOf(840f, 480f))
    }

    @Test
    fun dpOverloadMatchesFloatClassification() {
        assertEquals(FolioTier.Narrow, tierOf(390.dp, 844.dp))
        assertEquals(FolioTier.Wide, tierOf(1280.dp, 720.dp))
        assertEquals(FolioTier.Ultrawide, tierOf(1820.dp, 700.dp))
    }

    @Test
    fun tierSpecsMatchH5ChromeBehavior() {
        assertTrue(specForTier(FolioTier.Narrow).usesBottomCapsule)
        assertTrue(specForTier(FolioTier.Short).usesBottomCapsule)
        assertTrue(specForTier(FolioTier.Medium).usesBottomCapsule)
        assertFalse(specForTier(FolioTier.Wide).usesBottomCapsule)
        assertFalse(specForTier(FolioTier.Ultrawide).usesBottomCapsule)

        assertTrue(specForTier(FolioTier.Wide).showsSettingsSidebar)
        assertTrue(specForTier(FolioTier.Ultrawide).showsSettingsSidebar)
        assertFalse(specForTier(FolioTier.Medium).showsSettingsSidebar)
        assertFalse(specForTier(FolioTier.Narrow).showsSettingsSidebar)
        assertFalse(specForTier(FolioTier.Short).showsSettingsSidebar)
    }

    @Test
    fun adaptiveColumnCountsMatchCssAutoFillGrid() {
        // Phone 390dp: inner width 390 - 16 - 14 = 360dp. List (liw=150, gap=14) -> 2 columns.
        val phone = specForTier(FolioTier.Narrow)
        assertEquals(2, adaptiveColumnCount(360.dp, phone.liw, phone.lstGap))
        // Phone grid (gw=100, gap=14) -> 3 columns (100*3 + 14*2 = 328 <= 360).
        assertEquals(3, adaptiveColumnCount(360.dp, phone.gw, 14.dp))

        // Car 1280dp: inner width 1280 - 48 - 40 = 1192dp. List (liw=340, gap=32) -> 3 columns.
        val car = specForTier(FolioTier.Wide)
        assertEquals(3, adaptiveColumnCount(1192.dp, car.liw, car.lstGap))
    }

    @Test
    fun motionDurationsAndPalettesMatchH5Tokens() {
        assertEquals(200, FolioMotion.FAST_MS)
        assertEquals(600, FolioMotion.SLOW_MS)
        assertFalse(DarkFolioPalette.isLight)
        assertTrue(LightFolioPalette.isLight)
    }

    @Test
    fun densityClampsLowDensityDisplaysToOnePointFiveAndConvertsDp() {
        // 1920x1080 @ 160dpi (density 1.0) -> 1.5 -> 1280x720 dp (Wide)
        assertEquals(1.5f, folioRenderDensity(1.0f), 0.0001f)
        val (carW, carH) = folioLayoutDp(1920f, 1080f, 1.0f)
        assertEquals(1280f, carW, 0.01f)
        assertEquals(720f, carH, 0.01f)
        assertEquals(FolioTier.Wide, tierOf(carW, carH))

        // 3840x1080 @ 160dpi (density 1.0) -> 1.5 -> 2560x720 dp (Ultrawide)
        val (uwW, uwH) = folioLayoutDp(3840f, 1080f, 1.0f)
        assertEquals(2560f, uwW, 0.01f)
        assertEquals(720f, uwH, 0.01f)
        assertEquals(FolioTier.Ultrawide, tierOf(uwW, uwH))

        // 1920x1080 @ 240dpi (density 1.5) -> unchanged 1.5 -> 1280x720 dp (Wide)
        assertEquals(1.5f, folioRenderDensity(1.5f), 0.0001f)
        // High-density phone (3.0) -> unchanged 3.0 -> 390x844 dp (Narrow)
        assertEquals(3.0f, folioRenderDensity(3.0f), 0.0001f)
        val (phoneW, phoneH) = folioLayoutDp(1170f, 2532f, 3.0f)
        assertEquals(390f, phoneW, 0.01f)
        assertEquals(844f, phoneH, 0.01f)
        assertEquals(FolioTier.Narrow, tierOf(phoneW, phoneH))
    }
}
