package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebStationSnapshotTest {
    private val station = Track(
        id = "apple:ra.978194965", title = "Apple Music 1", artist = "Apple Music", album = "Radio",
        durationMs = 1L, palette = emptyList(), lyrics = emptyList(), catalogId = "ra.978194965",
        catalogKind = CatalogKind.Station, artworkUrl = "https://example.invalid/station.jpg",
    )

    private fun snapshot(json: String, prepared: Boolean = true, failure: PlaybackFailureKind? = null) =
        webStationSnapshot(JSONObject(json), station, generation = WEB_STATION_GENERATION_BASE + 3,
            prepared = prepared, failure = failure, failureMessage = { "failed:$it" }, nowElapsedMs = 0L)

    @Test fun liveStationIsUnseekableStationItemWithoutDuration() {
        val result = snapshot("""{"state":"playing","playing":true,"live":true,"positionMs":112000,
            "item":{"type":"radioStation","id":"ra.978194965","title":"Apple Music 1","live":true}}""")
        assertTrue(result.isLiveStream)
        assertFalse(result.canSeek)
        assertFalse(result.canSkipNext)
        assertEquals(0L, result.positionMs)
        assertEquals(0L, result.durationMs)
        assertEquals(CatalogKind.Station, result.currentTrack?.catalogKind)
        assertEquals("ra.978194965", result.currentTrack?.catalogId)
        assertTrue(result.hasPlayerCurrentTrack)
        assertEquals(WEB_STATION_GENERATION_BASE + 3, result.requestGeneration)
    }

    @Test fun songInsidePersonalStationKeepsItsCatalogIdForLyrics() {
        val result = snapshot("""{"state":"playing","playing":true,"positionMs":3000,
            "item":{"type":"song","id":"1440","catalogId":"1440","title":"彝人","artist":"A","album":"B",
            "durationMs":240000,"hasLyrics":true,"artworkUrl":"https://example.invalid/600x600.jpg"}}""")
        val track = checkNotNull(result.currentTrack)
        assertEquals(CatalogKind.Song, track.catalogKind)
        assertEquals("1440", track.catalogId)
        assertEquals("apple:1440", track.id)
        assertEquals(240_000L, track.durationMs)
        assertTrue(track.hasLyricsAvailable)
        assertEquals(3_000L, result.positionMs)
        assertTrue(result.canSkipNext)
        assertFalse(result.canSkipPrevious)
        assertFalse(result.isLiveStream)
    }

    @Test fun preparingSnapshotFallsBackToRequestedStationAndCannotSkip() {
        val result = snapshot("""{"state":"loading"}""", prepared = false)
        assertFalse(result.isPrepared)
        assertTrue(result.isBuffering)
        assertFalse(result.hasPlayerCurrentTrack)
        assertFalse(result.canSkipNext)
        assertEquals(station, result.currentTrack)
    }

    @Test fun failureCarriesKindAndMessage() {
        val result = snapshot("{}", prepared = false, failure = PlaybackFailureKind.Authorization)
        assertEquals(PlaybackFailureKind.Authorization, result.failureKind)
        assertEquals("failed:Authorization", result.errorMessage)
        assertNull(snapshot("{}").errorMessage)
    }

    @Test fun musicKitReasonsMapToAppFailureKinds() {
        assertEquals(PlaybackFailureKind.Authorization, webStationFailureKind("AUTHORIZATION_ERROR"))
        assertEquals(PlaybackFailureKind.Authorization, webStationFailureKind("SUBSCRIPTION_ERROR"))
        assertEquals(PlaybackFailureKind.Network, webStationFailureKind("NETWORK_ERROR"))
        assertEquals(PlaybackFailureKind.TrackUnavailable, webStationFailureKind("GEO_BLOCK"))
        assertEquals(PlaybackFailureKind.Unexpected, webStationFailureKind("MEDIA_LICENSE"))
    }
}
