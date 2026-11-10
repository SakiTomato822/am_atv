package dev.amatv.ui

import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
        assertEquals(R.string.browse_tracks_error_detail, browser.state.value.error)
        assertFalse(browser.state.value.loading)
        browser.open(item)
        assertNull(browser.state.value.error)
        assertEquals("loaded", browser.state.value.tracks.single().id)
        scope.cancel()
    }

    @Test fun cachedTracksAreUsableDuringRefreshAndRemainOnFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cached = listOf(collection("cached"))
        val refresh = CompletableDeferred<List<Track>>()
        val browser = CollectionBrowser(scope, peek = { cached }) { refresh.await() }

        browser.open(collection("playlist"))
        assertEquals(cached, browser.state.value.tracks)
        assertFalse(browser.state.value.loading)
        refresh.completeExceptionally(IllegalStateException("offline"))
        assertEquals(cached, browser.state.value.tracks)
        assertNull(browser.state.value.error)
        scope.cancel()
    }

    @Test fun diskSnapshotAppearsBeforeRefreshAndWinsOverPartialNetworkResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = listOf(collection("saved"))
        val request = CompletableDeferred<List<Track>>()
        val progress = MutableStateFlow<List<Track>?>(null)
        val browser = CollectionBrowser(scope, readSaved = { saved }, partials = { progress }) { request.await() }
        browser.open(collection("playlist"))
        progress.value = listOf(collection("incomplete"))
        assertEquals(saved, browser.state.value.tracks)
        request.completeExceptionally(IllegalStateException("offline"))
        assertEquals(saved, browser.state.value.tracks)
        assertNull(browser.state.value.error)
        scope.cancel()
    }

    @Test fun coldPaginationShowsFirstPageWhileWaitingForFullQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val request = CompletableDeferred<List<Track>>()
        val progress = MutableStateFlow<List<Track>?>(null)
        val browser = CollectionBrowser(scope, partials = { progress }) { request.await() }
        browser.open(collection("playlist"))
        val first = listOf(collection("first"))
        progress.value = first
        assertEquals(first, browser.state.value.tracks)
        assertTrue(browser.state.value.partial)
        val full = first + collection("second")
        request.complete(full)
        assertEquals(full, browser.state.value.tracks)
        assertFalse(browser.state.value.partial)
        scope.cancel()
    }
}
