package dev.amatv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class FocusFrameTarget(
    val key: Any,
    val bounds: Rect,
    val radiusPx: Float,
    val visibility: State<Float>? = null,
    val transform: FocusFrameTransform? = null,
    val scrollPlacement: FocusScrollPlacement? = null,
) {
    fun destinationBounds(): Rect = scrollPlacement?.destination(bounds, transform?.focusedScale ?: 1f) ?: bounds
}

internal class FocusFrameTransform(
    val scale: State<Float>,
    val liftProgress: State<Float>,
    val liftPx: Float,
    val focusedScale: Float,
)

internal fun visualBounds(bounds: Rect, scale: Float, liftPx: Float = 0f): Rect {
    val extraX = bounds.width * (scale - 1f) / 2f
    val extraY = bounds.height * (scale - 1f) / 2f
    return Rect(
        bounds.left - extraX,
        bounds.top - extraY - liftPx,
        bounds.right + extraX,
        bounds.bottom + extraY - liftPx,
    )
}

/** Lazy viewport clipping must not turn a full card into a partial focus target. */
internal fun LayoutCoordinates.focusFrameBounds(): Rect = Rect(
    positionInRoot(),
    Size(size.width.toFloat(), size.height.toFloat()),
)

internal class FocusFrameController {
    var target by mutableStateOf<FocusFrameTarget?>(null)
        private set

    fun report(
        key: Any, bounds: Rect, radiusPx: Float,
        visibility: State<Float>? = null, transform: FocusFrameTransform? = null,
        scrollPlacement: FocusScrollPlacement? = null,
    ) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val current = target
        if (current?.key == key && current.visibility === visibility && current.transform === transform &&
            current.scrollPlacement === scrollPlacement &&
            abs(current.bounds.left - bounds.left) < 0.75f &&
            abs(current.bounds.top - bounds.top) < 0.75f &&
            abs(current.bounds.width - bounds.width) < 0.75f &&
            abs(current.bounds.height - bounds.height) < 0.75f &&
            abs(current.radiusPx - radiusPx) < 0.75f
        ) return
        val next = FocusFrameTarget(key, bounds, radiusPx, visibility, transform, scrollPlacement)
        target = next
    }

    fun clear(key: Any) {
        if (target?.key == key) target = null
    }
}

internal val LocalFocusFrameController = staticCompositionLocalOf<FocusFrameController?> { null }
internal val LocalFocusFrameEnabled = staticCompositionLocalOf { true }
internal val LocalFocusFrameVisibility = staticCompositionLocalOf<State<Float>?> { null }

