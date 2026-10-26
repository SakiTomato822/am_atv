package dev.amatv.integration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EquivalentCatalogSongTest {
    private fun song(id: String) = """{"id":"$id","type":"songs","attributes":{
      "playParams":{"id":"$id","kind":"song"}}}"""
    private fun response(mapping: String, data: String) = JSONObject("""{
      "data":[$data],"meta":{"filters":{"equivalents":$mapping}}}""")

    @Test fun retiredLibraryIdsUseTheirOwnMappingRatherThanResponseOrder() {
        // Real Apple mappings; response order intentionally differs from source order.
        val root = response("""{
          "1465074689":[{"id":"6790045614","type":"songs"}],
          "1811036772":[{"id":"1872300455","type":"songs"}]
        }""", "${song("1872300455")},${song("6790045614")}")
        assertEquals("6790045614", equivalentCatalogSongId(root, "1465074689"))
        assertEquals("1872300455", equivalentCatalogSongId(root, "1811036772"))
    }

    @Test fun validCurrentSongCanMapToItself() {
        val root = response("""{"6790045614":[{"id":"6790045614","type":"songs"}]}""", song("6790045614"))
        assertEquals("6790045614", equivalentCatalogSongId(root, "6790045614"))
    }

    @Test fun missingOrUnplayableTargetNeverFallsBackToSomeOtherSong() {
        val mapping = """{"old":[{"id":"current","type":"songs"}]}"""
        assertNull(equivalentCatalogSongId(response(mapping, song("unrelated")), "old"))
        assertNull(equivalentCatalogSongId(response(mapping,
            """{"id":"current","type":"songs","attributes":{}}"""), "old"))
        assertNull(equivalentCatalogSongId(response("{}", song("current")), "old"))
    }

    @Test fun conflictingMappingsAreNotChosenArbitrarily() {
        val root = response("""{"old":[{"id":"a","type":"songs"},{"id":"b","type":"songs"}]}""",
            "${song("a")},${song("b")}")
        assertNull(equivalentCatalogSongId(root, "old"))
    }
}
