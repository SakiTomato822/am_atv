package dev.amatv.flyme

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.amatv.model.LyricAgentType
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.renderLyricWords

/** Shares the displayed playback position and identifies deliberate seeks made in the player. */
internal class FolioPlaybackPosition(initialPositionMs: Long) : State<Long> {
    private val position = mutableLongStateOf(initialPositionMs)
    private val appliedSeek = mutableIntStateOf(0)
    private var requestedAnchor: Long? = null
    override val value: Long get() = position.longValue
    val seekVersion: Int get() = appliedSeek.intValue

    fun requestedSeek(anchor: Long) {
        requestedAnchor = anchor
    }

    fun updateAnchor(anchor: Long, positionMs: Long) {
        val seek = requestedAnchor == anchor
        requestedAnchor = null
        if (seek) {
            Snapshot.withMutableSnapshot {
                position.longValue = positionMs
                appliedSeek.intValue++
            }
        } else {
            update(positionMs)
        }
    }

    fun update(positionMs: Long) {
        position.longValue = positionMs
    }
}

internal class PosterPlaybackClock(initialPositionMs: Long) {
    private var lastSampleMs = initialPositionMs
    var positionMs = initialPositionMs
        private set
    var seekGeneration = 0L
        private set

    fun sample(positionMs: Long, seek: Boolean = false): Long {
        this.positionMs = if (seek || lastSampleMs - positionMs > 1_000L) {
            seekGeneration++
            positionMs
        } else {
            maxOf(this.positionMs, positionMs)
        }
        lastSampleMs = positionMs
        return this.positionMs
    }
}

internal data class PosterTextLine(val text: String, val isOppositeSinger: Boolean)

internal data class PosterLyricsProjection(
    val focusLeadIndex: Int?,
    val hasStartedLead: Boolean,
    val previousLeadIndex: Int?,
    val smallLines: List<PosterTextLine>,
    val upcomingLeadIndices: List<Int>,
    val gap: LyricTimeline.InstrumentalGap?,
)

/** All inputs to a projection are constant between adjacent boundaries. */
internal class PosterLyricsTimeline(private val lines: List<LyricLine>) {
    private val leadTexts = lines.map(::posterLeadText)
    private val leadIndices = lines.indices.filter {
        leadTexts[it].isNotEmpty() && lines[it].agentType != LyricAgentType.Group && !lines[it].isStatic
    }
    private val gaps = LyricTimeline.Schedule(lines).instrumentalGaps
    private val backing = lines.map { line ->
        val words = line.backgroundWords + line.words.filter { it.isBackgroundVocal }
        val text = renderLyricWords(words, line.joinWithSpaces).trim()
        val display = when {
            text.isEmpty() || text.startsWith('(') || text.startsWith('（') -> text
            else -> "($text)"
        }
        val interval = if (words.isEmpty()) LongRange.EMPTY else {
            words.minOf { it.startMs } until words.maxOf { it.endMs }
        }
        interval to display
    }
    private val boundaries = buildSet {
        add(0L)
        for ((index, line) in lines.withIndex()) {
            add(line.startMs)
            add(line.mainEndMs)
            if (line.timing == LyricTiming.Word && index in leadIndices) {
                val nextStart = leadIndices.firstOrNull { it > index }?.let { lines[it].startMs }
                if (nextStart != null && nextStart < line.mainEndMs) {
                    line.words.filter { !it.isBackgroundVocal && it.endMs > nextStart }.forEach { add(it.endMs) }
                }
            }
            val (interval) = backing[index]
            if (!interval.isEmpty()) {
                add(interval.first)
                add(interval.last + 1)
            }
        }
        for (gap in gaps) {
            add(gap.startMs)
            add(gap.endMs)
        }
    }.sorted().toLongArray()

    fun boundaryAt(positionMs: Long): Int {
        val result = boundaries.binarySearch(positionMs)
        return if (result >= 0) result else -result - 2
    }

    fun projectionAt(boundary: Int): PosterLyricsProjection =
        projectPosterLyrics(boundaries[boundary])

    private fun projectPosterLyrics(positionMs: Long): PosterLyricsProjection {
        val startedLead = leadIndices.indexOfLast { lines[it].startMs <= positionMs }
        val gap = gaps.firstOrNull { positionMs in it.startMs until it.endMs }
        val focus = leadIndices.getOrNull(startedLead.coerceAtLeast(0))
        val previous = leadIndices.getOrNull(startedLead - 1)
        val smallLines = buildList {
            if (gap == null) {
                for ((index, line) in lines.withIndex()) {
                    if (line.isStatic) continue
                    val opposite = isOppositeSingerLine(line)
                    if (index != focus && (focus == null || index < focus || line.agentType == LyricAgentType.Group) &&
                        leadTexts[index].isNotEmpty() && positionMs in line.startMs until line.mainEndMs
                    ) {
                        val text = if (line.timing == LyricTiming.Word) {
                            renderLyricWords(
                                line.words.filter { !it.isBackgroundVocal && it.endMs > positionMs },
                                line.joinWithSpaces,
                            ).trim()
                        } else {
                            leadTexts[index]
                        }
                        if (text.isNotEmpty()) add(PosterTextLine(text, opposite))
                    }
                    val (interval, text) = backing[index]
                    if (positionMs in interval && text.isNotEmpty()) add(PosterTextLine(text, opposite))
                }
            }
        }
        return PosterLyricsProjection(
            focusLeadIndex = focus,
            hasStartedLead = startedLead >= 0,
            previousLeadIndex = previous.takeIf { gap == null },
            smallLines = smallLines,
            upcomingLeadIndices = if (gap == null) {
                leadIndices.drop(startedLead.coerceAtLeast(0) + 1).take(3)
            } else {
                leadIndices.asSequence().filter { it >= gap.beforeLineIndex }.take(3).toList()
            },
            gap = gap,
        )
    }
}
