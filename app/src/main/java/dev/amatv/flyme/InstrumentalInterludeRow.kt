package dev.amatv.flyme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.amatv.model.LyricTimeline

/** Three gradually lit dots, with a continuous pulse-to-exit handoff. */
@Composable
internal fun InstrumentalInterludeRow(
    gap: LyricTimeline.InstrumentalGap,
    positionState: State<Long>,
    isUserScrolling: Boolean,
) {
    val unlitAlpha = flymeLyricAlpha(if (isUserScrolling) {
        MotionTokens.KaraokeSecondaryAlpha
    } else {
        MotionTokens.KaraokeTertiaryAlpha
    })

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 84.dp, top = 18.dp, bottom = 26.dp),
    ) {
        val ink = FlymeInk
        Canvas(
            modifier = Modifier
                .width(66.dp)
                .height(20.dp)
                .graphicsLayer {
                    val pos = positionState.value
                    val pulseScale = InterludeMotion.scale(gap, pos)
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = InterludeMotion.alpha(gap, pos)
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        ) {
            val pos = positionState.value
            val elapsedInGap = (pos - gap.startMs).coerceAtLeast(0L)
            val dotRadius = 5.dp.toPx()
            val dotStepX = 22.dp.toPx()
            val firstCenterX = 11.dp.toPx()
            val centerY = size.height / 2f

            for (dotIndex in 0 until 3) {
                val enterElapsed = (elapsedInGap - dotIndex * MotionTokens.InstrumentalEnterStaggerMillis)
                    .coerceAtLeast(0L)
                val enterFraction = (enterElapsed.toFloat() / MotionTokens.InstrumentalEnterMillis)
                    .coerceIn(0f, 1f)
                val enterAlpha = enterFraction * enterFraction

                val tintFraction = InterludeMotion.dotFraction(gap, pos, dotIndex)
                val tintProgress = MotionTokens.InstrumentalDotTintEasing.transform(tintFraction)
                val dotTintAlpha = unlitAlpha + (MotionTokens.KaraokePrimaryAlpha - unlitAlpha) * tintProgress

                drawCircle(
                    color = ink.copy(alpha = (dotTintAlpha * enterAlpha).coerceIn(0f, 1f)),
                    radius = dotRadius,
                    center = Offset(firstCenterX + dotIndex * dotStepX, centerY),
                )
            }
        }
    }
}
