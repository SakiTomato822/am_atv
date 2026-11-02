package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {
    @Test fun newestFirstWithoutCaseDuplicates() {
        val history = pushSearchHistory(listOf("Ado", "laufey", "YOASOBI"), " Laufey ")
        assertEquals(listOf("Laufey", "Ado", "YOASOBI"), history)
    }

    @Test fun keepsAtMostTheLimitAndIgnoresBlank() {
        val full = (1..12).map { "t$it" }
        assertEquals(12, pushSearchHistory(full, "new").size)
        assertEquals("new", pushSearchHistory(full, "new").first())
        assertEquals(full, pushSearchHistory(full, "  "))
    }
}
