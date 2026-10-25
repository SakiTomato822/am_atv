package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {

    private fun testTrack(
        id: String,
        title: String,
        artist: String,
        catalogId: String? = null,
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogId = catalogId ?: id,
        catalogKind = CatalogKind.Song,
    )

    @Test
    fun testTitleNormalizationStripsBracketsAndSuffixes() {
        assertEquals("hotel california", SongIdentity.normalizeTitle("Hotel California (Remastered 2013)"))
        assertEquals("hotel california", SongIdentity.normalizeTitle("Hotel California - Live at the Forum"))
        assertEquals("bad guy", SongIdentity.normalizeTitle("bad guy (feat. Justin Bieber)"))
        assertEquals("starboy", SongIdentity.normalizeTitle("Starboy [Official Audio]"))
    }

    @Test
    fun testPrimaryArtistNormalizationExtractsLeadArtist() {
        assertEquals("the weeknd", SongIdentity.normalizePrimaryArtist("The Weeknd feat. Daft Punk"))
        assertEquals("billie eilish", SongIdentity.normalizePrimaryArtist("Billie Eilish & Khalid"))
        assertEquals("post malone", SongIdentity.normalizePrimaryArtist("Post Malone, Swae Lee"))
        assertEquals("alex warren", SongIdentity.normalizePrimaryArtist("Alex Warren"))
        assertEquals("the weeknd", SongIdentity.normalizePrimaryArtist("The Weeknd"))
        assertEquals("artist", SongIdentity.normalizePrimaryArtist("Artist&Guest"))
    }

    @Test
    fun testIsSameSongDetectsVariantsAcrossCatalog() {
        val original = testTrack("1", "Blinding Lights", "The Weeknd", catalogId = "1001")
        val remastered = testTrack("2", "Blinding Lights (Remastered)", "The Weeknd", catalogId = "1002")
        val featured = testTrack("3", "Blinding Lights (feat. Rosalía)", "The Weeknd", catalogId = "1003")

        assertTrue(SongIdentity.isSameSong(original, remastered))
        assertTrue(SongIdentity.isSameSong(original, featured))
    }

    @Test
    fun testDeduplicateDropsDuplicates() {
        val t1 = testTrack("1", "Shape of You", "Ed Sheeran", catalogId = "2001")
        val t2 = testTrack("2", "Shape of You (Acoustic)", "Ed Sheeran", catalogId = "2002")
        val t3 = testTrack("3", "Castle on the Hill", "Ed Sheeran", catalogId = "2003")

        val deduped = SongIdentity.deduplicate(listOf(t1, t2, t3))
        assertEquals(2, deduped.size)
        assertEquals("Shape of You", deduped[0].title)
        assertEquals("Castle on the Hill", deduped[1].title)
    }

    @Test fun missingMetadataDoesNotCollapseUnrelatedCatalogSongs() {
        val tracks = listOf(testTrack("1", "", ""), testTrack("2", "", ""))
        assertFalse(SongIdentity.isSameSong(tracks[0], tracks[1]))
        assertEquals(2, SongIdentity.deduplicate(tracks).size)
    }
}

