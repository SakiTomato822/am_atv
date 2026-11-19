package dev.amatv.flyme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class FlymePalette(
    val light: Boolean,
    val ink: Color,
)

internal val DarkFlymePalette = FlymePalette(
    light = false, ink = Color.White,
)
internal val LightFlymePalette = FlymePalette(
    light = true, ink = Color(0xFF1C1C1E),
)
internal val LocalFlymePalette = staticCompositionLocalOf { DarkFlymePalette }

/** Foreground color of the current palette: white in the dark theme, near-black in light mode. */
internal val FlymeInk: Color
    @Composable @ReadOnlyComposable get() = LocalFlymePalette.current.ink

/**
 * Lyric alpha for the current appearance. Apple's white-on-dark alphas leave dark ink on a light
 * page too faint (unsung lines at 18% read as blank in daylight), so the light page lifts each
 * level part of the way towards opaque.
 */
@Composable
@ReadOnlyComposable
internal fun flymeLyricAlpha(alpha: Float): Float =
    if (LocalFlymePalette.current.light) alpha + (1f - alpha) * LIGHT_LYRIC_LIFT else alpha

private const val LIGHT_LYRIC_LIFT = 0.18f
