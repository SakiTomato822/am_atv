package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Result state for the optional time-coded representation. Plain text remains available for untimed lyrics. */
enum class PlaybackLyricExportStatus {
    Ready,
    NoLyrics,
    MissingTimestamps,
    PayloadTooLarge,
}

enum class PlaybackLyricVoice { Main, Background }

data class PlaybackLyricSegment(
    val text: String,
    val voice: PlaybackLyricVoice,
    val sourceLineIndex: Int,
)

/** All original vocal segments that begin at the same timestamp. */
data class PlaybackLyricCue(
    val startMs: Long,
    val segments: List<PlaybackLyricSegment>,
) {
    /** Precomputed combined original text for same-time main and background vocals. */
    val text: String = segments.joinToString("\n") { it.text }
    val mainText: String? = segments.asSequence()
        .filter { it.voice == PlaybackLyricVoice.Main }
        .joinToString("\n") { it.text }
        .takeIf(String::isNotEmpty)
    val backgroundText: String? = segments.asSequence()
        .filter { it.voice == PlaybackLyricVoice.Background }
        .joinToString("\n") { it.text }
        .takeIf(String::isNotEmpty)
}

/**
 * Immutable snapshot of a track's original lyrics.
 *
 * `structuredLyricsVersion` fingerprints original main/background words and their timing, but
 * excludes translation and pronunciation fields. `currentLineAt` and `nextLineAfter` use a
 * prebuilt cue index; callers never need to regenerate the whole LRC on playback clock updates.
 */
