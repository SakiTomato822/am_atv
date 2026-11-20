package dev.amatv.flyme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Defaults for player & mini-player horizontal marquee scrolling (U1). */
object FolioMarqueeDefaults {
    const val INITIAL_DELAY_MS: Int = 1_500
    const val REPEAT_DELAY_MS: Int = 1_500
    val VELOCITY: Dp = 30.dp
    val EDGE_FADE_WIDTH: Dp = 12.dp
    const val LEFT_FADE_IN_MS: Int = 180
}

/** Pure helper determining when horizontal marquee is permitted (`false` falls back to ellipsis). */
fun shouldRunFolioMarquee(
    reducedMotion: Boolean,
    lowPerformance: Boolean,
    text: String,
): Boolean = !reducedMotion && !lowPerformance && text.isNotEmpty()

/**
 * Single-line text that scrolls horizontally via [basicMarquee] with soft faded edges only when it
 * overflows its container, pausing [FolioMarqueeDefaults.INITIAL_DELAY_MS] before scrolling and
 * between loops. Falls back to static ellipsis under reduced motion or low performance mode, and
 * restarts cleanly from the beginning whenever [resetKey] changes.
 */
@Composable
internal fun FolioMarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    resetKey: Any? = text,
    reducedMotion: Boolean = LocalFolioReducedMotion.current || !LocalFolioBrowseMotion.current.animated,
    lowPerformance: Boolean = LocalFolioLowPerformance.current || LocalFolioBrowseMotion.current.lowPerformance,
) {
    if (!shouldRunFolioMarquee(reducedMotion = reducedMotion, lowPerformance = lowPerformance, text = text)) {
        Text(
            text = text,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    key(resetKey, text) {
        var containerWidthPx by remember { mutableIntStateOf(0) }
        var contentWidthPx by remember { mutableIntStateOf(0) }
        val hasOverflow = containerWidthPx > 0 && contentWidthPx > containerWidthPx

        var leftFadeTarget by remember { mutableStateOf(false) }
        LaunchedEffect(hasOverflow) {
            leftFadeTarget = false
            if (hasOverflow) {
                delay(FolioMarqueeDefaults.INITIAL_DELAY_MS.toLong())
                leftFadeTarget = true
            }
        }
        val leftFadeAlphaState = animateFloatAsState(
            targetValue = if (leftFadeTarget) 1f else 0f,
            animationSpec = tween(FolioMarqueeDefaults.LEFT_FADE_IN_MS),
            label = "folio-marquee-left-fade",
        )

        val edgeModifier = if (hasOverflow) {
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fadePx = minOf(FolioMarqueeDefaults.EDGE_FADE_WIDTH.toPx(), size.width * 0.25f)
                    if (fadePx > 0.5f) {
                        val leftAlpha = leftFadeAlphaState.value
                        if (leftAlpha > 0.01f) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 1f - leftAlpha),
                                    1f to Color.Black,
                                    startX = 0f,
                                    endX = fadePx,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(fadePx, size.height),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startX = size.width - fadePx,
                                endX = size.width,
                            ),
                            topLeft = Offset(size.width - fadePx, 0f),
                            size = Size(fadePx, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
        } else {
            Modifier
        }

        val alignment = if (textAlign == TextAlign.Center && !hasOverflow) {
            Alignment.Center
        } else {
            Alignment.CenterStart
        }

        Box(
            modifier = modifier
                .onSizeChanged { containerWidthPx = it.width }
                .then(edgeModifier),
            contentAlignment = alignment,
        ) {
            Text(
                text = text,
                color = color,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = fontSize,
                lineHeight = lineHeight,
                textAlign = textAlign,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { layoutResult ->
                    val widthPx = layoutResult.size.width
                    if (contentWidthPx != widthPx) contentWidthPx = widthPx
                },
                modifier = Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    animationMode = MarqueeAnimationMode.Immediately,
                    repeatDelayMillis = FolioMarqueeDefaults.REPEAT_DELAY_MS,
                    initialDelayMillis = FolioMarqueeDefaults.INITIAL_DELAY_MS,
                    velocity = FolioMarqueeDefaults.VELOCITY,
                ),
            )
        }
    }
}
