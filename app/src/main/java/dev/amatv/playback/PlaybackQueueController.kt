package dev.amatv.playback

import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.QueuePreferences
import dev.amatv.integration.RecommendationProvider
import dev.amatv.integration.SongIdentity
import dev.amatv.integration.sanitizedAutoplayEntries
import dev.amatv.model.Track
import dev.amatv.model.CatalogKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class EntryOrigin {
    Context,
    Autoplay,
}

data class QueueEntry(
    val entryId: Long,
    val track: Track,
    val origin: EntryOrigin,
    /** Confirmed audio progress; retained even if this recording later becomes unavailable. */
    val wasPlayed: Boolean = false,
)

enum class ContextKind {
    Single,
    Album,
    Playlist,
    LibraryPlaylist,
    SongList,
}

data class PlaybackContext(
    val kind: ContextKind,
    val id: String? = null,
    val title: String? = null,
    val nextPageHref: String? = null,
)

sealed interface AutoplayStatus {
    object Idle : AutoplayStatus
    object Loading : AutoplayStatus
    data class Error(val message: String) : AutoplayStatus
}

data class QueueState(
    val history: List<QueueEntry> = emptyList(),
    val current: QueueEntry? = null,
    val upNext: List<QueueEntry> = emptyList(),
    val unshuffledUpNext: List<QueueEntry>? = null,
    val autoplay: List<QueueEntry> = emptyList(),
    val context: PlaybackContext? = null,
    val shuffle: Boolean = false,
    val repeat: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val infinite: Boolean = true,
    val source: AutoplaySource = AutoplaySource.YouTubeMusic,
    val autoplayStatus: AutoplayStatus = AutoplayStatus.Idle,
    val amStationId: String? = null,
    val ytmContinuation: String? = null,
    val ytmSeedVideoId: String? = null,
    val awaitingNext: Boolean = false,
    val contextEntries: List<QueueEntry> = emptyList(),
    /** User-selected anchor; only a confirmed unavailable anchor permits a healthy queued fallback. */
    val recommendationSeed: Track? = null,
    val consecutiveFailures: Int = 0,
    val failureLimitReached: Boolean = false,
    /** Changes only when the user starts a new context or a saved session is hydrated. */
    val sessionId: Long = 0L,
    val persistenceId: String = "",
) {
    val displayQueue: List<Track> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildList {
            current?.track?.let { add(it) }
            upNext.forEach { add(it.track) }
            autoplay.forEach { add(it.track) }
        }
    }

    /** UI timeline is separate from the forward playback/prefetch projection above. */
    val sessionEntries: List<QueueEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        history + listOfNotNull(current) + upNext + autoplay
    }

    val timelineEntries: List<QueueEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        history.filter { it.wasPlayed } + listOfNotNull(current) + upNext + autoplay
    }

    val currentTimelineIndex: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (current == null) -1 else history.count { it.wasPlayed }
    }

    val canSkipNext: Boolean
        get() = upNext.isNotEmpty() || autoplay.isNotEmpty() || repeat != PlaybackRepeatMode.Off || (infinite && current != null)

    val canSkipPrevious: Boolean
        get() = current != null
}

/**
 * Single source of truth for AM/ATV playback queue management.
 * Manages 4 distinct lifecycle layers:
 * 1. Context Queue (user-initiated Album, Playlist, Shelf, or SongList)
 * 2. Recommendations & Infinite Autoplay (∞ dual-engine AM / YTM)
 * 3. Metadata-only session history; recommendation lookback is bounded independently
 * 4. Deduplication via normalized title/artist identity keys to prevent playback loops
 */
