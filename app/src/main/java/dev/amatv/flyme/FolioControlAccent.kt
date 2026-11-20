package dev.amatv.flyme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.amatv.artwork.ArtworkPalette
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.model.AppleArtworkColors

internal data class ControlAccent(val color: Color, val foreground: Color)

internal fun controlAccent(
    appleColors: AppleArtworkColors?,
    palette: List<Color>,
    background: Color,
): ControlAccent? {
    val apple = appleColors?.run {
        listOfNotNull(textColor1, textColor2, textColor3, textColor4, bgColor)
    }.orEmpty()
    val candidates = apple.asSequence().map { Color(it.toInt(16) or 0xFF000000.toInt()) } +
        palette.asSequence()
    val color = candidates.firstOrNull {
        ArtworkPalette.chroma(it.toArgb()) >= 0.06 && contrastRatio(it, background) >= 3f
    } ?: return null
    return ControlAccent(color, controlAccentForeground(color))
}

// Taking the higher WCAG contrast of black and white guarantees at least sqrt(21):1.
internal fun controlAccentForeground(fill: Color): Color =
    if (contrastRatio(Color.Black, fill) >= contrastRatio(Color.White, fill)) Color.Black else Color.White

internal fun folioControlBackground(
    style: LyricBackgroundStyle,
    artworkPalette: List<Color>,
    base: Color,
    isLight: Boolean,
    lowPerformance: Boolean,
): Color = when {
    style == LyricBackgroundStyle.Soak -> base
    lowPerformance -> lerp(base, artworkPalette.last(), if (isLight) 0.06f else 0.11f)
    else -> {
        val flow = Color.Black.copy(alpha = 0.28f).compositeOver(artworkPalette.first())
        val veil = if (isLight) Color(0xFFF6F4F9).copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.24f)
        veil.compositeOver(flow)
    }
}

internal val LocalFolioControlAccent = staticCompositionLocalOf<State<Color>?> { null }

@Composable
internal fun rememberFolioControlAccent(
    appleColors: AppleArtworkColors?,
    palette: List<Color>,
    background: Color,
    neutral: Color,
    animate: Boolean,
): State<Color> {
    val accent = remember(appleColors, palette, background) {
        controlAccent(appleColors, palette, background)
    }
    val target = accent?.color ?: neutral.copy(alpha = 0f)
    return if (animate) {
        animateColorAsState(target, tween(400), label = "folio-control-accent")
    } else {
        rememberUpdatedState(target)
    }
}
