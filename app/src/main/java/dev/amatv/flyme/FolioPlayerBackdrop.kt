package dev.amatv.flyme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.model.Track

@Composable
internal fun FolioPlayerBackdrop(
    track: Track,
    artworkPalette: List<Color>,
    style: LyricBackgroundStyle,
    isLight: Boolean,
    animateFlow: Boolean,
    reducedMotion: Boolean = false,
    tierSpec: FolioPlayerTierSpec,
    displayState: FolioPlayerDisplayState,
    pullHandle: Modifier,
    onRevealTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseColor = folioPlayerBaseColor(artworkPalette, isLight)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor)
            .pointerInput(Unit) { detectTapGestures { onRevealTap() } },
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val coordHolder = LocalFolioGlassEnv.current.coordHolder
        Crossfade(
            targetState = style,
            animationSpec = tween(if (reducedMotion) 0 else BACKDROP_STYLE_CROSSFADE_MS),
            label = "backdrop-style-crossfade",
        ) { activeStyle ->
            Box(Modifier.fillMaxSize()) {
                if (activeStyle == LyricBackgroundStyle.Flow) {
                    CarLyricFlow(track = track, palette = artworkPalette, animate = animateFlow)
                    val veil = if (isLight) Color(0xFFF6F4F9).copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.24f)
                    Box(Modifier.fillMaxSize().background(veil))
                } else {
                    // Soak style ("海报（封面背景）")
                    val showBleedArtwork = showSoakArtworkStage(tierSpec.tier, activeStyle, displayState)
                    if (showBleedArtwork) {
                        val isVerticalBleed = tierSpec.tier == FolioTier.Narrow || tierSpec.tier == FolioTier.Medium
                        if (isVerticalBleed) {
                            val bleedHeight = containerHeight * tierSpec.soakCoverHeightFraction
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(bleedHeight)
                                    .then(if (coordHolder != null) Modifier.onPlaced { coordHolder.bleedCoords = it } else Modifier)
                                    .then(pullHandle),
                            ) {
                                MotionArtwork(
                                    track = track,
                                    modifier = Modifier.fillMaxSize(),
                                    animate = false,
                                    cornerRadius = 0.dp,
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                0.34f to Color.Transparent,
                                                0.66f to baseColor.copy(alpha = 0.56f),
                                                0.88f to baseColor.copy(alpha = 0.92f),
                                                1f to baseColor,
                                            ),
                                        ),
                                )
                            }
                        } else {
                            val bleedWidth = tierSpec.soakCoverWidth.coerceAtMost(containerWidth * 0.68f)
                            Box(
                                modifier = Modifier
                                    .width(bleedWidth)
                                    .fillMaxHeight()
                                    .then(if (coordHolder != null) Modifier.onPlaced { coordHolder.bleedCoords = it } else Modifier)
                                    .then(pullHandle),
                            ) {
                                MotionArtwork(
                                    track = track,
                                    modifier = Modifier.fillMaxSize(),
                                    animate = false,
                                    cornerRadius = 0.dp,
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                0f to Color.Transparent,
                                                0.40f to Color.Transparent,
                                                0.64f to baseColor.copy(alpha = 0.38f),
                                                0.84f to baseColor.copy(alpha = 0.78f),
                                                0.95f to baseColor.copy(alpha = 0.96f),
                                                1f to baseColor,
                                            ),
                                        ),
                                )
                            }
                        }
                    }

                    if (isLight) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.White.copy(alpha = 0.10f),
                                        0.5f to baseColor.copy(alpha = 0.16f),
                                        1f to baseColor.copy(alpha = 0.28f),
                                    ),
                                ),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.18f),
                                        0.32f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.28f),
                                    ),
                                ),
                        )
                        SoakGlow(artworkPalette)
                    }
                }
            }
        }
    }
}
