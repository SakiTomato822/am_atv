package dev.amatv.playback

import dev.amatv.integration.SongIdentity
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/** Provider relevance is primary; only a third consecutive artist needs a small local adjustment. */
internal object RecommendationSequencer {
    private const val DIVERSITY_LOOKAHEAD = 4

    fun sequence(candidates: List<Track>, excluded: List<Track>, recent: List<Track>): List<Track> {
        val pending = SongIdentity.deduplicate(
            candidates.filter { it.catalogKind == CatalogKind.Song },
            excluded.map(SongIdentity::key).toSet(),
            excluded.mapNotNull { it.catalogId }.toSet(),
            excluded.map { it.id }.filter(String::isNotBlank).toSet(),
        ).toMutableList()
        val ordered = ArrayList<Track>(pending.size)
        val recentArtists = recent.map { SongIdentity.normalizePrimaryArtist(it.artist) }
        var previousArtist = recentArtists.lastOrNull()
        var artistRun = recentArtists.takeLastWhile { it == previousArtist && it.isNotBlank() }.size
        while (pending.isNotEmpty()) {
            // Move only a nearby alternative. Lower-ranked songs cannot leap an
            // entire page, and a single-artist page stays intact rather than empty.
            val firstArtist = SongIdentity.normalizePrimaryArtist(pending.first().artist)
            val alternative = if (previousArtist.isNullOrBlank() || artistRun < 2 || firstArtist != previousArtist) -1 else {
                pending.take(DIVERSITY_LOOKAHEAD).indexOfFirst {
                    SongIdentity.normalizePrimaryArtist(it.artist).let { artist ->
                        artist.isNotBlank() && artist != previousArtist
                    }
                }
            }
            val next = pending.removeAt(if (alternative >= 0) alternative else 0)
            ordered += next
            val nextArtist = SongIdentity.normalizePrimaryArtist(next.artist)
            artistRun = if (nextArtist.isNotBlank() && nextArtist == previousArtist) artistRun + 1 else 1
            previousArtist = nextArtist
        }
        return ordered
    }
}
