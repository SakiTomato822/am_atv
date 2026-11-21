package dev.amatv.core

import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.GaplessEnd
import dev.amatv.integration.GaplessPath
import dev.amatv.integration.GaplessStage
import dev.amatv.integration.GaplessTransition
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.PlaybackDiagnostics
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.integration.classifyPlaybackFailure
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.model.openAction
import dev.amatv.playback.AutoplayStatus
import dev.amatv.playback.ContextKind
import dev.amatv.playback.EntryOrigin
import dev.amatv.playback.PlaybackContext
import dev.amatv.playback.PlaybackQueueController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class PlaybackState(
    val track: Track,
    val hasCurrentTrack: Boolean,
    val positionAnchorMs: Long,
    val playStartedRealtimeMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackPrepared: Boolean,
    val playbackMessage: String?,
    val stationHistory: List<Track>,
    val snapshot: PlaybackSnapshot?,
    val startupSessionLoaded: Boolean,
)

sealed interface PlaybackEvent {
    data class Message(val text: String?) : PlaybackEvent
    data object SignInRequired : PlaybackEvent
    data object ShowNowPlaying : PlaybackEvent
}

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

/**
 * The app's one player, kept for the whole process by [dev.amatv.AmAtvApplication]. Windows (full screen, the
 * Flyme small window) come and go around it: switching window neither stops nor rebuilds playback.
 */
