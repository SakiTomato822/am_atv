package dev.amatv.playback

import dev.amatv.model.Track
import dev.amatv.model.CatalogKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationSequencerTest {
    private fun track(id: String, artist: String, title: String = id) = Track(
        id = id, title = title, artist = artist, album = "Album",
        durationMs = 200_000L, palette = emptyList(), lyrics = emptyList(), catalogId = id,
    )

    @Test fun avoidsArtistRunsWithoutDiscardingProviderRecommendations() {
        val candidates = listOf(track("1", "A"), track("2", "A"), track("3", "B"), track("4", "B"))
        assertEquals(listOf("1", "3", "2", "4"), RecommendationSequencer.sequence(
            candidates, emptyList(), listOf(track("0", "A")),
        ).map { it.id })
    }

    @Test fun keepsSingleArtistPageAndProviderOrder() {
        val candidates = (1..4).map { track("$it", "A") }
        assertEquals(candidates, RecommendationSequencer.sequence(candidates, emptyList(), candidates.take(1)))
    }

    @Test fun diversityCannotPromoteDistantLowRankedResultAcrossPage() {
        val candidates = (1..7).map { track("$it", "A") } + track("8", "B")
        assertEquals("1", RecommendationSequencer.sequence(candidates, emptyList(), listOf(track("0", "A"))).first().id)
    }

    @Test fun rejectsContainersAndMusicVideosBeforeQueueingRecommendations() {
        val candidates = CatalogKind.entries.map { kind ->
            track(kind.name, "Artist").copy(catalogKind = kind)
        }
        assertEquals(listOf(CatalogKind.Song), RecommendationSequencer.sequence(
            candidates, emptyList(), emptyList(),
        ).map { it.catalogKind })
    }

    @Test fun excludesCatalogIdEvenWhenProviderMetadataChangedAndSongVariantsAcrossIds() {
        val heard = track("1", "Artist", "Song")
        val candidates = listOf(
            track("1", "Changed artist", "Changed title"),
            track("2", "Artist", "Song (Remastered)"),
            track("3", "Other", "Fresh"),
        )
        assertEquals(listOf("3"), RecommendationSequencer.sequence(candidates, listOf(heard), listOf(heard)).map { it.id })
    }

    @Test fun aThirdConsecutiveArtistCanUseANearbyAlternative() {
        val candidates = listOf(track("1", "A"), track("2", "A"), track("3", "B"))
        assertEquals(listOf("3", "1", "2"), RecommendationSequencer.sequence(
            candidates, emptyList(), listOf(track("old", "A"), track("last", "A")),
        ).map { it.id })
    }
}
