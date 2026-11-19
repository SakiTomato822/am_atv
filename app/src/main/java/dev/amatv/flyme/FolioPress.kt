package dev.amatv.flyme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** Restraint press feedback defaults shared across Folio UI surfaces. */
object FolioPressDefaults {
    const val SCALE_CARD: Float = 0.97f
    const val SCALE_COMPACT: Float = 0.92f
    const val SPRING_DAMPING: Float = 0.8f
    const val SPRING_STIFFNESS: Float = 600f
    const val DARK_OVERLAY_ALPHA: Float = 0.08f
    const val LIGHT_OVERLAY_ALPHA: Float = 0.06f
    const val OVERLAY_FADE_MS: Int = 120
}

internal val LocalFolioReducedMotion = staticCompositionLocalOf { false }
internal val LocalFolioLowPerformance = staticCompositionLocalOf { false }

fun resolveFolioPressScale(
    pressed: Boolean,
    enabled: Boolean,
    compact: Boolean,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
): Float = when {
    !pressed || !enabled || lowPerformance || reducedMotion -> 1f
    compact -> FolioPressDefaults.SCALE_COMPACT
    else -> FolioPressDefaults.SCALE_CARD
}

fun resolveFolioPressOverlayAlpha(
    pressed: Boolean,
    enabled: Boolean,
    isLight: Boolean,
): Float = if (pressed && enabled) {
    if (isLight) FolioPressDefaults.LIGHT_OVERLAY_ALPHA else FolioPressDefaults.DARK_OVERLAY_ALPHA
} else {
    0f
}

/**
 * Visual-only press feedback modifier (spring scale + overlay) bound to an external
 * [interactionSource], allowing a container (e.g. collapsed capsule) to animate only when its
 * non-nested region is pressed rather than when an inner button captures the press.
 */
fun Modifier.folioPressFeedback(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    compact: Boolean = false,
    isLight: Boolean? = null,
    lowPerformance: Boolean? = null,
    reducedMotion: Boolean? = null,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val activeLight = isLight ?: LocalFolioPalette.current.isLight
    val activeLowPerf = lowPerformance ?: LocalFolioLowPerformance.current
    val activeReduced = reducedMotion ?: LocalFolioReducedMotion.current

    val targetScale = resolveFolioPressScale(
        pressed = pressed,
        enabled = enabled,
        compact = compact,
        lowPerformance = activeLowPerf,
        reducedMotion = activeReduced,
    )
    val targetOverlayAlpha = resolveFolioPressOverlayAlpha(
        pressed = pressed,
        enabled = enabled,
        isLight = activeLight,
    )
    val scaleState = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (activeReduced || activeLowPerf) {
            snap()
        } else {
            spring(
                dampingRatio = FolioPressDefaults.SPRING_DAMPING,
                stiffness = FolioPressDefaults.SPRING_STIFFNESS,
            )
        },
        label = "folio-press-scale",
    )
    val overlayAlphaState = animateFloatAsState(
        targetValue = targetOverlayAlpha,
        animationSpec = tween(durationMillis = if (activeReduced) 0 else FolioPressDefaults.OVERLAY_FADE_MS),
        label = "folio-press-overlay",
    )
    val overlayColor = if (activeLight) Color.Black else Color.White

    this
        .graphicsLayer {
            val s = scaleState.value
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            val a = overlayAlphaState.value
            if (a > 0.001f) {
                drawRect(color = overlayColor.copy(alpha = a))
            }
        }
}

/**
 * Shared press feedback modifier replacing Android default ripple with subtle spring scale and
 * surface luminance overlay. Scale and overlay alpha are read strictly in graphics/draw phases.
 */
fun Modifier.folioPressable(
    enabled: Boolean = true,
    compact: Boolean = false,
    role: Role? = null,
    onClickLabel: String? = null,
    isLight: Boolean? = null,
    lowPerformance: Boolean? = null,
    reducedMotion: Boolean? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val resolvedSource = interactionSource ?: remember { MutableInteractionSource() }
    this
        .folioPressFeedback(
            interactionSource = resolvedSource,
            enabled = enabled,
            compact = compact,
            isLight = isLight,
            lowPerformance = lowPerformance,
            reducedMotion = reducedMotion,
        )
        .clickable(
            interactionSource = resolvedSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