/** Drawn after both browse and player content, outside every lazy viewport. */
@Composable
internal fun FocusFrameOverlay(controller: FocusFrameController) {
    val motion = remember { FocusFrameMotion() }
    val frame = remember { mutableLongStateOf(0L) }
    val retained = remember { arrayOfNulls<FocusFrameTarget>(1) }
    val sampledBounds = remember { arrayOfNulls<Rect>(1) }
    LaunchedEffect(controller) {
        var observed: FocusFrameTarget? = null
        var previousNanos = 0L
        while (isActive) {
            if (!motion.isMoving && controller.target == observed) {
                snapshotFlow { controller.target }.first { it != observed }
                previousNanos = 0L
            }
            withFrameNanos { now ->
                val target = controller.target
                observed = target
                val destination = target ?: retained[0]
                if (destination != null) {
                    val rect = destination.destinationBounds()
                    motion.advance(
                        deltaNanos = if (previousNanos == 0L) 0L else now - previousNanos,
                        left = rect.left, top = rect.top,
                        width = rect.width, height = rect.height, radius = destination.radiusPx,
                        visible = target != null,
                        followImmediately = target?.visibility?.value?.let { it < 0.999f } ?: false,
                        key = destination.key,
                    )
                    retained[0] = destination
                    sampledBounds[0] = rect
                }
                previousNanos = now
                frame.longValue = now
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        frame.longValue // Frame changes invalidate drawing only.
        val sampled = retained[0] ?: return@Canvas
        val sampledRect = sampledBounds[0] ?: return@Canvas
        // Placement can run after the frame-clock callback. Use the newest
        // anchor in this very draw, without waiting one more display frame.
        val target = controller.target?.takeIf { it.key == sampled.key } ?: sampled
        val destination = target.destinationBounds()
        val dx = destination.left - sampledRect.left
        val dy = destination.top - sampledRect.top
        val width = motion.width + destination.width - sampledRect.width
        val height = motion.height + destination.height - sampledRect.height
        val radius = motion.radius + target.radiusPx - sampled.radiusPx
        val opacity = motion.opacity * (target.visibility?.value ?: 1f)
        if (opacity < 0.01f) return@Canvas
        val transform = target.transform
        val scale = transform?.scale?.value ?: 1f
        val lift = (transform?.liftPx ?: 0f) * (transform?.liftProgress?.value ?: 0f)
        val inset = 4.dp.toPx()
        val x = motion.left + dx - width * (scale - 1f) / 2f - inset
        val y = motion.top + dy - height * (scale - 1f) / 2f - lift - inset
        val w = (width * scale + inset * 2f).coerceAtLeast(0f)
        val h = (height * scale + inset * 2f).coerceAtLeast(0f)
        val r = (radius * scale + inset).coerceAtLeast(0f)
        val corner = CornerRadius(r)
        val origin = Offset(x, y)
        val frameSize = Size(w, h)
        // Uneven illumination is the characteristic glass edge: white near the
        // light source, cool and quiet at the bottom right.
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.96f * opacity),
                    Color(0xFFE0F4FF).copy(alpha = 0.66f * opacity),
                    Color.White.copy(alpha = 0.23f * opacity),
                    Color.White.copy(alpha = 0.12f * opacity),
                ),
                start = Offset(x, y),
                end = Offset(x + w, y + h),
            ),
            topLeft = origin,
            size = frameSize,
            cornerRadius = corner,
            style = Stroke(width = 2.5.dp.toPx()),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.37f * opacity), Color.Transparent),
                startY = y + 1.dp.toPx(),
                endY = y + h * 0.65f,
            ),
            topLeft = Offset(x + 2.dp.toPx(), y + 2.dp.toPx()),
            size = Size((w - 4.dp.toPx()).coerceAtLeast(0f), (h - 4.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - 2.dp.toPx()).coerceAtLeast(0f)),
            style = Stroke(width = 0.7.dp.toPx()),
        )
    }
}

/** For controls whose selection is managed by the player rather than Compose focus. */
internal fun Modifier.focusFrameTarget(
    selected: Boolean,
    key: Any,
    cornerRadius: Dp,
    visualScale: Float = 1f,
): Modifier = composed {
    val controller = LocalFocusFrameController.current
    val enabled = LocalFocusFrameEnabled.current
    val visibility = LocalFocusFrameVisibility.current
    val scrollPlacement = LocalFocusScrollPlacement.current
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    val holder = remember { FocusFrameGeometry() }
    SideEffect {
        if (selected && enabled) holder.bounds?.let {
            controller?.report(
                key, visualBounds(it, visualScale), radiusPx * visualScale, visibility,
                scrollPlacement = scrollPlacement,
            )
        }
        else controller?.clear(key)
    }
    DisposableEffect(controller, key) {
        onDispose { controller?.clear(key) }
    }
    this.onGloballyPositioned { coordinates ->
        holder.bounds = coordinates.focusFrameBounds()
        if (selected && enabled) controller?.report(
            key, visualBounds(holder.bounds!!, visualScale), radiusPx * visualScale, visibility,
            scrollPlacement = scrollPlacement,
        )
    }
}

internal class FocusFrameGeometry(var bounds: Rect? = null)
