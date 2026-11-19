package dev.amatv.flyme

import dev.amatv.model.Track
import org.junit.Assert.*
import org.junit.Test
import java.text.Collator
import java.util.Locale

class LibrarySortTest {
    private fun item(id: String, title: String = id, artist: String = "", added: Long? = null) =
        Track(id, title, artist, "", 0, emptyList(), emptyList(), dateAddedMs = added)

    private val collator = Collator.getInstance(Locale.ROOT)

    @Test fun dateAddedOpensNewestFirstAndReverses() {
        val items = listOf(item("a", added = 2), item("b", added = 3), item("c", added = 1), item("d"))
        assertEquals(listOf("b", "a", "c", "d"), sortLibrary(items, LibrarySort.DateAdded, true, collator).map { it.id })
        assertEquals(listOf("d", "c", "a", "b"), sortLibrary(items, LibrarySort.DateAdded, false, collator).map { it.id })
    }

    @Test fun everySortCanBeReversed() {
        val items = listOf(item("1", "B", "Y"), item("2", "A", "Z"), item("3", "C", "X"))
        assertEquals(listOf("2", "1", "3"), sortLibrary(items, LibrarySort.Name, false, collator).map { it.id })
        assertEquals(listOf("3", "1", "2"), sortLibrary(items, LibrarySort.Name, true, collator).map { it.id })
        assertEquals(listOf("2", "1", "3"), sortLibrary(items, LibrarySort.Artist, true, collator).map { it.id })
        assertEquals(listOf("3", "2", "1"), sortLibrary(items, LibrarySort.Default, true, collator).map { it.id })
    }

    @Test fun albumsAndSongsDefaultToDateAdded() {
        assertEquals(LibrarySort.DateAdded, LibraryCategory.Albums.sorts().first())
        assertEquals(LibrarySort.DateAdded, LibraryCategory.Songs.sorts().first())
        assertEquals(LibrarySort.Default, LibraryCategory.Playlists.sorts().first())
    }

}
