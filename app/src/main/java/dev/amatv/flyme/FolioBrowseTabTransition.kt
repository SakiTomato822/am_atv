package dev.amatv.flyme

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal class FolioBrowseTabTransition(val from: Int, val to: Int) {
    val progress = Animatable(0f)
    val distance = abs(to - from)

    fun pageModifier(
        page: Int,
        layoutDirection: LayoutDirection,
        pagerPosition: () -> Float,
    ): Modifier = Modifier.graphicsLayer {
        val direction = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
        val step = if (to > from) 1 else -1
        val fraction = progress.value
        when (page) {
            from -> {
                alpha = 1f - fraction
                translationX = ((pagerPosition() - page) * size.width - step * 12.dp.toPx() * fraction) * direction
            }
            to -> {
                alpha = fraction
                translationX = ((pagerPosition() - page) * size.width + step * 12.dp.toPx() * (1f - fraction)) * direction
            }
            else -> alpha = 0f
        }
    }
}
