package dev.amatv.flyme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

internal data class WelcomeInkPoint(val x: Float, val y: Float, val pressure: Float, val ms: Float) {
    val widthScale: Float get() = min(1.25f, 0.55f + 0.75f * pressure)

    fun interpolate(to: WelcomeInkPoint, timeMs: Float): WelcomeInkPoint {
        val fraction = (timeMs - ms) / (to.ms - ms)
        return WelcomeInkPoint(
            x + (to.x - x) * fraction,
            y + (to.y - y) * fraction,
            pressure + (to.pressure - pressure) * fraction,
            timeMs,
        )
    }
}

internal fun parseWelcomeInk(bytes: ByteArray): List<List<WelcomeInkPoint>> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return List(buffer.short.toInt() and 0xffff) {
        List(buffer.short.toInt() and 0xffff) {
            WelcomeInkPoint(buffer.float, buffer.float, buffer.float, buffer.float)
        }
    }
}

internal data class WelcomeInkTip(val lastPointIndex: Int, val point: WelcomeInkPoint)

internal data class WelcomeInkFrame(
    val completedStrokes: Int,
    val tip: WelcomeInkTip? = null,
    val expansion: Float = 0f,
    val finished: Boolean = false,
)

internal class WelcomeInkTimeline(val strokes: List<List<WelcomeInkPoint>>) {
    private val startTimes = FloatArray(strokes.size)
    private val endTimes = FloatArray(strokes.size)

    init {
        var time = 0f
        strokes.forEachIndexed { index, stroke ->
            startTimes[index] = time
            time += stroke.last().ms
            endTimes[index] = time
            time += GAP_MS
        }
    }

    val writingMs: Float get() = endTimes.last() / SPEED
    val durationMs: Float get() = writingMs + HOLD_MS + EXPANSION_MS

    fun frameAt(elapsedMs: Float, skip: Boolean = false): WelcomeInkFrame {
        if (skip || elapsedMs >= durationMs) {
            return WelcomeInkFrame(strokes.size, expansion = 1f, finished = true)
        }
        val penTime = elapsedMs * SPEED
        val index = endTimes.indexOfFirst { it > penTime }
        if (index == -1) {
            val progress = ((elapsedMs - writingMs - HOLD_MS) / EXPANSION_MS).coerceAtLeast(0f)
            return WelcomeInkFrame(strokes.size, expansion = progress * progress * (3f - 2f * progress))
        }
        if (penTime < startTimes[index]) return WelcomeInkFrame(index)

        val stroke = strokes[index]
        val localTime = penTime - startTimes[index]
        val next = stroke.binarySearchBy(localTime) { it.ms }
        val tip = if (next >= 0) {
            WelcomeInkTip(next, stroke[next])
        } else {
            val previous = -next - 2
            WelcomeInkTip(previous, stroke[previous].interpolate(stroke[previous + 1], localTime))
        }
        return WelcomeInkFrame(index, tip)
    }

    private companion object {
        const val SPEED = 1.6f
        const val GAP_MS = 140f
        const val HOLD_MS = 500f
        const val EXPANSION_MS = 1200f
    }
}
