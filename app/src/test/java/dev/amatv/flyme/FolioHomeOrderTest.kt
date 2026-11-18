package dev.amatv.flyme

import dev.amatv.model.ContentShelf
import org.junit.Assert.assertEquals
import org.junit.Test

class FolioHomeOrderTest {
    private fun shelf(id: String) = ContentShelf(id, id, emptyList())

    @Test
    fun recentlyPlayedShelvesComeFirstAndOthersKeepTheirOrder() {
        val ordered = homeShelfOrder(listOf("for-you", "recent", "made-for-you", "recent-tracks").map(::shelf))
        assertEquals(listOf("recent-tracks", "recent", "for-you", "made-for-you"), ordered.map { it.id })
    }
}
