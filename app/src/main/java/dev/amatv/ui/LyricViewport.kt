package dev.amatv.ui

import dev.amatv.BuildConfig
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import kotlin.math.abs
import kotlin.math.ceil

/** Applies display preferences without changing timing, singer or background-vocal structure. */
internal fun LyricLine.forDisplay(
    showTranslation: Boolean,
    showPronunciation: Boolean,
): LyricLine {
    if (showTranslation && showPronunciation) return this
    fun filteredWords(source: List<dev.amatv.model.LyricWord>): List<dev.amatv.model.LyricWord> {
        if (showPronunciation || source.none {
                !it.pronunciationText.isNullOrBlank() || it.pronunciationEndMs != null
            }
        ) return source
        return source.map { it.copy(pronunciationText = null, pronunciationEndMs = null) }
    }
    return copy(
        words = filteredWords(words),
        backgroundWords = filteredWords(backgroundWords),
        translation = translation.takeIf { showTranslation },
        backgroundTranslation = backgroundTranslation.takeIf { showTranslation },
        pronunciationText = pronunciationText.takeIf { showPronunciation },
        backgroundPronunciationText = backgroundPronunciationText.takeIf { showPronunciation },
        pronunciationWords = pronunciationWords.takeIf { showPronunciation }.orEmpty(),
        backgroundPronunciationWords = backgroundPronunciationWords.takeIf { showPronunciation }.orEmpty(),
    )
}

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

/** Keep an overflow-driven anchor monotonic only while playback remains in the same vocal cluster. */
internal fun retainedLyricAnchorBase(
    clusterStartIndex: Int,
    previousAnchorIndex: Int,
    nextClusterStartIndex: Int,
): Int = if (
    previousAnchorIndex >= clusterStartIndex &&
    (nextClusterStartIndex < 0 || previousAnchorIndex < nextClusterStartIndex)
) {
    previousAnchorIndex
} else {
    clusterStartIndex
}

/**
 * Resolves the visual scroll anchor among currently active singing lines.
 *
 * If multiple lines are simultaneously active (e.g. duet, rapid overlapping phrases, or
 * successive entries within a vocal cluster) and a lower active line extends past the physical
 * bottom of the viewport, the scroll anchor advances only as far as needed to fit every active
 * line while retaining as many earlier lines as possible. Edge fades are visual decoration and
 * must not make lyrics move while they still fit on screen.
 */
internal fun resolveActiveLyricAnchor(
    baseAnchorIndex: Int,
    activeLineIndices: List<Int>,
    tops: FloatArray,
    heights: FloatArray,
    focalAnchorY: Float,
    viewportHeightPx: Float,
    stageOffsetPx: Float = 0f,
    maxScroll: Float = if (tops.isNotEmpty()) (tops.last() - focalAnchorY).coerceAtLeast(0f) else 0f,
): Int {
    if (baseAnchorIndex < 0 || activeLineIndices.isEmpty() || tops.isEmpty() || heights.isEmpty()) {
        return baseAnchorIndex
    }
    val count = minOf(tops.size, heights.size)
    if (baseAnchorIndex >= count) return baseAnchorIndex

    var maxActive = Int.MIN_VALUE
    for (i in 0 until activeLineIndices.size) {
        val idx = activeLineIndices[i]
        if (idx in 0 until count) {
            if (idx > maxActive) maxActive = idx
        }
    }
    if (maxActive == Int.MIN_VALUE) return baseAnchorIndex

    // Keep the cluster's earliest line for as long as the active lyrics fit. When they do not,
    // advance one active line at a time and stop at the earliest anchor that fits them all.
    var candidate = baseAnchorIndex
    while (candidate < maxActive) {
        val scroll = initialLyricScroll(tops, candidate, focalAnchorY, maxScroll)
        var overflows = false
        for (i in 0 until activeLineIndices.size) {
            val idx = activeLineIndices[i]
            if (idx in candidate until count) {
                val renderedRowBottom = tops[idx] + heights[idx] - scroll + stageOffsetPx
                if (renderedRowBottom > viewportHeightPx) {
                    overflows = true
                    break
                }
            }
        }
        if (!overflows) break

        var nextActive = Int.MAX_VALUE
        for (i in 0 until activeLineIndices.size) {
            val idx = activeLineIndices[i]
            if (idx in (candidate + 1) until count && idx < nextActive) nextActive = idx
        }
        if (nextActive == Int.MAX_VALUE) break
        candidate = nextActive
    }
    return candidate
}

