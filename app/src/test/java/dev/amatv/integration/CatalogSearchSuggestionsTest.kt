package dev.amatv.integration

import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.openAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogSearchSuggestionsTest {
    @Test
    fun missingSuggestionsArrayIsAnErrorButAnEmptyArrayIsValid() {
        val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }

        assertThrows(IllegalStateException::class.java) {
            api.parseSearchSuggestions(JSONObject("""{"results":{}}"""))
        }
        assertEquals(
            CatalogSearchSuggestions(emptyList(), emptyList()),
            api.parseSearchSuggestions(JSONObject("""{"results":{"suggestions":[]}}""")),
        )
    }

    @Test
    fun parsesDisplayAndSearchTermsAndKeepsThreePlayableOrOpenableResults() {
        val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
        val response = JSONObject(
            """
            {"results":{"suggestions":[
              {"kind":"terms","displayTerm":"Taylor Swift","searchTerm":"taylor swift"},
              {"kind":"topResults","content":{"id":"1","type":"artists","attributes":{"name":"Taylor Swift"}}},
              {"kind":"terms","displayTerm":"Taylor Swift essentials","searchTerm":"taylor swift essentials"},
              {"kind":"topResults","content":{"id":"2","type":"songs","attributes":{"name":"Anti-Hero","artistName":"Taylor Swift"}}},
              {"kind":"terms","displayTerm":"Taylor Swift live","searchTerm":"taylor swift live"},
              {"kind":"topResults","content":{"id":"3","type":"albums","attributes":{"name":"Midnights","artistName":"Taylor Swift"}}},
              {"kind":"terms","displayTerm":"Taylor Swift acoustic","searchTerm":"taylor swift acoustic"},
              {"kind":"terms","displayTerm":"Taylor Swift remix","searchTerm":"taylor swift remix"},
              {"kind":"terms","displayTerm":"Taylor Swift radio","searchTerm":"taylor swift radio"},
              {"kind":"topResults","content":{"id":"4","type":"songs","attributes":{"name":"Lavender Haze","artistName":"Taylor Swift"}}}
            ]}}
            """.trimIndent(),
        )

        val suggestions = api.parseSearchSuggestions(response)

        assertEquals(5, suggestions.terms.size)
        assertEquals(CatalogSearchTerm("Taylor Swift", "taylor swift"), suggestions.terms.first())
        assertEquals("taylor swift remix", suggestions.terms.last().searchTerm)
        assertEquals(listOf("Taylor Swift", "Anti-Hero", "Midnights"), suggestions.topResults.map { it.title })
        assertEquals(listOf("1", "2", "3"), suggestions.topResults.map { it.catalogId })
        assertEquals(
            listOf(CatalogOpenAction.OpenArtist, CatalogOpenAction.Play, CatalogOpenAction.Collection),
            suggestions.topResults.map { it.openAction },
        )
    }

    @Test
    fun resolvesReferenceOnlySuggestionsFromTheResourceMap() {
        val api = AppleMusicApi("tw", DeveloperTokenClient("")) { null }
        val response = JSONObject("""
            {"results":{"suggestions":[
              {"kind":"topResults","content":{"id":"1","type":"albums"}}
            ]},"resources":{"albums":{"1":{"attributes":{"name":"I AM GLORIA"}}}}}
        """.trimIndent())

        assertEquals("I AM GLORIA", api.parseSearchSuggestions(response).topResults.single().title)
    }
}
