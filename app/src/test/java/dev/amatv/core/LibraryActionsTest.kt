package dev.amatv.core

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryActionsTest {

    private class FakeLibraryActionsApi : LibraryActionsApi {
        var isFavoriteResult: Boolean? = false
        var isSongInLibraryResult: Boolean? = false
        var addFavoriteShouldFail = false
        var addToLibraryShouldFail = false
        var isSongInLibraryShouldFail = false

        var isFavoriteCallCount = 0
        var addFavoriteCallCount = 0
        var isSongInLibraryCallCount = 0
        var addToLibraryCallCount = 0

        override suspend fun isFavorite(catalogSongId: String): Boolean? {
            isFavoriteCallCount++
            return isFavoriteResult
        }

        override suspend fun addFavorite(catalogSongId: String) {
            addFavoriteCallCount++
            if (addFavoriteShouldFail) throw RuntimeException("Network error")
        }

        override suspend fun isSongInLibrary(catalogSongId: String): Boolean? {
            isSongInLibraryCallCount++
            if (isSongInLibraryShouldFail) throw RuntimeException("Network timeout")
            return isSongInLibraryResult
        }

        override suspend fun addToLibrary(catalogSongId: String) {
            addToLibraryCallCount++
            if (addToLibraryShouldFail) throw RuntimeException("Network error")
        }
    }

    private fun testTrack(
        id: String = "1001",
        catalogId: String? = "1001",
        resourceType: String? = "songs",
        kind: CatalogKind = CatalogKind.Song,
    ): Track = Track(
        id = id,
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 180_000L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogId = catalogId,
        catalogKind = kind,
        resourceType = resourceType,
    )

    private fun createActions(api: LibraryActionsApi, scope: CoroutineScope): LibraryActions =
        LibraryActions(api, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun librarySongDetectedLocallyWithoutNetworkCall() = runBlocking {
        val api = FakeLibraryActionsApi()
        val actions = createActions(api, this)

        val libraryTrack = testTrack(resourceType = "library-songs")
        actions.onCurrentTrackChanged(libraryTrack, signedIn = true)

        assertEquals("1001", actions.state.value.current.catalogId)
        assertTrue(actions.state.value.current.isInLibrary == true)
        assertEquals(0, api.isSongInLibraryCallCount)
    }

    @Test
    fun catalogSongFetchesStatusFromApi() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isFavoriteResult = true
            isSongInLibraryResult = false
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "2001")
        actions.onCurrentTrackChanged(track, signedIn = true)

        assertEquals("2001", actions.state.value.current.catalogId)
        assertTrue(actions.state.value.current.isFavorite == true)
        assertFalse(actions.state.value.current.isInLibrary == true)
        assertEquals(1, api.isFavoriteCallCount)
        assertEquals(1, api.isSongInLibraryCallCount)
    }

    @Test
    fun notSignedInDoesNotTriggerApi() = runBlocking {
        val api = FakeLibraryActionsApi()
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "3001")
        actions.onCurrentTrackChanged(track, signedIn = false)

        assertNull(actions.state.value.current.isFavorite)
        assertNull(actions.state.value.current.isInLibrary)
        assertEquals(0, api.isFavoriteCallCount)
        assertEquals(0, api.isSongInLibraryCallCount)
    }

    @Test
    fun toggleFavoriteOptimisticUpdateAndRollbackOnFailure() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isFavoriteResult = false
            addFavoriteShouldFail = true
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "4001")
        actions.onCurrentTrackChanged(track, signedIn = true)
        assertEquals(false, actions.state.value.current.isFavorite)

        actions.toggleFavorite()

        assertEquals(1, api.addFavoriteCallCount)
        assertFalse(actions.state.value.current.isFavorite == true)
        assertEquals(LibraryNotice.FavoriteFailed, actions.state.value.notice)
    }

    @Test
    fun toggleFavoriteWhenAlreadyFavoritedEmitsNoticeWithoutCallingApi() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isFavoriteResult = true
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "5001")
        actions.onCurrentTrackChanged(track, signedIn = true)
        assertEquals(true, actions.state.value.current.isFavorite)

        actions.toggleFavorite()

        assertEquals(0, api.addFavoriteCallCount)
        assertEquals(LibraryNotice.FavoriteRemoveUnsupported, actions.state.value.notice)
    }

    @Test
    fun addToLibraryOptimisticUpdateAndRollbackOnFailure() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isSongInLibraryResult = false
            addToLibraryShouldFail = true
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "6001")
        actions.onCurrentTrackChanged(track, signedIn = true)
        assertEquals(false, actions.state.value.current.isInLibrary)

        actions.addToLibrary()

        assertEquals(1, api.addToLibraryCallCount)
        assertFalse(actions.state.value.current.isInLibrary == true)
        assertEquals(LibraryNotice.AddToLibraryFailed, actions.state.value.notice)
    }

    @Test
    fun addToLibraryWhenAlreadyInLibraryEmitsNoticeWithoutCallingApi() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isSongInLibraryResult = true
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "7001")
        actions.onCurrentTrackChanged(track, signedIn = true)
        assertEquals(true, actions.state.value.current.isInLibrary)

        actions.addToLibrary()

        assertEquals(0, api.addToLibraryCallCount)
        assertEquals(LibraryNotice.RemoveFromLibraryUnsupported, actions.state.value.notice)
    }

    @Test
    fun apiMissingLibraryRelationshipReturnsNull() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isSongInLibraryResult = null // Apple Music API didn't return library relationship
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "8001")
        actions.onCurrentTrackChanged(track, signedIn = true)

        assertNull(actions.state.value.current.isInLibrary)
    }

    @Test
    fun apiThrowsOnSongInLibraryLeavesStateNull() = runBlocking {
        val api = FakeLibraryActionsApi().apply {
            isSongInLibraryShouldFail = true
        }
        val actions = createActions(api, this)

        val track = testTrack(catalogId = "9001")
        actions.onCurrentTrackChanged(track, signedIn = true)

        assertNull(actions.state.value.current.isInLibrary)
    }
}