class PlaybackQueueController(
    private val scope: CoroutineScope,
    private val services: RecommendationProvider,
    private val playbackPrefs: QueuePreferences,
    private val onPlayTrack: (Track) -> Unit,
    private val recommendationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        QueueState(
            repeat = playbackPrefs.repeatMode,
            shuffle = playbackPrefs.shuffleEnabled,
            infinite = playbackPrefs.autoplayEnabled,
            source = playbackPrefs.autoplaySource,
        ),
    )
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var nextEntryId = 1L
    private var nextSessionId = 1L
    private var playbackSessionActive = false
    private var refillGeneration = 0L
    private var refillJob: Job? = null
    private val failedSongs = LinkedHashMap<String, Track>()

    companion object {
        const val RECOMMENDATION_LOOKBACK = 200
        const val MAX_FAILURE_RECORDS = 200
        const val REFILL_THRESHOLD = 8
        const val AUTOPLAY_RESERVE = 16
        const val MAX_REFILL_PAGES = 3
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val AM_NETWORK_TIMEOUT_MS = 15_000L
        const val YTM_NETWORK_TIMEOUT_MS = 20_000L
    }

    fun playContext(
        tracks: List<Track>,
        startIndex: Int,
        context: PlaybackContext? = null,
        shuffle: Boolean? = null,
    ) {
        if (tracks.isEmpty()) return
        failedSongs.clear()
        playbackSessionActive = true
        // A playlist is an ordered list of entries, including deliberate repeats.
        // Deduplication belongs exclusively to generated recommendations.
        val entries = tracks.map {
            QueueEntry(nextEntryId++, it, EntryOrigin.Context)
        }
        val clampedIndex = startIndex.coerceIn(0, entries.lastIndex)
        val isShuffled = shuffle ?: _state.value.shuffle
        if (shuffle != null) playbackPrefs.shuffleEnabled = shuffle
        val historyEntries = if (isShuffled) emptyList() else entries.take(clampedIndex).map(::historyMetadata)
        val currentEntry = entries[clampedIndex]
        val remainingEntries = if (isShuffled) entries.filter { it.entryId != currentEntry.entryId }
            else entries.drop(clampedIndex + 1)

        cancelRefill()

        val (finalUpNext, unshuffled) = if (isShuffled) {
            remainingEntries.shuffled() to remainingEntries
        } else {
            remainingEntries to null
        }

        _state.update { curr ->
            curr.copy(
                history = historyEntries,
                current = currentEntry,
                upNext = finalUpNext,
                unshuffledUpNext = unshuffled,
                autoplay = emptyList(),
                context = context,
                autoplayStatus = AutoplayStatus.Idle,
                amStationId = null,
                ytmContinuation = null,
                ytmSeedVideoId = null,
                awaitingNext = false,
                contextEntries = entries,
                shuffle = isShuffled,
                recommendationSeed = currentEntry.track.takeIf { it.catalogKind == CatalogKind.Song },
                consecutiveFailures = 0,
                failureLimitReached = false,
                sessionId = nextSessionId++,
                persistenceId = java.util.UUID.randomUUID().toString(),
            )
        }

        onPlayTrack(currentEntry.track)
        triggerRefillIfNeeded()
    }

    /** Queue selection is a playhead move inside this session, never a new context. */
    fun selectTimelineEntry(entryId: Long): Boolean {
        val entry = _state.value.sessionEntries.firstOrNull { it.entryId == entryId } ?: return false
        selectEntry(entry)
        return true
    }

    fun selectEntry(entry: QueueEntry) {
        val curr = _state.value
        val upNextIndex = curr.upNext.indexOfFirst { it.entryId == entry.entryId }
        val autoplayIndex = if (upNextIndex < 0) curr.autoplay.indexOfFirst { it.entryId == entry.entryId } else -1
        val historyIndex = if (upNextIndex < 0 && autoplayIndex < 0) curr.history.indexOfFirst { it.entryId == entry.entryId } else -1
        if (upNextIndex < 0 && autoplayIndex < 0 && historyIndex < 0 && curr.current?.entryId != entry.entryId) return
        playbackSessionActive = true
        resetFailureStreak()
        _state.update { it.copy(awaitingNext = false) }
        // An explicit tap may retry this entry, but does not discard the radio rail.
        failedSongs.entries.removeAll { SongIdentity.isSameSong(it.value, entry.track) }
        when {
            upNextIndex >= 0 -> {
                val skipped = curr.upNext.take(upNextIndex).map(::historyMetadata)
                val newHistory = curr.history + listOfNotNull(curr.current?.let(::historyMetadata)) + skipped
                val newUpNext = curr.upNext.drop(upNextIndex + 1)
                val removedIds = skipped.map { it.entryId }.toSet() + entry.entryId
                _state.update { it.copy(
                    history = newHistory, current = entry, upNext = newUpNext,
                    unshuffledUpNext = curr.unshuffledUpNext?.filterNot { it.entryId in removedIds },
                ) }
            }
            autoplayIndex >= 0 -> {
                val traversed = (curr.upNext + curr.autoplay.take(autoplayIndex)).map(::historyMetadata)
                _state.update { it.copy(
                    history = curr.history + listOfNotNull(curr.current?.let(::historyMetadata)) + traversed,
                    current = entry, upNext = emptyList(), unshuffledUpNext = null,
                    autoplay = curr.autoplay.drop(autoplayIndex + 1),
                ) }
            }
            historyIndex >= 0 -> {
                val forward = curr.history.drop(historyIndex + 1) + listOfNotNull(curr.current)
                _state.update { it.copy(
                    history = curr.history.take(historyIndex), current = entry,
                    upNext = forward + curr.upNext,
                    unshuffledUpNext = if (curr.shuffle) forward + (curr.unshuffledUpNext ?: curr.upNext) else null,
                ) }
            }
            else -> Unit
        }
        onPlayTrack(entry.track)
        triggerRefillIfNeeded()
    }

    fun advanceNext(manual: Boolean = true): QueueEntry? {
        playbackSessionActive = true
        if (manual) resetFailureStreak()
        return advanceNextInternal(manual)
    }

    private fun advanceNextInternal(manual: Boolean): QueueEntry? {
        val curr = _state.value
        if (curr.repeat == PlaybackRepeatMode.One && !manual && curr.current?.track?.let(::isFailed) != true) {
            curr.current?.let { onPlayTrack(it.track); return it }
        }
        val currentPast = listOfNotNull(curr.current?.takeIf { !isFailed(it.track) || it.wasPlayed })
            .map(::historyMetadata)
        val nextIndex = curr.upNext.indexOfFirst { !isFailed(it.track) }
        if (nextIndex >= 0) {
            val next = curr.upNext[nextIndex]
            val traversed = curr.upNext.take(nextIndex)
            val consumedIds = traversed.map { it.entryId }.toSet() + next.entryId
            _state.update { it.copy(
                history = curr.history + currentPast + traversed.filter { it.wasPlayed }.map(::historyMetadata),
                current = next, upNext = curr.upNext.drop(nextIndex + 1),
                unshuffledUpNext = curr.unshuffledUpNext?.filterNot { it.entryId in consumedIds },
                awaitingNext = false,
            ) }
            onPlayTrack(next.track)
            triggerRefillIfNeeded()
            return next
        }
        val traversedPast = curr.upNext.filter { it.wasPlayed }.map(::historyMetadata)
        if (curr.repeat == PlaybackRepeatMode.All && curr.contextEntries.any { !isFailed(it.track) }) {
            val cycle = curr.contextEntries.filterNot { isFailed(it.track) }
                .map { it.copy(entryId = nextEntryId++, wasPlayed = false) }
            val ordered = if (curr.shuffle) cycle.shuffled() else cycle
            val next = ordered.first()
            _state.update { it.copy(
                history = curr.history + currentPast + traversedPast,
                current = next, upNext = ordered.drop(1),
                unshuffledUpNext = if (curr.shuffle) cycle.filter { it.entryId != next.entryId } else null,
                awaitingNext = false,
            ) }
            onPlayTrack(next.track)
            return next
        }
        val autoplayIndex = curr.autoplay.indexOfFirst { !isFailed(it.track) }
        if (autoplayIndex >= 0) {
            val next = curr.autoplay[autoplayIndex]
            val skippedPast = curr.autoplay.take(autoplayIndex).filter { it.wasPlayed }.map(::historyMetadata)
            _state.update { it.copy(
                history = curr.history + currentPast + traversedPast + skippedPast,
                current = next, upNext = emptyList(), unshuffledUpNext = null,
                autoplay = curr.autoplay.drop(autoplayIndex + 1), awaitingNext = false,
            ) }
            onPlayTrack(next.track)
            triggerRefillIfNeeded()
            return next
        }
        if (curr.infinite && curr.repeat == PlaybackRepeatMode.Off && curr.current != null && curr.recommendationSeed != null) {
            _state.update { it.copy(awaitingNext = true) }
            triggerRefillIfNeeded()
            return _state.value.current.takeIf { it?.entryId != curr.current.entryId }
        }
        return null
    }

    fun advancePrevious(positionMs: Long = 0L): QueueEntry? {
        playbackSessionActive = true
        resetFailureStreak()
        _state.update { it.copy(awaitingNext = false) }
        val curr = _state.value
        val previousIndex = curr.history.indexOfLast { !isFailed(it.track) }
        val currentPlayable = curr.current?.takeUnless { isFailed(it.track) }
        if (currentPlayable != null && (positionMs > 3000L || previousIndex < 0)) {
            onPlayTrack(currentPlayable.track)
            return currentPlayable
        }
        if (previousIndex < 0) return null
        val previous = curr.history[previousIndex]
        val currentForward = curr.current?.takeIf { !isFailed(it.track) || it.wasPlayed }
        val forward = curr.history.drop(previousIndex + 1) + listOfNotNull(currentForward)
        _state.update { it.copy(
            history = curr.history.take(previousIndex), current = previous,
            upNext = forward + curr.upNext,
            unshuffledUpNext = if (curr.shuffle) forward + (curr.unshuffledUpNext ?: curr.upNext) else null,
        ) }
        onPlayTrack(previous.track)
        return previous
    }

    fun clearUpcoming() {
        cancelRefill()
        _state.update { curr ->
            curr.copy(
                upNext = emptyList(),
                unshuffledUpNext = null,
                autoplay = emptyList(),
                amStationId = null,
                ytmContinuation = null,
                ytmSeedVideoId = null,
                autoplayStatus = AutoplayStatus.Idle,
                awaitingNext = false,
            )
        }
        triggerRefillIfNeeded()
    }

    fun setInfinite(enabled: Boolean) {
        if (_state.value.infinite == enabled) return
        playbackPrefs.autoplayEnabled = enabled
        if (!enabled) {
            cancelRefill()
            _state.update { curr ->
                curr.copy(
                    infinite = false,
                    autoplay = emptyList(),
                    amStationId = null,
                    ytmContinuation = null,
                    ytmSeedVideoId = null,
                    autoplayStatus = AutoplayStatus.Idle,
                    awaitingNext = false,
                )
            }
        } else {
            _state.update { it.copy(infinite = true) }
            triggerRefillIfNeeded()
        }
    }

    fun setAutoplaySource(source: AutoplaySource) {
        if (_state.value.source == source) return
        playbackPrefs.autoplaySource = source
        cancelRefill()
        _state.update { curr ->
            curr.copy(
                source = source,
                autoplay = emptyList(),
                amStationId = null,
                ytmContinuation = null,
                ytmSeedVideoId = null,
                autoplayStatus = AutoplayStatus.Idle,
            )
        }
        triggerRefillIfNeeded()
    }

    fun setShuffle(enabled: Boolean) {
        if (_state.value.shuffle == enabled) return
        playbackPrefs.shuffleEnabled = enabled
        _state.update { curr ->
            if (enabled) {
                val unshuffled = curr.upNext
                curr.copy(
                    shuffle = true,
                    upNext = unshuffled.shuffled(),
                    unshuffledUpNext = unshuffled,
                )
            } else {
                val restored = curr.unshuffledUpNext ?: curr.upNext
                curr.copy(
                    shuffle = false,
                    upNext = restored,
                    unshuffledUpNext = null,
                )
            }
        }
    }

    fun setRepeat(mode: PlaybackRepeatMode) {
        if (_state.value.repeat == mode) return
        playbackPrefs.repeatMode = mode
        if (mode != PlaybackRepeatMode.Off) cancelRefill()
        _state.update { it.copy(repeat = mode, awaitingNext = false, autoplayStatus = AutoplayStatus.Idle) }
        if (mode == PlaybackRepeatMode.Off) {
            triggerRefillIfNeeded()
        }
    }

    fun restoreFromSession(session: PlaybackSession) {
        val restored = session.sanitizedAutoplayEntries() ?: return
        cancelRefill()
        playbackSessionActive = false
        failedSongs.clear()
        val usedIds = HashSet<Long>()
        fun restoredEntry(id: Long?, track: Track, origin: EntryOrigin): QueueEntry {
            val stableId = id?.takeIf { it > 0 && usedIds.add(it) } ?: run {
                while (nextEntryId in usedIds) nextEntryId++
                val generated = nextEntryId++
                usedIds.add(generated)
                generated
            }
            nextEntryId = maxOf(nextEntryId, stableId + 1)
            return QueueEntry(stableId, track, origin, stableId in restored.playedEntryIds)
        }
        val entries = restored.queue.mapIndexed { index, track ->
            restoredEntry(restored.queueEntryIds.getOrNull(index), track,
                if (index in restored.autoplayEntryIndices) EntryOrigin.Autoplay else EntryOrigin.Context)
        }
        val clampedIndex = restored.currentIndex.coerceIn(entries.indices)
        val currentEntry = entries[clampedIndex]
        val future = entries.drop(clampedIndex + 1)
        val autoplayStart = future.indexOfLast { it.origin == EntryOrigin.Context } + 1
        val contextEntries = if (restored.contextQueue.isNotEmpty()) {
            // Context IDs may also be on the visible timeline; reuse that identity.
            restored.contextQueue.mapIndexed { index, track ->
                val id = restored.contextEntryIds.getOrNull(index)
                entries.firstOrNull { it.entryId == id && it.origin == EntryOrigin.Context }
                    ?: restoredEntry(id, track, EntryOrigin.Context)
            }
        } else entries.filter { it.origin == EntryOrigin.Context }
        _state.update { curr -> curr.copy(
            history = entries.take(clampedIndex).map(::historyMetadata),
            current = currentEntry, upNext = future.take(autoplayStart),
            unshuffledUpNext = null, autoplay = future.drop(autoplayStart),
            source = restored.autoplaySource, infinite = restored.radioActive, amStationId = null,
            ytmContinuation = restored.radioContinuation,
            ytmSeedVideoId = restored.radioSeedVideoId ?: restored.radioSeed?.ytmVideoId,
            autoplayStatus = AutoplayStatus.Idle, awaitingNext = false,
            contextEntries = contextEntries,
            recommendationSeed = restored.radioSeed?.takeIf { it.catalogKind == CatalogKind.Song }
                ?: currentEntry.track.takeIf { currentEntry.origin == EntryOrigin.Context && it.catalogKind == CatalogKind.Song }
                ?: contextEntries.firstOrNull { it.track.catalogKind == CatalogKind.Song }?.track,
            consecutiveFailures = 0, failureLimitReached = false, sessionId = nextSessionId++,
            persistenceId = restored.persistenceId.ifBlank { java.util.UUID.randomUUID().toString() },
        ) }
        // Restoration hydrates metadata only. User Play opens the recommendation gate.
    }

    private fun historyMetadata(entry: QueueEntry): QueueEntry = if (entry.track.lyrics.isEmpty()) entry
        else entry.copy(track = entry.track.copy(lyrics = emptyList()))

    fun activatePlaybackSession() {
        playbackSessionActive = true
        triggerRefillIfNeeded()
    }

    private fun isFailed(track: Track): Boolean = failedSongs.values.any { SongIdentity.isSameSong(it, track) }

    private fun resetFailureStreak() {
        if (_state.value.consecutiveFailures != 0 || _state.value.failureLimitReached) {
            _state.update { it.copy(consecutiveFailures = 0, failureLimitReached = false) }
        }
    }

    /** A matching, progressing player is the only automatic reset of the failure circuit. */
    fun markCurrentPlaybackStarted(expectedEntryId: Long) {
        if (_state.value.current?.entryId == expectedEntryId) {
            resetFailureStreak()
            if (_state.value.current?.wasPlayed != true) _state.update { curr ->
                curr.copy(current = curr.current?.copy(wasPlayed = true))
            }
            if (!playbackSessionActive) activatePlaybackSession()
        }
    }

    /** Only confirmed track-specific failures belong here; network/auth failures must wait. */
    fun skipFailedCurrent(expectedEntryId: Long): QueueEntry? {
        val current = _state.value.current ?: return null
        if (current.entryId != expectedEntryId || _state.value.failureLimitReached) return null
        // Duplicate terminal callbacks for the same failed attempt must not consume the circuit twice.
        if (isFailed(current.track)) return null
        failedSongs[SongIdentity.key(current.track)] = current.track
        while (failedSongs.size > MAX_FAILURE_RECORDS) failedSongs.remove(failedSongs.keys.first())
        val failures = _state.value.consecutiveFailures + 1
        val limit = failures >= MAX_CONSECUTIVE_FAILURES
        val anchorFailed = _state.value.recommendationSeed?.let(::isFailed) == true
        if (anchorFailed || limit) cancelRefill()
        _state.update { curr ->
            val context = curr.contextEntries.filterNot { isFailed(it.track) }
            val upcoming = curr.upNext.filter { !isFailed(it.track) || it.wasPlayed }
            val recommendations = curr.autoplay.filter { !isFailed(it.track) || it.wasPlayed }
            // A removed catalog anchor must not keep producing failed station requests.
            // This is the sole automatic re-anchor: use explicit context first, then
            // a healthy song already offered in this same radio queue.
            val replacementSeed = (context + upcoming + recommendations)
                .firstOrNull { it.track.catalogKind == CatalogKind.Song && !isFailed(it.track) }?.track
            curr.copy(
                history = curr.history.filter { !isFailed(it.track) || it.wasPlayed },
                upNext = upcoming,
                unshuffledUpNext = curr.unshuffledUpNext?.filter { !isFailed(it.track) || it.wasPlayed },
                autoplay = recommendations,
                contextEntries = curr.contextEntries,
                recommendationSeed = if (anchorFailed) replacementSeed ?: curr.recommendationSeed else curr.recommendationSeed,
                amStationId = if (anchorFailed) null else curr.amStationId,
                ytmContinuation = if (anchorFailed) null else curr.ytmContinuation,
                ytmSeedVideoId = if (anchorFailed) null else curr.ytmSeedVideoId,
                consecutiveFailures = failures,
                failureLimitReached = limit,
                awaitingNext = false,
                autoplayStatus = if (limit) AutoplayStatus.Error("連續多首歌曲無法播放，請稍後再試。")
                    else if (anchorFailed) AutoplayStatus.Idle else curr.autoplayStatus,
            )
        }
        if (limit) return null
        val next = advanceNextInternal(manual = true)
        if (next == null && !_state.value.awaitingNext) {
            _state.update { it.copy(autoplayStatus = AutoplayStatus.Error("這首歌曲無法播放，目前沒有其他可播放曲目。")) }
        }
        return next
    }

    private fun cancelRefill() {
        refillGeneration++
        refillJob?.cancel()
        refillJob = null
    }

    fun triggerRefillIfNeeded() {
        refillIfNeeded(MAX_REFILL_PAGES)
    }

    private fun refillIfNeeded(pagesRemaining: Int) {
        val s = _state.value
        if (!playbackSessionActive || !s.infinite || s.repeat != PlaybackRepeatMode.Off || s.failureLimitReached) return
        if (s.upNext.count { !isFailed(it.track) } >= REFILL_THRESHOLD ||
            s.autoplay.count { !isFailed(it.track) } >= AUTOPLAY_RESERVE) return
        if (s.autoplayStatus is AutoplayStatus.Loading) return
        val seedTrack = s.recommendationSeed ?: return
        val gen = ++refillGeneration
        _state.update { it.copy(autoplayStatus = AutoplayStatus.Loading) }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val fetchedTracks: List<Track>
                var nextAmStationId = s.amStationId
                var nextYtmContinuation = s.ytmContinuation
                var nextYtmSeedVideoId = s.ytmSeedVideoId
                when (s.source) {
                    AutoplaySource.AppleMusic -> {
                        val station = withContext(recommendationDispatcher) {
                            withTimeout(AM_NETWORK_TIMEOUT_MS) {
                                if (nextAmStationId == null) {
                                    services.createAmStation(seedTrack.catalogId ?: seedTrack.id)
                                } else {
                                    nextAmStationId to services.amStationNextTracks(checkNotNull(nextAmStationId))
                                }
                            }
                        }
                        nextAmStationId = station.first
                        fetchedTracks = station.second
                    }
                    AutoplaySource.YouTubeMusic -> {
                        val excluded = (s.history.takeLast(RECOMMENDATION_LOOKBACK) + listOfNotNull(s.current) + s.upNext + s.autoplay)
                            .map { it.track } + failedSongs.values
                        val page = withContext(recommendationDispatcher) {
                            withTimeout(YTM_NETWORK_TIMEOUT_MS) {
                                services.ytmRadio(seedTrack, s.ytmContinuation,
                                    excluded.mapNotNull { it.catalogId }.toSet(), s.ytmSeedVideoId)
                            }
                        }
                        nextYtmContinuation = page.continuation
                        nextYtmSeedVideoId = page.seedVideoId
                        fetchedTracks = page.tracks
                    }
                }
                if (refillGeneration != gen) return@launch
                val live = _state.value
                val occupied = live.history.takeLast(RECOMMENDATION_LOOKBACK) + listOfNotNull(live.current) + live.upNext + live.autoplay
                val newTracks = RecommendationSequencer.sequence(
                    candidates = fetchedTracks,
                    excluded = occupied.map { it.track } + failedSongs.values,
                    recent = occupied.takeLast(6).map { it.track },
                ).map { QueueEntry(nextEntryId++,
                    it.copy(recommendedByYtm = s.source == AutoplaySource.YouTubeMusic), EntryOrigin.Autoplay) }
                // A failed single-song anchor may have had no fallback until this
                // in-flight radio page arrived. Repair that seed before any refill.
                val replacementSeed = if (live.recommendationSeed?.let(::isFailed) == true) {
                    (live.contextEntries + live.upNext + live.autoplay + newTracks)
                        .firstOrNull { it.track.catalogKind == CatalogKind.Song && !isFailed(it.track) }?.track
                } else null
                val providerCanContinue = replacementSeed != null || when (s.source) {
                    AutoplaySource.AppleMusic -> nextAmStationId != null
                    AutoplaySource.YouTubeMusic -> nextYtmContinuation != null && nextYtmContinuation != s.ytmContinuation
                }
                val continueLoading = pagesRemaining > 1 && providerCanContinue &&
                    live.autoplay.size + newTracks.size < AUTOPLAY_RESERVE
                _state.update { curr ->
                    if (refillGeneration != gen) curr else curr.copy(
                        autoplay = curr.autoplay + newTracks,
                        autoplayStatus = if (newTracks.isEmpty() && curr.autoplay.isEmpty() && !continueLoading) {
                            AutoplayStatus.Error("暫時沒有可播放的推薦歌曲，請再試一次。")
                        } else AutoplayStatus.Idle,
                        recommendationSeed = replacementSeed ?: curr.recommendationSeed,
                        amStationId = if (replacementSeed != null) null else nextAmStationId,
                        ytmContinuation = if (replacementSeed != null) null else nextYtmContinuation,
                        ytmSeedVideoId = if (replacementSeed != null) null else nextYtmSeedVideoId,
                        awaitingNext = curr.awaitingNext && (newTracks.isNotEmpty() || continueLoading),
                    )
                }
                if (_state.value.awaitingNext && _state.value.autoplay.isNotEmpty()) {
                    advanceNextInternal(manual = true)
                } else if (continueLoading) {
                    refillIfNeeded(pagesRemaining - 1)
                }
            } catch (timeout: TimeoutCancellationException) {
                if (refillGeneration == gen) _state.update {
                    it.copy(autoplayStatus = AutoplayStatus.Error("推薦載入逾時，請稍後再試。"), awaitingNext = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (refillGeneration == gen) _state.update {
                    it.copy(autoplayStatus = AutoplayStatus.Error(error.message ?: "無法載入推薦歌曲。"), awaitingNext = false)
                }
            }
        }
        refillJob = job
        job.start()
    }
}
