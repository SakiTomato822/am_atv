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

    @Test fun headerActionTintUsesAccentOnlyInFlowAndFallsBackToHeaderFg() {
        val accent = Color(0xFFFFC107)
        val headerFg = Color.White
        val darkInk = Color(0xFF16141B)
        val themeFavoriteAccent = Color(0xFF3366FF)

        // Flow with valid accent -> header icons adopt cover accent
        assertEquals(accent, headerActionTint(isPosterMode = false, accentColor = accent, defaultFg = headerFg))

        // Flow without accent (monochrome cover -> transparent) -> header icons keep headerFg
        assertEquals(
            headerFg,
            headerActionTint(isPosterMode = false, accentColor = Color.Transparent, defaultFg = headerFg),
        )

        // Poster mode ignores cover accent and preserves top-strip luminance ink
        assertEquals(headerFg, headerActionTint(isPosterMode = true, accentColor = accent, defaultFg = headerFg))
        assertEquals(darkInk, headerActionTint(isPosterMode = true, accentColor = accent, defaultFg = darkInk))

        // Favorite ★ unifies with cover accent in Flow, stays distinct from unselected ☆
        val favSelectedFlow = resolveHeaderFavoriteTint(
            isFavorite = true,
            isPosterMode = false,
            controlAccent = accent,
            defaultFg = headerFg,
            fallbackFavoriteAccent = themeFavoriteAccent,
        )
        val favUnselectedFlow = resolveHeaderFavoriteTint(
            isFavorite = false,
            isPosterMode = false,
            controlAccent = accent,
            defaultFg = headerFg,
            fallbackFavoriteAccent = themeFavoriteAccent,
        )
        assertEquals(accent, favSelectedFlow)
        assertEquals(accent.copy(alpha = 0.72f), favUnselectedFlow)
        assertEquals(
            themeFavoriteAccent,
            resolveHeaderFavoriteTint(
                isFavorite = true,
                isPosterMode = true,
                controlAccent = accent,
                defaultFg = darkInk,
                fallbackFavoriteAccent = themeFavoriteAccent,
            ),
        )
    }
}
