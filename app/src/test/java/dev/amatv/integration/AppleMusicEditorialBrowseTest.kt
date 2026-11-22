package dev.amatv.integration

import dev.amatv.model.CatalogKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicEditorialBrowseTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    @Test
    fun editorialRequestUsesStorefrontAndPrimaryLanguage() {
        assertEquals(
            "/v1/editorial/tw/groupings?name=music&platform=web&tabs=nonsubscriber&l=zh-Hant-TW&extend=editorialArtwork",
            api.editorialBrowsePath("tw", "zh-TW,en-US"),
        )
    }

    @Test
    fun parsesOrderedShelvesAndSkipsUnsupportedResourcesAndElements() {
        val root = page(
            element("hero", "316", null, "children", element(
                "hero-card", "317", null, "contents", resource("pl.hero", "playlists"),
            )),
            element("albums", "326", "最新發行", "contents", resource("10", "albums")),
            element("songs", "327", "本週新歌", "contents",
                resource("22", "songs"), resource("21", "songs"), resource("23", "music-videos"),
                resource("unknown", "future-resources"), JSONObject().put("type", "songs")),
            element("unknown", "900", "未知欄目", "contents", resource("60", "songs")),
            element("radio", "488", "直播電台", "children", element(
                "radio-card", "489", null, "contents", resource("ra.live", "stations"),
            )),
            element("videos", "345", "音樂錄影帶", "contents", resource("70", "music-videos")),
        )

        val shelves = api.parseEditorialBrowseShelves(root)
        assertEquals(listOf("", "最新發行", "本週新歌", "直播電台"), shelves.map { it.title })
        assertEquals(listOf("pl.hero", "10", "22", "21", "ra.live"), shelves.flatMap { shelf ->
            shelf.items.map { it.catalogId }
        })
        assertEquals(
            listOf(CatalogKind.Playlist, CatalogKind.Album, CatalogKind.Song, CatalogKind.Song, CatalogKind.Station),
            shelves.flatMap { shelf -> shelf.items.map { it.catalogKind } },
        )
    }

    @Test
    fun emptyAndUnsupportedPagesProduceNoShelves() {
        assertTrue(api.parseEditorialBrowseShelves(JSONObject()).isEmpty())
        assertTrue(api.parseEditorialBrowseShelves(page()).isEmpty())
        val linkOnly = element("links", "391", "探索更多", "contents", resource("10", "albums"))
        assertTrue(api.parseEditorialBrowseShelves(page(linkOnly)).isEmpty())
    }

    @Test
    fun personalShelfUsesFirstAlbumGroupAfterMadeForYouAndSkipsRecentlyPlayed() {
        val madeForYou = recommendation("made", "recommendation", resource("10", "albums"))
        val recentlyPlayed = recommendation("recent", "recently-played", resource("20", "albums"))
        val releases = recommendation("new", "recommendation", resource("30", "albums"), resource("31", "albums"))
        val other = recommendation("other", "recommendation", resource("40", "albums"))
        val empty = recommendation("empty", "recommendation")

        val shelf = requireNotNull(api.parsePersonalizedNewReleases(data(empty, madeForYou, recentlyPlayed, releases, other)))
        assertEquals(BROWSE_PERSONAL_RELEASES_ID, shelf.id)
        assertEquals(listOf("30", "31"), shelf.items.map { it.catalogId })
        assertNull(api.parsePersonalizedNewReleases(data(madeForYou, recentlyPlayed)))
    }

    private fun data(vararg entries: JSONObject) = JSONObject().put("data", JSONArray(entries.toList()))

    private fun page(vararg sections: JSONObject) = data(
        JSONObject().put("type", "groupings").withRelationship(
            "tabs", element("new", "382", null, "children", *sections),
        ),
    )

    private fun element(
        id: String, kind: String, name: String?, relationship: String, vararg entries: JSONObject,
    ) = JSONObject()
        .put("id", id)
        .put("type", "editorial-elements")
        .put("attributes", JSONObject().put("editorialElementKind", kind).put("name", name))
        .withRelationship(relationship, *entries)

    private fun recommendation(id: String, kind: String, vararg entries: JSONObject) = JSONObject()
        .put("id", id)
        .put("type", "personal-recommendation")
        .put("attributes", JSONObject().put("kind", kind))
        .withRelationship("contents", *entries)

    private fun resource(id: String, type: String) = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("attributes", JSONObject().put("name", "Item $id"))

    private fun JSONObject.withRelationship(name: String, vararg entries: JSONObject) =
        put("relationships", JSONObject().put(name, data(*entries)))
}
