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

internal const val POSTER_PREVIOUS_FADE_MS = 200L

internal fun posterLeadEndMs(line: LyricLine): Long =
    if (line.timing == LyricTiming.Line && line.lineEndMs != null) line.lineEndMs
    else line.words.filterNot { it.isBackgroundVocal }.maxOfOrNull { it.endMs } ?: line.startMs

internal data class PosterLyricsProjection(
    val focusLeadIndex: Int?,
    val hasStartedLead: Boolean,
    val previousLeadIndex: Int?,
    val previousLeadSweepEndMs: Long?,
    val smallLines: List<PosterTextLine>,
    val upcomingLeadIndices: List<Int>,
    val gap: LyricTimeline.InstrumentalGap?,
)

/** All inputs to a projection are constant between adjacent boundaries. */
internal class PosterLyricsTimeline(private val lines: List<LyricLine>) {
    private val leadTexts = lines.map(::posterLeadText)
    private val leadEnds = lines.map(::posterLeadEndMs)
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
            add(leadEnds[index])
            val (interval) = backing[index]
            if (!interval.isEmpty()) {
                add(interval.first)
                add(interval.last + 1)
            }
        }
        for ((index, next) in leadIndices.zipWithNext()) {
            if (leadEnds[index] > lines[next].startMs) {
                add(leadEnds[index] + POSTER_PREVIOUS_FADE_MS)
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
        val focusLine = focus?.let { lines[it] }
        val previous = leadIndices.getOrNull(startedLead - 1).takeIf { gap == null }
        val previousSweepEnd = if (previous != null && focusLine != null) {
            leadEnds[previous].takeIf { end -> end > focusLine.startMs && positionMs < end + POSTER_PREVIOUS_FADE_MS }
        } else null
        val smallLines = buildList {
            if (gap == null) {
                for ((index, line) in lines.withIndex()) {
                    if (line.isStatic) continue
                    val opposite = isOppositeSingerLine(line)
                    if (line.agentType == LyricAgentType.Group ||
                        (focusLine != null && index != focus && index != previous && isDifferentSinger(line, focusLine))
                    ) {
                        val lead = leadTexts[index]
                        if (lead.isNotEmpty() && positionMs in line.startMs until leadEnds[index]) {
                            add(PosterTextLine(lead, opposite))
                        }
                    }
                    val (interval, text) = backing[index]
                    if (positionMs in interval && text.isNotEmpty()) add(PosterTextLine(text, opposite))
                }
            }
        }
        return PosterLyricsProjection(
            focusLeadIndex = focus,
            hasStartedLead = startedLead >= 0,
            previousLeadIndex = previous,
            previousLeadSweepEndMs = previousSweepEnd,
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

private fun isDifferentSinger(line: LyricLine, focus: LyricLine): Boolean {
    val agent = line.agentId
    val focusAgent = focus.agentId
    return if (!agent.isNullOrBlank() && !focusAgent.isNullOrBlank()) agent != focusAgent
    else isOppositeSingerLine(line) != isOppositeSingerLine(focus)
}
