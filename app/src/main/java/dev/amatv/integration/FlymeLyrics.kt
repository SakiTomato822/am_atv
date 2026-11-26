package dev.amatv.integration

import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import dev.amatv.model.renderLyricWords
import java.util.Locale

internal class FlymeLyrics private constructor(private val lines: List<Line>, val lrc: String) {
    private data class Line(val startMs: Long, val text: String)

    val lineCount: Int get() = lines.size

    fun indexAt(positionMs: Long): Int = lines.indexOfLast { it.startMs <= positionMs }
    fun sentence(index: Int): String = lines.getOrNull(index)?.text.orEmpty()

    companion object {
        fun from(track: Track): FlymeLyrics? {
            val lines = track.lyrics.mapNotNull { line ->
                if (line.isTranslation || line.isStatic || line.timing == LyricTiming.None) return@mapNotNull null
                val words = line.words.filterNot(LyricWord::isBackgroundVocal)
                val text = singleLine(renderLyricWords(words, line.joinWithSpaces)).trim()
                if (text.isEmpty()) return@mapNotNull null
                Line(line.lineStartMs ?: words.first().startMs, text)
            }.sortedBy(Line::startMs)
            if (lines.isEmpty()) return null
            val lrc = buildString {
                appendLine("[ti:${singleLine(track.title)}]")
                appendLine("[ar:${singleLine(track.artist)}]")
                append("[al:${singleLine(track.album)}]")
                // The panel highlights its first row until playback reaches the next one.
                if (lines.first().startMs > 0) {
                    append("\n[00:00.00]${singleLine(track.title)} - ${singleLine(track.artist)}")
                }
                for (line in lines) {
                    append('\n')
                    append(timestamp(line.startMs))
                    append(line.text)
                }
            }
            return FlymeLyrics(lines, lrc)
        }

        private val lineBreak = Regex("\\R")
        private fun singleLine(text: String): String = text.replace(lineBreak, " ")

        private fun timestamp(ms: Long): String {
            val minutes = ms / 60_000
            val seconds = ms / 1_000 % 60
            val hundredths = ms / 10 % 100
            return if (minutes < 100) {
                String.format(Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
            } else {
                String.format(Locale.ROOT, "[%02d:%02d:%02d.%02d]", minutes / 60, minutes % 60, seconds, hundredths)
            }
        }
    }
}

/** A null result suppresses the RPC. Index -1 clears the remote sentence. */
internal class FlymeLyricPush {
    private var sentIndex = -1

    fun update(index: Int, playing: Boolean): Int? {
        if (!playing || index == sentIndex) return null
        sentIndex = index
        return index
    }

    fun reset() {
        sentIndex = -1
    }
}
