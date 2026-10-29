package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryArtistBrowserTest {
    private fun artist(id: String) = Track(id, id, "", "", 0, emptyList(), emptyList(), catalogKind = CatalogKind.Artist)
    private fun album(id: String) = artist(id).copy(catalogKind = CatalogKind.Album, resourceId = id, resourceType = "library-albums")

    @Test fun staleArtistResultsCannotReplaceNewSelectionOrReopenClosedDetail() = runBlocking {
        val first = CompletableDeferred<LibraryPage>()
        val second = CompletableDeferred<LibraryPage>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = LibraryArtistBrowser(scope) { item, _ ->
            withContext(NonCancellable) { if (item.id == "first") first.await() else second.await() }
        }
        browser.open(artist("first"))
        browser.open(artist("second"))
        first.complete(LibraryPage(listOf(album("stale")), null, "first"))
        assertEquals("second", browser.state.value.artist?.id)
        assertTrue(browser.state.value.albums.isEmpty())
        browser.close()
        second.complete(LibraryPage(listOf(album("also stale")), null, "second"))
        assertNull(browser.state.value.artist)
        scope.cancel()
    }

    @Test fun failedContinuationPreservesAlbumsAndRetriesTheSamePage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var continuationCalls = 0
        val browser = LibraryArtistBrowser(scope) { _, next ->
            if (next == null) LibraryPage(listOf(album("one")), "next", "first")
            else {
                assertEquals("next", next)
                if (++continuationCalls == 1) error("Temporary failure")
                LibraryPage(listOf(album("one"), album("two")), null, "next")
            }
        }
        browser.open(artist("artist"))
        browser.loadMore()
        assertTrue(browser.state.value.failed)
        assertEquals(listOf("one"), browser.state.value.albums.map { it.id })
        browser.retry()
        assertFalse(browser.state.value.failed)
        assertEquals(listOf("one", "two"), browser.state.value.albums.map { it.id })
        assertNull(browser.state.value.nextPage)
        scope.cancel()
    }
}