/** Estimates are never painted; mounted rows replace them with exact Compose measurements. */
internal fun estimateLyricRowHeight(line: LyricLine, widthPx: Float, density: Float, fontScale: Float): Float {
    val sp = density * fontScale
    fun textWidth(text: String, sizeSp: Float): Float {
        var width = 0f
        for (character in text) width += sizeSp * sp * when {
            character.isWhitespace() -> 0.3f
            character.code >= 0x2e80 -> 1f
            else -> 0.58f
        }
        return width
    }
    fun textRows(text: String, sizeSp: Float, available: Float): Int {
        return ceil(textWidth(text, sizeSp) / available.coerceAtLeast(sizeSp * sp))
            .toInt()
            .coerceAtLeast(1)
    }
    fun karaokeRows(
        words: List<dev.amatv.model.LyricWord>,
        mainSizeSp: Float,
        pronunciationSizeSp: Float,
        available: Float,
    ): List<IntArray> {
        val widths = IntArray(words.size) { index ->
            val word = words[index]
            val trailing = if (word.hasTrailingWhitespace && index < words.lastIndex) " " else ""
            val lyricText = (word.displayText.takeIf { it != word.text } ?: word.text) + trailing
            val pronunciationText = word.pronunciationText?.plus(trailing).orEmpty()
            ceil(maxOf(
                textWidth(lyricText, mainSizeSp),
                textWidth(pronunciationText, pronunciationSizeSp),
            )).toInt().coerceAtLeast(1)
        }
        return lyricFlexRows(
            groupIds = words.map { it.emphasisGroupId },
            wordWidths = widths,
            maxWidth = available.coerceAtLeast(1f).toInt(),
        )
    }
    fun karaokeBlockHeight(
        words: List<dev.amatv.model.LyricWord>,
        mainSizeSp: Float,
        mainLineHeightSp: Float,
        pronunciationSizeSp: Float,
        pronunciationLineHeightSp: Float,
        pronunciationGapPx: Float,
        available: Float,
    ): Float {
        val rows = karaokeRows(words, mainSizeSp, pronunciationSizeSp, available)
        return rows.mapIndexed { rowIndex, indices ->
            mainLineHeightSp * sp +
                if (indices.any { !words[it].pronunciationText.isNullOrBlank() }) {
                    pronunciationLineHeightSp * sp + pronunciationGapPx
                } else {
                    0f
                } + if (rowIndex < rows.lastIndex) 4f * density else 0f
        }.sum()
    }
    if (line.isStatic || line.timing == LyricTiming.None) {
        val contentWidth = (widthPx - 60f * density).coerceAtLeast(1f)
        val hasPronunciation = !line.pronunciationText.isNullOrBlank() ||
            line.words.any { !it.pronunciationText.isNullOrBlank() }
        val compactLocalization = hasPronunciation && !line.translation.isNullOrBlank()
        val mainSizeSp = if (compactLocalization) 22f else 24f
        val mainLineHeightSp = if (compactLocalization) 29f else 32f
        val pronunciationSizeSp = if (compactLocalization) 15f else 17f
        val pronunciationLineHeightSp = if (compactLocalization) 19f else 22f
        val translationSizeSp = if (compactLocalization) 17f else 19f
        val translationLineHeightSp = if (compactLocalization) 22f else 25f
        val localizationGapPx = if (compactLocalization) 2f * density else 4f * density
        val staticPronunciation = line.pronunciationText
            ?: line.words.mapNotNull { it.pronunciationText }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        val hasBackgroundPronunciation = !line.backgroundPronunciationText.isNullOrBlank() ||
            line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }
        val compactBackgroundLocalization = hasBackgroundPronunciation &&
            !line.backgroundTranslation.isNullOrBlank()
        val backgroundTypography = backgroundVocalTypography(
            flymeAuto = BuildConfig.FLYME_AUTO,
            isStaticMode = true,
            compactMainLocalization = compactLocalization,
            compactBackgroundLocalization = compactBackgroundLocalization,
        )
        var height = 24f * density +
            textRows(line.text, mainSizeSp, contentWidth) * mainLineHeightSp * sp
        if (!staticPronunciation.isNullOrBlank()) {
            height += localizationGapPx +
                textRows(staticPronunciation, pronunciationSizeSp, contentWidth) *
                pronunciationLineHeightSp * sp
        }
        if (!line.translation.isNullOrBlank()) {
            height += localizationGapPx +
                textRows(line.translation, translationSizeSp, contentWidth) *
                translationLineHeightSp * sp
        }
        val staticBackgroundPronunciation = line.backgroundPronunciationText
            ?: line.backgroundWords.mapNotNull { it.pronunciationText }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        if (BuildConfig.FLYME_AUTO && !staticBackgroundPronunciation.isNullOrBlank()) {
            height += 4f * density +
                textRows(staticBackgroundPronunciation, backgroundTypography.pronunciationSizeSp, contentWidth) *
                backgroundTypography.pronunciationLineHeightSp * sp +
                backgroundTypography.localizationGapDp * density
        }
        if (line.backgroundWords.isNotEmpty()) {
            height += 4f * density + textRows(
                "(${line.backgroundText})",
                backgroundTypography.fontSizeSp,
                contentWidth,
            ) * backgroundTypography.lineHeightSp * sp
        }
        if (!line.backgroundTranslation.isNullOrBlank()) {
            height += 4f * density + textRows(
                line.backgroundTranslation,
                backgroundTypography.translationSizeSp,
                contentWidth,
            ) * backgroundTypography.translationLineHeightSp * sp
        }
        return height
    }
    val contentWidth = ((widthPx - 44f * density) * if (line.isMultiAgentSong) 0.85f else 1f)
        .coerceAtLeast(1f)
    val flexWidth = (contentWidth - 12f * density).coerceAtLeast(1f)
    val hasWordPronunciation = line.words.any { !it.pronunciationText.isNullOrBlank() }
    val hasMainPronunciation = !line.pronunciationText.isNullOrBlank() || hasWordPronunciation
    val compactMainLocalization = hasMainPronunciation && !line.translation.isNullOrBlank()
    val mainSizeSp = if (compactMainLocalization) 31f else 34f
    val mainLineHeightSp = if (compactMainLocalization) 37f else 41f
    val mainPronunciationSizeSp = if (compactMainLocalization) 15f else 17f
    val mainPronunciationLineHeightSp = if (compactMainLocalization) 19f else 22f
    val mainTranslationSizeSp = if (compactMainLocalization) 21f else 24f
    val mainTranslationLineHeightSp = if (compactMainLocalization) 25f else 29f
    val mainGap = if (compactMainLocalization) 2f * density else 4f * density
    var height = 22f * density
    if (!line.pronunciationText.isNullOrBlank() && !hasWordPronunciation) {
        height += textRows(line.pronunciationText, mainPronunciationSizeSp, flexWidth) *
            mainPronunciationLineHeightSp * sp + mainGap
    }
    height += karaokeBlockHeight(
        line.words,
        mainSizeSp,
        mainLineHeightSp,
        mainPronunciationSizeSp,
        mainPronunciationLineHeightSp,
        mainGap,
        flexWidth,
    )
    if (!line.translation.isNullOrBlank()) {
        height += mainGap + textRows(line.translation, mainTranslationSizeSp, flexWidth) *
            mainTranslationLineHeightSp * sp
    }
    if (line.backgroundWords.isNotEmpty()) {
        height += 4f * density
        val hasBackgroundWordPronunciation = line.backgroundWords.any {
            !it.pronunciationText.isNullOrBlank()
        }
        val hasBackgroundPronunciation = !line.backgroundPronunciationText.isNullOrBlank() ||
            hasBackgroundWordPronunciation
        val compactBackgroundLocalization = hasBackgroundPronunciation &&
            !line.backgroundTranslation.isNullOrBlank()
        val backgroundTypography = backgroundVocalTypography(
            flymeAuto = BuildConfig.FLYME_AUTO,
            isStaticMode = false,
            compactMainLocalization = compactMainLocalization,
            compactBackgroundLocalization = compactBackgroundLocalization,
        )
        if (!line.backgroundPronunciationText.isNullOrBlank() && !hasBackgroundWordPronunciation) {
            height += textRows(
                line.backgroundPronunciationText,
                backgroundTypography.pronunciationSizeSp,
                flexWidth,
            ) * backgroundTypography.pronunciationLineHeightSp * sp +
                backgroundTypography.localizationGapDp * density
        }
        height += karaokeBlockHeight(
            line.backgroundWords,
            backgroundTypography.fontSizeSp,
            backgroundTypography.lineHeightSp,
            backgroundTypography.pronunciationSizeSp,
            backgroundTypography.pronunciationLineHeightSp,
            backgroundTypography.localizationGapDp * density,
            flexWidth,
        )
    }
    if (!line.backgroundTranslation.isNullOrBlank()) {
        val hasBackgroundPronunciation = !line.backgroundPronunciationText.isNullOrBlank() ||
            line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }
        val compactBackgroundLocalization = hasBackgroundPronunciation &&
            !line.backgroundTranslation.isNullOrBlank()
        val backgroundTypography = backgroundVocalTypography(
            flymeAuto = BuildConfig.FLYME_AUTO,
            isStaticMode = false,
            compactMainLocalization = compactMainLocalization,
            compactBackgroundLocalization = compactBackgroundLocalization,
        )
        height += backgroundTypography.localizationGapDp * density + textRows(
            line.backgroundTranslation,
            backgroundTypography.translationSizeSp,
            flexWidth,
        ) * backgroundTypography.translationLineHeightSp * sp
    }
    return height
}

