package dev.amatv.core

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentStoreTest {

    private class FakeContentOperations(
        override var isCatalogConfigured: Boolean = true,
    ) : ContentOperations {
        val savedShelvesMap = mutableMapOf<String, List<ContentShelf>>()
        var savedPlaylistIndexList: List<Track>? = null
        var recommendationShelvesCallCount = 0
        var chartsCallCount = 0
        var clearDetailPagesCalled = false
        var savedSnapshotCalls = mutableListOf<Triple<String, String, List<ContentShelf>>>()

        var recommendationShelvesHandler: suspend () -> List<ContentShelf> = { emptyList() }
        var recentlyPlayedTracksHandler: suspend () -> List<Track> = { emptyList() }
        var recentlyPlayedHandler: suspend () -> List<Track> = { emptyList() }
        var chartsHandler: suspend (String) -> List<Track> = { emptyList() }
        var liveRadioHandler: suspend () -> List<Track> = { emptyList() }
        var recentlyAddedHandler: suspend () -> List<Track> = { emptyList() }
        var libraryArtistsPageHandler: suspend (String?) -> LibraryPage = { page ->
            LibraryPage(emptyList(), null, page ?: "artists-0")
        }
        var libraryAlbumsPageHandler: suspend (String?) -> LibraryPage = { page ->
            LibraryPage(emptyList(), null, page ?: "albums-0")
        }
        var libraryPlaylistsPageHandler: suspend (String?) -> LibraryPage = { page ->
            LibraryPage(emptyList(), null, page ?: "playlists-0")
        }
        var librarySongsPageHandler: suspend (String?) -> LibraryPage = { page ->
            LibraryPage(emptyList(), null, page ?: "songs-0")
        }
        var searchHandler: suspend (String) -> List<Track> = { emptyList() }

        override suspend fun savedShelves(name: String, languageTag: String): List<ContentShelf>? =
            savedShelvesMap["$name:$languageTag"] ?: savedShelvesMap[name]

        override suspend fun saveShelves(name: String, languageTag: String, shelves: List<ContentShelf>) {
            savedSnapshotCalls.add(Triple(name, languageTag, shelves))
            savedShelvesMap["$name:$languageTag"] = shelves
        }

        override suspend fun savedPlaylistIndex(): List<Track>? = savedPlaylistIndexList

        override suspend fun recommendationShelves(): List<ContentShelf> {
            recommendationShelvesCallCount++
            return recommendationShelvesHandler()
        }

        override suspend fun recentlyPlayedTracks(): List<Track> = recentlyPlayedTracksHandler()

        override suspend fun recentlyPlayed(): List<Track> = recentlyPlayedHandler()

        override suspend fun charts(types: String): List<Track> {
            chartsCallCount++
            return chartsHandler(types)
        }

        override suspend fun liveRadio(): List<Track> = liveRadioHandler()

        override suspend fun recentlyAdded(): List<Track> = recentlyAddedHandler()

        override suspend fun libraryArtistsPage(nextPage: String?): LibraryPage =
            libraryArtistsPageHandler(nextPage)

        override suspend fun libraryAlbumsPage(nextPage: String?): LibraryPage =
            libraryAlbumsPageHandler(nextPage)

        override suspend fun libraryPlaylistsPage(nextPage: String?): LibraryPage =
            libraryPlaylistsPageHandler(nextPage)

        override suspend fun librarySongsPage(nextPage: String?): LibraryPage =
            librarySongsPageHandler(nextPage)

        override suspend fun search(term: String): List<Track> = searchHandler(term)

        override fun clearDetailPages(dropSnapshots: Boolean) {
            clearDetailPagesCalled = true
        }
    }

    private fun testTrack(id: String, title: String = "Track $id"): Track = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogId = id,
    )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun switchingDestinationsDoesNotReloadExistingContent() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                recommendationShelvesHandler = {
                    listOf(ContentShelf("rec-1", "For You", listOf(testTrack("1"))))
                }
                chartsHandler = { type -> listOf(testTrack("chart-$type")) }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(1, ops.recommendationShelvesCallCount)
            assertTrue(store.state.value.shelvesFor(Destination.Home).isNotEmpty())

            store.loadDestination(Destination.Browse, MusicConnectionState.SignedIn, 0, "en-US")
            assertTrue(store.state.value.shelvesFor(Destination.Browse).isNotEmpty())

            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(1, ops.recommendationShelvesCallCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun librarySnapshotShownFirstThenReplacedByNetwork() = runBlocking {
        val scope = testScope()
        try {
            val networkGate = CompletableDeferred<Unit>()
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Library"] = listOf(
                    ContentShelf("snap-1", "Saved Snapshot", listOf(testTrack("old-1"))),
                )
                recentlyAddedHandler = {
                    networkGate.await()
                    listOf(testTrack("new-added"))
                }
                libraryArtistsPageHandler = {
                    networkGate.await()
                    LibraryPage(listOf(testTrack("artist-1")), null, "artists-0")
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")

            assertTrue(Destination.Library in store.state.value.savedShelfDestinations)
            assertEquals("snap-1", store.state.value.shelvesFor(Destination.Library).first().id)
            assertTrue(store.state.value.isLoading(Destination.Library))

            networkGate.complete(Unit)

            assertFalse(Destination.Library in store.state.value.savedShelfDestinations)
            assertFalse(store.state.value.isLoading(Destination.Library))
            assertTrue(store.state.value.shelvesFor(Destination.Library).any { it.id == "lib-recently-added" })
            assertTrue(store.state.value.shelvesFor(Destination.Library).none { it.id == "snap-1" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun networkFailureWithSnapshotOnScreenRetainsSnapshotAndEmitsNullMessage() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Home"] = listOf(
                    ContentShelf("home-snap", "Home Snapshot", listOf(testTrack("snap-song"))),
                )
                recommendationShelvesHandler = { error("Net fail rec") }
                recentlyPlayedTracksHandler = { error("Net fail recent tracks") }
                recentlyPlayedHandler = { error("Net fail recent") }
                chartsHandler = { error("Net fail charts") }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)
            val messages = mutableListOf<String?>()
            val collectJob = scope.launch(Dispatchers.Unconfined) {
                store.messages.collect { messages.add(it) }
            }

            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")

            assertEquals(1, store.state.value.shelvesFor(Destination.Home).size)
            assertEquals("home-snap", store.state.value.shelvesFor(Destination.Home).first().id)
            assertTrue(Destination.Home in store.state.value.savedShelfDestinations)
            assertFalse(store.state.value.isLoading(Destination.Home))
            assertEquals(listOf<String?>(null), messages)

            collectJob.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rapidConsecutiveSearchesOnlyRetainLastResult() = runBlocking {
        val scope = testScope()
        try {
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()
            val firstTrack = testTrack("q1-track", "First Result")
            val secondTrack = testTrack("q2-track", "Second Result")
            val ops = FakeContentOperations().apply {
                searchHandler = { term ->
                    when (term) {
                        "query1" -> {
                            firstGate.await()
                            listOf(firstTrack)
                        }
                        "query2" -> {
                            secondGate.await()
                            listOf(secondTrack)
                        }
                        else -> emptyList()
                    }
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.search("query1")
            assertTrue(store.state.value.searchLoading)

            store.search("query2")
            assertTrue(store.state.value.searchLoading)

            secondGate.complete(Unit)

            assertEquals(listOf(secondTrack), store.state.value.searchResults)
            assertFalse(store.state.value.searchLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onSignedOutClearsShelvesAndRecentTracksWithoutResettingPaginationOrSearch() = runBlocking {
        val scope = testScope()
        try {
            val searchTrack = testTrack("search-hit")
            val recentTrack = testTrack("recent-hit")
            val ops = FakeContentOperations().apply {
                recentlyPlayedTracksHandler = { listOf(recentTrack) }
                libraryAlbumsPageHandler = {
                    LibraryPage(listOf(testTrack("alb-1")), nextPage = "albums-next-1", requestedPage = "albums-0")
                }
                searchHandler = { listOf(searchTrack) }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            store.search("taylor")

            assertEquals(listOf(recentTrack), store.state.value.recentTracks)
            assertEquals("albums-next-1", store.state.value.libraryNextPages["lib-albums"])
            assertEquals(listOf(searchTrack), store.state.value.searchResults)
            val generationBefore = store.state.value.reloadGeneration

            store.onSignedOut()

            assertTrue(store.state.value.shelvesByDestination.isEmpty())
            assertTrue(store.state.value.recentTracks.isEmpty())
            assertEquals("albums-next-1", store.state.value.libraryNextPages["lib-albums"])
            assertEquals(listOf(searchTrack), store.state.value.searchResults)
            assertEquals(generationBefore + 1, store.state.value.reloadGeneration)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refreshAllClearsDetailPagesResetsPaginationAndIncrementsGeneration() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                libraryAlbumsPageHandler = {
                    LibraryPage(listOf(testTrack("alb-1")), nextPage = "albums-next-1", requestedPage = "albums-0")
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)
            val messages = mutableListOf<String?>()
            val collectJob = scope.launch(Dispatchers.Unconfined) {
                store.messages.collect { messages.add(it) }
            }

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals("albums-next-1", store.state.value.libraryNextPages["lib-albums"])
            ops.clearDetailPagesCalled = false
            messages.clear()
            val generationBefore = store.state.value.reloadGeneration

            store.refreshAll(Destination.Browse)

            assertTrue(ops.clearDetailPagesCalled)
            assertTrue(store.state.value.libraryNextPages.isEmpty())
            assertTrue(store.state.value.shelvesByDestination.isEmpty())
            assertEquals(setOf(Destination.Browse), store.state.value.loadingDestinations)
            assertEquals(generationBefore + 1, store.state.value.reloadGeneration)
            assertEquals(listOf<String?>(null), messages)

            collectJob.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onSignedInResetsPaginationAndLoadsHomeWithoutClearingDetailPages() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                libraryAlbumsPageHandler = {
                    LibraryPage(listOf(testTrack("alb-1")), nextPage = "albums-next-1", requestedPage = "albums-0")
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals("albums-next-1", store.state.value.libraryNextPages["lib-albums"])
            ops.clearDetailPagesCalled = false
            val generationBefore = store.state.value.reloadGeneration

            store.onSignedIn()

            assertFalse(ops.clearDetailPagesCalled)
            assertTrue(store.state.value.libraryNextPages.isEmpty())
            assertTrue(store.state.value.shelvesByDestination.isEmpty())
            assertEquals(setOf(Destination.Home), store.state.value.loadingDestinations)
            assertEquals(generationBefore + 1, store.state.value.reloadGeneration)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reloadGenerationIncrementsOncePerClearFunction() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations()
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            assertEquals(0, store.state.value.reloadGeneration)
            store.refreshAll(Destination.Home)
            assertEquals(1, store.state.value.reloadGeneration)
            store.onSignedIn()
            assertEquals(2, store.state.value.reloadGeneration)
            store.onSignedOut()
            assertEquals(3, store.state.value.reloadGeneration)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun duplicateLoadDestinationWithSameKeyDeduplicatesNetworkRequest() = runBlocking {
        val scope = testScope()
        try {
            val homeGate = CompletableDeferred<Unit>()
            val ops = FakeContentOperations().apply {
                recommendationShelvesHandler = {
                    homeGate.await()
                    listOf(ContentShelf("rec-1", "For You", listOf(testTrack("1"))))
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")

            homeGate.complete(Unit)

            assertEquals(1, ops.recommendationShelvesCallCount)
            assertEquals(1, store.state.value.shelvesFor(Destination.Home).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun libraryPaginationDetectsRepeatedPageAndMarksShelfFailed() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                libraryAlbumsPageHandler = { nextPage ->
                    when (nextPage) {
                        null -> LibraryPage(
                            items = listOf(testTrack("alb-1")),
                            nextPage = "page-2",
                            requestedPage = "page-1",
                        )
                        "page-2" -> LibraryPage(
                            items = listOf(testTrack("alb-2")),
                            nextPage = "page-1",
                            requestedPage = "page-2",
                        )
                        else -> LibraryPage(emptyList(), null, nextPage)
                    }
                }
            }
            val store = ContentStore(ops, scope, Dispatchers.Unconfined)

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals("page-2", store.state.value.libraryNextPages["lib-albums"])
            assertFalse("lib-albums" in store.state.value.libraryFailedShelves)

            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)

            assertTrue("lib-albums" in store.state.value.libraryFailedShelves)
            assertFalse("lib-albums" in store.state.value.libraryLoadingShelves)
        } finally {
            scope.cancel()
        }
    }
}
