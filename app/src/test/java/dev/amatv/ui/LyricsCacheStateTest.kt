package dev.amatv.ui

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCacheStateTest {
    private val baseline = listOf(LyricLine(listOf(LyricWord("Original", 0L, 1_000L))))

    @Test fun clearingCacheDuringLocalizationKeepsVisibleTextButDoesNotResolveThePendingVariant() {
        val loading = ActiveLyricsState("song", baseline, phase = LyricsLoadPhase.Loading)
        assertTrue(loading.resolvedCacheEntries().isEmpty())
        assertEquals(baseline, loading.lines)
        assertEquals(LyricsLoadPhase.Loading, loading.phase)
    }

    @Test fun completedLocalizationSurvivesOnlyUnderItsActualVariantKey() {
        val completed = ActiveLyricsState("song", baseline, "zh-TW|translation", LyricsLoadPhase.Ready)
        assertEquals(mapOf("song|zh-TW|translation" to baseline), completed.resolvedCacheEntries())
        assertTrue(completed.copy(resolvedVariant = null).resolvedCacheEntries().isEmpty())
        assertTrue(completed.copy(phase = LyricsLoadPhase.Failed).resolvedCacheEntries().isEmpty())
    }
}