internal data class BackgroundVocalTypography(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val pronunciationSizeSp: Float,
    val pronunciationLineHeightSp: Float,
    val translationSizeSp: Float,
    val translationLineHeightSp: Float,
    val localizationGapDp: Float,
)

/** Shared background-vocal sizes for rendering and the pre-measure row-height estimate. */
internal fun backgroundVocalTypography(
    flymeAuto: Boolean,
    isStaticMode: Boolean,
    compactMainLocalization: Boolean,
    compactBackgroundLocalization: Boolean,
): BackgroundVocalTypography {
    if (!flymeAuto) {
        if (isStaticMode) {
            return BackgroundVocalTypography(
                fontSizeSp = 19f,
                lineHeightSp = 25f,
                pronunciationSizeSp = 16f,
                pronunciationLineHeightSp = 20f,
                translationSizeSp = 17f,
                translationLineHeightSp = 22f,
                localizationGapDp = 4f,
            )
        }
        return if (compactBackgroundLocalization) {
            BackgroundVocalTypography(22f, 27f, 15f, 18f, 17f, 22f, 2f)
        } else {
            BackgroundVocalTypography(24f, 29f, 16f, 20f, 19f, 25f, 4f)
        }
    }

    val compactSubtext = compactMainLocalization || compactBackgroundLocalization
    if (isStaticMode) {
        return BackgroundVocalTypography(
            fontSizeSp = if (compactMainLocalization) 13.5f else 14.5f,
            lineHeightSp = if (compactMainLocalization) 17f else 19f,
            pronunciationSizeSp = if (compactSubtext) 10.5f else 11.5f,
            pronunciationLineHeightSp = if (compactSubtext) 13f else 15f,
            translationSizeSp = if (compactSubtext) 11.5f else 12.5f,
            translationLineHeightSp = if (compactSubtext) 15f else 16f,
            localizationGapDp = if (compactSubtext) 2f else 4f,
        )
    }
    return BackgroundVocalTypography(
        fontSizeSp = if (compactMainLocalization) 18.5f else 20f,
        lineHeightSp = if (compactMainLocalization) 23f else 25f,
        pronunciationSizeSp = if (compactSubtext) 12f else 13f,
        pronunciationLineHeightSp = if (compactSubtext) 15f else 17f,
        translationSizeSp = if (compactSubtext) 14f else 15f,
        translationLineHeightSp = if (compactSubtext) 18f else 19f,
        localizationGapDp = if (compactSubtext) 2f else 4f,
    )
}
