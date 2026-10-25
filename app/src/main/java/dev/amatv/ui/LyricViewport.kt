package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import kotlin.math.abs
import kotlin.math.ceil

/** Only the current viewport and its outgoing scroll context need word trees. */
internal fun lyricViewportMountOrder(
    tops: FloatArray,
    heights: FloatArray,
    viewportHeight: Float,
    fromScroll: Float,
    toScroll: Float,
    anchorIndex: Int,
    priorityIndices: List<Int> = emptyList(),
    overscan: Float = 120f,
    maxRows: Int = 18,
): List<Int> {
    val count = minOf(tops.size, heights.size)
    if (count == 0 || maxRows <= 0) return emptyList()
    val anchor = anchorIndex.coerceIn(0, count - 1)
    fun inView(index: Int, scroll: Float): Boolean =
        tops[index] + heights[index] >= scroll - overscan &&
            tops[index] <= scroll + viewportHeight + overscan
    val priority = (listOf(anchor) + priorityIndices.filter { it in 0 until count }).distinct()
    return (0 until count).filter {
        it in priority || inView(it, toScroll) || inView(it, fromScroll)
    }.sortedWith(compareBy<Int> {
        when {
            it in priority -> 0
            inView(it, toScroll) -> 1
            else -> 2
        }
    }.thenBy { if (it == anchor) -1 else abs(it - anchor) }.thenBy { it })
        .take(maxRows)
}

/** Restarts/retargets in the same display frame cannot grant a second heavy row. */
internal class LyricRowMountBudget {
    private var lastFrame = Long.MIN_VALUE
    fun next(order: List<Int>, mounted: Set<Int>, frameNanos: Long): Int? {
        if (lastFrame == frameNanos) return null
        val next = order.firstOrNull { it !in mounted } ?: return null
        lastFrame = frameNanos
        return next
    }
}

internal fun lyricAnchorHeightCorrection(previousTops: FloatArray, newTops: FloatArray, anchor: Int): Float =
    if (anchor in previousTops.indices && anchor in newTops.indices) newTops[anchor] - previousTops[anchor]
    else 0f

/**
 * Places an opening lyric viewport on its current singer before its first
 * frame. Initial restoration is not a lyric hand-off and must not be shown as
 * a scroll from the first line of the song.
 */
internal fun initialLyricScroll(
    tops: FloatArray,
    anchorIndex: Int,
    focalAnchorY: Float,
    maxScroll: Float,
): Float {
    if (anchorIndex !in tops.indices) return 0f
    return (tops[anchorIndex] - focalAnchorY).coerceIn(0f, maxScroll.coerceAtLeast(0f))
}

/** Estimates are never painted; mounted rows replace them with exact Compose measurements. */
internal fun estimateLyricRowHeight(line: LyricLine, widthPx: Float, density: Float, fontScale: Float): Float {
    val sp = density * fontScale
    fun textRows(text: String, sizeSp: Float, available: Float): Int {
        var width = 0f
        for (character in text) width += sizeSp * sp * when {
            character.isWhitespace() -> 0.3f
            character.code >= 0x2e80 -> 1f
            else -> 0.58f
        }
        return ceil(width / available.coerceAtLeast(sizeSp * sp)).toInt().coerceAtLeast(1)
    }
    if (line.isStatic || line.timing == LyricTiming.None) {
        return 24f * density + textRows(line.text, 24f, widthPx - 136f * density) * 32f * sp
    }
    val contentWidth = ((widthPx - 120f * density) * if (line.isMultiAgentSong) 0.85f else 1f)
        .coerceAtLeast(1f)
    val mainRows = textRows(line.text, 34f, contentWidth - 12f * density)
    val pronunciation = if (!line.pronunciationText.isNullOrBlank() || line.words.any { !it.pronunciationText.isNullOrBlank() }) {
        22f * sp + 4f * density
    } else 0f
    var height = 22f * density + mainRows * (41f * sp + pronunciation) + (mainRows - 1) * 4f * density
    if (!line.translation.isNullOrBlank()) height += 4f * density + textRows(line.translation, 24f, contentWidth) * 29f * sp
    if (line.backgroundWords.isNotEmpty()) {
        val rows = textRows(line.backgroundText, 24f, contentWidth - 12f * density)
        val pronunciationHeight = if (!line.backgroundPronunciationText.isNullOrBlank() ||
            line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }) 20f * sp + 4f * density else 0f
        height += 4f * density + rows * (29f * sp + pronunciationHeight) + (rows - 1) * 4f * density
    }
    if (!line.backgroundTranslation.isNullOrBlank()) height += 4f * density + textRows(line.backgroundTranslation, 19f, contentWidth) * 25f * sp
    return height
}
