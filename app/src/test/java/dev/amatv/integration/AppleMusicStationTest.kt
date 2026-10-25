package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicStationTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    private fun song(id: String) = Track(
        id = "apple:$id", title = "Song $id", artist = "Artist", album = "Album",
        durationMs = 200_000L, palette = emptyList(), lyrics = emptyList(), catalogId = id,
    )

    @Test fun stationContainerIsSkippedButItsSongRelationshipIsExpanded() {
        val root = JSONObject("""
            {"data":[{
              "id":"ra.station","type":"stations","attributes":{"name":"Song station"},
              "relationships":{"tracks":{"data":[
                {"id":"101","type":"songs","attributes":{"name":"First","artistName":"A"}},
                {"id":"mv.1","type":"music-videos","attributes":{"name":"Video"}},
                {"id":"102","type":"songs","attributes":{"name":"Second","artistName":"B"}}
              ]}}
            }]}
        """.trimIndent())

        val tracks = api.parseStationTracks(root)
        assertEquals(listOf("101", "102"), tracks.map { it.catalogId })
        assertTrue(tracks.all { it.catalogKind == CatalogKind.Song })
    }

    @Test fun nextTracksResponseRejectsEveryNonSongResource() {
        val root = JSONObject("""
            {"results":{"tracks":{"data":[
              {"id":"ra.1","type":"stations","attributes":{"name":"Station"}},
              {"id":"al.1","type":"albums","attributes":{"name":"Album"}},
              {"id":"pl.1","type":"playlists","attributes":{"name":"Playlist"}},
              {"id":"mv.1","type":"music-videos","attributes":{"name":"Video"}},
              {"id":"6781751954","type":"songs","attributes":{"name":"Song","artistName":"Artist"}}
            ]}}}
        """.trimIndent())

        assertEquals(listOf("6781751954"), api.parseStationTracks(root).map { it.catalogId })
    }

    @Test fun stationMetadataOnlyCreationLoadsRealNextTracks() = runBlocking {
        val root = JSONObject("""
            {"data":[{"id":"ra.generated","type":"stations","attributes":{"name":"Generated station"}}]}
        """.trimIndent())
        val requested = mutableListOf<String>()

        val (stationId, tracks) = api.continuousStationFromResponse(root) { id ->
            requested += id
            listOf(song("101"), song("bad").copy(catalogKind = CatalogKind.Station))
        }

        assertEquals("ra.generated", stationId)
        assertEquals(listOf("ra.generated"), requested)
        assertEquals(listOf("101"), tracks.map { it.catalogId })
    }

    @Test fun inlineSongsAvoidConsumingAnAdditionalStationPage() = runBlocking {
        val root = JSONObject("""
            {"results":{
              "station":{"id":"ra.generated","type":"stations","attributes":{"name":"Station"}},
              "tracks":{"data":[{"id":"101","type":"songs","attributes":{"name":"Song"}}]}
            }}
        """.trimIndent())
        var called = false
        val (stationId, tracks) = api.continuousStationFromResponse(root) {
            called = true
            emptyList()
        }

        assertFalse(called)
        assertEquals("ra.generated", stationId)
        assertEquals(listOf("101"), tracks.map { it.catalogId })
    }

    @Test fun songIdIsNeverMistakenForAStationContinuationHandle() = runBlocking {
        val root = JSONObject("""
            {"data":[{"id":"101","type":"songs","attributes":{"name":"Song"}}]}
        """.trimIndent())
        val (stationId, tracks) = api.continuousStationFromResponse(root) { error("No station was returned") }
        assertNull(stationId)
        assertEquals(listOf("101"), tracks.map { it.catalogId })
    }
}
