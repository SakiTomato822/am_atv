package dev.amatv.flyme

import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricWord

internal data class KaraokeSweepWord(
    val word: LyricWord,
    val left: Float,
    val right: Float,
)

internal sealed interface KaraokeRowHighlight {
    data object Unlit : KaraokeRowHighlight
    data object Lit : KaraokeRowHighlight
    data class Partial(val ramp: KaraokeMaskRamp) : KaraokeRowHighlight
}

internal fun wholeLyricHighlight(started: Boolean): KaraokeRowHighlight =
    if (started) KaraokeRowHighlight.Lit else KaraokeRowHighlight.Unlit

/** Maps a visual row's timed words to the four-stop mask used by scrolling lyrics. */
internal class KaraokeRowSweep(
    words: List<KaraokeSweepWord>,
    private val width: Float,
    featherPx: Float,
    private val isRtl: Boolean,
) {
    private data class Anchor(val word: LyricWord, val start: Float, val delta: Float)

    private val feather = (featherPx / width).coerceIn(0.001f, 1f)
    private val origin = if (isRtl) 1f else 0f
    private val tailRushMs = if (LyricTimeline.containsSplitOnCharsScript(words.last().word.text)) {
        0L
    } else MotionTokens.KaraokeRushGradientMillis.toLong()
    private val anchors = buildList<Anchor> {
        val first = words.first()
        val last = words.last()
        val textWidth = (maxOf(first.right, last.right) - minOf(first.left, last.left)).coerceAtLeast(1f)
        for ((index, word) in words.withIndex()) {
            val wordWidth = word.right - word.left
            val ratio = wordWidth / textWidth
            val bonus = when {
                index == words.lastIndex -> feather
                LyricTimeline.containsSplitOnCharsScript(word.word.text) -> 0f
                ratio <= 0.2f -> feather * 0.5f
                ratio <= 0.4f -> feather * 0.25f
                else -> feather * 0.12f
            }
            val edge = (if (isRtl) word.right else word.left) / width
            val wordStart = edge.coerceIn(0f, 1f)
            val previousEnd = lastOrNull()?.let { it.start + it.delta } ?: origin
            val start = if (isRtl) minOf(previousEnd, wordStart) else maxOf(previousEnd, wordStart)
            val overlap = if (isRtl) wordStart - start else start - wordStart
            val span = (wordWidth / width - overlap + bonus).coerceAtLeast(0f)
            add(Anchor(word.word, start, if (isRtl) -span else span))
        }
    }

    fun highlightAt(positionMs: Long): KaraokeRowHighlight {
        val time = positionMs - LyricTimeline.KARAOKE_WORD_OFFSET_MS
        val endMs = anchors.last().word.endMs
        if (time < anchors.first().word.startMs) return KaraokeRowHighlight.Unlit
        if (time >= endMs + tailRushMs) return KaraokeRowHighlight.Lit

        var progress = origin
        for (anchor in anchors) {
            val word = anchor.word
            if (time < word.startMs) break
            val fraction = ((time - word.startMs).toFloat() / word.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            progress = anchor.start + anchor.delta * fraction
            if (time <= word.endMs) break
        }
        if (time > endMs && tailRushMs > 0L) {
            val fraction = ((time - endMs).toFloat() / tailRushMs).coerceIn(0f, 1f)
            val target = if (isRtl) -feather else 1f + feather
            progress += (target - progress) * fraction
        }
        val ramp = karaokeMaskRamp(progress, feather, isRtl)
        return KaraokeRowHighlight.Partial(KaraokeMaskRamp(ramp.start * width, ramp.end * width))
    }
}
