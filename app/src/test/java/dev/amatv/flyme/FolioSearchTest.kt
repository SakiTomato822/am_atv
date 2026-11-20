package dev.amatv.flyme

import dev.amatv.integration.CatalogSearchSuggestions
import dev.amatv.integration.CatalogSearchTerm
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolioSearchTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidTypingOnlyRequestsTheLastTermAfter250Milliseconds() = runTest {
        val session = FolioSearchSession(this)
        val requests = mutableListOf<String>()
        val expected = CatalogSearchSuggestions(listOf(CatalogSearchTerm("Taylor", "taylor")), emptyList())
        val suggest: suspend (String) -> CatalogSearchSuggestions = {
            requests.add(it)
            expected
        }

        session.edit("t", suggest)
        advanceTimeBy(100)
        session.edit("ta", suggest)
        advanceTimeBy(100)
        session.edit("taylor", suggest)
        advanceTimeBy(249)
        assertEquals(emptyList<String>(), requests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("taylor"), requests)
        assertEquals(expected, session.suggestions)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateSuggestionsCannotReplaceCurrentSuggestionsOrReopenAfterSubmitAndClear() = runTest {
        val session = FolioSearchSession(this)
        val stale = CompletableDeferred<CatalogSearchSuggestions>()
        val old = CatalogSearchSuggestions(listOf(CatalogSearchTerm("Old", "old")), emptyList())
        val latest = CatalogSearchSuggestions(listOf(CatalogSearchTerm("New", "new")), emptyList())

        session.edit("old") { withContext(NonCancellable) { stale.await() } }
        advanceTimeBy(250)
        runCurrent()
        session.edit("new") { latest }
        advanceTimeBy(250)
        runCurrent()
        stale.complete(old)
        runCurrent()
        assertEquals(latest, session.suggestions)

        session.submit("new") { emptyList() }
        assertNull(session.suggestions)
        val requests = mutableListOf<String>()
        val suggest: suspend (String) -> CatalogSearchSuggestions = {
            requests.add(it)
            latest
        }
        session.edit("pending", suggest)
        session.edit("", suggest)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(emptyList<String>(), requests)
        assertNull(session.suggestions)
    }

    @Test
    fun searchOnlyShowsSubmittedResultsAndClearingCancelsPendingResults() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val session = FolioSearchSession(scope)
            val first = CompletableDeferred<List<Track>>()
            val second = CompletableDeferred<List<Track>>()
            val current = track("new", CatalogKind.Album)

            val emptySuggestions = CatalogSearchSuggestions(emptyList(), emptyList())
            session.edit("draft") { emptySuggestions }
            assertNull(session.submission)
            session.submit("first") { first.await() }
            session.submit("second") { listOf(current) }
            first.complete(listOf(track("old", CatalogKind.Album)))
            assertEquals("second", session.query)
            assertEquals(listOf(current), session.submission?.results)

            session.submit("pending") { second.await() }
            session.edit("") { emptySuggestions }
            second.complete(listOf(current))
            assertEquals("", session.query)
            assertNull(session.submission)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun groupsResultsByKindAndKeepsOnlyTheFirstSixSongs() {
        val best = track("best", CatalogKind.Artist)
        val songs = List(8) { track("song-$it", CatalogKind.Song) }
        val artist = track("artist", CatalogKind.Artist)
        val album = track("album", CatalogKind.Album)
        val playlist = track("playlist", CatalogKind.Playlist)
        val sections = folioSearchSections(listOf(best, playlist, artist) + songs + album)

        assertEquals(listOf(best), sections.best)
        assertEquals(songs.take(6), sections.songs)
        assertEquals(listOf(album), sections.albums)
        assertEquals(listOf(artist), sections.artists)
        assertEquals(listOf(playlist), sections.playlists)
    }

    private fun track(id: String, kind: CatalogKind) =
        Track(id, id, "", "", 0, emptyList(), emptyList(), catalogKind = kind)
}
