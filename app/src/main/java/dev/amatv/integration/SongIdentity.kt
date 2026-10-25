package dev.amatv.integration

import dev.amatv.model.Track
import java.text.Normalizer
import java.util.Locale

/**
 * Universal track identity and deduplication engine.
 * Normalizes titles and artists to identify when two tracks represent the same song
 * (e.g. single version vs album version, remastered, live, feat. differences)
 * to prevent looping or duplicate playback.
 */
object SongIdentity {

    private val FEAT_REGEX = Regex("(?i)\\s*[\\[(](?:feat\\.|ft\\.|featuring|with)\\s+.*?[)\\]]")
    private val BRACKETED_SUFFIX_REGEX = Regex("(?i)\\s*[\\[(](?:remastered|remaster|live|radio edit|deluxe|bonus track|clean|explicit|version|official|mono|stereo|album version|single version|acoustic).*?[)\\]]")
    private val DASH_SUFFIX_REGEX = Regex("(?i)\\s*-\\s*(?:remastered|remaster|live|radio edit|deluxe|bonus track|single version|album version).*$")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{Nd}]+")

    /**
     * Normalizes a song title:
     * - Strips `(feat. ...)` or `[feat. ...]`
     * - Strips `(Remastered 2011)`, `(Live at ...)`, `- Single Version`, etc.
     * - Normalizes unicode NFKC
     * - Converts to lower-case and strips non-alphanumeric characters
     */
    fun normalizeTitle(title: String): String {
        if (title.isBlank()) return ""
        var cleaned = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        cleaned = FEAT_REGEX.replace(cleaned, "")
        cleaned = BRACKETED_SUFFIX_REGEX.replace(cleaned, "")
        cleaned = DASH_SUFFIX_REGEX.replace(cleaned, "")
        cleaned = NON_ALPHANUMERIC_REGEX.replace(cleaned, " ").trim().lowercase(Locale.ROOT)
        return cleaned.ifBlank { title.trim().lowercase(Locale.ROOT) }
    }

    /**
     * Normalizes the primary artist:
     * Extracts the lead artist before `&`, `feat.`, `ft.`, `,`.
     */
    fun normalizePrimaryArtist(artist: String): String {
        if (artist.isBlank()) return ""
        val cleaned = Normalizer.normalize(artist, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        val primary = cleaned.split(Regex("(?i)\\s*[,&]\\s*|\\s+(?:feat\\.?|ft\\.?|featuring|with|x)\\s+"))
            .firstOrNull()?.trim() ?: cleaned.trim()
        val normalized = NON_ALPHANUMERIC_REGEX.replace(primary, " ").trim().lowercase(Locale.ROOT)
        return normalized.ifBlank { artist.trim().lowercase(Locale.ROOT) }
    }

    /**
     * Unique identity key for a track: `normalizedTitle|normalizedPrimaryArtist`.
     */
    fun key(track: Track): String {
        val titleKey = normalizeTitle(track.title)
        val artistKey = normalizePrimaryArtist(track.artist)
        if (titleKey.isNotBlank() && artistKey.isNotBlank()) return "$titleKey|$artistKey"
        return track.catalogId?.takeIf(String::isNotBlank)?.let { "catalog:$it" }
            ?: track.id.takeIf(String::isNotBlank)?.let { "track:$it" }.orEmpty()
    }

    /**
     * Returns true if two tracks represent the same song.
     */
    fun isSameSong(a: Track, b: Track): Boolean {
        if (!a.catalogId.isNullOrBlank() && a.catalogId == b.catalogId) return true
        if (a.id.isNotBlank() && a.id == b.id) return true
        val keyA = key(a)
        val keyB = key(b)
        return keyA.isNotBlank() && keyA == keyB
    }

    /** Editions may share a deduplication key, but must never be substituted in a search match. */
    fun isSameEdition(a: String, b: String): Boolean = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed",
        "re recorded", "taylor s version", "現場", "伴奏",
    ).all { marker ->
        val expression = if (marker.any { it.code > 127 }) Regex(Regex.escape(marker))
        else Regex("\\b${Regex.escape(marker)}\\b")
        fun editionText(value: String) = value.lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC_REGEX, " ").replace(Regex("\\s+"), " ")
        expression.containsMatchIn(editionText(a)) == expression.containsMatchIn(editionText(b))
    }

    /**
     * Deduplicates a list of tracks, rejecting any track matching [excludedKeys]
     * or duplicates within the list itself.
     */
    fun deduplicate(
        tracks: List<Track>,
        excludedKeys: Set<String> = emptySet(),
        excludedCatalogIds: Set<String> = emptySet(),
        excludedTrackIds: Set<String> = emptySet(),
    ): List<Track> {
        val seenKeys = HashSet<String>(excludedKeys)
        val seenCatalogIds = HashSet<String>(excludedCatalogIds)
        val seenTrackIds = HashSet<String>(excludedTrackIds)
        val result = ArrayList<Track>(tracks.size)
        for (track in tracks) {
            val catId = track.catalogId
            val k = key(track)
            if (!catId.isNullOrBlank() && catId in seenCatalogIds) continue
            if (track.id.isNotBlank() && track.id in seenTrackIds) continue
            if (k.isNotBlank() && k in seenKeys) continue
            if (!catId.isNullOrBlank()) seenCatalogIds.add(catId)
            if (track.id.isNotBlank()) seenTrackIds.add(track.id)
            if (k.isNotBlank()) seenKeys.add(k)
            result.add(track)
        }
        return result
    }
}
