package dev.amatv.integration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistViewsTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    @Test fun catalogNextBecomesShelfMoreHref() {
        val next = "/v1/catalog/tw/artists/123/view/full-albums?offset=20"
        val views = JSONObject().put("full-albums", view("Albums").put("next", next))

        val shelf = api.parseArtistViews(artistRoot(views), "123").single()

        assertEquals(next, shelf.moreHref)
        assertEquals("Fixture item", shelf.items.single().title)
    }

    @Test fun missingNextLeavesShelfMoreHrefNull() {
        val views = JSONObject().put("full-albums", view("Albums"))

        val shelf = api.parseArtistViews(artistRoot(views), "123").single()

        assertNull(shelf.moreHref)
    }

    @Test fun nonCatalogNextIsIgnored() {
        val views = JSONObject().put("full-albums",
            view("Albums").put("next", "https://evil.example/x"))

        val shelf = api.parseArtistViews(artistRoot(views), "123").single()

        assertNull(shelf.moreHref)
    }

    @Test fun viewTitlesAndArtistViewsOrderArePreservedWithEmptyViewsOmitted() {
        val views = JSONObject()
            .put("similar-artists", view("Related performers", "artists"))
            .put("appears-on-albums", view("Guest appearances"))
            .put("compilation-albums", view("Collected releases"))
            .put("live-albums", view("On stage"))
            .put("featured-playlists", view("Curated selections", "playlists"))
            .put("singles", JSONObject()
                .put("attributes", JSONObject().put("title", "Empty singles"))
                .put("data", JSONArray()))
            .put("full-albums", view("Studio releases"))
            .put("latest-release", view("Newest release"))
            .put("top-songs", view("Popular tracks", "songs"))

        val shelves = api.parseArtistViews(artistRoot(views), "123")

        assertEquals(listOf(
            "artist-123-top-songs",
            "artist-123-latest-release",
            "artist-123-full-albums",
            "artist-123-featured-playlists",
            "artist-123-live-albums",
            "artist-123-compilation-albums",
            "artist-123-appears-on-albums",
            "artist-123-similar-artists",
        ), shelves.map { it.id })
        assertEquals(listOf(
            "Popular tracks",
            "Newest release",
            "Studio releases",
            "Curated selections",
            "On stage",
            "Collected releases",
            "Guest appearances",
            "Related performers",
        ), shelves.map { it.title })
        assertEquals(List(8) { "Fixture item" }, shelves.map { it.items.single().title })
    }

    private fun artistRoot(views: JSONObject) = JSONObject().put("data", JSONArray().put(
        JSONObject().put("id", "123").put("type", "artists").put("views", views)
    ))

    private fun view(title: String, resourceType: String = "albums") = JSONObject()
        .put("attributes", JSONObject().put("title", title))
        .put("data", JSONArray().put(JSONObject()
            .put("id", "456")
            .put("type", resourceType)
            .put("attributes", JSONObject()
                .put("name", "Fixture item")
                .put("artistName", "Fixture artist")
                .put("albumName", "Fixture album"))))
}
