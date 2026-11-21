package dev.amatv.flyme

import androidx.compose.ui.text.font.FontWeight
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord

internal val LyricBackingWeight = FontWeight.Bold
internal const val LYRIC_BACKING_FADE_MS = 200L

internal fun LyricLine.backingVocalWords(): List<LyricWord> =
    (backgroundWords + words.filter { it.isBackgroundVocal }).sortedBy { it.startMs }

internal fun List<LyricWord>.backingVocalInterval(): LongRange =
    if (isEmpty()) LongRange.EMPTY else minOf { it.startMs } until maxOf { it.endMs }

internal fun backingVocalOpacity(positionMs: Long, interval: LongRange, animate: Boolean): Float =
    when {
        positionMs !in interval -> 0f
        !animate -> 1f
        else -> ((positionMs - interval.first).toFloat() / LYRIC_BACKING_FADE_MS).coerceAtMost(1f)
    }

/** A geometric retention boundary may move several pixels per frame, but its tint must not snap. */
internal fun advanceLyricPresence(current: Float, desired: Float, elapsedMs: Long): Float {
    val from = current.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val to = desired.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    // The live karaoke/line-sync envelope already owns activation timing.
    if (to >= from) return to
    val maximumFall = elapsedMs.coerceIn(0L, 64L) / 300f
    return maxOf(to, from - maximumFall)
}
