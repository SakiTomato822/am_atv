package dev.amatv.core

import dev.amatv.core.SettingsStoreTest.FakeSettingsStorage
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsStoreTest {

    private class FakeLyricsSource : LyricsSource {
        var playableTrackResolver: (Track) -> Track = { it }
        var lyricsResolver: (Track, LyricsLocalizationRequest) -> List<LyricLine> = { _, _ -> emptyList() }
        var canWrite: Boolean = true
        override var cacheGeneration: Long = 1L

        var lyricsForCallCount = 0
        var resolvePlayableTrackCallCount = 0

        override suspend fun resolvePlayableTrack(track: Track): Track {
            resolvePlayableTrackCallCount++
            return playableTrackResolver(track)
        }

        override suspend fun lyricsFor(track: Track, localization: LyricsLocalizationRequest): List<LyricLine> {
            lyricsForCallCount++
            return lyricsResolver(track, localization)
        }

        override fun canWriteCache(): Boolean = canWrite
    }

    private fun lyricLine(text: String, startMs: Long = 0L, endMs: Long = 1_000L): LyricLine =
        LyricLine(listOf(LyricWord(text, startMs, endMs)))

    private fun testTrack(
        id: String = "1001",
        catalogId: String? = "1001",
        lyrics: List<LyricLine> = emptyList(),
        resourceType: String? = "songs",
        kind: CatalogKind = CatalogKind.Song,
    ): Track = Track(
        id = id,
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 180_000L,
        palette = emptyList(),
        lyrics = lyrics,
        catalogId = catalogId,
        catalogKind = kind,
        resourceType = resourceType,
    )

    private fun createStore(
        source: LyricsSource,
        settingsStore: SettingsStore = SettingsStore(FakeSettingsStorage()),
    ): LyricsStore = LyricsStore(
        source = source,
        settingsStore = settingsStore,
        coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        appLanguageTagProvider = { "en-US" },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun loadsLyricsSuccessfullyAndUpdatesStateToReady() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ -> listOf(lyricLine("Hello world")) }
        }
        val store = createStore(source)
        val track = testTrack(catalogId = "1001")

        store.setCurrentTrack(track, isSignedIn = true)

        assertEquals("1001", store.state.value.trackIdentity)
        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)
        assertTrue(store.state.value.isReady)
        assertEquals(1, store.state.value.lines.size)
        assertEquals("Hello world", store.state.value.lines[0].text)
        assertEquals(1, source.lyricsForCallCount)
    }

    @Test
    fun hitMemoryCacheAvoidsNetworkCall() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ -> listOf(lyricLine("Cached Line")) }
        }
        val store = createStore(source)
        val track1 = testTrack(catalogId = "2001")
        val track2 = testTrack(catalogId = "2002")

        store.setCurrentTrack(track1, isSignedIn = true)
        assertEquals(1, source.lyricsForCallCount)

        store.setCurrentTrack(track2, isSignedIn = true)
        assertEquals(2, source.lyricsForCallCount)

        // Switching back to track1 hits in-memory cache
        store.setCurrentTrack(track1, isSignedIn = true)
        assertEquals(2, source.lyricsForCallCount)
        assertEquals("2001", store.state.value.trackIdentity)
        assertEquals("Cached Line", store.state.value.lines[0].text)
    }

    @Test
    fun cachesBaselineWhenLocalizationDisabled() = runBlocking {
        val source = FakeLyricsSource()
        val settings = SettingsStore(FakeSettingsStorage()).apply {
            setLyricTranslationEnabled(false)
            setLyricPronunciationEnabled(false)
        }
        val store = createStore(source, settings)
        val track = testTrack(
            catalogId = "3001",
            lyrics = listOf(lyricLine("Original Baseline")),
        )

        store.setCurrentTrack(track, isSignedIn = true)

        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)
        assertEquals(1, store.state.value.lines.size)
        assertEquals("Original Baseline", store.state.value.lines[0].text)
        assertEquals(0, source.lyricsForCallCount)
    }

    @Test
    fun handlesUnavailableDefinitiveMissAndRemembersFailure() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ -> emptyList() } // Empty means unavailable
        }
        val store = createStore(source)
        val track = testTrack(catalogId = "4001")

        store.setCurrentTrack(track, isSignedIn = true)

        assertEquals(LyricsLoadPhase.Unavailable, store.state.value.phase)
        assertTrue(store.state.value.isLookupFailed)
        assertEquals(1, source.lyricsForCallCount)

        // Re-requesting same track does not trigger network call again due to recorded failure
        store.setCurrentTrack(testTrack(catalogId = "4002"), isSignedIn = true)
        store.setCurrentTrack(track, isSignedIn = true)
        assertEquals(2, source.lyricsForCallCount) // Only called for 4001 and 4002, not 4001 again
        assertEquals(LyricsLoadPhase.Unavailable, store.state.value.phase)
    }

    @Test
    fun retainsBaselineDuringLoadingAndTransientFailure() = runBlocking {
        val baselineLines = listOf(lyricLine("Stable Baseline"))
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ ->
                throw dev.amatv.integration.LyricsRequestException(dev.amatv.integration.LyricsFailureReason.Authorization, "Auth error")
            }
        }
        val settings = SettingsStore(FakeSettingsStorage()).apply {
            setLyricTranslationEnabled(true)
        }
        val store = createStore(source, settings)
        val track = testTrack(catalogId = "5001", lyrics = baselineLines)

        store.setCurrentTrack(track, isSignedIn = true)

        // Must retain baseline in Transient Failure (Failed) phase
        assertEquals(LyricsLoadPhase.Failed, store.state.value.phase)
        assertTrue(store.state.value.isTransientFailure)
        assertEquals(baselineLines.size, store.state.value.lines.size)
        assertEquals(baselineLines[0].text, store.state.value.lines[0].text)
    }

    @Test
    fun retryCurrentLyricsClearsFailureAndTriggersFetch() = runBlocking {
        var shouldFail = true
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ ->
                if (shouldFail) throw dev.amatv.integration.LyricsRequestException(dev.amatv.integration.LyricsFailureReason.Authorization, "Auth error")
                else listOf(lyricLine("Recovered Line"))
            }
        }
        val settings = SettingsStore(FakeSettingsStorage()).apply {
            setLyricTranslationEnabled(true)
        }
        val store = createStore(source, settings)
        val track = testTrack(catalogId = "6001")

        store.setCurrentTrack(track, isSignedIn = true)
        assertEquals(LyricsLoadPhase.Failed, store.state.value.phase)

        shouldFail = false
        store.retryCurrentLyrics()

        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)
        assertEquals(1, store.state.value.lines.size)
        assertEquals("Recovered Line", store.state.value.lines[0].text)
    }

    @Test
    fun retryCurrentLyricsOnlyTriggersSingleLyricsForFetch() = runBlocking {
        var fetchCount = 0
        var shouldFail = true
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ ->
                fetchCount++
                if (shouldFail) throw dev.amatv.integration.LyricsRequestException(
                    dev.amatv.integration.LyricsFailureReason.Authorization,
                    "Auth error"
                )
                else listOf(lyricLine("Recovered Line"))
            }
        }
        val store = createStore(source)
        val track = testTrack(catalogId = "6002")

        store.setCurrentTrack(track, isSignedIn = true)
        assertEquals(1, fetchCount)
        assertEquals(LyricsLoadPhase.Failed, store.state.value.phase)

        shouldFail = false
        store.retryCurrentLyrics()
        assertEquals(2, fetchCount)
        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)
    }

    @Test
    fun duplicateSetCurrentTrackWithSameVariantIsNoOp() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ -> listOf(lyricLine("Test")) }
        }
        val store = createStore(source)
        val track = testTrack(catalogId = "7001")

        store.setCurrentTrack(track, isSignedIn = true)
        assertEquals(1, source.lyricsForCallCount)
        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)

        // Exact same track and variant
        store.setCurrentTrack(track, isSignedIn = true)
        assertEquals(1, source.lyricsForCallCount)
    }

    @Test
    fun trackIdentityResolutionMigratesCacheKeys() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { _, _ -> listOf(lyricLine("Migrated Line")) }
        }
        val store = createStore(source)
        val originalTrack = testTrack(id = "library:8001", catalogId = "8001")

        store.setCurrentTrack(originalTrack, isSignedIn = true)
        assertEquals(LyricsLoadPhase.Ready, store.state.value.phase)

        // Resolve to new catalog identity
        store.onTrackIdentityResolved(oldIdentity = "8001", newIdentity = "resolved:8001")

        assertEquals("resolved:8001", store.state.value.trackIdentity)
        val candidate = testTrack(id = "resolved:8001", catalogId = "resolved:8001")
        val withLyrics = store.withCachedLyrics(candidate)
        assertEquals(1, withLyrics.lyrics.size)
        assertEquals("Migrated Line", withLyrics.lyrics[0].text)
    }

    @Test
    fun prefetchUpcomingTargetsLoadsAndCachesAhead() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { track, _ -> listOf(lyricLine("Prefetched ${track.catalogId}")) }
        }
        val settings = SettingsStore(FakeSettingsStorage()).apply {
            setCacheDiskEnabled(true)
            setCachePrefetchNextTwo(true)
        }
        val store = createStore(source, settings)
        val currentTrack = testTrack(catalogId = "9001")
        store.setCurrentTrack(currentTrack, isSignedIn = true)
        assertEquals(1, source.lyricsForCallCount)

        val next1 = testTrack(catalogId = "9002")
        val next2 = testTrack(catalogId = "9003")
        store.setPrefetchTargets(listOf(next1, next2), isPlaying = true)

        assertEquals(3, source.lyricsForCallCount)
        val cached1 = store.withCachedLyrics(next1)
        val cached2 = store.withCachedLyrics(next2)
        assertEquals("Prefetched 9002", cached1.lyrics.firstOrNull()?.text)
        assertEquals("Prefetched 9003", cached2.lyrics.firstOrNull()?.text)
    }

    @Test
    fun cacheClearedRetainsActiveLines() = runBlocking {
        val source = FakeLyricsSource().apply {
            lyricsResolver = { track, _ -> listOf(lyricLine("Line ${track.catalogId}")) }
        }
        val store = createStore(source)
        val track1 = testTrack(catalogId = "10001")
        val track2 = testTrack(catalogId = "10002")

        store.setCurrentTrack(track1, isSignedIn = true)
        store.setCurrentTrack(track2, isSignedIn = true)
        assertEquals("10002", store.state.value.trackIdentity)

        store.onCacheCleared()

        // Active track lines are retained
        assertEquals("10002", store.state.value.trackIdentity)
        assertEquals(1, store.state.value.lines.size)
        assertEquals("Line 10002", store.state.value.lines[0].text)

        // Previous track cache was cleared
        val track1WithLyrics = store.withCachedLyrics(track1)
        assertTrue(track1WithLyrics.lyrics.isEmpty())
    }
}
