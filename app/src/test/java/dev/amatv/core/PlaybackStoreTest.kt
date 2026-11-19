package dev.amatv.core

import android.content.Intent
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class PlaybackStoreTest {

    private val baseTrack = MockCatalog.tracks.first()

    private fun song(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        durationMs: Long = 180_000L,
    ) = baseTrack.copy(
        id = id,
        title = title,
        artist = artist,
        album = "Album $id",
        durationMs = durationMs,
        lyrics = emptyList(),
        catalogId = id,
        catalogKind = CatalogKind.Song,
        resourceId = id,
        resourceType = "songs",
    )

    private fun demoTrack(
        id: String = "demo-1",
        durationMs: Long = 180_000L,
    ) = baseTrack.copy(
        id = id,
        title = "Demo $id",
        artist = "Demo Artist",
        album = "Demo Album",
        durationMs = durationMs,
        lyrics = emptyList(),
        catalogId = null,
    )

    private fun station(id: String = "st-1") = baseTrack.copy(
        id = id,
        title = "Station $id",
        artist = "Apple Music",
        album = "Radio",
        durationMs = 0L,
        lyrics = emptyList(),
        catalogId = id,
        catalogKind = CatalogKind.Station,
        resourceId = id,
        resourceType = "stations",
    )

    private fun snapshot(
        track: Track?,
        generation: Long,
        positionMs: Long = 0L,
        isPlaying: Boolean = false,
        isPrepared: Boolean = true,
        isEnded: Boolean = false,
        hasPlayerCurrentTrack: Boolean = track != null,
        errorMessage: String? = null,
        failureKind: PlaybackFailureKind? = null,
        gaplessAdvanceCatalogId: String? = null,
        canSkipNext: Boolean = false,
        canSkipPrevious: Boolean = false,
        capturedAtMs: Long = 1_000L,
    ) = PlaybackSnapshot(
        currentTrack = track,
        hasPlayerCurrentTrack = hasPlayerCurrentTrack,
        queue = listOfNotNull(track),
        positionMs = positionMs,
        durationMs = track?.durationMs ?: 0L,
        isPlaying = isPlaying,
        isBuffering = false,
        canSkipNext = canSkipNext,
        canSkipPrevious = canSkipPrevious,
        repeatMode = PlaybackRepeatMode.Off,
        shuffleEnabled = false,
        autoplayEnabled = false,
        canSetRepeat = true,
        canSetShuffle = true,
        canSetAutoplay = true,
        errorMessage = errorMessage,
        gaplessAdvanceCatalogId = gaplessAdvanceCatalogId,
        capturedAtElapsedRealtimeMs = capturedAtMs,
        isEnded = isEnded,
        requestGeneration = generation,
        isPrepared = isPrepared,
        failureKind = failureKind,
    )

    private class DelayGate {
        private data class Entry(val ms: Long, val deferred: CompletableDeferred<Unit>)

        private val entries = mutableListOf<Entry>()

        suspend fun delay(ms: Long) {
            val d = CompletableDeferred<Unit>()
            entries += Entry(ms, d)
            try {
                d.await()
            } finally {
                entries.removeAll { it.deferred === d }
            }
        }

        suspend fun fireOne(ms: Long) {
            repeat(5) { yield() }
            var attempts = 0
            while (attempts < 20) {
                entries.removeAll { !it.deferred.isActive }
                val match = entries.firstOrNull { it.ms == ms && it.deferred.isActive }
                if (match != null) {
                    entries.remove(match)
                    match.deferred.complete(Unit)
                    repeat(5) { yield() }
                    return
                }
                yield()
                attempts++
            }
            error("No pending delay($ms) registered. Pending: ${entries.map { it.ms }}")
        }
    }

    private class FakePlayerOperations : PlayerOperations {
        override var isCatalogConfigured: Boolean = true
        override var widevineReady: Boolean = true

        var listener: ((PlaybackSnapshot) -> Unit)? = null
        var systemCallbacks: PlaybackMediaSession.Callbacks? = null

        var nextGeneration: Long = 1L
        var prepareGate: CompletableDeferred<Unit>? = null
        var prepareError: Throwable? = null
        var resolveHook: (Track) -> Track = { it }
        val prepareCalls = mutableListOf<Triple<Track, Long, Boolean>>()
        val resolvedCalls = mutableListOf<Track>()
        var playCalls = 0
        var pauseCalls = 0
        var seekCalls = mutableListOf<Long>()
        var skipNextCalls = 0
        var skipPreviousCalls = 0
        val queuedSongCalls = mutableListOf<String?>()
        var nativeQueueSongs = emptySet<String>()
        val lookaheadHistory = mutableListOf<Track?>()
        var currentLookahead: Track? = null
        val fadeHistory = mutableListOf<Boolean>()
        val publishedSnapshots = mutableListOf<Triple<PlaybackSnapshot, Boolean, Boolean>>()
        var createStationCalls = 0
        var stationTracks: List<Track> = emptyList()

        override suspend fun resolvePlayableTrack(track: Track): Track {
            resolvedCalls += track
            return resolveHook(track)
        }

        override suspend fun preparePlayback(
            track: Track,
            positionMs: Long,
            playWhenReady: Boolean,
        ): Long {
            prepareCalls += Triple(track, positionMs, playWhenReady)
            prepareGate?.await()
            prepareError?.let { throw it }
            return nextGeneration++
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {
            pauseCalls++
        }

        override fun seekTo(positionMs: Long) {
            seekCalls += positionMs
        }

        override fun skipToNext() {
            skipNextCalls++
        }

        override fun skipToPrevious() {
            skipPreviousCalls++
        }

        override suspend fun playQueuedSong(track: Track): Long? {
            queuedSongCalls += track.catalogId
            return if (track.catalogId in nativeQueueSongs) nextGeneration++ else null
        }

        override fun setLookahead(next: Track?) {
            currentLookahead = next
            lookaheadHistory += next
        }

        override fun setFadeEnabled(enabled: Boolean) {
            fadeHistory += enabled
        }

        override suspend fun stationSongs(stationId: String): List<Track> = emptyList()

        override suspend fun prefetchLibraryDetails() {}

        override fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
            this.listener = listener
        }

        override fun setSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?) {
            this.systemCallbacks = callbacks
        }

        override fun publishSystemPlayback(
            snapshot: PlaybackSnapshot,
            canSkipNext: Boolean,
            canSkipPrevious: Boolean,
        ) {
            publishedSnapshots += Triple(snapshot, canSkipNext, canSkipPrevious)
        }

        override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> {
            createStationCalls++
            return "am-st-$songId" to stationTracks
        }

        override suspend fun amStationNextTracks(stationId: String): List<Track> = emptyList()

        fun emit(snapshot: PlaybackSnapshot) {
            listener?.invoke(snapshot)
        }
    }

    private class FakeSessionStorage(
        override var activeTab: Destination = Destination.Home,
        var checkpoint: PlaybackSession? = null,
        var fullSession: PlaybackSession? = null,
        var fullSessionGate: CompletableDeferred<PlaybackSession?>? = null,
    ) : PlaybackSessionStorage {
        val savedSessions = mutableListOf<Pair<PlaybackSession, Boolean>>()
        val savedActiveTabs = mutableListOf<Pair<Destination, Boolean>>()
        var stationHistory: List<Track> = emptyList()

        override fun restoreCheckpointSession(): PlaybackSession? = checkpoint

        override fun restoreSession(): PlaybackSession? = fullSession

        override fun saveSession(session: PlaybackSession, synchronous: Boolean) {
            savedSessions += session to synchronous
        }

        override fun saveActiveTab(tab: Destination, synchronous: Boolean) {
            savedActiveTabs += tab to synchronous
        }

        override fun loadStationHistory(): List<Track> = stationHistory

        override fun saveStationHistory(songs: List<Track>) {
            stationHistory = songs
        }
    }

    private class AsyncSessionStorage(
        override var activeTab: Destination = Destination.Home,
        var checkpoint: PlaybackSession? = null,
        val enteredRestore: CompletableDeferred<Unit> = CompletableDeferred(),
        var sessionGate: CompletableDeferred<PlaybackSession?>? = null,
        var immediateSession: PlaybackSession? = null,
    ) : PlaybackSessionStorage {
        val savedSessions = mutableListOf<Pair<PlaybackSession, Boolean>>()
        var stationHistory: List<Track> = emptyList()

        override fun restoreCheckpointSession(): PlaybackSession? = checkpoint

        override fun restoreSession(): PlaybackSession? = runBlocking {
            enteredRestore.complete(Unit)
            sessionGate?.await() ?: immediateSession
        }

        override fun saveSession(session: PlaybackSession, synchronous: Boolean) {
            savedSessions += session to synchronous
        }

        override fun saveActiveTab(tab: Destination, synchronous: Boolean) = Unit

        override fun loadStationHistory(): List<Track> = stationHistory

        override fun saveStationHistory(songs: List<Track>) {
            stationHistory = songs
        }
    }

    private class FakeAccountOps(
        override var connectionState: MusicConnectionState = MusicConnectionState.SignedIn,
    ) : AccountOperations {
        var signOutCalls = 0
        var onSignOutHook: () -> Unit = {}

        override fun completeWebAuthentication(data: Intent): String = "token"
        override fun signOut() {
            signOutCalls++
            onSignOutHook()
            connectionState = MusicConnectionState.ReadyToSignIn
        }
    }

    private class FakeSettingsStorage(
        private val initial: SettingsState = SettingsState(autoplayEnabled = false),
    ) : SettingsStorage {
        override fun loadInitialState(): SettingsState = initial
        override fun saveRepeatMode(mode: PlaybackRepeatMode) = Unit
        override fun saveShuffleEnabled(enabled: Boolean) = Unit
        override fun saveAutoplayEnabled(enabled: Boolean) = Unit
        override fun saveLowPerformanceMode(enabled: Boolean) = Unit
        override fun saveLyricsPreferred(preferred: Boolean) = Unit
        override fun saveLyricOffsetMs(offsetMs: Long) = Unit
        override fun saveLyricTextSize(size: dev.amatv.integration.LyricTextSize) = Unit
        override fun saveLyricTranslationEnabled(enabled: Boolean) = Unit
        override fun saveLyricPronunciationEnabled(enabled: Boolean) = Unit
        override fun saveLyricTranslationLanguageTag(tag: String) = Unit
        override fun saveLyricPronunciationLanguageTag(tag: String) = Unit
        override fun saveLyricStyle(style: LyricBackgroundStyle) = Unit
        override fun saveAppearance(appearance: AppearanceMode) = Unit
        override fun saveLightSchedule(fromMinute: Int, untilMinute: Int) = Unit
        override fun saveFadeAlbums(fade: Boolean) = Unit
        override fun saveFadeStations(fade: Boolean) = Unit
        override fun saveFadePlaylists(fade: Boolean) = Unit
        override fun saveStationsAsSongs(asSongs: Boolean) = Unit
        override fun saveCacheDiskEnabled(enabled: Boolean) = Unit
        override fun saveCachePrefetchNextTwo(enabled: Boolean) = Unit
    }

    private class FakeLyricsSource : LyricsSource {
        override suspend fun resolvePlayableTrack(track: Track): Track = track
        override suspend fun lyricsFor(
            track: Track,
            localization: LyricsLocalizationRequest,
        ): List<LyricLine> = emptyList()
        override fun canWriteCache(): Boolean = false
        override val cacheGeneration: Long = 0L
    }

    private class FakeLibraryApi : LibraryActionsApi {
        override suspend fun isFavorite(catalogSongId: String): Boolean? = null
        override suspend fun addFavorite(catalogSongId: String) = Unit
        override suspend fun isSongInLibrary(catalogSongId: String): Boolean? = null
        override suspend fun addToLibrary(catalogSongId: String) = Unit
    }

    private class TestHarness(
        val store: PlaybackStore,
        val player: FakePlayerOperations,
        val sessions: PlaybackSessionStorage,
        val accountStore: AccountStore,
        val accountOps: FakeAccountOps,
        val delayGate: DelayGate,
        val scope: CoroutineScope,
        var nowMs: Long,
    ) {
        fun close() {
            scope.cancel()
        }
    }

    private fun createHarness(
        runBlockingContext: kotlin.coroutines.CoroutineContext,
        connectionState: MusicConnectionState = MusicConnectionState.SignedIn,
        sessions: PlaybackSessionStorage = FakeSessionStorage(),
        settingsState: SettingsState = SettingsState(autoplayEnabled = false),
        flymeAuto: Boolean = true,
        isCatalogConfigured: Boolean = true,
        ioDispatcherOverride: CoroutineDispatcher? = null,
    ): TestHarness {
        val dispatcher = runBlockingContext[ContinuationInterceptor] as CoroutineDispatcher
        val scope = CoroutineScope(runBlockingContext + SupervisorJob(runBlockingContext[kotlinx.coroutines.Job]))
        val player = FakePlayerOperations().apply {
            this.isCatalogConfigured = isCatalogConfigured
        }
        val accountOps = FakeAccountOps(connectionState)
        val accountStore = AccountStore(accountOps)
        val settingsStore = SettingsStore(FakeSettingsStorage(settingsState))
        val lyricsStore = LyricsStore(
            source = FakeLyricsSource(),
            settingsStore = settingsStore,
            coroutineScope = scope,
            appLanguageTagProvider = { "en-US" },
            ioDispatcher = dispatcher,
        )
        val libraryActions = LibraryActions(
            api = FakeLibraryApi(),
            coroutineScope = scope,
            ioDispatcher = dispatcher,
        )
        val delayGate = DelayGate()
        var nowMs = 1_000L
        val store = PlaybackStore(
            player = player,
            sessions = sessions,
            accountStore = accountStore,
            settingsStore = settingsStore,
            lyricsStore = lyricsStore,
            libraryActions = libraryActions,
            coroutineScope = scope,
            clock = { nowMs },
            ioDispatcher = ioDispatcherOverride ?: dispatcher,
            flymeAuto = flymeAuto,
            delayFn = { delayGate.delay(it) },
        )
        return TestHarness(store, player, sessions, accountStore, accountOps, delayGate, scope, nowMs)
    }

    @Test
    fun playTrack_resolvesPreparesAndFollowsPreparedSnapshot() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            val events = mutableListOf<PlaybackEvent>()
            val collector = h.scope.launch { h.store.events.collect { events += it } }
            h.store.attach()
            yield()

            val s1 = song("101")
            h.store.playTrack(s1, listOf(s1))
            yield()

            assertEquals(listOf(s1), h.player.resolvedCalls)
            assertEquals(1, h.player.prepareCalls.size)
            assertEquals(Triple(s1, 0L, false), h.player.prepareCalls[0])
            assertEquals(1, h.player.playCalls)
            assertFalse(h.store.state.value.playbackPrepared)
            assertFalse(h.store.state.value.isPlaying)
            assertTrue(PlaybackEvent.ShowNowPlaying in events)

            h.player.emit(snapshot(s1, generation = 1L, positionMs = 100L, isPlaying = true, isPrepared = true))

            assertTrue(h.store.state.value.playbackPrepared)
            assertTrue(h.store.state.value.isPlaying)
            assertNotNull(h.store.state.value.snapshot)
            collector.cancel()
        } finally {
            h.close()
        }
    }

    @Test
    fun pauseDuringPrepare_callsPauseInsteadOfPlay() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            val gate = CompletableDeferred<Unit>()
            h.player.prepareGate = gate
            h.store.attach()
            yield()

            val s1 = song("102")
            h.store.playTrack(s1, listOf(s1))
            yield()

            assertEquals(1, h.player.prepareCalls.size)
            h.store.pausePlayback()
            assertFalse(h.store.state.value.playWhenReady)
            val pausesRightAfterPause = h.player.pauseCalls

            gate.complete(Unit)
            yield()

            assertEquals(0, h.player.playCalls)
            assertEquals(pausesRightAfterPause + 1, h.player.pauseCalls)
        } finally {
            h.close()
        }
    }

    @Test
    fun staleGenerationSnapshot_isIgnored() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            h.store.attach()
            yield()

            val s1 = song("103")
            h.store.playTrack(s1, listOf(s1))
            yield()

            h.player.emit(snapshot(s1, generation = 0L, positionMs = 5_000L, isPlaying = true, isPrepared = true))
            h.player.emit(snapshot(s1, generation = 99L, positionMs = 5_000L, isPlaying = true, isPrepared = true))

            assertFalse(h.store.state.value.playbackPrepared)
            assertFalse(h.store.state.value.isPlaying)
            assertNull(h.store.state.value.snapshot)
        } finally {
            h.close()
        }
    }

    @Test
    fun trackUnavailable_skipsToNextAndShowsLimitWhenAllFail() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            h.store.attach()
            yield()

            val s1 = song("201")
            val s2 = song("202")
            h.store.playTrack(s1, listOf(s1, s2))
            yield()

            h.player.emit(
                snapshot(
                    track = s1,
                    generation = 1L,
                    isPrepared = false,
                    errorMessage = "Song unavailable",
                    failureKind = PlaybackFailureKind.TrackUnavailable,
                ),
            )
            yield()

            assertEquals(s2.id, h.store.state.value.track.id)
            assertEquals(2, h.player.prepareCalls.size)
            assertEquals(s2.id, h.player.prepareCalls[1].first.id)

            // Five consecutive failures hit MAX_CONSECUTIVE_FAILURES and stop with failureLimitReached message.
            val failingQueue = (1..6).map { song("fail-$it") }
            h.store.playTrack(failingQueue[0], failingQueue)
            yield()

            repeat(5) { idx ->
                val gen = h.player.nextGeneration - 1L
                val curr = h.store.state.value.track
                h.player.emit(
                    snapshot(
                        track = curr,
                        generation = gen,
                        isPrepared = false,
                        errorMessage = "unavailable-$idx",
                        failureKind = PlaybackFailureKind.TrackUnavailable,
                    ),
                )
                yield()
            }

            assertFalse(h.store.state.value.playWhenReady)
            assertTrue(h.store.queueController.state.value.failureLimitReached)
            assertEquals(
                AppText.text(R.string.app_multiple_songs_unavailable),
                h.store.state.value.playbackMessage,
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun networkFailure_retriesWithCountdownStopsOnThirdAndResetsAfter250msPlayback() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            h.store.attach()
            yield()

            val s1 = song("301")
            h.store.playTrack(s1, listOf(s1))
            yield()

            // First failure -> retry countdown 3, 2, 1 -> prepare #2 (gen=2)
            h.player.emit(
                snapshot(s1, generation = 1L, errorMessage = "net-1", failureKind = PlaybackFailureKind.Network),
            )
            yield()
            assertEquals(AppText.text(R.string.services_song_connection_retry, 3), h.store.state.value.playbackMessage)
            h.delayGate.fireOne(1_000L)
            assertEquals(AppText.text(R.string.services_song_connection_retry, 2), h.store.state.value.playbackMessage)
            h.delayGate.fireOne(1_000L)
            assertEquals(AppText.text(R.string.services_song_connection_retry, 1), h.store.state.value.playbackMessage)
            h.delayGate.fireOne(1_000L)
            assertNull(h.store.state.value.playbackMessage)
            assertEquals(2, h.player.prepareCalls.size)

            // Second failure -> retry countdown -> prepare #3 (gen=3)
            h.player.emit(
                snapshot(s1, generation = 2L, errorMessage = "net-2", failureKind = PlaybackFailureKind.Network),
            )
            repeat(3) { h.delayGate.fireOne(1_000L) }
            assertEquals(3, h.player.prepareCalls.size)

            // Third failure (networkRetryCount == 2) -> stops with error message
            h.player.emit(
                snapshot(s1, generation = 3L, errorMessage = "net-final", failureKind = PlaybackFailureKind.Network),
            )
            yield()
            assertFalse(h.store.state.value.playWhenReady)
            assertEquals("net-final", h.store.state.value.playbackMessage)
            assertEquals(3, h.player.prepareCalls.size)

            // Part B: fail twice on s2, progress > 250ms, then verify retry counter was reset.
            val s2 = song("302")
            h.store.playTrack(s2, listOf(s2))
            yield()
            val genA = h.player.nextGeneration - 1L
            h.player.emit(
                snapshot(s2, generation = genA, errorMessage = "net-a", failureKind = PlaybackFailureKind.Network),
            )
            repeat(3) { h.delayGate.fireOne(1_000L) }
            val genB = h.player.nextGeneration - 1L
            h.player.emit(
                snapshot(s2, generation = genB, errorMessage = "net-b", failureKind = PlaybackFailureKind.Network),
            )
            repeat(3) { h.delayGate.fireOne(1_000L) }

            val genC = h.player.nextGeneration - 1L
            h.player.emit(snapshot(s2, generation = genC, positionMs = 300L, isPlaying = true, isPrepared = true))
            yield()

            val preparesBeforeResetCheck = h.player.prepareCalls.size
            h.player.emit(
                snapshot(s2, generation = genC, errorMessage = "net-after-reset", failureKind = PlaybackFailureKind.Network),
            )
            repeat(3) { h.delayGate.fireOne(1_000L) }
            assertEquals(preparesBeforeResetCheck + 1, h.player.prepareCalls.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun gaplessLookahead_preloadsAfterDelayAndAdoptsWithoutPreparePlayback() = runBlocking {
        val h = createHarness(coroutineContext, flymeAuto = true)
        try {
            h.store.attach()
            yield()

            val s1 = song("401")
            val s2 = song("402")
            h.store.playTrack(s1, listOf(s1, s2))
            yield()
            assertEquals(1, h.player.prepareCalls.size)

            h.player.emit(snapshot(s1, generation = 1L, positionMs = 500L, isPlaying = true, isPrepared = true))
            h.delayGate.fireOne(1_500L)

            assertEquals(s2, h.player.currentLookahead)

            h.player.emit(
                snapshot(
                    track = s2,
                    generation = 1L,
                    positionMs = 10L,
                    isPlaying = true,
                    isPrepared = true,
                    hasPlayerCurrentTrack = true,
                    gaplessAdvanceCatalogId = s2.catalogId,
                ),
            )
            yield()

            assertEquals(s2.id, h.store.state.value.track.id)
            assertTrue(h.store.state.value.playbackPrepared)
            assertTrue(h.store.state.value.isPlaying)
            assertEquals(1, h.player.prepareCalls.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun manualNavigationAfterGaplessKeepsQueueHistoryAndIgnoresSupersededSnapshots() = runBlocking {
        val h = createHarness(coroutineContext, flymeAuto = true)
        try {
            h.store.attach()
            yield()
            val a = song("411")
            val b = song("412")
            val c = song("413")
            val d = song("414")
            h.store.playTrack(a, listOf(a, b, c, d))
            yield()
            h.player.emit(snapshot(a, 1L, positionMs = 500L, isPlaying = true))
            h.delayGate.fireOne(1_500L)
            h.player.emit(snapshot(b, 1L, positionMs = 10L, isPlaying = true, gaplessAdvanceCatalogId = b.catalogId))
            withTimeout(1_000L) {
                while (h.player.currentLookahead != c) yield()
            }
            h.player.nativeQueueSongs = setOf("411", "412", "413")
            val resolvesBeforeNavigation = h.player.resolvedCalls.size

            h.player.systemCallbacks?.previous?.invoke()
            yield()
            assertEquals(a.id, h.store.state.value.track.id)
            h.store.skip(1)
            yield()
            h.store.skip(1)
            yield()
            h.player.emit(snapshot(b, 3L, isPlaying = true))
            assertEquals(c.id, h.store.state.value.track.id)
            h.player.emit(snapshot(c, 4L, positionMs = 300L, isPlaying = true))
            assertTrue(h.store.state.value.playbackPrepared)

            h.store.selectQueueTrack(0)
            yield()
            assertEquals(a.id, h.store.state.value.track.id)
            assertEquals(listOf("411", "412", "413", "411"), h.player.queuedSongCalls)
            assertEquals(1, h.player.prepareCalls.size)
            assertEquals(resolvesBeforeNavigation, h.player.resolvedCalls.size)

            h.store.selectQueueTrack(3)
            yield()
            assertEquals(d.id, h.store.state.value.track.id)
            assertEquals(2, h.player.prepareCalls.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun isEndedSnapshot_advancesNextOnceOnly() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            h.store.attach()
            yield()

            val s1 = song("501")
            val s2 = song("502")
            h.store.playTrack(s1, listOf(s1, s2))
            yield()

            h.player.emit(snapshot(s1, generation = 1L, positionMs = 179_000L, isPlaying = true, isPrepared = true))
            val ended = snapshot(s1, generation = 1L, positionMs = 180_000L, isPlaying = false, isPrepared = true, isEnded = true)
            h.player.emit(ended)
            // Duplicate end callback while generation already bumped or same snapshot arrives
            h.player.emit(ended)
            yield()

            assertEquals(s2.id, h.store.state.value.track.id)
            assertEquals(1, h.store.queueController.state.value.history.size)
            assertEquals(s1.id, h.store.queueController.state.value.history[0].track.id)
            assertEquals(2, h.player.prepareCalls.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun startupRestore_hydratesReplaysBufferedNextAndDefersPlay() = runBlocking {
        val s1 = song("601")
        val s2 = song("602")
        val s3 = song("603")
        val checkpointSession = PlaybackSession(
            queue = listOf(s1),
            currentIndex = 0,
            positionMs = 4_000L,
            wasPlaying = true,
            destination = Destination.Home,
            previousDestination = Destination.Home,
            radioActive = false,
            radioSeed = null,
        )
        val fullSession = PlaybackSession(
            queue = listOf(s1, s2, s3),
            currentIndex = 0,
            positionMs = 4_000L,
            wasPlaying = true,
            destination = Destination.Home,
            previousDestination = Destination.Home,
            radioActive = false,
            radioSeed = null,
        )
        val gate = CompletableDeferred<PlaybackSession?>()
        val asyncSessions = AsyncSessionStorage(
            checkpoint = checkpointSession,
            sessionGate = gate,
        )
        val h = createHarness(
            coroutineContext,
            sessions = asyncSessions,
            ioDispatcherOverride = Dispatchers.IO,
        )
        try {
            assertEquals(s1.id, h.store.state.value.track.id)
            assertEquals(4_000L, h.store.state.value.positionAnchorMs)
            assertFalse(h.store.state.value.startupSessionLoaded)

            h.store.attach()
            asyncSessions.enteredRestore.await()

            // Buffer Next and Play before full hydration completes
            assertTrue(h.store.dispatchQueueAction(StartupQueueAction.Next))
            h.store.requestPlayback()

            assertTrue(h.store.state.value.playWhenReady)
            assertTrue(h.player.prepareCalls.isEmpty())

            gate.complete(fullSession)
            var spins = 0
            while (!h.store.state.value.startupSessionLoaded && spins++ < 50) {
                kotlinx.coroutines.delay(5L)
            }
            repeat(5) { yield() }

            assertTrue(h.store.state.value.startupSessionLoaded)
            assertEquals(s2.id, h.store.state.value.track.id)
            assertEquals(1, h.player.prepareCalls.size)
            assertEquals(s2.id, h.player.prepareCalls[0].first.id)
        } finally {
            if (!gate.isCompleted) gate.complete(null)
            h.close()
        }
    }

    @Test
    fun skip_handlesStationVsQueueVsGaplessLookahead() = runBlocking {
        // Case A: Station skip respects snapshot capabilities
        run {
            val h = createHarness(coroutineContext, flymeAuto = false)
            try {
                h.store.attach()
                yield()

                val st = station("st-9")
                h.store.playTrack(st)
                yield()
                h.player.emit(
                    snapshot(st, generation = 1L, isPlaying = true, isPrepared = true, canSkipNext = true, canSkipPrevious = false),
                )

                h.store.skip(-1)
                assertEquals(0, h.player.skipPreviousCalls)
                h.store.skip(1)
                assertEquals(1, h.player.skipNextCalls)
            } finally {
                h.close()
            }
        }

        // Case B & C: Queue skip and Gapless lookahead skip
        run {
            val h = createHarness(coroutineContext, flymeAuto = true)
            try {
                h.store.attach()
                yield()

                val s1 = song("701")
                val s2 = song("702")
                val s3 = song("703")
                h.store.playTrack(s1, listOf(s1, s2, s3))
                yield()

                // Normal skip(1) before lookahead advances queue normally
                h.store.skip(1)
                yield()
                assertEquals(s2.id, h.store.state.value.track.id)
                assertEquals(2, h.player.prepareCalls.size)

                // Now prepare s2 and load gapless lookahead s3
                val gen2 = h.player.nextGeneration - 1L
                h.player.emit(snapshot(s2, generation = gen2, positionMs = 500L, isPlaying = true, isPrepared = true))
                h.delayGate.fireOne(1_500L)
                assertEquals(s3, h.player.currentLookahead)

                h.player.nativeQueueSongs = setOf(checkNotNull(s3.catalogId))
                h.store.skip(1)
                yield()
                assertEquals(listOf(s2.catalogId, s3.catalogId), h.player.queuedSongCalls)
                assertEquals(2, h.player.prepareCalls.size)
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun saveSession_debouncesChangesAt350msAndSavesEvery5sWhilePlaying() = runBlocking {
        val h = createHarness(coroutineContext, isCatalogConfigured = false, flymeAuto = false)
        val sessions = h.sessions as FakeSessionStorage
        try {
            h.store.attach()
            yield()
            assertTrue(h.store.state.value.startupSessionLoaded)

            // Put a demo queue into PlaybackQueueController and pause before testing debounce.
            h.store.playTrack(demoTrack("demo-1"))
            h.store.pausePlayback()
            repeat(5) { yield() }
            sessions.savedSessions.clear()

            h.store.setNavigation(Destination.Library, Destination.Home)
            h.store.setNavigation(Destination.Search, Destination.Library)
            repeat(5) { yield() }
            assertTrue(sessions.savedSessions.isEmpty())

            h.delayGate.fireOne(350L)
            assertEquals(1, sessions.savedSessions.size)
            assertEquals(Destination.Search, sessions.savedSessions[0].first.destination)
            assertFalse(sessions.savedSessions[0].second)

            // Start demo playback -> periodic 5_000L save runs every tick
            sessions.savedSessions.clear()
            h.store.requestPlayback()
            yield()
            assertTrue(h.store.state.value.isPlaying)

            h.delayGate.fireOne(5_000L)
            assertEquals(1, sessions.savedSessions.size)
            h.delayGate.fireOne(5_000L)
            assertEquals(2, sessions.savedSessions.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun onSignedOut_resetsPlaybackStateQueueAndGeneration() = runBlocking {
        val h = createHarness(coroutineContext)
        try {
            h.store.attach()
            yield()

            val s1 = song("801")
            h.store.playTrack(s1, listOf(s1))
            yield()
            h.player.emit(snapshot(s1, generation = 1L, positionMs = 1_000L, isPlaying = true, isPrepared = true))
            assertTrue(h.store.state.value.isPlaying)
            assertTrue(h.store.state.value.hasCurrentTrack)
            assertNotNull(h.store.state.value.snapshot)

            var queueStillPresentDuringSignOut = false
            h.accountOps.onSignOutHook = {
                queueStillPresentDuringSignOut = h.store.queueController.state.value.current != null
            }

            h.store.onSignedOut()
            assertEquals(1, h.accountOps.signOutCalls)
            assertTrue(queueStillPresentDuringSignOut)
            assertEquals(MusicConnectionState.ReadyToSignIn, h.accountStore.state.value.connectionState)
            val after = h.store.state.value
            assertFalse(after.isPlaying)
            assertFalse(after.playWhenReady)
            assertFalse(after.hasCurrentTrack)
            assertFalse(after.playbackPrepared)
            assertNull(after.snapshot)
            assertNull(h.store.queueController.state.value.current)

            // Stale snapshot for gen=1 must be ignored because expectedPlaybackGeneration is now 0L
            h.player.emit(snapshot(s1, generation = 1L, positionMs = 2_000L, isPlaying = true, isPrepared = true))
            assertNull(h.store.state.value.snapshot)
            assertFalse(h.store.state.value.isPlaying)
        } finally {
            h.close()
        }
    }

    @Test
    fun playTrackWhenSignedOut_emitsSignInRequiredWithoutPreparing() = runBlocking {
        val h = createHarness(coroutineContext, connectionState = MusicConnectionState.ReadyToSignIn)
        try {
            val events = mutableListOf<PlaybackEvent>()
            val collector = h.scope.launch { h.store.events.collect { events += it } }
            h.store.attach()
            yield()

            h.store.playTrack(song("901"))
            yield()

            assertTrue(PlaybackEvent.SignInRequired in events)
            assertTrue(h.player.prepareCalls.isEmpty())
            collector.cancel()
        } finally {
            h.close()
        }
    }
}
