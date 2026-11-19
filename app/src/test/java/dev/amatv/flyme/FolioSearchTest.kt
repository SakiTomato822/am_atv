package dev.amatv.flyme

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolioSearchTest {
    @Test
    fun searchOnlyShowsSubmittedResultsAndClearingCancelsPendingResults() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val session = FolioSearchSession(scope)
            val first = CompletableDeferred<List<Track>>()
            val second = CompletableDeferred<List<Track>>()
            val current = track("new", CatalogKind.Album)

            session.edit("draft")
            assertNull(session.submission)
            session.submit("first") { first.await() }
            session.submit("second") { listOf(current) }
            first.complete(listOf(track("old", CatalogKind.Album)))
            assertEquals("second", session.query)
            assertEquals(listOf(current), session.submission?.results)

            session.submit("pending") { second.await() }
            session.edit("")
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