class PlaybackLyricExport private constructor(
    val trackIdentity: String,
    val structuredLyricsVersion: String,
    val includeBackgroundVocals: Boolean,
    val status: PlaybackLyricExportStatus,
    val plainText: String?,
    val timedLrc: String?,
    /** UTF-8 bytes of identity, version, plainText, and timedLrc as a single logical payload. */
    val payloadUtf8Bytes: Int,
    private val cues: List<PlaybackLyricCue>,
) {
    fun currentLineAt(positionMs: Long): PlaybackLyricCue? {
        if (status != PlaybackLyricExportStatus.Ready) return null
        val index = upperBound(positionMs) - 1
        return cues.getOrNull(index)
    }

    fun nextLineAfter(positionMs: Long): PlaybackLyricCue? {
        if (status != PlaybackLyricExportStatus.Ready) return null
        return cues.getOrNull(upperBound(positionMs))
    }

    private fun upperBound(positionMs: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val DEFAULT_MAX_PAYLOAD_UTF8_BYTES = 256 * 1024

        /** Serialize once for an identity/version pair and retain cues for cheap cursor lookup. */
        fun create(
            trackIdentity: String,
            lines: List<LyricLine>,
            maxPayloadUtf8Bytes: Int = DEFAULT_MAX_PAYLOAD_UTF8_BYTES,
            includeBackgroundVocals: Boolean = true,
        ): PlaybackLyricExport {
            require(trackIdentity.isNotBlank()) { "Track identity is required" }
            require(maxPayloadUtf8Bytes >= 0) { "Payload limit must be nonnegative" }

            val structuredVersion = structuredLyricsVersion(lines, includeBackgroundVocals)
            val segments = mutableListOf<UnindexedSegment>()
            var missingTimestamp = false
            var sourceOrder = 0
            var mainOnlyLineIndex = 0

            lines.forEachIndexed { lineIndex, line ->
                if (line.isTranslation) return@forEachIndexed
                val mainWords = if (includeBackgroundVocals) {
                    line.words
                } else {
                    line.words.filterNot(LyricWord::isBackgroundVocal)
                }
                val main = cleanLyricText(
                    mainWords.joinToString(if (line.joinWithSpaces) " " else "") { it.text },
                )
                if (main.isNotEmpty()) {
                    val startMs = lineTimestamp(line, mainWords)
                    if (startMs == null) missingTimestamp = true
                    segments += UnindexedSegment(
                        text = main,
                        voice = PlaybackLyricVoice.Main,
                        sourceLineIndex = if (includeBackgroundVocals) lineIndex else mainOnlyLineIndex,
                        startMs = startMs,
                        sourceOrder = sourceOrder++,
                    )
                    mainOnlyLineIndex++
                }

                if (includeBackgroundVocals) {
                    val background = cleanLyricText(line.backgroundText)
                    if (background.isNotEmpty()) {
                        val startMs = lineTimestamp(line, line.backgroundWords)
                        if (startMs == null) missingTimestamp = true
                        segments += UnindexedSegment(
                            text = background,
                            voice = PlaybackLyricVoice.Background,
                            sourceLineIndex = lineIndex,
                            startMs = startMs,
                            sourceOrder = sourceOrder++,
                        )
                    }
                }
            }

            val plainText = segments.joinToString("\n") { it.text }
            val timedSegments = segments.filter { it.startMs != null }
                .sortedWith(compareBy<UnindexedSegment> { checkNotNull(it.startMs) }.thenBy { it.sourceOrder })
            val cues = groupByTimestamp(timedSegments)
            val canCreateCompleteLrc = segments.isNotEmpty() && !missingTimestamp && timedSegments.size == segments.size
            val timedLrc = if (canCreateCompleteLrc) {
                timedSegments.joinToString("\n") { segment ->
                    "${formatLrcTimestamp(checkNotNull(segment.startMs))}${segment.text}"
                }
            } else {
                null
            }
            val payloadByteCount = listOfNotNull(trackIdentity, structuredVersion, plainText, timedLrc)
                .sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() }
            val payloadUtf8Bytes = payloadByteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            if (payloadByteCount > maxPayloadUtf8Bytes.toLong()) {
                return PlaybackLyricExport(
                    trackIdentity = trackIdentity,
                    structuredLyricsVersion = structuredVersion,
                    includeBackgroundVocals = includeBackgroundVocals,
                    status = PlaybackLyricExportStatus.PayloadTooLarge,
                    plainText = null,
                    timedLrc = null,
                    payloadUtf8Bytes = payloadUtf8Bytes,
                    cues = emptyList(),
                )
            }

            val status = when {
                segments.isEmpty() -> PlaybackLyricExportStatus.NoLyrics
                canCreateCompleteLrc -> PlaybackLyricExportStatus.Ready
                else -> PlaybackLyricExportStatus.MissingTimestamps
            }
            return PlaybackLyricExport(
                trackIdentity = trackIdentity,
                structuredLyricsVersion = structuredVersion,
                includeBackgroundVocals = includeBackgroundVocals,
                status = status,
                plainText = plainText,
                timedLrc = timedLrc,
                payloadUtf8Bytes = payloadUtf8Bytes,
                cues = cues,
            )
        }

        private data class UnindexedSegment(
            val text: String,
            val voice: PlaybackLyricVoice,
            val sourceLineIndex: Int,
            val startMs: Long?,
            val sourceOrder: Int,
        )

        private fun lineTimestamp(line: LyricLine, words: List<LyricWord>): Long? {
            if (line.isStatic || line.timing == LyricTiming.None || words.isEmpty()) return null
            if (words.any { it.startMs < 0L }) return null
            return words.minOfOrNull(LyricWord::startMs)
        }

        private fun cleanLyricText(text: String): String = text
            .replace(Regex("[\\r\\n\\u0085\\u2028\\u2029]+"), " ")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
            .trim()

        private fun formatLrcTimestamp(timeMs: Long): String {
            val wholeSeconds = timeMs / 1_000L
            val minutes = wholeSeconds / 60L
            val seconds = wholeSeconds % 60L
            val milliseconds = timeMs % 1_000L
            return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${milliseconds.toString().padStart(3, '0')}]"
        }

        private fun groupByTimestamp(segments: List<UnindexedSegment>): List<PlaybackLyricCue> {
            val cues = ArrayList<PlaybackLyricCue>()
            for (segment in segments) {
                val startMs = checkNotNull(segment.startMs)
                val lyric = PlaybackLyricSegment(segment.text, segment.voice, segment.sourceLineIndex)
                val last = cues.lastOrNull()
                if (last?.startMs == startMs) {
                    cues[cues.lastIndex] = last.copy(segments = last.segments + lyric)
                } else {
                    cues += PlaybackLyricCue(startMs, listOf(lyric))
                }
            }
            return cues
        }

        private fun structuredLyricsVersion(lines: List<LyricLine>, includeBackgroundVocals: Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun putString(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            fun putLong(value: Long) {
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
            }
            fun putWords(marker: String, words: List<LyricWord>) {
                putString(marker)
                putLong(words.size.toLong())
                for (word in words) {
                    putString(word.text)
                    putString(word.displayText)
                    putLong(word.startMs)
                    putLong(word.endMs)
                    putLong(word.emphasisGroupId?.toLong() ?: Long.MIN_VALUE)
                    putLong(if (word.hasTrailingWhitespace) 1L else 0L)
                    putLong(if (word.isBackgroundVocal) 1L else 0L)
                }
            }

            putString(if (includeBackgroundVocals) "playback-lyrics-v1:all" else "playback-lyrics-v1:main-only")
            val originalLines = lines.filter { line ->
                !line.isTranslation &&
                    (includeBackgroundVocals || line.words.any { !it.isBackgroundVocal })
            }
            putLong(originalLines.size.toLong())
            for (line in originalLines) {
                putLong(if (line.timing == LyricTiming.None) 1L else 0L)
                putLong(if (line.isStatic) 1L else 0L)
                putLong(if (line.joinWithSpaces) 1L else 0L)
                val mainWords = if (includeBackgroundVocals) {
                    line.words
                } else {
                    line.words.filterNot(LyricWord::isBackgroundVocal)
                }
                putWords("main", mainWords)
                if (includeBackgroundVocals) {
                    putWords("background", line.backgroundWords)
                }
            }
            val hash = digest.digest()
            val hex = "0123456789abcdef"
            return buildString(7 + hash.size * 2) {
                append("sha256:")
                for (byte in hash) {
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }
    }
}
