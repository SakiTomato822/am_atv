package dev.amatv.integration

import dev.amatv.model.AppleArtworkColors
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
        val colors = AppleArtworkColors(bgColor = "112233", textColor1 = "abcdef")
        val items = listOf(track.copy(artworkColors = colors), track)
        store.write(store.revision(), scope, "tracks/playlist", encodeDetailTracks(items))

        val reopened = DetailSnapshots(directory)
        val restored = reopened.read(scope, "tracks/playlist", ::decodeDetailTracks)
        assertEquals(listOf("i.saved", "i.saved"), restored?.map { it.resourceId })
        assertEquals(listOf("42", "42"), restored?.map { it.catalogId })
        assertEquals(listOf(colors, null), restored?.map { it.artworkColors })
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

    @Test fun discAndTrackNumbersRoundTripAndLegacySnapshotsDecodeNull() {
        val discTrack = track.copy(id = "apple:43", catalogId = "43", discNumber = 2, trackNumber = 5)
        val plainTrack = track.copy(id = "apple:44", catalogId = "44", discNumber = null, trackNumber = null)
        val encoded = encodeDetailTracks(listOf(discTrack, plainTrack))
        val decoded = decodeDetailTracks(encoded)
        assertEquals(listOf(2, null), decoded.map { it.discNumber })
        assertEquals(listOf(5, null), decoded.map { it.trackNumber })

        // Simulate an older cached snapshot that predates discNumber/trackNumber
        val legacyJson = encodeDetailTracks(listOf(discTrack))
        legacyJson.getJSONArray("tracks").getJSONObject(0).apply {
            remove("discNumber")
            remove("trackNumber")
        }
        val legacyDecoded = decodeDetailTracks(legacyJson).single()
        assertNull(legacyDecoded.discNumber)
        assertNull(legacyDecoded.trackNumber)
        assertEquals("43", legacyDecoded.catalogId)

        // PlaybackSessionCodec must not serialize discNumber or trackNumber
        val sessionJson = discTrack.toSessionJson()
        assertFalse(sessionJson.has("discNumber"))
        assertFalse(sessionJson.has("trackNumber"))
    }
}
