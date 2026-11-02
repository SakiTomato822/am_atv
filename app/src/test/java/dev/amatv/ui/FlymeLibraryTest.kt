package dev.amatv.ui

import dev.amatv.integration.AudioSpectrum
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.text.Collator
import java.util.Locale

class FlymeLibraryTest {
    private fun item(id: String, title: String = id, artist: String = "", added: Long? = null) =
        Track(id, title, artist, "", 0, emptyList(), emptyList(), dateAddedMs = added)

    private fun savedAlbum(catalogId: String? = "1440") = Track(
        "apple:$catalogId", "Album", "Artist", "", 0, emptyList(), emptyList(),
        catalogId = catalogId, catalogKind = CatalogKind.Album,
        resourceId = "l.abc", resourceType = "library-albums",
    )

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

    @Test fun savedAlbumExpandsToCatalogReleaseAndBack() {
        val requested = mutableListOf<Track>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope) { requested += it; listOf(item(it.resourceType.orEmpty())) }
        browser.open(savedAlbum())
        assertTrue(browser.state.value.canShowFullAlbum)
        browser.setFullAlbum(true)
        val state = browser.state.value
        assertTrue(state.fullAlbum)
        assertEquals("albums", requested.last().resourceType)
        assertNull(requested.last().resourceId)
        assertEquals("albums", state.playbackCollection?.resourceType)
        browser.setFullAlbum(false)
        assertEquals("library-albums", requested.last().resourceType)
        scope.cancel()
    }

    @Test fun unmatchedOrCatalogAlbumsOfferNoCompleteAlbum() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope) { emptyList() }
        browser.open(savedAlbum(catalogId = null))
        assertFalse(browser.state.value.canShowFullAlbum)
        browser.setFullAlbum(true)
        assertFalse(browser.state.value.fullAlbum)
        scope.cancel()
    }

    @Test fun pulseMovesOnlyWithoutMeasurement() {
        val envelope = AudioBandEnvelope()
        var t = 0.0
        repeat(20) { t += 0.033; envelope.pulse(t, 33f) }
        assertFalse(envelope.isFlat)
        // A real capture of silence still settles flat; the pulse never stands in for it.
        repeat(60) { envelope.advance(AudioSpectrum(available = true, captured = true), true, 33f) }
        assertTrue(envelope.isFlat)
    }
}
