package dev.amatv.core

import androidx.compose.ui.graphics.Color
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCacheIdentityTest {
    private fun track(id: String, catalogId: String) = Track(
        id = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000L,
        palette = listOf(Color.Black),
        lyrics = emptyList(),
        catalogId = catalogId,
    )

    @Test
    fun catalogIdentityKeepsLyricsAcrossBrowseAndNowPlayingWrappers() {
        val browse = track(id = "1496639586", catalogId = "1496639586")
        val nowPlaying = track(id = "apple:1496639586", catalogId = "1496639586")

        assertEquals(browse.lyricsIdentity(), nowPlaying.lyricsIdentity())
        assertEquals(
            lyricsCacheKey(browse.lyricsIdentity(), "zh-Hant"),
            lyricsCacheKey(nowPlaying.lyricsIdentity(), "zh-Hant"),
        )
    }
}
