package dev.amatv.flyme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricWord
import dev.amatv.model.renderLyricWords

internal data class PosterSweepUnit(val startOffset: Int, val endOffset: Int, val word: LyricWord)

/** Offsets refer to the same trimmed lyric string used to measure the poster. */
internal fun posterSweepUnits(line: LyricLine): List<PosterSweepUnit> {
    val words = line.words.filterNot { it.isBackgroundVocal }
    val trimStart = renderLyricWords(words, line.joinWithSpaces).takeWhile { it.isWhitespace() }.length
    var offset = -trimStart
    return words.mapIndexedNotNull { index, word ->
        val display = word.displayText
        val start = offset
        offset += display.length
        if (display == word.text && line.joinWithSpaces && word.hasTrailingWhitespace &&
            index < words.lastIndex
        ) offset++

        val first = display.indexOfFirst { !it.isWhitespace() }
        if (first < 0) null else PosterSweepUnit(
            start + first,
            start + display.indexOfLast { !it.isWhitespace() } + 1,
            word,
        )
    }
}

internal fun posterSweepWordsOnRow(
    units: List<PosterSweepUnit>,
    rowStart: Int,
    rowEnd: Int,
    bounds: (Int) -> Rect,
): List<KaraokeSweepWord> = units.mapNotNull { unit ->
    val start = maxOf(rowStart, unit.startOffset)
    val end = minOf(rowEnd, unit.endOffset)
    if (start >= end) return@mapNotNull null

    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    for (offset in start until end) {
        val box = bounds(offset)
        if (box.width <= 0f) continue
        left = minOf(left, box.left)
        right = maxOf(right, box.right)
    }
    if (left >= right) return@mapNotNull null

    val word = unit.word
    if (start == unit.startOffset && end == unit.endOffset) {
        return@mapNotNull KaraokeSweepWord(word, left, right)
    }
    val length = unit.endOffset - unit.startOffset
    val startMs = word.startMs + word.durationMs * (start - unit.startOffset) / length
    val endMs = word.startMs + word.durationMs * (end - unit.startOffset) / length
    KaraokeSweepWord(word.copy(startMs = startMs, endMs = endMs), left, right)
}

internal class PosterSweepRow(
    val words: List<KaraokeSweepWord>,
    width: Float,
    featherPx: Float,
    val isRtl: Boolean,
    private val nextRowStartMs: Long?,
) {
    // Give the full feather room beyond either edge of the measured text.
    private val sweep = if (words.isEmpty()) null else KaraokeRowSweep(
        words.map { it.copy(left = it.left + featherPx, right = it.right + featherPx) },
        width + 2f * featherPx, featherPx, isRtl,
    )
    private val padding = featherPx

    fun highlightAt(positionMs: Long): KaraokeRowHighlight {
        if (nextRowStartMs != null &&
            positionMs - LyricTimeline.KARAOKE_WORD_OFFSET_MS >= nextRowStartMs
        ) return KaraokeRowHighlight.Lit
        return when (val highlight = sweep?.highlightAt(positionMs)) {
            null -> KaraokeRowHighlight.Unlit
            is KaraokeRowHighlight.Partial -> KaraokeRowHighlight.Partial(
                KaraokeMaskRamp(highlight.ramp.start - padding, highlight.ramp.end - padding),
            )
            else -> highlight
        }
    }
}

internal class PosterLyricSweep(
    private val layout: TextLayoutResult,
    line: LyricLine,
) {
    private data class Row(val top: Float, val bottom: Float, val sweep: PosterSweepRow)

    private val endMs = posterLeadEndMs(line)
    private val featherPx = with(layout.layoutInput.density) {
        MotionTokens.KaraokeGradientFeatherDp.dp.toPx() *
            (layout.layoutInput.style.fontSize.value / MotionTokens.KaraokeMainFontSizeSp)
    }
    private val rows = run {
        val units = posterSweepUnits(line)
        val wordsByRow = List(layout.lineCount) { index ->
            posterSweepWordsOnRow(
                units, layout.getLineStart(index), layout.getLineEnd(index, visibleEnd = true),
                layout::getBoundingBox,
            )
        }
        var nextRowStartMs: Long? = null
        wordsByRow.indices.reversed().map { index ->
            val words = wordsByRow[index]
            val sweep = PosterSweepRow(
                words, layout.size.width.toFloat(), featherPx,
                layout.getParagraphDirection(layout.getLineStart(index)) == ResolvedTextDirection.Rtl,
                nextRowStartMs,
            )
            nextRowStartMs = words.firstOrNull()?.word?.startMs ?: nextRowStartMs
            Row(layout.getLineTop(index), layout.getLineBottom(index), sweep)
        }.asReversed()
    }
    private val emphasisClip = Path()

    fun draw(scope: DrawScope, positionMs: Long, ink: Color) = with(scope) {
        val time = positionMs - LyricTimeline.KARAOKE_WORD_OFFSET_MS
        if (time >= endMs + MotionTokens.KaraokeRushGradientMillis) {
            drawText(layout, color = ink)
            return@with
        }

        for (row in rows) {
            val highlight = row.sweep.highlightAt(positionMs)
            val word = row.sweep.words.lastOrNull { it.word.startMs <= time }
            val lift = if (word == null || time >= word.word.endMs + WORD_RELEASE_MS) 0f else {
                val release = ((word.word.endMs + WORD_RELEASE_MS - time).toFloat() / WORD_RELEASE_MS)
                    .coerceIn(0f, 1f)
                LyricTimeline.wordLiftFraction(
                    time, word.word.startMs, word.word.endMs, release,
                    MotionTokens.KaraokeSpringDampingRatio, MotionTokens.KaraokeSpringStiffness,
                )
            }
            clipRect(top = row.top, bottom = row.bottom) {
                if (lift > 0f && word != null) {
                    emphasisClip.reset()
                    emphasisClip.addRect(Rect(word.left, row.top, word.right, row.bottom))
                    clipPath(emphasisClip, ClipOp.Difference) { drawRow(highlight, row.sweep.isRtl, ink) }
                } else {
                    drawRow(highlight, row.sweep.isRtl, ink)
                }
            }
            if (lift > 0f && word != null) {
                translate(top = MotionTokens.KaraokeWordLiftDp.dp.toPx() * lift) {
                    clipPath(emphasisClip) { drawRow(highlight, row.sweep.isRtl, ink, lift) }
                }
            }
        }
    }

    private fun DrawScope.drawRow(
        highlight: KaraokeRowHighlight,
        isRtl: Boolean,
        ink: Color,
        emphasis: Float = 0f,
    ) {
        val lit = ink.copy(alpha = ink.alpha + (1f - ink.alpha) * emphasis * 0.12f)
        val unlit = ink.copy(alpha = ink.alpha * (0.40f + 0.10f * emphasis))
        when (highlight) {
            KaraokeRowHighlight.Unlit -> drawText(layout, color = unlit)
            KaraokeRowHighlight.Lit -> drawText(layout, color = lit)
            is KaraokeRowHighlight.Partial -> {
                val (start, end) = highlight.ramp
                val first = if (isRtl) unlit else lit
                val last = if (isRtl) lit else unlit
                if (start == end) {
                    drawText(layout, color = if (start <= 0f) last else first)
                } else {
                    drawText(layout, brush = Brush.horizontalGradient(
                        colors = listOf(first, last), startX = start, endX = end,
                    ))
                }
            }
        }
    }

    private companion object {
        const val WORD_RELEASE_MS = 100L
    }
}
