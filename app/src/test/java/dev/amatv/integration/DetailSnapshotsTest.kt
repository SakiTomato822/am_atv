package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetailSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val track = Track("apple:42", "Saved Song", "Artist", "Album", 1_000, emptyList(), emptyList(),
        catalogId = "42", resourceId = "i.saved", resourceType = "library-songs")

    @Test fun snapshotsSurviveReopenOnlyInMatchingAccountAndStorefront() {
        val directory = temporary.newFolder()
        val store = DetailSnapshots(directory)
        val scope = DetailSnapshots.scope("test-token-first", "tw")
        val items = listOf(track, track)
        store.write(store.revision(), scope, "tracks/playlist", encodeDetailTracks(items))

        val reopened = DetailSnapshots(directory)
        val restored = reopened.read(scope, "tracks/playlist", ::decodeDetailTracks)
        assertEquals(listOf("i.saved", "i.saved"), restored?.map { it.resourceId })
        assertEquals(listOf("42", "42"), restored?.map { it.catalogId })
        assertNull(reopened.read(DetailSnapshots.scope("test-token-second", "tw"), "tracks/playlist", ::decodeDetailTracks))
        assertNull(reopened.read(DetailSnapshots.scope("test-token-first", "us"), "tracks/playlist", ::decodeDetailTracks))
        assertFalse(directory.listFiles()!!.single().readText().contains("test-token-first"))
    }

    @Test fun clearPreventsAnOldRequestFromRestoringAnAccountSnapshot() {
        val directory = temporary.newFolder()
        val store = DetailSnapshots(directory)
        val scope = DetailSnapshots.scope("first", "tw")
        val started = store.revision()
        store.write(started, scope, "tracks", encodeDetailTracks(listOf(track)))
        store.clear()
        store.write(started, scope, "tracks", encodeDetailTracks(listOf(track)))
        assertNull(store.read(scope, "tracks", ::decodeDetailTracks))
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun artistSnapshotKeepsItsShelvesAndCatalogContinuation() {
        val hero = track.copy(catalogKind = CatalogKind.Artist)
        val shelf = ContentShelf("id", "Albums", listOf(track), moreHref = "/v1/catalog/tw/artists/42/view/full-albums?offset=2")
        val decoded = decodeDetailArtist(encodeDetailArtist(CatalogArtistPage(hero, listOf(track), listOf(shelf))))
        assertEquals(CatalogKind.Artist, decoded.artist?.catalogKind)
        assertEquals("42", decoded.topSongs.single().catalogId)
        assertEquals(shelf.moreHref, decoded.shelves.single().moreHref)
    }
}
