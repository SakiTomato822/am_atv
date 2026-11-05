package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The selected look of a segmented capsule group, drawn once behind the row and moved between
 * segments on a spring, instead of each segment snapping its own background on and off.
 *
 * Cost: the selected segment reports its box when placed; the capsule animates four floats and
 * is drawn in the draw phase only, so a change never recomposes or re-measures the row.
 */
internal class SlidingPill {
    var target by mutableStateOf<Rect?>(null)
    val left = Animatable(0f)
    val right = Animatable(0f)
    val top = Animatable(0f)
    val bottom = Animatable(0f)
    var shown by mutableStateOf(false)
}

internal val LocalSlidingPill = staticCompositionLocalOf<SlidingPill?> { null }

@Composable
internal fun rememberSlidingPill(): SlidingPill {
    val pill = remember { SlidingPill() }
    LaunchedEffect(pill) {
        androidx.compose.runtime.snapshotFlow { pill.target }.collect { rect ->
            if (rect == null) return@collect
            if (!pill.shown) {
                // First placement: appear in place, nothing slides in from the corner.
                pill.left.snapTo(rect.left); pill.right.snapTo(rect.right)
                pill.top.snapTo(rect.top); pill.bottom.snapTo(rect.bottom)
                pill.shown = true
                return@collect
            }
            coroutineScope {
                // The leading edge moves a touch faster than the trailing one, so the capsule
                // stretches towards its new segment and then catches up (a liquid feel).
                val forward = rect.left > pill.left.value
                val lead = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
                val trail = spring<Float>(dampingRatio = 0.8f, stiffness = 300f)
                launch { pill.left.animateTo(rect.left, if (forward) trail else lead) }
                launch { pill.right.animateTo(rect.right, if (forward) lead else trail) }
                launch { pill.top.animateTo(rect.top, lead) }
                launch { pill.bottom.animateTo(rect.bottom, lead) }
            }
        }
    }
    return pill
}

/** Put on the row (after its padding) that holds the segments. */
internal fun Modifier.slidingPillBackground(pill: SlidingPill, color: Color): Modifier = drawBehind {
    if (!pill.shown) return@drawBehind
    val l = pill.left.value
    val t = pill.top.value
    val w = (pill.right.value - l).coerceAtLeast(0f)
    val h = (pill.bottom.value - t).coerceAtLeast(0f)
    drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f))
}

/**
 * Put on each segment; the selected one tells the capsule where to go. Placement alone does not
 * re-run when only the selection changes, so the box is kept and sent on every selection change.
 */
@Composable
internal fun Modifier.slidingPillSegment(selected: Boolean): Modifier {
    val pill = LocalSlidingPill.current ?: return this
    var box by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(selected, box) {
        if (selected) box?.let { pill.target = it }
    }
    return onPlaced { coordinates -> box = Rect(coordinates.positionInParent(), coordinates.size.toSize()) }
}

@Composable
internal fun ProvideSlidingPill(pill: SlidingPill, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSlidingPill provides pill, content = content)
}
