package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.model.AppleArtworkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioControlAccentTest {
    @Test fun blackAndWhiteArtworkKeepsMonochromeControls() {
        assertNull(controlAccent(null, listOf(Color.White, Color.Gray, Color.Black), Color.Black))
    }

    @Test fun neonAccentPassesContrastInDarkFlowAndSoak() {
        val palette = listOf(Color(0xFF131021), Color(0xFF65FF00))
        val base = folioPlayerBaseColor(palette, isLight = false)
        for (style in LyricBackgroundStyle.entries) {
            val background = folioControlBackground(style, palette, base, isLight = false, lowPerformance = false)
            val accent = checkNotNull(controlAccent(null, palette, background))
            assertEquals(palette[1], accent.color)
            assertTrue(contrastRatio(accent.color, background) >= 3f)
            assertTrue(contrastRatio(accent.foreground, accent.color) >= 4.5f)
            assertEquals(Color.Black, accent.foreground)
        }
    }

    @Test fun nearWhiteAccentIsRejectedAgainstLightBackground() {
        val blue = Color(0xFF123C98)
        val palette = listOf(Color.White, Color(0xFFFFE2CD), blue)
        val base = folioPlayerBaseColor(palette, isLight = true)
        for (style in LyricBackgroundStyle.entries) {
            val background = folioControlBackground(style, palette, base, isLight = true, lowPerformance = false)
            val accent = checkNotNull(controlAccent(null, palette, background))
            assertEquals(blue, accent.color)
            assertTrue(contrastRatio(accent.color, background) >= 3f)
            assertEquals(Color.White, accent.foreground)
        }
    }

    @Test fun eligibleAppleTextColorPrecedesAppleBackgroundAndExtractedPalette() {
        val apple = AppleArtworkColors(
            bgColor = "12ff12", textColor1 = "ffffff", textColor2 = "110a44", textColor3 = "ff9300",
        )
        val palette = listOf(Color(0xFF00AAFF))
        assertEquals(Color(0xFFFF9300), controlAccent(apple, palette, Color.Black)?.color)
        assertEquals(Color(0xFF12FF12), controlAccent(apple.copy(textColor3 = null), palette, Color.Black)?.color)
    }
}
