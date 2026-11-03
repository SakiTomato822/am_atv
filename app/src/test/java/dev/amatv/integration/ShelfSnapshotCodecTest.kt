package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfSnapshotCodecTest {
    @Test
    fun shelvesSurviveARoundTrip() {
        val pick = Track(
            id = "pl.1", title = "Top Picks", artist = "Apple Music", album = "",
            durationMs = 1L, palette = listOf(Color(0xFF112233)), lyrics = emptyList(),
            catalogId = "pl.1", catalogKind = CatalogKind.Playlist, artworkUrl = "https://example.com/a.jpg",
            pickReason = "Made for You",
        )
        val song = Track(
            id = "1", title = "Song", artist = "Artist", album = "Album", durationMs = 200_000L,
            palette = listOf(Color(0xFF445566)), lyrics = emptyList(), catalogId = "1",
            isExplicit = true, dateAddedMs = 1_700_000_000_000L,
        )
        val shelves = listOf(
            ContentShelf("picks", "Top Picks for You", listOf(pick), featured = true),
            ContentShelf("songs", "Songs", listOf(song)),
        )

        assertEquals(shelves, decodeShelves(encodeShelves(shelves)))
    }
}