class PlaybackStore(
    private val player: PlayerOperations,
    private val sessions: PlaybackSessionStorage,
    private val accountStore: AccountStore,
    private val settingsStore: SettingsStore,
    private val lyricsStore: LyricsStore,
    private val libraryActions: LibraryActions,
    private val coroutineScope: CoroutineScope,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val flymeAuto: Boolean = BuildConfig.FLYME_AUTO,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val diagnostics: PlaybackDiagnostics? = null,
    private val playHistoryStore: PlayHistoryStore? = null,
) {
    private val startupCheckpoint: PlaybackSession? =
        sessions.restoreCheckpointSession()?.copy(wasPlaying = false)

    private var currentDestination: Destination =
        startupCheckpoint?.destination ?: sessions.activeTab
    private var previousDestination: Destination =
        startupCheckpoint?.previousDestination ?: sessions.activeTab
    private val navigationFlow = MutableStateFlow(currentDestination to previousDestination)

    /** The page shown and the one before it; a window that opens starts on these. */
    val navigation: StateFlow<Pair<Destination, Destination>> = navigationFlow.asStateFlow()

    private val initialTrack: Track =
        startupCheckpoint?.queue?.getOrNull(startupCheckpoint.currentIndex)
            ?: MockCatalog.tracks.first()

    private val _state = MutableStateFlow(
        PlaybackState(
            track = initialTrack,
            hasCurrentTrack = startupCheckpoint != null || !player.isCatalogConfigured,
            positionAnchorMs = startupCheckpoint?.positionMs
                ?: if (initialTrack.catalogId == null) 7_500L else 0L,
            playStartedRealtimeMs = clock(),
            isPlaying = false,
            playWhenReady = false,
            playbackPrepared = false,
            playbackMessage = null,
            stationHistory = sessions.loadStationHistory(),
            snapshot = null,
            startupSessionLoaded = false,
        ),
    )
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private var startupCursorEdited = false
    private val startupQueueActions = StartupQueueActions()
    private val hasUserPlaybackRequestFlow = MutableStateFlow(false)
    private val waitingQueueEntryIdFlow = MutableStateFlow<Long?>(null)
    private var expectedPlaybackGeneration = 0L
    private var lastPlaybackFailure: PlaybackFailureKind? = null
    private val commandFailureFlow = MutableStateFlow<Triple<Long, PlaybackFailureKind, String>?>(null)
    private var failureHandledSelection = -1L
    private var successfulSelection = -1L
    private var networkRetryJob: Job? = null
    private var networkRetryCount = 0
    private var lastPlaybackNotice: String? = null
    private var gaplessLookahead: Pair<Long, Track>? = null
    private var lookaheadTiming: GaplessTransition? = null
    private var naturalEndTiming: GaplessEnd? = null
    private var adoptingGaplessEntryId: Long? = null
    private var navigatingQueue = false
    private var advancingEndedTrackId: String? = null
    private var pendingRestore: PlaybackSession? =
        startupCheckpoint?.takeIf { initialTrack.catalogId != null }
    private var playbackRequestJob: Job? = null
    private var requestTiming: GaplessTransition? = null
    private var selectionGeneration = 0L
    private var pendingRequestedSongCatalogId: String? = null
    private var rawSystemSnapshot: PlaybackSnapshot? = null

    val queueController: PlaybackQueueController = PlaybackQueueController(
        scope = coroutineScope,
        services = player,
        playbackPrefs = settingsStore,
        onPlayTrack = ::executeTrackPlayback,
        recommendationDispatcher = ioDispatcher,
    )

    private data class GaplessKey(
        val currentEntryId: Long?,
        val currentTrack: Track?,
        val nextEntryId: Long?,
        val nextTrack: Track?,
    )

    private data class WaitingKey(
        val waitingEntryId: Long?,
        val currentEntryId: Long?,
        val awaitingNext: Boolean,
        val autoplayStatus: AutoplayStatus,
    )

    private data class SaveQueueKey(
        val displayQueue: List<Track>,
        val repeat: PlaybackRepeatMode,
        val shuffle: Boolean,
        val infinite: Boolean,
    )

    private fun updateTrack(newTrack: Track) {
        if (_state.value.track.id != newTrack.id) {
            networkRetryCount = 0
        }
        _state.value = _state.value.copy(track = newTrack)
    }

    private fun setExpectedPlaybackGeneration(generation: Long) {
        expectedPlaybackGeneration = generation
        _state.value = _state.value.copy(snapshot = currentPlaybackSnapshot())
    }

    private fun setHasUserPlaybackRequest(requested: Boolean) {
        hasUserPlaybackRequestFlow.value = requested
    }

    private fun emitMessage(text: String?) {
        _events.tryEmit(PlaybackEvent.Message(text))
    }

    private fun stationQueueTrack(): Track? = queueController.state.value.current?.track
        ?.takeIf { it.catalogKind == CatalogKind.Station }

    private fun isStationPlayback(): Boolean = stationQueueTrack() != null

    private fun currentPlayHistorySource(): PlayHistorySource {
        val queue = queueController.state.value
        val isStation = isStationPlayback() || queue.stationFeed != null
        return resolvePlayHistorySource(
            contextKind = queue.context?.kind,
            isStation = isStation,
            currentDestination = currentDestination,
            previousDestination = previousDestination,
        )
    }

    private fun requestedPlaybackTrack(): Track = stationQueueTrack() ?: _state.value.track

    private fun requestedPlaybackPositionMs(): Long =
        if (isStationPlayback()) 0L else _state.value.positionAnchorMs

    private fun currentPlaybackSnapshot(): PlaybackSnapshot? = rawSystemSnapshot
        ?.takeIf { it.requestGeneration != 0L && it.requestGeneration == expectedPlaybackGeneration }

    private fun withCachedLyrics(candidate: Track): Track = lyricsStore.withCachedLyrics(candidate)

    private fun acceptResolvedTrack(original: Track, resolved: Track): Track {
        if (original.catalogId == resolved.catalogId) return resolved
        val oldIdentity = original.lyricsIdentity()
        val newIdentity = resolved.lyricsIdentity()
        val lyricsState = lyricsStore.state.value
        val currentLines = lyricsState.lines.takeIf { lyricsState.trackIdentity == oldIdentity }
            ?: original.lyrics
        val updated = resolved.copy(lyrics = currentLines)
        queueController.updateCatalogIdentity(original, updated)
        val currentTrack = _state.value.track
        if (currentTrack.lyricsIdentity() == oldIdentity && currentTrack.resourceId == original.resourceId) {
            lyricsStore.onTrackIdentityResolved(oldIdentity, newIdentity)
            updateTrack(updated)
        }
        return updated
    }

    fun setNavigation(destination: Destination, previous: Destination) {
        currentDestination = destination
        previousDestination = previous
        navigationFlow.value = destination to previous
    }

    /** Connects the player and starts the store's work. Called once, when the store is created. */
    fun attach() {

        player.setSystemPlaybackCallbacks(
            PlaybackMediaSession.Callbacks(
                play = ::requestPlayback,
                pause = ::pausePlayback,
                next = { skip(1) },
                previous = { skip(-1) },
                seekTo = { position -> seekPlaybackBy(position - currentPositionMs()) },
            ),
        )
        player.setPlaybackListener(::applyPlaybackSnapshot)

        // Load the full session on IO and replay any buffered user actions once.
        coroutineScope.launch {
            startupCheckpoint?.let(queueController::restoreFromSession)
            val fullSession = cancellableResult {
                withContext(ioDispatcher) { sessions.restoreSession() }
            }.getOrNull()?.copy(wasPlaying = false)
            if (selectionGeneration == 0L && fullSession != null) {
                val current = _state.value
                val cursor = if (startupCursorEdited &&
                    current.track.catalogId == fullSession.queue[fullSession.currentIndex].catalogId
                ) {
                    current.positionAnchorMs
                } else {
                    fullSession.positionMs
                }
                val restored = fullSession.copy(positionMs = cursor, wasPlaying = current.playWhenReady)
                queueController.restoreFromSession(restored)
                val restoredTrack = withCachedLyrics(restored.queue[restored.currentIndex])
                updateTrack(restoredTrack)
                _state.value = _state.value.copy(
                    hasCurrentTrack = true,
                    positionAnchorMs = cursor,
                )
                pendingRestore = restored.takeIf { restoredTrack.catalogId != null }
            }
            if (selectionGeneration == 0L) {
                // Replay metadata-only against the full rail, then honor the latest
                // Play/Pause choice once. No intermediate item prepares native audio.
                startupQueueActions.replayHydrated(
                    requestedPlayIntent = _state.value.playWhenReady,
                    setPlayIntent = { intent -> _state.value = _state.value.copy(playWhenReady = intent) },
                    apply = ::applyQueueAction,
                )
            } else {
                startupQueueActions.drain()
            }
            _state.value = _state.value.copy(startupSessionLoaded = true)
        }

        // A Play press during metadata IO is deferred until the full queue/cursor is hydrated.
        coroutineScope.launch {
            _state.map { Triple(it.startupSessionLoaded, it.playWhenReady, it.track.id) }
                .distinctUntilChanged()
                .collectLatest { (loaded, ready, _) ->
                    val current = _state.value
                    if (loaded && ready && !hasUserPlaybackRequestFlow.value &&
                        waitingQueueEntryIdFlow.value == null && !queueController.state.value.awaitingNext &&
                        expectedPlaybackGeneration == 0L && playbackRequestJob?.isActive != true
                    ) {
                        if (current.track.catalogId != null) {
                            selectionGeneration++
                            launchNativePlayback(current.track, current.positionAnchorMs)
                        } else if (!player.isCatalogConfigured) {
                            setHasUserPlaybackRequest(true)
                            _state.value = _state.value.copy(
                                playStartedRealtimeMs = clock(),
                                isPlaying = true,
                            )
                        }
                    }
                }
        }

        coroutineScope.launch {
            hasUserPlaybackRequestFlow.collect { requested ->
                if (requested) queueController.activatePlaybackSession()
            }
        }

        // Load the Favorite Songs / Library state of each song shown on Now Playing (Flyme star button).
        coroutineScope.launch {
            combine(
                _state.map { it.track.catalogId }.distinctUntilChanged(),
                accountStore.state.map { it.connectionState }.distinctUntilChanged(),
            ) { _, connectionState ->
                _state.value.track to (connectionState == MusicConnectionState.SignedIn)
            }.collectLatest { (currentTrack, signedIn) ->
                libraryActions.onCurrentTrackChanged(currentTrack, signedIn)
            }
        }

        // Fade at gapless song changes, chosen per kind of queue in Settings.
        if (flymeAuto) {
            coroutineScope.launch {
                combine(
                    queueController.state.map { it.context?.kind to it.stationFeed }.distinctUntilChanged(),
                    settingsStore.state.map { Triple(it.fadeStations, it.fadeAlbums, it.fadePlaylists) }
                        .distinctUntilChanged(),
                ) { (contextKind, stationFeed), (fadeStations, fadeAlbums, fadePlaylists) ->
                    when {
                        stationFeed != null -> fadeStations
                        contextKind == ContextKind.Album -> fadeAlbums
                        else -> fadePlaylists
                    }
                }.collect { enabled ->
                    player.setFadeEnabled(enabled)
                }
            }

            // Gapless (Flyme): once the current song is ready, mirror the app's next song into MusicKit so it
            // can preload it and continue without a gap. Any change of current or next replaces it.
            coroutineScope.launch {
                combine(
                    queueController.state.map { q ->
                        val next = queueController.peekAutoNext()
                        GaplessKey(
                            currentEntryId = q.current?.entryId,
                            currentTrack = q.current?.track,
                            nextEntryId = next?.entryId,
                            nextTrack = next?.track,
                        )
                    }.distinctUntilChanged { a, b ->
                        a.currentEntryId == b.currentEntryId &&
                            a.nextEntryId == b.nextEntryId &&
                            a.nextTrack?.catalogId == b.nextTrack?.catalogId
                    },
                    _state.map { it.playbackPrepared }.distinctUntilChanged(),
                ) { key, prepared -> key to prepared }
                    .collectLatest { (key, prepared) ->
                        val next = key.nextEntryId?.let { id -> key.nextTrack?.let { id to it } }
                        val current = key.currentTrack
                        if (next == null || !prepared || isStationPlayback() ||
                            current?.catalogKind != CatalogKind.Song ||
                            next.second.catalogKind != CatalogKind.Song ||
                            next.second.catalogId == current.catalogId
                        ) {
                            if (prepared && gaplessLookahead != null) player.setLookahead(null)
                            gaplessLookahead = null
                            return@collectLatest
                        }
                        // Fresh prepares get time to settle. A natural continuation is already loaded.
                        val timing = lookaheadTiming?.takeIf {
                            it.sourceGeneration == expectedPlaybackGeneration && it.entryId == next.first
                        } ?: diagnostics?.newTransition(expectedPlaybackGeneration, next.first, clock())
                        lookaheadTiming = timing
                        val continued = _state.value.snapshot?.gaplessAdvanceCatalogId
                        if (continued == null || continued != current.catalogId) delayFn(1_500L)
                        val resolved = cancellableResult { player.resolvePlayableTrack(next.second) }
                            .getOrNull() ?: return@collectLatest
                        timing?.mark(GaplessStage.Resolved, clock())
                        if (resolved.catalogId != next.second.catalogId) {
                            queueController.updateCatalogIdentity(next.second, resolved)
                        }
                        gaplessLookahead = next.first to resolved
                        player.setLookahead(resolved, timing)
                    }
            }
        }

        coroutineScope.launch {
            commandFailureFlow.collect { failure ->
                failure?.let { handlePlaybackFailure(it.first, it.second, it.third) }
            }
        }

        coroutineScope.launch {
            combine(
                waitingQueueEntryIdFlow,
                queueController.state.map { Triple(it.current?.entryId, it.awaitingNext, it.autoplayStatus) }
                    .distinctUntilChanged(),
            ) { waitingEntry, (currentEntryId, awaitingNext, autoplayStatus) ->
                WaitingKey(waitingEntry, currentEntryId, awaitingNext, autoplayStatus)
            }.distinctUntilChanged().collect { key ->
                val waitingEntry = key.waitingEntryId ?: return@collect
                if (key.currentEntryId != waitingEntry) {
                    naturalEndTiming = null
                    waitingQueueEntryIdFlow.value = null
                    return@collect
                }
                val message = waitingRecommendationError(
                    waitingEntry,
                    key.currentEntryId,
                    key.awaitingNext,
                    (key.autoplayStatus as? AutoplayStatus.Error)?.message,
                )
                if (message != null) {
                    // The failed wait is terminal. A later explicit Play may retry the
                    // displayed item, but hydration must never replay it automatically.
                    naturalEndTiming = null
                    waitingQueueEntryIdFlow.value = null
                    _state.value = _state.value.copy(
                        playWhenReady = false,
                        isPlaying = false,
                        playbackMessage = message,
                    )
                } else if (!key.awaitingNext && key.autoplayStatus is AutoplayStatus.Idle) {
                    naturalEndTiming = null
                    waitingQueueEntryIdFlow.value = null
                    _state.value = _state.value.copy(playWhenReady = false)
                }
            }
        }

        coroutineScope.launch {
            queueController.state.map { Triple(it.canSkipNext, it.canSkipPrevious, it.current?.entryId) }
                .distinctUntilChanged()
                .collect {
                    rawSystemSnapshot?.takeIf {
                        it.requestGeneration == expectedPlaybackGeneration
                    }?.let(::publishSystemSnapshot)
                }
        }

        coroutineScope.launch {
            combine(
                navigationFlow,
                _state.map { Triple(it.track.id, it.isPlaying, it.playWhenReady) }.distinctUntilChanged(),
                queueController.state.map { SaveQueueKey(it.displayQueue, it.repeat, it.shuffle, it.infinite) }
                    .distinctUntilChanged(),
            ) { nav, stateKey, queueKey ->
                Triple(nav, stateKey, queueKey)
            }.collectLatest {
                delayFn(350L)
                saveSession(false)
            }
        }

        coroutineScope.launch {
            _state.map { it.isPlaying to it.track.id }
                .distinctUntilChanged()
                .collectLatest { (playing, _) ->
                    if (!playing) return@collectLatest
                    while (true) {
                        delayFn(5_000L)
                        saveSession(false)
                    }
                }
        }

        if (flymeAuto) {
            coroutineScope.launch {
                combine(
                    accountStore.state.map { it.connectionState }.distinctUntilChanged(),
                    _state.map { it.playWhenReady }.distinctUntilChanged(),
                ) { connectionState, playWhenReady ->
                    connectionState == MusicConnectionState.SignedIn && !playWhenReady
                }.distinctUntilChanged().collectLatest { shouldPrefetch ->
                    if (shouldPrefetch) {
                        delayFn(5_000L)
                        player.prefetchLibraryDetails()
                    }
                }
            }
        }

        // Auto-advance timer: detects track finish and smoothly advances
        coroutineScope.launch {
            _state.map { Triple(it.isPlaying, it.track.id, it.track.durationMs) to it.track.catalogId }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collectLatest { (key, catalogId) ->
                    val (playing, _, durationMs) = key
                    // Native playback ends only on its generation-bound SDK end event.
                    if (catalogId != null || !playing || durationMs <= 2_000L) return@collectLatest
                    while (_state.value.isPlaying) {
                        val currentMs = currentPositionMs()
                        if (currentMs >= durationMs - 250L) {
                            playHistoryStore?.onTrackFinished(_state.value.track, clock(), currentPlayHistorySource())
                            advanceNextTrack(isAuto = true)
                            break
                        }
                        delayFn(250L)
                    }
                }
        }

        if (playHistoryStore != null) {
            coroutineScope.launch {
                _state.map { Triple(it.track, it.isPlaying, it.hasCurrentTrack) }
                    .distinctUntilChanged()
                    .collectLatest { (currentTrack, playing, hasCurrent) ->
                        if (!hasCurrent) return@collectLatest
                        playHistoryStore.onPlaybackProgress(
                            track = currentTrack,
                            isPlaying = playing,
                            elapsedRealtimeMs = clock(),
                            source = currentPlayHistorySource(),
                        )
                        if (playing) {
                            val remainingMs = playHistoryStore.remainingThresholdMs()
                            if (remainingMs > 0L) {
                                delayFn(remainingMs)
                                val live = _state.value
                                if (live.hasCurrentTrack && live.isPlaying &&
                                    playHistorySongKey(live.track) == playHistorySongKey(currentTrack)
                                ) {
                                    playHistoryStore.onPlaybackProgress(
                                        track = live.track,
                                        isPlaying = true,
                                        elapsedRealtimeMs = clock(),
                                        source = currentPlayHistorySource(),
                                    )
                                }
                            }
                        }
                    }
            }
        }

        // Lyrics arrive independently of MusicKit's playback clock. Publish their
        // completion/clearing immediately, rather than waiting for the next SDK event.
        coroutineScope.launch {
            combine(
                _state.map { Triple(it.track.lyricsIdentity(), it.track.lyrics, it.hasCurrentTrack) }
                    .distinctUntilChanged(),
                lyricsStore.state.map { it.trackIdentity to it.lines }.distinctUntilChanged(),
            ) { (identity, trackLyrics, hasCurrent), (lyricsIdentity, storeLines) ->
                val currentLines = if (lyricsIdentity == identity) storeLines else trackLyrics
                Triple(identity, currentLines, hasCurrent)
            }.distinctUntilChanged().collect { (identity, _, hasCurrent) ->
                if (!hasCurrent) return@collect
                rawSystemSnapshot?.takeIf {
                    it.requestGeneration == expectedPlaybackGeneration &&
                        it.currentTrack?.lyricsIdentity() == identity
                }?.let(::publishSystemSnapshot)
            }
        }

        // Station history (Flyme): the songs a station played, kept across launches, like Apple Music's
        // played list. The queue view shows them before the current song while a station plays.
        if (flymeAuto) {
            coroutineScope.launch {
                combine(
                    _state.map { it.track.catalogId }.distinctUntilChanged(),
                    queueController.state.map { it.current?.track?.catalogKind == CatalogKind.Station }
                        .distinctUntilChanged(),
                ) { _, stationQueueContext ->
                    stationQueueContext to _state.value.track
                }.collect { (stationQueueContext, currentTrack) ->
                    if (!stationQueueContext) return@collect
                    val song = currentTrack.takeIf { it.catalogKind == CatalogKind.Song && it.catalogId != null }
                        ?: return@collect
                    val currentHistory = _state.value.stationHistory
                    if (currentHistory.lastOrNull()?.catalogId == song.catalogId) return@collect
                    val updatedHistory = (currentHistory.filter { it.catalogId != song.catalogId } +
                        song.copy(lyrics = emptyList())).takeLast(STATION_HISTORY_LIMIT)
                    _state.value = _state.value.copy(stationHistory = updatedHistory)
                    sessions.saveStationHistory(updatedHistory)
                }
            }
        }
    }

    fun currentPositionMs(): Long {
        val current = _state.value
        if (!current.isPlaying) return current.positionAnchorMs
        val elapsed = clock() - current.playStartedRealtimeMs
        val advanced = current.positionAnchorMs + elapsed
        return if (current.track.catalogId == null) advanced.mod(current.track.durationMs.coerceAtLeast(1L))
        else advanced.coerceAtMost(current.track.durationMs.coerceAtLeast(1L))
    }

    private fun launchNativePlayback(
        nextTrack: Track,
        resumePositionMs: Long = 0L,
        reuseQueue: Boolean = false,
        timing: GaplessTransition? = null,
    ) {
        requestTiming = timing
        setHasUserPlaybackRequest(true)
        _state.value = _state.value.copy(
            playbackPrepared = false,
            playbackMessage = null,
        )
        networkRetryJob?.cancel()
        networkRetryJob = null
        val selection = selectionGeneration
        playbackRequestJob?.cancel()
        setExpectedPlaybackGeneration(0L)
        lastPlaybackFailure = null
        commandFailureFlow.value = null
        // Stations intentionally resolve to individual song items; only gate exact song requests.
        pendingRequestedSongCatalogId = nextTrack.catalogId.takeIf { nextTrack.catalogKind == CatalogKind.Song }
        playbackRequestJob = coroutineScope.launch {
            try {
                var nativeGeneration = if (reuseQueue) player.playQueuedSong(nextTrack, timing) else null
                if (nativeGeneration == null) {
                    val resolved = player.resolvePlayableTrack(nextTrack)
                    if (selection != selectionGeneration) return@launch
                    timing?.mark(GaplessStage.Resolved, clock())
                    val playableTrack = acceptResolvedTrack(nextTrack, resolved)
                    pendingRequestedSongCatalogId = playableTrack.catalogId.takeIf {
                        playableTrack.catalogKind == CatalogKind.Song
                    }
                    if (reuseQueue && playableTrack.catalogId != nextTrack.catalogId) {
                        nativeGeneration = player.playQueuedSong(playableTrack, timing)
                    }
                    if (nativeGeneration == null) {
                        if (reuseQueue) player.pause()
                        nativeGeneration = player.preparePlayback(
                            playableTrack, resumePositionMs, playWhenReady = false, transition = timing,
                        )
                    }
                }
                // Re-read the user intent after playback and authorization work.
                if (selection != selectionGeneration) return@launch
                setExpectedPlaybackGeneration(nativeGeneration)
                if (_state.value.playWhenReady) player.play() else player.pause()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (selection == selectionGeneration) {
                    commandFailureFlow.value = Triple(
                        selection,
                        classifyPlaybackFailure(null, null, null, error),
                        error.message ?: AppText.text(R.string.app_song_load_failed),
                    )
                }
            }
        }
    }

    fun pausePlayback() {
        val pos = currentPositionMs()
        _state.value = _state.value.copy(positionAnchorMs = pos, playWhenReady = false)
        networkRetryJob?.cancel()
        networkRetryJob = null
        pendingRestore = pendingRestore?.copy(wasPlaying = false)
        if (_state.value.track.catalogId != null) player.pause()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun requestPlayback() {
        _state.value = _state.value.copy(playWhenReady = true)
        val current = _state.value
        val lyricsState = lyricsStore.state.value
        if (lyricsState.trackIdentity == current.track.lyricsIdentity() &&
            lyricsState.phase == LyricsLoadPhase.Failed
        ) {
            lyricsStore.retryCurrentLyrics()
        }
        pendingRestore = pendingRestore?.copy(wasPlaying = true)
        if (!current.startupSessionLoaded && selectionGeneration == 0L) return
        // Play while Next is resolving belongs to the incoming entry, not
        // the old song still used as the queue's continuation anchor.
        if (waitingQueueEntryIdFlow.value != null) return
        val requestedTrack = requestedPlaybackTrack()
        if (requestedTrack.catalogId != null) {
            if (lastPlaybackFailure != null ||
                (expectedPlaybackGeneration == 0L && playbackRequestJob?.isActive != true)
            ) {
                selectionGeneration++
                launchNativePlayback(requestedTrack, requestedPlaybackPositionMs())
            } else if (expectedPlaybackGeneration != 0L) {
                player.play()
            }
        } else {
            _state.value = _state.value.copy(
                playStartedRealtimeMs = clock(),
                isPlaying = true,
            )
        }
        _state.value = _state.value.copy(playbackMessage = null)
    }

    fun togglePlayback() {
        if (_state.value.playWhenReady) pausePlayback() else requestPlayback()
    }

    fun retryCurrentLyrics() {
        lyricsStore.retryCurrentLyrics()
    }

    fun seekPlaybackBy(delta: Long) {
        val snapshot = currentPlaybackSnapshot()
        if (snapshot?.isLiveStream == true || snapshot?.canSeek == false) return
        if (isStationPlayback() && snapshot?.isPrepared != true) return
        val current = _state.value
        val newAnchor = (currentPositionMs() + delta).coerceIn(0L, current.track.durationMs.coerceAtLeast(0L))
        _state.value = _state.value.copy(
            positionAnchorMs = newAnchor,
            playStartedRealtimeMs = clock(),
        )
        pendingRestore = pendingRestore?.copy(positionMs = newAnchor)
        startupCursorEdited = true
        if (current.track.catalogId == null || expectedPlaybackGeneration == 0L) return
        if (current.playbackPrepared) {
            runCatching { player.seekTo(newAnchor) }
                .onFailure { _state.value = _state.value.copy(playbackMessage = AppText.text(R.string.app_seek_failed)) }
        } else if (hasUserPlaybackRequestFlow.value) {
            selectionGeneration++
            launchNativePlayback(requestedPlaybackTrack(), requestedPlaybackPositionMs())
        }
    }

    private fun executeTrackPlayback(nextTrack: Track) {
        val selectedAt = diagnostics?.let { clock() }
        val ended = naturalEndTiming
        naturalEndTiming = null
        waitingQueueEntryIdFlow.value = null
        selectionGeneration++
        playbackRequestJob?.cancel()
        pendingRestore = null
        if (_state.value.playWhenReady && nextTrack.catalogId != null &&
            accountStore.state.value.connectionState != MusicConnectionState.SignedIn
        ) {
            emitMessage(AppText.text(R.string.app_apple_sign_in_required))
            _events.tryEmit(PlaybackEvent.SignInRequired)
            return
        }
        val initialTrack = withCachedLyrics(nextTrack)
        advancingEndedTrackId = null
        val ready = _state.value.playWhenReady
        val reuseQueue = flymeAuto && navigatingQueue && ready && !isStationPlayback() &&
            nextTrack.catalogKind == CatalogKind.Song
        updateTrack(initialTrack)
        _state.value = _state.value.copy(
            positionAnchorMs = 0L,
            hasCurrentTrack = true,
            playStartedRealtimeMs = clock(),
            isPlaying = nextTrack.catalogId == null && ready,
            playbackMessage = null,
            playbackPrepared = false,
        )

        val adopted = adoptingGaplessEntryId != null &&
            adoptingGaplessEntryId == queueController.state.value.current?.entryId
        if (adopted) {
            // MusicKit already continued into this song: keep its generation, no pause or prepare.
            _state.value = _state.value.copy(
                isPlaying = ready,
                playbackPrepared = true,
            )
            pendingRequestedSongCatalogId = null
        } else if (nextTrack.catalogId != null) {
            // Stop the previous song while authorization/preparation is pending.
            if (!reuseQueue) player.pause()
            if (ready) {
                val timing = if (nextTrack.catalogKind == CatalogKind.Song && diagnostics != null && selectedAt != null) {
                    val entryId = queueController.state.value.current?.entryId
                    val preloaded = lookaheadTiming?.takeIf {
                        ended != null && it.sourceGeneration == ended.generation && it.entryId == entryId
                    }
                    (preloaded ?: diagnostics.newTransition(expectedPlaybackGeneration, entryId, selectedAt)).apply {
                        start(if (ended == null) GaplessPath.Manual else GaplessPath.Fallback, selectedAt, ended)
                    }
                } else null
                lookaheadTiming = null
                launchNativePlayback(initialTrack, reuseQueue = reuseQueue, timing = timing)
            } else {
                setExpectedPlaybackGeneration(0L)
                pendingRequestedSongCatalogId = null
                lastPlaybackFailure = null
                commandFailureFlow.value = null
            }
        } else {
            setExpectedPlaybackGeneration(0L)
            pendingRequestedSongCatalogId = null
        }
    }

    private fun waitForNextQueueEntry(entryId: Long?) {
        if (entryId == null) return
        waitingQueueEntryIdFlow.value = entryId
        // Consume the startup Play gate even when only metadata navigation was
        // replayed. The next entry's callback will honor the latest user intent.
        setHasUserPlaybackRequest(true)
        val pos = currentPositionMs()
        setExpectedPlaybackGeneration(0L)
        playbackRequestJob?.cancel()
        player.pause()
        _state.value = _state.value.copy(
            positionAnchorMs = pos,
            isPlaying = false,
            playbackPrepared = false,
            playbackMessage = null,
        )
    }

    private fun applyQueueAction(action: StartupQueueAction): Boolean {
        when (action) {
            StartupQueueAction.Next -> {
                pendingRestore = null
                val next = queueController.advanceNext(manual = true)
                val live = queueController.state.value
                if (next == null && live.awaitingNext) {
                    waitForNextQueueEntry(live.current?.entryId)
                } else if (next == null) {
                    val pos = currentPositionMs()
                    player.pause()
                    _state.value = _state.value.copy(
                        positionAnchorMs = pos,
                        isPlaying = false,
                        playWhenReady = false,
                    )
                }
            }
            StartupQueueAction.Previous -> {
                pendingRestore = null
                queueController.advancePrevious(currentPositionMs())
            }
            is StartupQueueAction.Select -> {
                if (!queueController.selectTimelineEntry(action.entryId)) return false
            }
            StartupQueueAction.ClearUpcoming -> queueController.clearUpcoming()
            StartupQueueAction.CycleRepeat -> queueController.setRepeat(
                when (queueController.state.value.repeat) {
                    PlaybackRepeatMode.Off -> PlaybackRepeatMode.All
                    PlaybackRepeatMode.All -> PlaybackRepeatMode.One
                    PlaybackRepeatMode.One -> PlaybackRepeatMode.Off
                },
            )
            StartupQueueAction.ToggleShuffle -> queueController.setShuffle(!queueController.state.value.shuffle)
            StartupQueueAction.ToggleInfinite -> queueController.setInfinite(!queueController.state.value.infinite)
        }
        return true
    }

    fun dispatchQueueAction(action: StartupQueueAction): Boolean {
        if (!_state.value.startupSessionLoaded && selectionGeneration == 0L) return startupQueueActions.enqueue(action)
        when (action) {
            StartupQueueAction.Next, StartupQueueAction.Previous, is StartupQueueAction.Select -> Unit
            else -> return applyQueueAction(action)
        }
        navigatingQueue = true
        naturalEndTiming = null
        try {
            return applyQueueAction(action)
        } finally {
            navigatingQueue = false
        }
    }

    private fun supersedeStartupQueueWithNewContext() {
        naturalEndTiming = null
        // A catalog play action deliberately replaces the old rail. Keep pending
        // preference choices, but don't replay old navigation into the new context.
        startupQueueActions.preferencesForNewContext().forEach { applyQueueAction(it) }
    }

    private fun playStationAsSongs(station: Track, stationId: String, songs: List<Track>) {
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        pendingRestore = null
        _state.value = _state.value.copy(playWhenReady = true)
        queueController.playStationSongs(
            stationId = stationId,
            songs = songs.map(::withCachedLyrics),
            context = PlaybackContext(kind = ContextKind.SongList, id = stationId, title = station.title),
        )
        _events.tryEmit(PlaybackEvent.ShowNowPlaying)
    }

    fun playTrack(nextTrack: Track, contextQueue: List<Track> = emptyList(), webStation: Boolean = false) {
        val isStation = nextTrack.catalogKind == CatalogKind.Station
        if (nextTrack.openAction != CatalogOpenAction.Play && !isStation) return
        if (nextTrack.catalogId != null &&
            accountStore.state.value.connectionState != MusicConnectionState.SignedIn
        ) {
            emitMessage(AppText.text(R.string.app_apple_sign_in_required))
            _events.tryEmit(PlaybackEvent.SignInRequired)
            return
        }
        // Without a usable system Widevine the web player only gets 30-second previews: play the
        // station's songs on the native player instead. Live radio has no songs and stays on the web.
        val stationId = nextTrack.catalogId
        if (isStation && flymeAuto && !webStation && stationId != null) {
            coroutineScope.launch {
                val asSongs = settingsStore.state.value.stationsAsSongs ||
                    !withContext(ioDispatcher) { player.widevineReady }
                val songs = if (asSongs) {
                    cancellableResult { player.stationSongs(stationId) }.getOrNull().orEmpty()
                } else emptyList()
                if (songs.isNotEmpty()) playStationAsSongs(nextTrack, stationId, songs)
                else playTrack(nextTrack, contextQueue, webStation = true)
            }
            return
        }
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        pendingRestore = null

        val fullQueue = if (isStation) {
            listOf(withCachedLyrics(nextTrack))
        } else if (contextQueue.isNotEmpty()) {
            contextQueue.map(::withCachedLyrics)
        } else if (nextTrack.catalogId == null) {
            MockCatalog.tracks
        } else {
            listOf(withCachedLyrics(nextTrack))
        }

        val trackIndex = fullQueue.indexOfFirst { it.matchesPlaybackQueueEntry(nextTrack) }.coerceAtLeast(0)
        _state.value = _state.value.copy(playWhenReady = true)
        queueController.playContext(
            tracks = fullQueue,
            startIndex = trackIndex,
            context = PlaybackContext(
                kind = if (isStation) ContextKind.Single else ContextKind.SongList,
                id = nextTrack.catalogId,
                title = if (isStation) nextTrack.title else nextTrack.album,
            ),
        )
        _events.tryEmit(PlaybackEvent.ShowNowPlaying)
    }

    fun playCollection(collection: Track, tracks: List<Track>, index: Int, shuffle: Boolean) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        if (accountStore.state.value.connectionState != MusicConnectionState.SignedIn) {
            emitMessage(AppText.text(R.string.app_apple_sign_in_required))
            _events.tryEmit(PlaybackEvent.SignInRequired)
            return
        }
        supersedeStartupQueueWithNewContext()
        pendingRestore = null
        _state.value = _state.value.copy(playWhenReady = true)
        queueController.playContext(
            tracks = tracks.map(::withCachedLyrics),
            startIndex = index,
            context = PlaybackContext(
                kind = when {
                    collection.catalogKind == CatalogKind.Album -> ContextKind.Album
                    collection.resourceType == "library-playlists" -> ContextKind.LibraryPlaylist
                    else -> ContextKind.Playlist
                },
                id = collection.resourceId ?: collection.catalogId,
                title = collection.title,
            ),
            shuffle = shuffle,
        )
        _events.tryEmit(PlaybackEvent.ShowNowPlaying)
    }

    private fun advanceNextTrack(isAuto: Boolean = true) {
        val next = queueController.advanceNext(manual = !isAuto)
        val live = queueController.state.value
        if (next == null && live.awaitingNext) {
            waitForNextQueueEntry(live.current?.entryId)
        } else if (next == null) {
            naturalEndTiming = null
            _state.value = _state.value.copy(
                isPlaying = false,
                playWhenReady = false,
            )
        }
    }

    fun selectQueueTrack(index: Int) {
        queueController.state.value.timelineEntries.getOrNull(index)?.let { entry ->
            val previousIntent = _state.value.playWhenReady
            _state.value = _state.value.copy(playWhenReady = true)
            if (!dispatchQueueAction(StartupQueueAction.Select(entry.entryId))) {
                _state.value = _state.value.copy(playWhenReady = previousIntent)
            }
        }
    }

    fun skip(relative: Int) {
        if (isStationPlayback()) {
            val snapshot = currentPlaybackSnapshot() ?: return
            val canSkip = if (relative > 0) snapshot.canSkipNext else snapshot.canSkipPrevious
            if (!canSkip) return
            if (relative > 0) player.skipToNext() else player.skipToPrevious()
            return
        }
        dispatchQueueAction(if (relative > 0) StartupQueueAction.Next else StartupQueueAction.Previous)
    }

    private fun handlePlaybackFailure(selection: Long, kind: PlaybackFailureKind, message: String) {
        if (selection != selectionGeneration || failureHandledSelection == selection) return
        failureHandledSelection = selection
        lastPlaybackFailure = kind
        pendingRestore = null
        pendingRequestedSongCatalogId = null
        _state.value = _state.value.copy(
            isPlaying = false,
            playbackPrepared = false,
        )
        player.pause()
        networkRetryJob?.cancel()
        networkRetryJob = null
        if (kind == PlaybackFailureKind.TrackUnavailable && _state.value.playWhenReady && !isStationPlayback()) {
            val entry = queueController.state.value.current
            val next = entry?.let { queueController.skipFailedCurrent(it.entryId) }
            // onPlayTrack can already have started another request synchronously.
            if (selection != selectionGeneration) return
            if (next == null && queueController.state.value.awaitingNext) {
                waitForNextQueueEntry(queueController.state.value.current?.entryId)
            } else if (next == null) {
                _state.value = _state.value.copy(
                    playWhenReady = false,
                    playbackMessage = if (queueController.state.value.failureLimitReached) {
                        AppText.text(R.string.app_multiple_songs_unavailable)
                    } else {
                        message
                    },
                )
            }
        } else if (kind == PlaybackFailureKind.Network && _state.value.playWhenReady && networkRetryCount < 2) {
            networkRetryCount++
            val retrySelection = selection
            val retryTrack = requestedPlaybackTrack()
            val retryPosition = requestedPlaybackPositionMs()
            val retryTiming = requestTiming?.takeUnless { it.has(GaplessStage.Advanced) }
            networkRetryJob = coroutineScope.launch {
                for (countdown in 3 downTo 1) {
                    if (retrySelection != selectionGeneration || !_state.value.playWhenReady) return@launch
                    _state.value = _state.value.copy(
                        playbackMessage = AppText.text(R.string.services_song_connection_retry, countdown),
                    )
                    delayFn(1_000L)
                }
                if (retrySelection != selectionGeneration || !_state.value.playWhenReady) return@launch
                _state.value = _state.value.copy(playbackMessage = null)
                selectionGeneration++
                retryTiming?.mark(GaplessStage.Retry, clock())
                launchNativePlayback(retryTrack, retryPosition, timing = retryTiming)
            }
        } else {
            _state.value = _state.value.copy(
                playWhenReady = false,
                playbackMessage = message,
            )
        }
    }

    private fun publishSystemSnapshot(snapshot: PlaybackSnapshot) {
        val current = _state.value
        val playback = if (!current.playWhenReady && (snapshot.isPlaying || snapshot.isBuffering)) {
            snapshot.copy(isPlaying = false, isBuffering = false)
        } else snapshot
        val lyricsState = lyricsStore.state.value
        val published = playback.withSystemLyrics(lyricsState.trackIdentity, lyricsState.lines)
        rawSystemSnapshot = published
        _state.value = _state.value.copy(snapshot = currentPlaybackSnapshot())
        val live = queueController.state.value
        val stationPlayback = live.current?.track?.catalogKind == CatalogKind.Station
        player.publishSystemPlayback(
            published,
            if (stationPlayback) published.canSkipNext else live.canSkipNext,
            if (stationPlayback) published.canSkipPrevious else live.canSkipPrevious,
        )
    }

    private fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.requestGeneration == 0L || snapshot.requestGeneration != expectedPlaybackGeneration) return
        if (snapshot.errorMessage != null) {
            publishSystemSnapshot(snapshot)
            handlePlaybackFailure(
                selectionGeneration,
                snapshot.failureKind ?: PlaybackFailureKind.Unexpected,
                snapshot.errorMessage,
            )
            return
        }
        val pendingCatalogId = pendingRequestedSongCatalogId
        // A Station request may report its current Song, so it must never pass this song-only gate.
        if (pendingCatalogId != null &&
            (!snapshot.hasPlayerCurrentTrack || snapshot.currentTrack?.catalogId != pendingCatalogId)
        ) return
        publishSystemSnapshot(snapshot)
        if (snapshot.notice != lastPlaybackNotice) {
            snapshot.notice?.let { emitMessage(it) }
            lastPlaybackNotice = snapshot.notice
        }
        if (!snapshot.isPrepared) return
        val queued = gaplessLookahead
        if (snapshot.gaplessAdvanceCatalogId != null && queued != null &&
            queued.second.catalogId == snapshot.gaplessAdvanceCatalogId &&
            snapshot.currentTrack?.catalogId == queued.second.catalogId &&
            queueController.peekAutoNext()?.entryId == queued.first
        ) {
            gaplessLookahead = null
            lookaheadTiming = null
            requestTiming = null
            playHistoryStore?.onTrackFinished(_state.value.track, clock(), currentPlayHistorySource())
            adoptingGaplessEntryId = queued.first
            try {
                queueController.advanceNext(manual = false)
            } finally {
                adoptingGaplessEntryId = null
            }
            return
        }
        _state.value = _state.value.copy(playbackPrepared = true)
        pendingRequestedSongCatalogId = null
        pendingRestore = null
        if (snapshot.isPlaying && snapshot.positionMs > 250L && successfulSelection != selectionGeneration) {
            successfulSelection = selectionGeneration
            networkRetryCount = 0
            networkRetryJob?.cancel()
            networkRetryJob = null
            queueController.state.value.current?.let { queueController.markCurrentPlaybackStarted(it.entryId) }
        }
        val currentStateBeforeEnded = _state.value
        val endedEntryKey = queueController.state.value.current?.entryId?.toString()
            ?: currentStateBeforeEnded.track.id
        if (!snapshot.isEnded && snapshot.currentTrack?.catalogId == currentStateBeforeEnded.track.catalogId) {
            advancingEndedTrackId = null
        }
        if (snapshot.isEnded && snapshot.currentTrack?.catalogId == currentStateBeforeEnded.track.catalogId &&
            advancingEndedTrackId != endedEntryKey
        ) {
            advancingEndedTrackId = endedEntryKey
            playHistoryStore?.onTrackFinished(currentStateBeforeEnded.track, clock(), currentPlayHistorySource())
            _state.value = _state.value.copy(isPlaying = false)
            if (isStationPlayback()) {
                setExpectedPlaybackGeneration(0L)
                _state.value = _state.value.copy(
                    playWhenReady = false,
                    playbackPrepared = false,
                )
            } else {
                naturalEndTiming = diagnostics?.lastGaplessEnd?.takeIf {
                    it.generation == snapshot.requestGeneration && it.catalogId == snapshot.currentTrack?.catalogId
                }
                advanceNextTrack(isAuto = true)
            }
            return
        }
        val trackChanged = snapshot.currentTrack != null && (
            _state.value.track.id != snapshot.currentTrack.id ||
                _state.value.track.catalogId != snapshot.currentTrack.catalogId
            )
        snapshot.currentTrack?.let { current ->
            val withLyrics = withCachedLyrics(current)
            val existing = _state.value.track
            if (existing.id != withLyrics.id ||
                existing.lyrics != withLyrics.lyrics ||
                existing.title != withLyrics.title ||
                existing.artist != withLyrics.artist ||
                existing.durationMs != withLyrics.durationMs
            ) {
                updateTrack(withLyrics)
            }
            _state.value = _state.value.copy(hasCurrentTrack = true)
        }

        // Performance optimization: prevent 20fps root recomposition by checking drift > 500ms
        val afterTrackState = _state.value
        val currentPredictedMs = if (afterTrackState.isPlaying) {
            afterTrackState.positionAnchorMs +
                (snapshot.capturedAtElapsedRealtimeMs - afterTrackState.playStartedRealtimeMs)
        } else {
            afterTrackState.positionAnchorMs
        }
        val drift = abs(snapshot.positionMs - currentPredictedMs)
        val seekOrDrift = drift > 500L
        val playStateChanged = snapshot.isPlaying != afterTrackState.isPlaying
        _state.value = afterTrackState.copy(
            positionAnchorMs = if (trackChanged || playStateChanged || seekOrDrift) {
                snapshot.positionMs
            } else afterTrackState.positionAnchorMs,
            playStartedRealtimeMs = if (trackChanged || playStateChanged || seekOrDrift) {
                snapshot.capturedAtElapsedRealtimeMs
            } else afterTrackState.playStartedRealtimeMs,
            isPlaying = if (playStateChanged) snapshot.isPlaying else afterTrackState.isPlaying,
            playbackMessage = null,
        )
    }

    fun saveSession(synchronous: Boolean) {
        sessions.saveActiveTab(
            if (currentDestination == Destination.NowPlaying) previousDestination else currentDestination,
            synchronous,
        )
        val current = _state.value
        if (!current.startupSessionLoaded && selectionGeneration == 0L) return
        if (current.hasCurrentTrack) {
            val queueState = queueController.state.value
            val currentEntries = queueState.history + listOfNotNull(queueState.current) +
                queueState.upNext + queueState.autoplay
            val currentQueue = currentEntries.map { it.track }
            if (currentQueue.isNotEmpty()) {
                val currentIdx = queueState.history.size.coerceIn(0, currentQueue.lastIndex)
                sessions.saveSession(
                    PlaybackSession(
                        queue = currentQueue,
                        currentIndex = currentIdx,
                        positionMs = currentPositionMs().coerceIn(0L, current.track.durationMs.coerceAtLeast(1L)),
                        wasPlaying = current.playWhenReady,
                        destination = currentDestination,
                        previousDestination = previousDestination,
                        radioActive = queueState.infinite,
                        radioSeed = queueState.recommendationSeed,
                        autoplayEntryIndices = currentEntries.withIndex()
                            .filter { it.value.origin == EntryOrigin.Autoplay }
                            .map { it.index }.toSet(),
                        queueEntryIds = currentEntries.map { it.entryId },
                        playedEntryIds = currentEntries.filter { it.wasPlayed }.map { it.entryId }.toSet(),
                        contextQueue = queueState.contextEntries.map { it.track },
                        contextEntryIds = queueState.contextEntries.map { it.entryId },
                        sessionId = queueState.sessionId,
                        persistenceId = queueState.persistenceId,
                    ),
                    synchronous,
                )
            }
        }
    }

    fun onSignedIn() {
        selectionGeneration++
        playbackRequestJob?.cancel()
        playbackRequestJob = null
        setExpectedPlaybackGeneration(0L)
        _state.value = _state.value.copy(
            isPlaying = false,
            playWhenReady = false,
            playbackPrepared = false,
            playbackMessage = null,
        )
        setHasUserPlaybackRequest(false)
    }

    fun onSignedOut() {
        selectionGeneration++
        playbackRequestJob?.cancel()
        accountStore.signOut()
        queueController.reset()
        playHistoryStore?.clear()
        waitingQueueEntryIdFlow.value = null
        pendingRestore = null
        setExpectedPlaybackGeneration(0L)
        _state.value = _state.value.copy(
            isPlaying = false,
            playWhenReady = false,
            playbackPrepared = false,
            hasCurrentTrack = false,
        )
        setHasUserPlaybackRequest(false)
    }

    companion object {
        private const val STATION_HISTORY_LIMIT = 50
    }
}
