package dev.amatv.integration

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryPagingTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    private fun song(index: Int, catalogId: String = index.toString()): String = """
        {"id":"i.$index","type":"library-songs","attributes":{
          "name":"Song $index","artistName":"Artist","durationInMillis":180000,
          "playParams":{"catalogId":"$catalogId"}}}
    """.trimIndent()

    @Test fun firstPageDoesNotEagerlyFetchTheContinuation() = runBlocking {
        val requested = mutableListOf<String>()
        val page = api.libraryPage(LibraryCollection.Songs) { path ->
            requested += path
            JSONObject("""{"data":[${(1..100).joinToString(",") { song(it) }}],
              "next":"/v1/me/library/songs?offset=100"}""")
        }
        assertEquals(listOf("/v1/me/library/songs?limit=100&include=catalog"), requested)
        assertEquals(100, page.items.size)
        assertEquals("/v1/me/library/songs?offset=100&include=catalog", page.nextPage)
        assertEquals(requested.single(), page.requestedPage)
    }

    @Test fun explicitPagesReachPastOldThreeHundredItemCap() = runBlocking {
        var items = emptyList<dev.amatv.model.Track>()
        var next: String? = null
        val requested = mutableListOf<String>()
        repeat(5) { pageIndex ->
            val page = api.libraryPage(LibraryCollection.Songs, next) { path ->
                requested += path
                val start = pageIndex * 100 + 1
                val root = JSONObject("""{"data":[${(start until start + 100).joinToString(",") { song(it) }}]}""")
                if (pageIndex < 4) root.put("next", "/v1/me/library/songs?offset=${start + 99}")
                root
            }
            items = mergeLibraryPage(items, page.items)
            next = page.nextPage
        }
        assertEquals(5, requested.size)
        assertEquals(500, items.size)
        assertEquals("i.500", items.last().resourceId)
        assertNull(next)
    }

    @Test fun resourceIdentityPreservesSeparateLibrarySongsSharingACatalogId() = runBlocking {
        val first = api.libraryPage(LibraryCollection.Songs) {
            JSONObject("""{"data":[${song(1, "same")},${song(2, "same")}]}""")
        }
        val second = api.libraryPage(LibraryCollection.Songs, "/v1/me/library/songs?offset=2") {
            JSONObject("""{"data":[${song(2, "same")},${song(3, "same")}]}""")
        }
        assertEquals(listOf("i.1", "i.2", "i.3"),
            mergeLibraryPage(first.items, second.items).map { it.resourceId })
    }

    @Test fun nestedCatalogAndOtherLibraryResourcesDoNotBecomeShelfEntries() = runBlocking {
        val page = api.libraryPage(LibraryCollection.Playlists) {
            JSONObject("""{"data":[{"id":"p.one","type":"library-playlists","attributes":{"name":"One"},
              "relationships":{"catalog":{"data":[{"id":"pl.one","type":"playlists","attributes":{"name":"Catalog"}}]}}},
              ${song(1)}]}""")
        }
        assertEquals(listOf("p.one"), page.items.map { it.resourceId })
    }

    @Test fun aContinuationCannotSendAccountRequestsOutsideItsLibraryEndpoint() = runBlocking {
        for (next in listOf("https://evil.test/v1/me/library/songs?offset=100",
            "/v1/me/library/playlists?offset=100", "//evil.test/v1/me/library/songs")) {
            var requested = false
            try {
                api.libraryPage(LibraryCollection.Songs, next) {
                    requested = true
                    JSONObject("""{"data":[]}""")
                }
                fail("Unsafe continuation must be refused")
            } catch (_: IllegalStateException) {
                assertFalse(requested)
            }
        }
    }

    @Test fun immediatelyRepeatedContinuationIsAnErrorRatherThanSilentTruncation() = runBlocking {
        try {
            api.libraryPage(LibraryCollection.Songs, "/v1/me/library/songs?offset=100") {
                JSONObject("""{"data":[],"next":"/v1/me/library/songs?offset=100"}""")
            }
            fail("A repeated page must fail")
        } catch (_: IllegalStateException) { }
    }
}
