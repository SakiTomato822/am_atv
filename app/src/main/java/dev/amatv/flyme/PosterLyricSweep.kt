package dev.amatv.flyme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming

internal data class PosterSweepUnit(
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Text offsets follow renderLyricWords, including the exact whitespace from TTML. */
internal fun posterSweepUnits(line: LyricLine): List<PosterSweepUnit> {
    if (line.timing != LyricTiming.Word) return emptyList()
    val words = line.words.filterNot { it.isBackgroundVocal }
    val text = StringBuilder()
    val units = mutableListOf<PosterSweepUnit>()
    for ((index, word) in words.withIndex()) {
        val start = text.length
        text.append(word.displayText)
        val end = text.length
        val previous = words.getOrNull(index - 1)
        if (word.emphasisGroupId != null && previous?.emphasisGroupId == word.emphasisGroupId) {
            val unit = units.last()
            units[units.lastIndex] = unit.copy(endOffset = end, endMs = maxOf(unit.endMs, word.endMs))
        } else {
            units += PosterSweepUnit(start, end, word.startMs, word.endMs)
        }
        if (word.displayText == word.text && line.joinWithSpaces && word.hasTrailingWhitespace &&
            index < words.lastIndex
        ) text.append(' ')
    }
    val trimStart = text.indexOfFirst { !it.isWhitespace() }
    if (trimStart < 0) return emptyList()
    return units.mapNotNull { unit ->
        val display = text.substring(unit.startOffset, unit.endOffset)
        val start = display.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return@mapNotNull null
        unit.copy(
            startOffset = unit.startOffset + start - trimStart,
            endOffset = unit.startOffset + display.indexOfLast { !it.isWhitespace() } + 1 - trimStart,
        )
    }
}

internal data class PosterSweepSpan(val timing: PosterSweepUnit, val startPx: Float, val endPx: Float)

/** A single axis through all the visual rows of a lyric, in their reading order. */
internal class PosterSweepAxis(startMs: Long, endMs: Long, private val width: Float, spans: List<PosterSweepSpan>) {
    private data class Point(val timeMs: Long, val px: Float)

    private val points = buildList {
        add(Point(startMs, 0f))
        fun anchor(timeMs: Long, px: Float) {
            // Coincident boundaries (e.g. adjacent Hanzi or a wrap) share the neighboring slope.
            if (timeMs > last().timeMs && timeMs < endMs && px > last().px && px < width) {
                add(Point(timeMs, px))
            }
        }
        for ((index, span) in spans.withIndex()) {
            val timing = span.timing
            val nextStart = spans.getOrNull(index + 1)?.timing?.startMs ?: endMs
            anchor(timing.startMs, span.startPx)
            anchor(minOf(timing.endMs, nextStart), span.endPx)
        }
        add(Point(endMs, width))
    }

    fun positionAt(positionMs: Long): Float {
        if (positionMs >= points.last().timeMs) return width
        if (positionMs <= points.first().timeMs) return 0f
        val index = points.binarySearchBy(positionMs) { it.timeMs }
        if (index >= 0) return points[index].px
        val next = -index - 1
        val start = points[next - 1]
        val end = points[next]
        val fraction = (positionMs - start.timeMs).toFloat() / (end.timeMs - start.timeMs)
        return start.px + (end.px - start.px) * fraction
    }
}

/** Reserve room for the feather to clear the row before the next row starts. */
internal fun posterSweepRamp(positionPx: Float, rowWidth: Float, featherPx: Float): KaraokeMaskRamp {
    val edge = positionPx / rowWidth * (rowWidth + featherPx)
    return KaraokeMaskRamp(edge - featherPx, edge)
}

internal class PosterLyricSweep(
    private val layout: TextLayoutResult,
    line: LyricLine,
    private val featherPx: Float,
) {
    private data class Row(
        val left: Float,
        val top: Float,
        val bottom: Float,
        val width: Float,
        val startPx: Float,
    ) {
        val endPx: Float get() = startPx + width
    }

    private val rows = buildList<Row> {
        for (index in 0 until layout.lineCount) {
            val left = layout.getLineLeft(index)
            add(Row(
                left, layout.getLineTop(index), layout.getLineBottom(index),
                layout.getLineRight(index) - left, lastOrNull()?.endPx ?: 0f,
            ))
        }
    }
    private val width = rows.last().endPx
    private val axis: PosterSweepAxis

    init {
        val units = posterSweepUnits(line)
        val lastRow = rows.lastIndex
        val visibleEnd = layout.getLineEnd(lastRow, visibleEnd = layout.isLineEllipsized(lastRow))
        val spans = units.mapNotNull { unit ->
            var start = Float.POSITIVE_INFINITY
            var end = 0f
            for (offset in unit.startOffset until minOf(unit.endOffset, visibleEnd)) {
                val row = rows[layout.getLineForOffset(offset)]
                val box = layout.getBoundingBox(offset)
                if (box.width > 0f) {
                    start = minOf(start, row.startPx + (box.left - row.left).coerceIn(0f, row.width))
                    end = maxOf(end, row.startPx + (box.right - row.left).coerceIn(0f, row.width))
                }
            }
            if (end > start) PosterSweepSpan(unit, start, end) else null
        }
        axis = PosterSweepAxis(units.firstOrNull()?.startMs ?: line.startMs, posterLeadEndMs(line), width, spans)
    }

    fun draw(scope: DrawScope, positionMs: Long, color: Color) = with(scope) {
        val positionPx = axis.positionAt(positionMs)
        if (positionPx >= width) {
            drawText(layout, color = color)
            return@with
        }
        drawText(layout, color = color.copy(alpha = color.alpha * 0.40f))
        if (positionPx <= 0f) return@with

        val row = rows.first { positionPx < it.endPx }
        if (row.startPx > 0f) {
            clipRect(bottom = row.top) { drawText(layout, color = color) }
        }
        val ramp = posterSweepRamp(positionPx - row.startPx, row.width, featherPx)
        val brush = Brush.horizontalGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            startX = row.left + ramp.start,
            endX = row.left + ramp.end,
        )
        clipRect(top = row.top, bottom = row.bottom) { drawText(layout, brush = brush) }
    }
}
