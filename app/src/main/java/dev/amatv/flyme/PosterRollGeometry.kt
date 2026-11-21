package dev.amatv.flyme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.util.lerp

private val posterRollEasing = CubicBezierEasing(0.12f, 0.85f, 0.12f, 1f)

internal fun posterRollMovement(time: Float): Float = posterRollEasing.transform(time)

internal data class PosterRollBounds(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top

    fun interpolate(end: PosterRollBounds, progress: Float) =
        PosterRollBounds(lerp(top, end.top, progress), lerp(bottom, end.bottom, progress))
}

internal fun posterPackedPartBounds(
    top: Float,
    heights: List<Float>,
    scale: Float,
    gap: Float,
): List<PosterRollBounds> {
    var cursor = top
    return heights.map { height ->
        val part = PosterRollBounds(cursor, cursor + height * scale)
        cursor = part.bottom + gap * scale
        part
    }
}

/** Completes one end of the roll in lyric order, including the rows that are hidden at this end. */
internal fun posterRollBounds(
    orders: List<Int>,
    visible: Map<Int, PosterRollBounds>,
    other: Map<Int, PosterRollBounds>,
    panelHeight: Float,
    gap: Float,
): Map<Int, PosterRollBounds> {
    val first = orders.indexOfFirst { it in visible }
    val last = orders.indexOfLast { it in visible }
    return buildMap {
        if (first >= 0) {
            var cursor = minOf(0f, visible.getValue(orders[first]).top - gap)
            for (index in first - 1 downTo 0) {
                val order = orders[index]
                val top = cursor - other.getValue(order).height
                put(order, PosterRollBounds(top, cursor))
                cursor = top - gap
            }
            cursor = visible.getValue(orders[first]).top
            for (index in first..last) {
                val order = orders[index]
                val bounds = visible[order] ?: PosterRollBounds(cursor, cursor)
                put(order, bounds)
                cursor = bounds.bottom
            }
        }
        val lastBottom = if (last >= 0) visible.getValue(orders[last]).bottom + gap else panelHeight
        var cursor = maxOf(panelHeight, lastBottom)
        for (index in last + 1 until orders.size) {
            val order = orders[index]
            val bottom = cursor + other.getValue(order).height
            put(order, PosterRollBounds(cursor, bottom))
            cursor = bottom + gap
        }
    }
}

internal data class PosterInkPose(val top: Float, val scale: Float, val opacity: Float) {
    /** Maps the entire layout into the other slot, preserving spacing within the block. */
    fun hiddenIn(home: PosterRollBounds, away: PosterRollBounds, width: Float, panelWidth: Float): PosterInkPose {
        val fit = minOf(away.height / home.height, panelWidth / (width * scale))
        return PosterInkPose(away.top + (top - home.top) * fit, scale * fit, 0f)
    }
}

internal enum class PosterInkFade {
    RoleChange,
    IncomingFocus,
    OutgoingFocus,
    ReplacedPreview,
}

internal data class PosterInkTrack(
    val start: PosterInkPose,
    val end: PosterInkPose,
    val fadeStyle: PosterInkFade = PosterInkFade.RoleChange,
) {
    fun poseAt(time: Float, movement: Float): PosterInkPose {
        fun window(from: Float, to: Float): Float = ((time - from) / (to - from)).coerceIn(0f, 1f)
        val fade = when {
            fadeStyle == PosterInkFade.IncomingFocus -> {
                val remaining = 1f - window(0f, 0.20f)
                1f - remaining * remaining
            }
            fadeStyle == PosterInkFade.OutgoingFocus -> {
                val elapsed = window(0.11f, 0.50f)
                elapsed * elapsed
            }
            fadeStyle == PosterInkFade.ReplacedPreview -> window(0f, 0.15f)
            end.opacity == 0f -> window(0f, 0.45f)
            start.opacity == 0f -> window(0.25f, 1f)
            else -> time
        }
        return PosterInkPose(
            lerp(start.top, end.top, movement),
            lerp(start.scale, end.scale, movement),
            lerp(start.opacity, end.opacity, fade),
        )
    }
}

internal fun posterEdgeOpacity(centerY: Float, panelHeight: Float): Float =
    (minOf(centerY, panelHeight - centerY) / (panelHeight * 0.12f)).coerceIn(0f, 1f)
