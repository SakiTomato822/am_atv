package dev.amatv.flyme

import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class FolioHomeOrderTest {
    private fun shelf(id: String) = ContentShelf(id, id, emptyList())

    @Test
    fun recentlyPlayedShelvesComeFirstAndOthersKeepTheirOrder() {
        val ordered = homeShelfOrder(listOf("for-you", "recent", "made-for-you", "recent-tracks").map(::shelf))
        assertEquals(listOf("recent", "recent-tracks", "for-you", "made-for-you"), ordered.map { it.id })
    }

    @Test
    fun recentTrackPreviewKeepsFourOnSingleColumnAndTwoRowsOnWiderTiers() {
        val tracks = List(20) { index ->
            Track("$index", "Track $index", "", "", 0, emptyList(), emptyList())
        }
        for ((columns, expected) in listOf(1 to 4, 2 to 4, 3 to 6, 5 to 10)) {
            assertEquals(tracks.take(expected), recentTrackPreview(tracks, columns))
        }
        assertEquals(tracks.take(2), recentTrackPreview(tracks.take(2), 3))
    }
}
