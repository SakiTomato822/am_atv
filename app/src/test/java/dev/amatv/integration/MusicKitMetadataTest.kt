package dev.amatv.integration

import dev.amatv.data.MockCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitMetadataTest {
    @Test
    fun explicitBadgeRequiresMatchingCatalogIdEvenWhenTitlesMatch() {
        val explicit = MockCatalog.tracks.first().copy(catalogId = "11", isExplicit = true)
        val clean = explicit.copy(catalogId = "12", isExplicit = false)

        assertTrue(nativeSongIsExplicit("11", explicit, clean))
        assertFalse(nativeSongIsExplicit("12", explicit, clean))
        assertTrue(nativeSongIsExplicit("11", clean, explicit))
        assertFalse(nativeSongIsExplicit("13", clean, explicit))
        assertFalse(nativeSongIsExplicit(null, explicit.copy(catalogId = null), null))
    }
}
