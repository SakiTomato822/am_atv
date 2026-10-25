package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossCatalogResolverTest {
    private val resolver = CrossCatalogResolver(AppleMusicApi("tw", DeveloperTokenClient("")) { null })

    private fun track(id: String, title: String, artist: String, duration: Long) = Track(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = duration,
        palette = listOf(Color.Black),
        lyrics = emptyList(),
        catalogId = id,
    )

    @Test fun normalizesDiacriticsAndFeaturing() {
        assertEquals("cafe song", resolver.normalize("Café Song (feat. Guest)"))
    }

    @Test fun acceptsExactSongWithinTenSeconds() {
        val song = YtmSong("dQw4w9WgXcQ", "Café Song", "Artist", 200_000L)
        val match = resolver.select(song, listOf(track("123", "Cafe Song", "Artist", 204_000L)))
        assertEquals("123", match?.catalogId)
    }

    @Test fun rejectsWrongDurationAndEdition() {
        val song = YtmSong("dQw4w9WgXcQ", "Song", "Artist", 200_000L)
        assertNull(resolver.select(song, listOf(track("123", "Song", "Artist", 211_000L))))
        assertNull(resolver.select(song, listOf(track("123", "Song (Live)", "Artist", 200_000L))))
    }

    @Test fun acceptsEquivalentSingleAndAlbumReleases() {
        val song = YtmSong("dQw4w9WgXcQ", "Song", "Artist", 200_000L)
        assertEquals("123", resolver.select(song, listOf(
            track("123", "Song", "Artist", 200_000L),
            track("456", "Song", "Artist", 200_000L),
        ))?.catalogId)
    }

    @Test fun rejectsAmbiguousRecordingsWithDifferentDurations() {
        val song = YtmSong("dQw4w9WgXcQ", "Song", "Artist", null)
        assertNull(resolver.select(song, listOf(
            track("123", "Song", "Artist", 200_000L),
            track("456", "Song", "Artist", 206_000L),
        )))
    }
}
