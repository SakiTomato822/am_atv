package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlymeLyricsTest {
    @Test
    fun lrcUsesCentisecondsAndOnlyMainVoice() {
        val lyrics = requireNotNull(FlymeLyrics.from(track(
            line("line one", 1_279).copy(
                lineStartMs = 1_239L,
                words = listOf(word("line one", 1_279), word("echo", 1_500).copy(isBackgroundVocal = true)),
                translation = "translation",
                backgroundWords = listOf(word("harmony", 1_220)),
            ),
            line("translation", 1_239).copy(isTranslation = true),
            LyricLine(emptyList(), backgroundWords = listOf(word("echo", 2_000))),
            line("line two", 600_005),
            line("line three", 6_001_987),
        )))

        assertEquals(3, lyrics.lineCount)
        assertEquals(
            "[ti:Title]\n[ar:Artist]\n[al:Album]\n[00:00.00]Title - Artist\n[00:01.23]line one\n[10:00.00]line two\n[01:40:01.98]line three",
            lyrics.lrc,
        )
        assertEquals("", lyrics.sentence(lyrics.indexAt(1_238)))
        assertEquals("line one", lyrics.sentence(lyrics.indexAt(1_239)))
    }

    @Test
    fun emptyAndUntimedTracksHaveNoScrollableLyrics() {
        assertNull(FlymeLyrics.from(track()))
        assertNull(FlymeLyrics.from(track(line("line one", 0).copy(isStatic = true))))
    }

    @Test
    fun pushDeduplicatesAndUsesSeekPositionAsSoonAsPlaybackReportsIt() {
        val lyrics = requireNotNull(FlymeLyrics.from(track(
            line("line one", 1_000), line("line two", 2_000), line("line three", 3_000),
        )))
        val push = FlymeLyricPush()
        fun publish(positionMs: Long, playing: Boolean = true) = push.update(lyrics.indexAt(positionMs), playing)

        assertNull(publish(0))
        assertEquals(0, publish(1_000))
        assertNull(publish(1_250))
        assertNull(publish(3_000, playing = false))
        assertEquals(2, publish(3_000))
        assertNull(publish(3_000))
        assertEquals(1, publish(2_000))
        assertEquals(-1, publish(500))
        assertNull(publish(500))
        push.reset()
        assertEquals(0, publish(1_000))
    }

    private fun line(text: String, start: Long) = LyricLine(listOf(word(text, start)))
    private fun word(text: String, start: Long) = LyricWord(text, start, start + 200)
    private fun track(vararg lines: LyricLine) =
        Track("test", "Title", "Artist", "Album", 10_000, emptyList(), lines.toList())
}
