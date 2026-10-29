package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.openAction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryArtistTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    private suspend fun artist(id: String = "r.y8mMT7t") = api.libraryPage(LibraryCollection.Artists) {
        JSONObject("""{"data":[{"id":"$id","type":"library-artists","attributes":{"name":"Orville Peck"}}]}""")
    }

    @Test fun artistShelfUsesOnlyDirectUserLibraryResourcesAndKeepsLibraryIdentity() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Artists) { path ->
            assertEquals("/v1/me/library/artists?limit=100&include=catalog", path)
            JSONObject("""{"data":[
              {"id":"r.y8mMT7t","type":"library-artists","attributes":{"name":"Orville Peck"},
                "relationships":{"catalog":{"data":[{"id":"1234","type":"artists",
                  "attributes":{"name":"Catalog artist"}}]}}},
              {"id":"1234","type":"artists","attributes":{"name":"Directory only"}}],
              "next":"/v1/me/library/artists?offset=100"}""")
        }

        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals(CatalogKind.Artist, item.catalogKind)
        assertEquals("Orville Peck", item.title)
        assertEquals("r.y8mMT7t", item.resourceId)
        assertEquals("library-artists", item.resourceType)
        assertNull(item.catalogId)
        assertEquals(CatalogOpenAction.OpenArtist, item.openAction)
        assertEquals("/v1/me/library/artists?offset=100&include=catalog", page.nextPage)
    }

    @Test fun artistSavedAlbumsPageStaysOnLibraryRelationshipAndCanContinue() = runBlocking {
        val item = artist().items.single()
        val requested = mutableListOf<String>()
        val first = api.libraryArtistAlbumsPage(item) { path ->
            requested += path
            JSONObject("""{"data":[
              {"id":"l.owned","type":"library-albums","attributes":{"name":"Owned album","artistName":"Orville Peck"}},
              {"id":"1234","type":"albums","attributes":{"name":"Directory album","artistName":"Orville Peck"}}],
              "next":"/v1/me/library/artists/r.y8mMT7t/albums?offset=100"}""")
        }
        val second = api.libraryArtistAlbumsPage(item, first.nextPage) { path ->
            requested += path
            JSONObject("""{"data":[{"id":"l.second","type":"library-albums",
              "attributes":{"name":"Another owned album","artistName":"Orville Peck"}}]}""")
        }

        assertEquals(listOf(
            "/v1/me/library/artists/r.y8mMT7t/albums?limit=100&include=catalog",
            "/v1/me/library/artists/r.y8mMT7t/albums?offset=100&include=catalog",
        ), requested)
        assertEquals("/v1/me/library/albums/l.owned/tracks?limit=100&include=catalog",
            first.items.single().collectionRequest("tw").firstPage)
        assertEquals("l.owned", first.items.single().resourceId)
        assertNull(first.items.single().catalogId)
        assertEquals("l.second", second.items.single().resourceId)
        assertNull(second.nextPage)
    }

    @Test fun artistAlbumContinuationCannotEscapeItsRelationshipEndpoint() = runBlocking {
        val item = artist().items.single()
        var fetched = false
        try {
            api.libraryArtistAlbumsPage(item, "/v1/me/library/artists/r.other/albums?offset=100") {
                fetched = true
                JSONObject("""{"data":[]}""")
            }
            fail("A continuation for another artist must be refused")
        } catch (_: IllegalStateException) {
            assertFalse(fetched)
        }
    }

    @Test fun nonLibraryArtistCannotRequestSavedAlbums() = runBlocking {
        val fake = artist().items.single().copy(resourceType = "artists")
        try {
            api.libraryArtistAlbumsPage(fake) { JSONObject("""{"data":[]}""") }
            fail("Catalog artists do not stand in for a saved library artist")
        } catch (_: IllegalArgumentException) { }
    }
}
