package dev.amatv.ui

import dev.amatv.data.MockCatalog
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class SystemPlaybackLyricsTest {
    private val song = MockCatalog.tracks.first().copy(catalogId = "song-a", lyrics = emptyList())
    private val lines = listOf(LyricLine(listOf(LyricWord("Current lyrics", 100L, 1000L))))

    @Test fun lateLyricsPopulateTheExistingPlaybackSnapshot() {
        val original = snapshot()
        val published = original.withSystemLyrics("song-a", lines)
        assertEquals(lines, published.currentTrack!!.lyrics)
        assertEquals(original.positionMs, published.positionMs)
        assertEquals(original.requestGeneration, published.requestGeneration)
    }

    @Test fun oldSongLyricsNeverAttachToTheNewSong() {
        val original = snapshot()
        assertSame(original, original.withSystemLyrics("song-b", lines))
        assertTrue(original.currentTrack!!.lyrics.isEmpty())
    }

    @Test fun clearingTheCurrentLyricsAlsoClearsThePublishedDocument() {
        val original = snapshot().withSystemLyrics("song-a", lines)
        assertTrue(original.withSystemLyrics("song-a", emptyList()).currentTrack!!.lyrics.isEmpty())
    }

    private fun snapshot() = PlaybackSnapshot(
        currentTrack = song, positionMs = 500L, durationMs = song.durationMs,
        queue = listOf(song), isPlaying = false, isBuffering = false,
        canSkipNext = false, canSkipPrevious = false,
        repeatMode = dev.amatv.integration.PlaybackRepeatMode.Off,
        shuffleEnabled = false, autoplayEnabled = false,
        canSetRepeat = false, canSetShuffle = false, canSetAutoplay = false,
        capturedAtElapsedRealtimeMs = 1000L, requestGeneration = 7L,
    )
}
