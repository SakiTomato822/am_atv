package dev.amatv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Uses the row's real scrolling policy, including its reachable start/end. */
@OptIn(ExperimentalFoundationApi::class)
internal class FocusScrollPlacement(
    private val state: LazyListState,
    private val spec: BringIntoViewSpec,
    private val viewport: FocusFrameGeometry,
    private val verticalScrollOffset: State<Float>? = null,
) {
    fun destination(bounds: Rect, focusedScale: Float): Rect {
        val viewportBounds = viewport.bounds ?: return bounds
        if (viewportBounds.width <= 0f) return bounds
        val visual = visualBounds(bounds, focusedScale)
        val requested = spec.calculateScrollDistance(
            visual.left - viewportBounds.left, visual.width, viewportBounds.width,
        )
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        val last = layout.visibleItemsInfo.lastOrNull()
        val distance = reachableFocusScroll(
            requested,
            first?.takeIf { it.index == 0 }?.offset,
            last?.takeIf { it.index == layout.totalItemsCount - 1 }?.let { it.offset + it.size },
            layout.viewportEndOffset,
            layout.afterContentPadding,
        )
        return bounds.translate(-distance, -(verticalScrollOffset?.value ?: 0f))
    }
}

internal fun reachableFocusScroll(
    requested: Float,
    firstItemOffset: Int?,
    lastItemEnd: Int?,
    viewportEnd: Int,
    afterPadding: Int,
): Float {
    val minimum = firstItemOffset?.coerceAtMost(0)?.toFloat() ?: Float.NEGATIVE_INFINITY
    val maximum = lastItemEnd?.let { (it + afterPadding - viewportEnd).coerceAtLeast(0).toFloat() }
        ?: Float.POSITIVE_INFINITY
    return requested.coerceIn(minimum, maximum)
}

internal val LocalFocusScrollPlacement = staticCompositionLocalOf<FocusScrollPlacement?> { null }

/** Keep visible cards in place; TV's pivot policy otherwise drags a new row sideways on entry. */
internal fun minimumCardScroll(offset: Float, size: Float, containerSize: Float): Float = when {
    containerSize <= 0f -> 0f
    offset < 0f -> offset
    offset + size > containerSize -> minOf(offset, offset + size - containerSize)
    else -> 0f
}

@OptIn(ExperimentalFoundationApi::class)
private object VisibleCardBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        minimumCardScroll(offset, size, containerSize)
}

/** Shares the same viewport and scrolling policy with the overlay's forecast. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusTrackingRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    verticalScrollOffset: State<Float>? = null,
    content: LazyListScope.() -> Unit,
) {
    val spec = VisibleCardBringIntoView
    val viewport = remember { FocusFrameGeometry() }
    val placement = remember(state, spec, verticalScrollOffset) {
        FocusScrollPlacement(state, spec, viewport, verticalScrollOffset)
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides spec,
        LocalFocusScrollPlacement provides placement,
    ) {
        LazyRow(
            state = state,
            modifier = modifier.onGloballyPositioned { viewport.bounds = it.focusFrameBounds() },
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}
