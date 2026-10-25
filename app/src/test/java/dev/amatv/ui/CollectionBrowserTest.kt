package dev.amatv.ui

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CollectionBrowserTest {
    private fun collection(id: String) = Track(
        id, id, "Curator", "", 0, emptyList(), emptyList(), catalogKind = CatalogKind.Playlist,
    )

    @Test fun lateResultCannotReplaceNewSelectionOrReopenDismissedPage() = runBlocking {
        val first = CompletableDeferred<List<Track>>()
        val second = CompletableDeferred<List<Track>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope) { item ->
            // Model a non-cooperative transport completing after cancellation.
            withContext(NonCancellable) { if (item.id == "first") first.await() else second.await() }
        }
        browser.open(collection("first"))
        assertTrue(browser.state.value.loading)
        browser.open(collection("second"))
        first.complete(listOf(collection("stale")))
        assertEquals("second", browser.state.value.collection?.id)
        browser.close()
        second.complete(listOf(collection("also stale")))
        assertNull(browser.state.value.collection)
        assertTrue(browser.state.value.tracks.isEmpty())
        scope.cancel()
    }

    @Test fun failedLoadCanRetryAndNeverBecomesAnEmptySuccessfulPage() {
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CollectionBrowser(scope) {
            if (++calls == 1) error("Try again") else listOf(collection("loaded"))
        }
        val item = collection("playlist")
        browser.open(item)
        assertEquals("Try again", browser.state.value.error)
        assertFalse(browser.state.value.loading)
        browser.open(item)
        assertNull(browser.state.value.error)
        assertEquals("loaded", browser.state.value.tracks.single().id)
        scope.cancel()
    }
}
