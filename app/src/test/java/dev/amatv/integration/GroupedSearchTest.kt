package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.openAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedSearchTest {
    private val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

    @Test fun topResultsComeFirstThenEachKindOnce() {
        val root = JSONObject("""
            {"results":{
              "songs":{"data":[
                {"id":"201","type":"songs","attributes":{"name":"Idol","artistName":"YOASOBI"}},
                {"id":"202","type":"songs","attributes":{"name":"Yoru ni Kakeru","artistName":"YOASOBI"}}
              ]},
              "artists":{"data":[{"id":"301","type":"artists","attributes":{"name":"YOASOBI"}}]},
              "albums":{"data":[{"id":"401","type":"albums","attributes":{"name":"THE BOOK","artistName":"YOASOBI"}}]},
              "top":{"data":[
                {"id":"301","type":"artists","attributes":{"name":"YOASOBI"}},
                {"id":"201","type":"songs","attributes":{"name":"Idol","artistName":"YOASOBI"}}
              ]}
            }}
        """.trimIndent())

        val results = api.parseGroupedSearch(root)

        assertEquals(listOf("YOASOBI", "Idol", "Yoru ni Kakeru", "THE BOOK"), results.map { it.title })
        assertEquals(CatalogKind.Artist, results.first().catalogKind)
        assertEquals(CatalogOpenAction.OpenArtist, results.first().openAction)
    }

    @Test fun missingResultsAreEmpty() {
        assertEquals(emptyList<Any>(), api.parseGroupedSearch(JSONObject("""{"results":{}}""")))
        assertEquals(emptyList<Any>(), api.parseGroupedSearch(JSONObject("{}")))
    }
}
