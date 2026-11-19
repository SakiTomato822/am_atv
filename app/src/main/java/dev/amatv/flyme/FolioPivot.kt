package dev.amatv.flyme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class FolioPivotPlacement(
    val translationX: Float,
    val isPeekingLeft: Boolean,
)

/**
 * Computes Metro pivot title strip translation and left-edge peek state, matching `goTo` in
 * `flyme15-app.html`:
 * `full = prv ? px + prvWidth + gap : px; limit = pivotWidth / 3; at = prv ? min(full, limit) : px`
 */
internal fun computePivotPlacementForPage(
    pageIndex: Int,
    tabWidthsPx: List<Float>,
    pxPx: Float,
    gapPx: Float,
    pivotWidthPx: Float,
): FolioPivotPlacement {
    if (tabWidthsPx.isEmpty()) return FolioPivotPlacement(pxPx, isPeekingLeft = false)
    val clamped = pageIndex.coerceIn(0, tabWidthsPx.lastIndex)
    var offsetLeft = 0f
    for (i in 0 until clamped) {
        offsetLeft += tabWidthsPx[i] + gapPx
    }
    if (clamped == 0) {
        return FolioPivotPlacement(translationX = pxPx, isPeekingLeft = false)
    }
    val prevWidth = tabWidthsPx[clamped - 1]
    val full = pxPx + prevWidth + gapPx
    val limit = pivotWidthPx / 3f
    val at = minOf(full, limit)
    return FolioPivotPlacement(
        translationX = at - offsetLeft,
        isPeekingLeft = full > limit,
    )
}

internal fun tabAlphaForPage(tabIndex: Int, pageIndex: Int): Float = when (tabIndex) {
    pageIndex -> 1f
    pageIndex - 1 -> 0.30f
    else -> 0.32f
}

/** Metro pivot title strip synchronized with [pageProgress] (`0f..titles.lastIndex`). */
@Composable
internal fun FolioPivotBar(
    titles: List<String>,
    pageProgress: Float,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current

    val maxIndex = (titles.size - 1).coerceAtLeast(0)
    val progress = pageProgress.coerceIn(0f, maxIndex.toFloat())
    val lower = floor(progress).toInt().coerceIn(0, maxIndex)
    val upper = ceil(progress).toInt().coerceIn(0, maxIndex)
    val fraction = progress - lower

    val peekStrength = remember { floatArrayOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tierSpec.pvh)
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val maskAlpha = peekStrength[0]
                if (maskAlpha > 0.01f && size.width > 0f) {
                    val fadePx = 18.dp.toPx()
                    val stop = (fadePx / size.width).coerceIn(0.01f, 0.25f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 1f - maskAlpha),
                            stop to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                titles.forEachIndexed { index, title ->
                    val lowAlpha = tabAlphaForPage(index, lower)
                    val highAlpha = tabAlphaForPage(index, upper)
                    val tabAlpha = lowAlpha + (highAlpha - lowAlpha) * fraction
                    Box(
                        modifier = Modifier
                            .folioBrowsePressable(
                                role = Role.Tab,
                                onClick = { onSelectPage(index) },
                            )
                            .semantics { selected = index == selectedPage }
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            text = title,
                            color = palette.fg.copy(alpha = tabAlpha),
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Light,
                            fontSize = tierSpec.pvf,
                            lineHeight = tierSpec.pvf * 1.12f,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val unbounded = Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
            val placeables = measurables.map { it.measure(unbounded) }
            val widths = placeables.map { it.width.toFloat() }
            val pxPx = tierSpec.px.toPx()
            val gapPx = tierSpec.pvg.toPx()
            val pivotWidthPx = constraints.maxWidth.toFloat()

            val lowPlacement = computePivotPlacementForPage(lower, widths, pxPx, gapPx, pivotWidthPx)
            val highPlacement = computePivotPlacementForPage(upper, widths, pxPx, gapPx, pivotWidthPx)
            val startX = lowPlacement.translationX +
                (highPlacement.translationX - lowPlacement.translationX) * fraction
            peekStrength[0] = (if (lowPlacement.isPeekingLeft) 1f - fraction else 0f) +
                (if (highPlacement.isPeekingLeft) fraction else 0f)

            layout(constraints.maxWidth, constraints.maxHeight) {
                var x = startX
                placeables.forEach { placeable ->
                    placeable.placeRelative(x = x.roundToInt(), y = 0)
                    x += placeable.width + gapPx
                }
            }
        }
    }
}
