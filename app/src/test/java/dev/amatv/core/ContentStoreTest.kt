package dev.amatv.core

import dev.amatv.integration.BROWSE_PERSONAL_RELEASES_ID
import dev.amatv.integration.LibraryCollection
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.flyme.LibrarySort
import dev.amatv.flyme.sortLibrary
import java.text.Collator
import java.util.Locale
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
        var libraryRequestCount = 0
        var clearDetailPagesCalled = false
        var savedSnapshotCalls = mutableListOf<Triple<String, String, List<ContentShelf>>>()
        var beforeSaveShelves: suspend (String) -> Unit = {}

        var recommendationShelvesHandler: suspend () -> List<ContentShelf> = { emptyList() }
        var personalizedNewReleasesHandler: suspend () -> ContentShelf? = { null }
        var editorialBrowseShelvesHandler: suspend (String) -> List<ContentShelf> = { emptyList() }
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
            beforeSaveShelves(name)
            savedSnapshotCalls.add(Triple(name, languageTag, shelves))
            savedShelvesMap["$name:$languageTag"] = shelves
        }

        override suspend fun savedPlaylistIndex(): List<Track>? = savedPlaylistIndexList

        override suspend fun recommendationShelves(): List<ContentShelf> {
            recommendationShelvesCallCount++
            return recommendationShelvesHandler()
        }
        override suspend fun personalizedNewReleases(): ContentShelf? = personalizedNewReleasesHandler()
        override suspend fun editorialBrowseShelves(languageTag: String): List<ContentShelf> =
            editorialBrowseShelvesHandler(languageTag)

        override suspend fun recentlyPlayedTracks(): List<Track> {
            libraryRequestCount++
            return recentlyPlayedTracksHandler()
        }

        override suspend fun recentlyPlayed(): List<Track> = recentlyPlayedHandler()

        override suspend fun charts(types: String): List<Track> {
            chartsCallCount++
            return chartsHandler(types)
        }

        override suspend fun liveRadio(): List<Track> = liveRadioHandler()

        override suspend fun recentlyAdded(): List<Track> {
            libraryRequestCount++
            return recentlyAddedHandler()
        }

        override suspend fun libraryArtistsPage(nextPage: String?): LibraryPage {
            libraryRequestCount++
            return libraryArtistsPageHandler(nextPage)
        }

        override suspend fun libraryAlbumsPage(nextPage: String?): LibraryPage {
            libraryRequestCount++
            return libraryAlbumsPageHandler(nextPage)
        }

        override suspend fun libraryPlaylistsPage(nextPage: String?): LibraryPage {
            libraryRequestCount++
            return libraryPlaylistsPageHandler(nextPage)
        }

        override suspend fun librarySongsPage(nextPage: String?): LibraryPage {
            libraryRequestCount++
            return librarySongsPageHandler(nextPage)
        }

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

    private fun libraryAlbum(id: String, title: String, dateAddedMs: Long) = testTrack(id, title).copy(
        catalogKind = CatalogKind.Album,
        resourceType = LibraryCollection.Albums.resourceType,
        resourceId = id,
        dateAddedMs = dateAddedMs,
    )

    private fun testStore(ops: ContentOperations, scope: CoroutineScope, elapsedRealtimeMs: () -> Long = { 0L }) =
        ContentStore(ops, scope, Dispatchers.Unconfined, elapsedRealtimeMs)

    @Test
    fun signedInStartupRefreshesLibraryOnceAfterHomeWithoutOpeningLibrary() = runBlocking {
        val scope = testScope()
        try {
            val homeGate = CompletableDeferred<Unit>()
            val home = ContentShelf("for-you", "For You", listOf(testTrack("home")))
            val latest = libraryAlbum("latest", "petal", 4L)
            val ops = FakeContentOperations().apply {
                recommendationShelvesHandler = {
                    homeGate.await()
                    listOf(home)
                }
                recentlyAddedHandler = { listOf(latest) }
            }
            val store = testStore(ops, scope)

            store.startSignedInContent(MusicConnectionState.SignedIn, "en-US")
            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(1, ops.recommendationShelvesCallCount)
            assertEquals(1, ops.libraryRequestCount) // Only Home's recently played request.
            assertTrue(store.state.value.isLoading(Destination.Home))

            homeGate.complete(Unit)
            assertEquals(listOf(home), store.state.value.shelvesFor(Destination.Home))
            assertEquals(7, ops.libraryRequestCount)
            assertEquals(listOf(latest), store.state.value.shelvesFor(Destination.Library)
                .single { it.id == "lib-albums" }.items)
            assertFalse(store.state.value.isLoading(Destination.Library))

            store.startSignedInContent(MusicConnectionState.SignedIn, "en-US")
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(7, ops.libraryRequestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun openingLibraryReusesQueuedAndRunningStartupRefreshAcrossNavigation() = runBlocking {
        val scope = testScope()
        try {
            val homeGate = CompletableDeferred<Unit>()
            val libraryGate = CompletableDeferred<Unit>()
            val ops = FakeContentOperations().apply {
                recommendationShelvesHandler = {
                    homeGate.await()
                    listOf(ContentShelf("for-you", "For You", listOf(testTrack("home"))))
                }
                recentlyAddedHandler = {
                    libraryGate.await()
                    listOf(libraryAlbum("latest", "petal", 4L))
                }
            }
            val store = testStore(ops, scope)
            fun open(destination: Destination) = store.loadDestination(destination, MusicConnectionState.SignedIn, 0, "en-US")

            store.startSignedInContent(MusicConnectionState.SignedIn, "en-US")
            open(Destination.Library)
            assertEquals(1, ops.libraryRequestCount)

            homeGate.complete(Unit)
            assertTrue(store.state.value.isLoading(Destination.Library))
            assertEquals(7, ops.libraryRequestCount)

            open(Destination.Browse)
            open(Destination.Library)
            assertTrue(store.state.value.isLoading(Destination.Library))
            assertEquals(7, ops.libraryRequestCount)

            libraryGate.complete(Unit)
            assertFalse(store.state.value.isLoading(Destination.Library))
            assertTrue(store.state.value.shelvesFor(Destination.Library).any { it.id == "lib-albums" })
            assertEquals(7, ops.libraryRequestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun librarySnapshotsAreSavedInPublicationOrderWithoutBlockingRefresh() = runBlocking {
        val scope = testScope()
        try {
            val pageGate = CompletableDeferred<Unit>()
            val snapshotGate = CompletableDeferred<Unit>()
            val old = libraryAlbum("old", "Aardvark", 1L)
            val latest = libraryAlbum("latest", "petal", 4L)
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Library"] = listOf(ContentShelf("lib-albums", "Albums", listOf(old)))
                recentlyAddedHandler = { listOf(latest) }
                libraryAlbumsPageHandler = {
                    pageGate.await()
                    LibraryPage(listOf(old), null, "albums-first")
                }
                beforeSaveShelves = { snapshotGate.await() }
            }
            val store = testStore(ops, scope)

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            val merged = store.state.value.shelvesFor(Destination.Library)
            assertEquals(listOf(latest, old), merged.single().items)
            assertTrue(store.state.value.isLoading(Destination.Library))

            pageGate.complete(Unit)
            val refreshed = store.state.value.shelvesFor(Destination.Library)
            assertFalse(store.state.value.isLoading(Destination.Library))
            assertTrue(ops.savedSnapshotCalls.isEmpty())

            snapshotGate.complete(Unit)
            assertEquals(listOf(Triple("Library", "en-US", merged), Triple("Library", "en-US", refreshed)),
                ops.savedSnapshotCalls)
            assertEquals(refreshed, ops.savedShelvesMap["Library:en-US"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun signedOutStartupDoesNotRefreshLibrary() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations()
            val store = testStore(ops, scope)

            store.startSignedInContent(MusicConnectionState.ReadyToSignIn, "en-US")
            store.loadDestination(Destination.Library, MusicConnectionState.ReadyToSignIn, 0, "en-US")

            assertEquals(0, ops.libraryRequestCount)
            assertEquals(0, ops.recommendationShelvesCallCount)
            assertFalse(store.state.value.isLoading(Destination.Library))
            assertTrue(ops.savedSnapshotCalls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

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
            val store = testStore(ops, scope)

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
    fun browseShowsSnapshotThenPersonalizedAndEditorialShelvesInOrder() = runBlocking {
        val scope = testScope()
        try {
            val editorialGate = CompletableDeferred<Unit>()
            val saved = ContentShelf("saved", "Saved", listOf(testTrack("saved")))
            val personal = ContentShelf(BROWSE_PERSONAL_RELEASES_ID, "For You", listOf(testTrack("personal")))
            val editorial = listOf(
                ContentShelf("new", "最新專輯", listOf(testTrack("new"))),
                ContentShelf("featured", "精選歌單", listOf(testTrack("featured"))),
            )
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Browse:zh-TW"] = listOf(saved)
                personalizedNewReleasesHandler = { personal }
                editorialBrowseShelvesHandler = { language ->
                    assertEquals("zh-TW", language)
                    editorialGate.await()
                    editorial
                }
            }
            val store = testStore(ops, scope)
            store.loadDestination(Destination.Browse, MusicConnectionState.SignedIn, 0, "zh-TW")
            assertEquals(listOf(saved), store.state.value.shelvesFor(Destination.Browse))
            assertTrue(store.state.value.isLoading(Destination.Browse))

            editorialGate.complete(Unit)
            assertEquals(listOf(personal) + editorial, store.state.value.shelvesFor(Destination.Browse))
            assertEquals(listOf(personal) + editorial, ops.savedShelvesMap["Browse:zh-TW"])
            assertEquals(0, ops.chartsCallCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun browseKeepsSnapshotWhenOnlyPersonalizationRefreshSucceeds() = runBlocking {
        val scope = testScope()
        try {
            val saved = ContentShelf("saved", "Saved", listOf(testTrack("saved")))
            val personal = ContentShelf(BROWSE_PERSONAL_RELEASES_ID, "For You", listOf(testTrack("personal")))
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Browse:en-US"] = listOf(saved)
                personalizedNewReleasesHandler = { personal }
            }
            val store = testStore(ops, scope)
            store.loadDestination(Destination.Browse, MusicConnectionState.SignedIn, 0, "en-US")

            assertEquals(listOf(saved), store.state.value.shelvesFor(Destination.Browse))
            assertTrue(Destination.Browse in store.state.value.savedShelfDestinations)
            assertFalse(store.state.value.isLoading(Destination.Browse))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun signedOutBrowseFallsBackWhenEditorialIsEmpty() = runBlocking {
        assertBrowseFallback(MusicConnectionState.ReadyToSignIn) { emptyList() }
    }

    @Test
    fun signedInBrowseFallsBackWhenEditorialFails() = runBlocking {
        assertBrowseFallback(MusicConnectionState.SignedIn) { error("Editorial request failed") }
    }

    private fun assertBrowseFallback(
        connectionState: MusicConnectionState,
        loadEditorial: suspend (String) -> List<ContentShelf>,
    ) {
        val scope = testScope()
        try {
            var personalCalls = 0
            val ops = FakeContentOperations().apply {
                editorialBrowseShelvesHandler = loadEditorial
                personalizedNewReleasesHandler = {
                    personalCalls++
                    ContentShelf("personal", "For You", listOf(testTrack("personal")))
                }
                chartsHandler = { type -> listOf(testTrack(type)) }
                liveRadioHandler = { listOf(testTrack("radio")) }
            }
            val store = testStore(ops, scope)
            store.loadDestination(Destination.Browse, connectionState, 0, "en-US")
            val signedIn = connectionState == MusicConnectionState.SignedIn
            val chartIds = listOf("browse-songs", "browse-albums", "browse-playlists", "browse-radio")
            val expectedIds = if (signedIn) listOf("personal") + chartIds else chartIds
            assertEquals(expectedIds, store.state.value.shelvesFor(Destination.Browse).map { it.id })
            assertEquals(if (signedIn) 1 else 0, personalCalls)
            assertEquals(3, ops.chartsCallCount)
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
            val store = testStore(ops, scope)

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
    fun recentAlbumsReplaceTheSnapshotHeadBeforeLibraryFirstPagesFinish() = runBlocking {
        val scope = testScope()
        try {
            val recentGate = CompletableDeferred<Unit>()
            val firstPageGate = CompletableDeferred<Unit>()
            val old = libraryAlbum("old", "Aardvark", 1L)
            val latest = libraryAlbum("latest", "petal", 4L)
            val other = libraryAlbum("other", "Bee", 3L)
            val continuation = libraryAlbum("continuation", "Aster", 2L)
            val requests = mutableListOf<String?>()
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Library"] = listOf(ContentShelf("lib-albums", "Albums", listOf(old)))
                recentlyAddedHandler = {
                    recentGate.await()
                    listOf(other, testTrack("playlist").copy(resourceType = "library-playlists"), latest)
                }
                libraryAlbumsPageHandler = { page ->
                    requests += page
                    when (page) {
                        null -> {
                            firstPageGate.await()
                            LibraryPage(listOf(old, other), "albums-next", "albums-first")
                        }
                        "albums-next" -> LibraryPage(listOf(continuation, latest.copy(id = "relinked")), null, page)
                        else -> error("Unexpected page $page")
                    }
                }
            }
            val store = testStore(ops, scope)
            val collator = Collator.getInstance(Locale.US)
            fun sortedAlbums(sort: LibrarySort) = sortLibrary(
                store.state.value.shelvesFor(Destination.Library).single { it.id == "lib-albums" }.items,
                sort, sort.descendingFirst, collator,
            )

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(listOf(old), sortedAlbums(LibrarySort.DateAdded))
            recentGate.complete(Unit)

            assertTrue(store.state.value.isLoading(Destination.Library))
            assertEquals(listOf(latest, other, old), sortedAlbums(LibrarySort.DateAdded))
            assertEquals(listOf<String?>(null), requests)

            firstPageGate.complete(Unit)
            assertEquals(listOf(latest, other, old), sortedAlbums(LibrarySort.DateAdded))
            assertEquals("albums-next", store.state.value.libraryNextPages["lib-albums"])
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)

            assertEquals(listOf(null, "albums-next"), requests)
            assertEquals(listOf(latest, other, continuation, old), sortedAlbums(LibrarySort.DateAdded))
            assertEquals(listOf(old, continuation, other, latest), sortedAlbums(LibrarySort.Name))
            assertEquals(listOf(old, continuation, other, latest), sortedAlbums(LibrarySort.Artist))
            assertNull(store.state.value.libraryNextPages["lib-albums"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recentAlbumsSurviveAnAlbumFirstPageFailureAndRetryWithoutASnapshot() = runBlocking {
        val scope = testScope()
        try {
            val latest = libraryAlbum("latest", "petal", 4L)
            val old = libraryAlbum("old", "Aardvark", 1L)
            val ops = FakeContentOperations().apply {
                recentlyAddedHandler = { listOf(latest) }
                libraryAlbumsPageHandler = { error("Albums unavailable") }
            }
            val store = testStore(ops, scope)
            fun albumItems() = store.state.value.shelvesFor(Destination.Library).single { it.id == "lib-albums" }.items

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertEquals(listOf(latest), albumItems())
            assertTrue("lib-albums" in store.state.value.libraryFailedShelves)

            ops.libraryAlbumsPageHandler = { page ->
                assertNull(page)
                LibraryPage(listOf(old, latest), null, "albums-first")
            }
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)

            assertEquals(listOf(latest, old), albumItems())
            assertFalse("lib-albums" in store.state.value.libraryFailedShelves)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reopeningLibraryWithinTenMinutesKeepsLoadedPagesAndCursorWithoutRequests() = runBlocking {
        val scope = testScope()
        try {
            var now = 0L
            val ops = FakeContentOperations().apply {
                libraryAlbumsPageHandler = { page ->
                    when (page) {
                        null -> LibraryPage(listOf(testTrack("album-1")), "albums-2", "albums-1")
                        "albums-2" -> LibraryPage(listOf(testTrack("album-2")), "albums-3", page)
                        else -> error("Unexpected page $page")
                    }
                }
            }
            val store = testStore(ops, scope) { now }
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)
            val loaded = store.state.value
            assertEquals(7, ops.libraryRequestCount)
            assertEquals(2, loaded.shelvesFor(Destination.Library).single { it.id == "lib-albums" }.items.size)
            assertEquals("albums-3", loaded.libraryNextPages["lib-albums"])

            store.loadDestination(Destination.Search, MusicConnectionState.SignedIn, 0, "en-US")
            now = 10 * 60 * 1000L - 1
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")

            assertEquals(7, ops.libraryRequestCount)
            assertEquals(loaded, store.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reopeningLibraryAfterTenMinutesRefreshesFirstPagesAndReplacesTheCursor() = runBlocking {
        val scope = testScope()
        try {
            var now = 0L
            val requests = mutableListOf<String?>()
            val firstPlaylistPage = LibraryCollection.Playlists.request.firstPage
            val ops = FakeContentOperations().apply {
                libraryPlaylistsPageHandler = { page ->
                    requests += page
                    LibraryPage(emptyList(), null, checkNotNull(page))
                }
                libraryAlbumsPageHandler = { page ->
                    when (page) {
                        null -> LibraryPage(listOf(testTrack("old-album")), "old-next", "albums-0")
                        "old-next" -> LibraryPage(listOf(testTrack("old-second")), null, page)
                        else -> error("Unexpected page $page")
                    }
                }
            }
            val store = testStore(ops, scope) { now }
            fun openLibrary() = store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            fun albums() = store.state.value.shelvesFor(Destination.Library).first { it.id == "lib-albums" }.items

            openLibrary()
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)
            assertEquals(listOf("old-album", "old-second"), albums().map { it.id })

            val refresh = CompletableDeferred<Unit>()
            ops.libraryAlbumsPageHandler = { page ->
                when (page) {
                    null -> {
                        refresh.await()
                        LibraryPage(listOf(testTrack("new-album")), "new-next", "albums-0")
                    }
                    "new-next" -> LibraryPage(listOf(testTrack("new-second")), null, page)
                    else -> error("Unexpected page $page")
                }
            }
            store.loadDestination(Destination.Home, MusicConnectionState.SignedIn, 0, "en-US")
            now = 10 * 60 * 1000L + 1
            openLibrary()
            assertTrue(store.state.value.isLoading(Destination.Library))
            assertEquals(listOf("old-album", "old-second"), albums().map { it.id })
            refresh.complete(Unit)

            assertEquals(listOf("new-album"), albums().map { it.id })
            assertEquals(listOf(firstPlaylistPage, firstPlaylistPage), requests)
            assertEquals("new-next", store.state.value.libraryNextPages["lib-albums"])
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)
            assertEquals(listOf("new-album", "new-second"), albums().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun manualRefreshReloadsLibraryImmediatelyAfterSuccess() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations()
            val store = testStore(ops, scope)
            fun openLibrary() = store.loadDestination(
                Destination.Library, MusicConnectionState.SignedIn, store.state.value.reloadGeneration, "en-US",
            )

            openLibrary()
            assertEquals(6, ops.libraryRequestCount)

            store.refreshAll(Destination.Library)
            openLibrary()

            assertEquals(12, ops.libraryRequestCount)
            assertFalse(store.state.value.isLoading(Destination.Library))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun incompleteLibraryRefreshCanBeRetriedOnTheNextVisit() = runBlocking {
        val scope = testScope()
        try {
            val ops = FakeContentOperations().apply {
                libraryAlbumsPageHandler = { error("Albums unavailable") }
            }
            val store = testStore(ops, scope)
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertTrue("lib-albums" in store.state.value.libraryFailedShelves)

            ops.libraryAlbumsPageHandler = { LibraryPage(listOf(testTrack("new-album")), null, "albums-1") }
            store.loadDestination(Destination.Search, MusicConnectionState.SignedIn, 0, "en-US")
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")

            assertEquals(12, ops.libraryRequestCount)
            assertTrue(store.state.value.libraryFailedShelves.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedLibraryCategoriesRetainTheirSnapshotsAndFirstPageRetryReplacesOldItems() = runBlocking {
        val scope = testScope()
        try {
            val savedCategories = listOf("artists", "albums", "playlists", "songs").map { category ->
                ContentShelf("lib-$category", category, listOf(testTrack("saved-$category")))
            }
            val ops = FakeContentOperations().apply {
                savedShelvesMap["Library"] = savedCategories
                libraryArtistsPageHandler = { error("Artists unavailable") }
                libraryAlbumsPageHandler = { error("Albums unavailable") }
                libraryPlaylistsPageHandler = { error("Playlists unavailable") }
                librarySongsPageHandler = { error("Songs unavailable") }
                recentlyAddedHandler = { listOf(testTrack("new-added")) }
            }
            val store = testStore(ops, scope)
            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")

            val current = store.state.value
            assertEquals(savedCategories, current.shelvesFor(Destination.Library).filter { it in savedCategories })
            assertEquals(savedCategories.map { it.id }.toSet(), current.libraryFailedShelves)
            assertTrue(current.shelvesFor(Destination.Library).any { it.items.single().id == "new-added" })

            ops.libraryAlbumsPageHandler = { page ->
                assertNull(page)
                LibraryPage(listOf(testTrack("new-album")), null, "albums-0")
            }
            store.loadMoreLibraryShelf("lib-albums", MusicConnectionState.SignedIn)
            val updated = store.state.value
            assertFalse("lib-albums" in updated.libraryFailedShelves)
            assertEquals(
                listOf("new-album"),
                updated.shelvesFor(Destination.Library).single { it.id == "lib-albums" }.items.map { it.id },
            )
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
            val store = testStore(ops, scope)
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
            val store = testStore(ops, scope)

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
            val store = testStore(ops, scope)

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
            val store = testStore(ops, scope)
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
            val store = testStore(ops, scope)

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
            val store = testStore(ops, scope)

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
            val store = testStore(ops, scope)

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
            val store = testStore(ops, scope)

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

    @Test
    fun inFlightLibraryLoadCancelledOnSignOutAndNewDestinationDoesNotWriteStaleShelves() = runBlocking {
        val scope = testScope()
        try {
            val libraryGate = CompletableDeferred<Unit>()
            val ops = FakeContentOperations().apply {
                recentlyAddedHandler = {
                    libraryGate.await()
                    listOf(testTrack("stale-lib"))
                }
                chartsHandler = { type -> listOf(testTrack("home-chart-$type")) }
            }
            val store = testStore(ops, scope)

            store.loadDestination(Destination.Library, MusicConnectionState.SignedIn, 0, "en-US")
            assertTrue(store.state.value.isLoading(Destination.Library))

            store.onSignedOut()
            store.loadDestination(
                Destination.Home,
                MusicConnectionState.ReadyToSignIn,
                store.state.value.reloadGeneration,
                "en-US",
            )

            libraryGate.complete(Unit)

            assertTrue(store.state.value.shelvesFor(Destination.Library).isEmpty())
            assertTrue(store.state.value.shelvesFor(Destination.Home).isNotEmpty())
        } finally {
            scope.cancel()
        }
    }
}
