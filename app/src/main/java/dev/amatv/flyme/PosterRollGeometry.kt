package dev.amatv.flyme

import androidx.compose.ui.util.lerp

internal data class PosterRollBounds(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top

    fun interpolate(end: PosterRollBounds, progress: Float) =
        PosterRollBounds(lerp(top, end.top, progress), lerp(bottom, end.bottom, progress))
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

    fun interpolate(end: PosterInkPose, time: Float, movement: Float): PosterInkPose {
        val fade = when {
            end.opacity == 0f -> (time / 0.45f).coerceIn(0f, 1f)
            opacity == 0f -> ((time - 0.25f) / 0.75f).coerceIn(0f, 1f)
            else -> time
        }
        return PosterInkPose(lerp(top, end.top, movement), lerp(scale, end.scale, movement), lerp(opacity, end.opacity, fade))
    }
}

internal fun posterEdgeOpacity(centerY: Float, panelHeight: Float): Float =
    (minOf(centerY, panelHeight - centerY) / (panelHeight * 0.12f)).coerceIn(0f, 1f)
