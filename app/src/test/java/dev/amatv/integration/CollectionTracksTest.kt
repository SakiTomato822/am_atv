package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectionTracksTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
    private fun collection(kind: CatalogKind = CatalogKind.Playlist) = Track(
        "apple:pl.test", "A Radio Named Playlist", "Curator", "", 0, emptyList(), emptyList(),
        catalogId = "pl.test", catalogKind = kind,
    )
    private fun song(id: String) = """{"id":"$id","type":"songs","attributes":{"name":"Song $id","artistName":"A","durationInMillis":180000}}"""

    @Test fun followsEveryPageInOrderAndPreservesRepeatedEntries() = runBlocking {
        val requested = mutableListOf<String>()
        val tracks = api.collectionTracks(collection()) { path, user ->
            assertFalse(user)
            requested += path
            if (requested.size == 1) JSONObject("""{"data":[${(1..100).joinToString(",") { song(it.toString()) }}],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=100"}""")
            else JSONObject("""{"data":[${song("1")},${song("101")}]}""")
        }
        assertEquals(102, tracks.size)
        assertEquals(listOf("100", "1", "101"), tracks.takeLast(3).map { it.catalogId })
        assertEquals(2, requested.size)
    }

    @Test fun libraryIdentityWinsOverDifferentCatalogPlaybackId() = runBlocking {
        val item = collection().copy(resourceId = "p.own", resourceType = "library-playlists")
        val requested = mutableListOf<String>()
        val tracks = api.collectionTracks(item) { path, user ->
            assertTrue(user)
            requested += path
            JSONObject("""{"data":[{"id":"i.library","type":"library-songs",
              "attributes":{"name":"Owned","artistName":"A","playParams":{"catalogId":"201"}},
              "relationships":{"catalog":{"data":[${song("201")}]}}}]}""")
        }
        assertTrue(requested.single().startsWith("/v1/me/library/playlists/p.own/tracks?"))
        assertEquals(listOf("201"), tracks.map { it.catalogId })
        assertEquals("i.library", tracks.single().resourceId)
        assertEquals("library-songs", tracks.single().resourceType)
    }

    @Test fun libraryAlbumUsesLibraryRelationshipEndpoint() {
        val request = collection(CatalogKind.Album).copy(
            catalogId = "123", resourceId = "l.own", resourceType = "library-albums",
        ).collectionRequest("tw")
        assertEquals("/v1/me/library/albums/l.own/tracks?limit=100&include=catalog", request.firstPage)
        assertEquals("/v1/me/library/albums/l.own/tracks?offset=100&include=catalog",
            request.nextPage("/v1/me/library/albums/l.own/tracks?offset=100"))
    }

    @Test fun onlyDirectPlayableSongsEnterCollectionQueue() = runBlocking {
        val tracks = api.collectionTracks(collection()) { _, _ ->
            JSONObject("""{"data":[${song("1")},
              {"id":"ra.1","type":"stations","attributes":{"name":"Station"}},
              {"id":"pl.related","type":"playlists","attributes":{"name":"Related"},
                "relationships":{"tracks":{"data":[${song("2")}]}}},
              {"id":"mv.1","type":"music-videos","attributes":{"name":"Video"}}]}""")
        }
        assertEquals(listOf("1"), tracks.map { it.catalogId })
    }

    @Test fun stationAndPlaylistRouteByTypeRegardlessOfTitle() {
        assertEquals(CatalogOpenAction.Collection, collection().openAction)
        val station = collection(CatalogKind.Station).copy(title = "My Playlist")
        assertEquals(CatalogOpenAction.Station, station.openAction)
        assertThrows(IllegalStateException::class.java) { station.collectionRequest("tw") }
    }

    @Test fun refusesPaginationOutsideOriginalRelationship() = runBlocking {
        for (next in listOf("https://evil.test/tracks", "/v1/catalog/tw/stations/ra.1/next-tracks")) {
            var calls = 0
            try {
                api.collectionTracks(collection()) { _, _ ->
                    calls++
                    JSONObject().put("data", org.json.JSONArray()).put("next", next)
                }
                fail("Unsafe next page must be rejected")
            } catch (_: IllegalStateException) { assertEquals(1, calls) }
        }
    }

    @Test fun cyclicPaginationFailsRatherThanTruncatingSilently() = runBlocking {
        var calls = 0
        try {
            api.collectionTracks(collection()) { _, _ ->
                calls++
                JSONObject("""{"data":[],"next":"/v1/catalog/tw/playlists/pl.test/tracks?offset=100"}""")
            }
            fail("Repeated page must not be accepted")
        } catch (_: IllegalStateException) { assertEquals(2, calls) }
    }
}
