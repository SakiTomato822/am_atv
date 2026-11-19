package dev.amatv.flyme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import java.text.BreakIterator
import java.util.Locale

internal data class PosterSweepUnit(
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val endMs: Long,
) {
    fun progressAt(positionMs: Long): Float = when {
        positionMs >= endMs -> 1f
        positionMs <= startMs -> 0f
        else -> (positionMs - startMs).toFloat() / (endMs - startMs)
    }
}

/** Text offsets follow renderLyricWords, including the exact whitespace from TTML. */
internal fun posterSweepUnits(line: LyricLine): List<PosterSweepUnit> {
    if (line.timing != LyricTiming.Word) return emptyList()
    val words = line.words.filterNot { it.isBackgroundVocal }
    val text = StringBuilder()
    val wordUnits = mutableListOf<PosterSweepUnit>()
    for ((index, word) in words.withIndex()) {
        val start = text.length
        text.append(word.displayText)
        val end = text.length
        val previous = words.getOrNull(index - 1)
        if (word.emphasisGroupId != null && previous?.emphasisGroupId == word.emphasisGroupId) {
            val unit = wordUnits.last()
            wordUnits[wordUnits.lastIndex] = unit.copy(endOffset = end, endMs = maxOf(unit.endMs, word.endMs))
        } else {
            wordUnits += PosterSweepUnit(start, end, word.startMs, word.endMs)
        }
        if (word.displayText == word.text && line.joinWithSpaces && word.hasTrailingWhitespace &&
            index < words.lastIndex
        ) text.append(' ')
    }
    val trimStart = text.indexOfFirst { !it.isWhitespace() }
    if (trimStart < 0) return emptyList()
    return buildList {
        for (unit in wordUnits) {
            val display = text.substring(unit.startOffset, unit.endOffset)
            val parts = posterSweepParts(display)
            for ((index, part) in parts.withIndex()) {
                val duration = unit.endMs - unit.startMs
                add(
                    PosterSweepUnit(
                        startOffset = unit.startOffset + part.first - trimStart,
                        endOffset = unit.startOffset + part.last + 1 - trimStart,
                        startMs = unit.startMs + duration * index / parts.size,
                        endMs = unit.startMs + duration * (index + 1) / parts.size,
                    ),
                )
            }
        }
    }
}

private fun posterSweepParts(text: String): List<IntRange> {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return emptyList()
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    if (!containsPosterSplitScript(text)) return listOf(start until end)
    return buildList {
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
        graphemes.setText(text)
        var wordStart = -1
        var left = graphemes.first()
        var right = graphemes.next()
        while (right != BreakIterator.DONE) {
            val part = text.substring(left, right)
            val cjk = containsPosterSplitScript(part)
            if (cjk || part.isBlank()) {
                if (wordStart >= 0) add(wordStart until left)
                wordStart = -1
                if (cjk) add(left until right)
            } else if (wordStart < 0) {
                wordStart = left
            }
            left = right
            right = graphemes.next()
        }
        if (wordStart >= 0) add(wordStart until end)
    }
}

private fun containsPosterSplitScript(text: String): Boolean {
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val block = Character.UnicodeBlock.of(codePoint)
        if (block != null && block in LyricTimeline.splitOnCharsBlocks) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

/** The full feather passes over the last glyph before the word's interval ends. */
internal fun posterSweepRamp(progress: Float, feather: Float, rtl: Boolean): KaraokeMaskRamp {
    val edge = progress * (1f + feather)
    // Normalize over the padded interval so the ramp can enter and leave the glyph bounds.
    val span = 1f + 2f * feather
    val anchor = (if (rtl) 1f - edge else edge) + feather
    val ramp = karaokeMaskRamp(anchor / span, feather / span, rtl)
    return KaraokeMaskRamp(ramp.start * span - feather, ramp.end * span - feather)
}

internal class PosterLyricSweep(
    private val layout: TextLayoutResult,
    timing: List<PosterSweepUnit>,
) {
    private data class Fragment(val bounds: Rect, val rtl: Boolean)
    private data class Word(val timing: PosterSweepUnit, val fragments: List<Fragment>, val width: Float)

    private val lastLayoutRow = layout.lineCount - 1
    private val visibleEnd = layout.getLineEnd(lastLayoutRow, visibleEnd = layout.isLineEllipsized(lastLayoutRow))
    private val words = timing.map { unit ->
        val fragments = buildList<Fragment> {
            var lastRow = -1
            for (offset in unit.startOffset until minOf(unit.endOffset, visibleEnd)) {
                val bounds = layout.getBoundingBox(offset)
                if (bounds.width <= 0f) continue
                val row = layout.getLineForOffset(offset)
                val rtl = layout.getBidiRunDirection(offset) == ResolvedTextDirection.Rtl
                val previous = lastOrNull()
                if (previous != null && lastRow == row && previous.rtl == rtl) {
                    this[lastIndex] = Fragment(
                        Rect(
                            minOf(previous.bounds.left, bounds.left), minOf(previous.bounds.top, bounds.top),
                            maxOf(previous.bounds.right, bounds.right), maxOf(previous.bounds.bottom, bounds.bottom),
                        ),
                        rtl,
                    )
                } else {
                    add(Fragment(bounds, rtl))
                }
                lastRow = row
            }
        }
        Word(unit, fragments, fragments.sumOf { it.bounds.width.toDouble() }.toFloat())
    }
    private val endMs = timing.maxOfOrNull { it.endMs } ?: 0L
    private val completed = Path()

    fun draw(scope: DrawScope, positionMs: Long, color: Color) = with(scope) {
        if (positionMs >= endMs) {
            drawText(layout, color = color)
            return@with
        }
        drawText(layout, color = color.copy(alpha = 0.40f))
        completed.rewind()
        for (word in words) {
            val progress = word.timing.progressAt(positionMs)
            if (progress == 0f) continue
            val featherPx = minOf(12.dp.toPx(), word.width * 0.28f)
            var litPx = progress * (word.width + featherPx)
            for ((bounds, rtl) in word.fragments) {
                val width = bounds.width
                when {
                    litPx >= width + featherPx -> completed.addRect(bounds)
                    litPx > 0f -> {
                        val feather = featherPx / width
                        val ramp = posterSweepRamp(litPx / (width + featherPx), feather, rtl)
                        val brush = Brush.horizontalGradient(
                            colors = if (rtl) listOf(color.copy(alpha = 0f), color)
                            else listOf(color, color.copy(alpha = 0f)),
                            startX = bounds.left + ramp.start * width,
                            endX = bounds.left + ramp.end * width,
                        )
                        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                            drawText(layout, brush = brush)
                        }
                    }
                }
                litPx -= width
            }
        }
        if (!completed.isEmpty) {
            clipPath(completed) { drawText(layout, color = color) }
        }
    }
}
