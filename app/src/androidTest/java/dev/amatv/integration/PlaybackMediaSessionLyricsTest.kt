package dev.amatv.integration

import android.media.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real framework MediaController readback using EVCC's observed key scan. */
@RunWith(AndroidJUnit4::class)
class PlaybackMediaSessionLyricsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun metadataPublishesMainLrcAndRefreshesAfterLyricsArrive() {
        val owner = newSession()
        try {
            val controller = MediaController(instrumentation.targetContext, owner.sessionToken)
            val emptySong = song()
            publish(owner, emptySong)
            assertEquals("", evccLyricString(controller))
            val line = LyricLine(
                words = listOf(LyricWord("Main lyric %20 + 原文", 1000L, 3000L)),
                backgroundWords = listOf(LyricWord("Harmony is app-only", 1500L, 2500L, isBackgroundVocal = true)),
                translation = "Do not send translated text",
                pronunciationText = "Do not send pronunciation",
                timing = LyricTiming.Word,
            )
            publish(owner, emptySong.copy(lyrics = listOf(line)))
            assertEquals("[00:01.000]Main lyric %20 + 原文", evccLyricString(controller))
            assertFalse(evccLyricString(controller).contains("Harmony"))
        } finally { instrumentation.runOnMainSync { owner.release() } }
    }

    @Test fun switchingToAnUnresolvedSongDoesNotKeepPreviousLyrics() {
        val owner = newSession()
        try {
            val controller = MediaController(instrumentation.targetContext, owner.sessionToken)
            val first = song().copy(lyrics = listOf(LyricLine(listOf(LyricWord("First song", 0L, 2000L)))))
            publish(owner, first)
            assertTrue(evccLyricString(controller).contains("First song"))
            publish(owner, song().copy(id = "second", catalogId = "second", title = "Second song"))
            assertEquals("", evccLyricString(controller))
            instrumentation.runOnMainSync { owner.clear() }
            assertNull(controller.metadata)
        } finally { instrumentation.runOnMainSync { owner.release() } }
    }

    private fun newSession(): PlaybackMediaSession {
        var owner: PlaybackMediaSession? = null
        instrumentation.runOnMainSync { owner = PlaybackMediaSession(instrumentation.targetContext) }
        return requireNotNull(owner)
    }

    private fun publish(owner: PlaybackMediaSession, track: Track) {
        instrumentation.runOnMainSync {
            owner.publish(PlaybackSnapshot(
                currentTrack = track, queue = listOf(track), positionMs = 1000L, durationMs = track.durationMs,
                isPlaying = false, isBuffering = false, canSkipNext = false, canSkipPrevious = false,
                repeatMode = PlaybackRepeatMode.Off, shuffleEnabled = false, autoplayEnabled = false,
                canSetRepeat = false, canSetShuffle = false, canSetAutoplay = false,
            ), canSkipNext = false, canSkipPrevious = false)
        }
    }

    private fun evccLyricString(controller: MediaController): String {
        val metadata = requireNotNull(controller.metadata)
        val key = metadata.keySet().first { it.contains("lyric", ignoreCase = true) || it.contains("lrc", ignoreCase = true) }
        val value = metadata.getString(key).orEmpty()
        return if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(value)) {
            java.net.URLDecoder.decode(value, "UTF-8")
        } else value
    }

    private fun song() = Track(
        id = "first", catalogId = "first", title = "First song", artist = "Artist", album = "Album",
        durationMs = 10_000L, palette = emptyList(), lyrics = emptyList(),
    )
}
