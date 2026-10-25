package dev.amatv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.integration.GoogleTvPairing
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.requestLyricsWithRetry
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.classifyPlaybackFailure
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.openAction
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.playback.ContextKind
import dev.amatv.playback.EntryOrigin
import dev.amatv.playback.PlaybackContext
import dev.amatv.playback.PlaybackQueueController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun demoShelves(): Map<Destination, List<ContentShelf>> = mapOf(
    Destination.Home to listOf(
        ContentShelf("demo-picks", AppText.text(R.string.app_demo_top_picks), MockCatalog.tracks, featured = true),
        ContentShelf("demo-recent", AppText.text(R.string.app_recently_played), MockCatalog.tracks.asReversed()),
    ),
    Destination.Browse to listOf(
        ContentShelf("demo-new", AppText.text(R.string.app_demo_new_this_week), MockCatalog.tracks, featured = true),
    ),
    Destination.Library to listOf(
        ContentShelf("demo-library", AppText.text(R.string.app_recently_added), MockCatalog.tracks, featured = true),
    ),
)

@Composable
fun AmAtvApp() {
    val context = LocalContext.current
    val contentLanguageTag = LocalConfiguration.current.locales.toLanguageTags()
    val scope = rememberCoroutineScope()
    val services = remember(context) { MusicServices(context) }
    val cacheSettings by services.contentCache.settings.collectAsState()
    val playbackPrefs = remember(context) { PlaybackPreferences(context) }
    val startupCheckpoint = remember(playbackPrefs) {
        playbackPrefs.restoreCheckpointSession()?.copy(wasPlaying = false)
    }
    var startupSessionLoaded by remember { mutableStateOf(false) }
    var startupCursorEdited by remember { mutableStateOf(false) }
    val startupQueueActions = remember { StartupQueueActions() }
    var destination by remember {
        mutableStateOf(startupCheckpoint?.destination ?: playbackPrefs.activeTab)
    }
    var previousDestination by remember {
        mutableStateOf(startupCheckpoint?.previousDestination ?: playbackPrefs.activeTab)
    }
    var track by remember {
        mutableStateOf(startupCheckpoint?.queue?.getOrNull(startupCheckpoint.currentIndex)
            ?: MockCatalog.tracks.first())
    }
    var hasCurrentTrack by remember {
        mutableStateOf(startupCheckpoint != null || !services.isCatalogConfigured)
    }
    var positionAnchorMs by remember {
        mutableLongStateOf(startupCheckpoint?.positionMs ?: if (track.catalogId == null) 7_500L else 0L)
    }
    var playStartedRealtimeMs by remember {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }
    // Startup restores metadata and cursor only. An explicit user action owns playback.
    var isPlaying by remember { mutableStateOf(false) }
    var playWhenReady by remember { mutableStateOf(false) }
    var playbackPrepared by remember { mutableStateOf(false) }
    var hasUserPlaybackRequest by remember { mutableStateOf(false) }
    var playbackMessage by remember { mutableStateOf<String?>(null) }
    var waitingQueueEntryId by remember { mutableStateOf<Long?>(null) }
    var expectedPlaybackGeneration by remember { mutableLongStateOf(0L) }
    var lastPlaybackFailure by remember { mutableStateOf<PlaybackFailureKind?>(null) }
    var commandFailure by remember { mutableStateOf<Triple<Long, PlaybackFailureKind, String>?>(null) }
    var failureHandledSelection by remember { mutableLongStateOf(-1L) }
    var successfulSelection by remember { mutableLongStateOf(-1L) }
    var connectionState by remember { mutableStateOf(services.connectionState) }
    var serviceMessage by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var shelvesLanguageTag by remember { mutableStateOf(contentLanguageTag) }
    var shelvesByDestination by remember {
        mutableStateOf<Map<Destination, List<ContentShelf>>>(
            if (services.isCatalogConfigured) {
                emptyMap()
            } else {
                demoShelves()
            },
        )
    }
    var loadingDestinations by remember {
        mutableStateOf(
            if (services.isCatalogConfigured) setOf(Destination.Home) else emptySet(),
        )
    }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var recentTracks by remember {
        mutableStateOf(
            if (services.isCatalogConfigured) emptyList() else MockCatalog.tracks.asReversed(),
        )
    }
    var advancingEndedTrackId by remember { mutableStateOf<String?>(null) }
    var ytmPairing by remember { mutableStateOf<GoogleTvPairing?>(null) }
    var ytmSignedIn by remember { mutableStateOf(services.ytmSignedIn) }
    var canSkipNext by remember { mutableStateOf(true) }
    var canSkipPrevious by remember { mutableStateOf(true) }
    var repeatMode by remember { mutableStateOf(playbackPrefs.repeatMode) }
    var shuffleEnabled by remember { mutableStateOf(playbackPrefs.shuffleEnabled) }
    var autoplayEnabled by remember { mutableStateOf(playbackPrefs.autoplayEnabled) }
    var lyricOffsetMs by remember { mutableLongStateOf(playbackPrefs.lyricOffsetMs) }
    var canSetRepeat by remember { mutableStateOf(false) }
    var canSetShuffle by remember { mutableStateOf(false) }
    var pendingRestore by remember {
        mutableStateOf(startupCheckpoint?.takeIf { track.catalogId != null })
    }
    var lyricsByTrackId by remember {
        mutableStateOf<Map<String, List<dev.amatv.model.LyricLine>>>(emptyMap())
    }
    var lyricsLookupFailures by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lyricsLoadingTrackId by remember { mutableStateOf<String?>(null) }
    var lyricsTransientFailureTrackId by remember { mutableStateOf<String?>(null) }
    var lyricsRetryNonce by remember { mutableIntStateOf(0) }
    var playbackRequestJob by remember { mutableStateOf<Job?>(null) }
    var selectionGeneration by remember { mutableLongStateOf(0L) }
    var spectrumPermissionAsked by remember { mutableStateOf(false) }
    var spectrumPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val spectrumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        spectrumPermissionGranted = it
    }
    var pendingRequestedSongCatalogId by remember { mutableStateOf<String?>(null) }
    // Read only by callbacks/effects: native position polls do not recompose the app.
    val systemPlaybackSnapshot = remember { mutableStateOf<PlaybackSnapshot?>(null) }

    fun withCachedLyrics(candidate: Track): Track {
        if (candidate.lyrics.isNotEmpty()) return candidate
        val cached = lyricsByTrackId[candidate.id].orEmpty()
        return if (cached.isEmpty()) candidate else candidate.copy(lyrics = cached)
    }

    fun cacheLyrics(id: String, lines: List<dev.amatv.model.LyricLine>) {
        // The persistent TTML store owns history; don't grow TV heap usage all night.
        val recent = (lyricsByTrackId - id) + (id to lines)
        lyricsByTrackId = recent.entries.toList().takeLast(24).associate { it.toPair() }
    }

    fun refreshAllContent() {
        connectionState = services.connectionState
        shelvesByDestination = emptyMap()
        loadingDestinations = setOf(destination)
        serviceMessage = null
        lyricsLookupFailures = emptySet()
        lyricsTransientFailureTrackId = null
        reloadNonce++
    }

    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            serviceMessage = data?.getStringExtra(
                dev.amatv.integration.MusicKitWebAuthActivity.EXTRA_ERROR,
            ) ?: AppText.text(R.string.app_apple_sign_in_cancelled)
        } else {
            runCatching { services.completeWebAuthentication(data) }
                .onSuccess {
                    selectionGeneration++
                    playbackRequestJob?.cancel()
                    isPlaying = false
                    playWhenReady = false
                    playbackPrepared = false
                    hasUserPlaybackRequest = false
                    expectedPlaybackGeneration = 0L
                    playbackMessage = null
                    lyricsLookupFailures = emptySet()
                    lyricsTransientFailureTrackId = null
                    connectionState = MusicConnectionState.SignedIn
                    shelvesByDestination = emptyMap()
                    loadingDestinations = setOf(Destination.Home)
                    destination = Destination.Home
                    serviceMessage = null
                    reloadNonce++
                }
                .onFailure { serviceMessage = it.message ?: AppText.text(R.string.app_sign_in_failed) }
        }
    }

    fun currentPositionMs(): Long {
        if (!isPlaying) return positionAnchorMs
        val elapsed = android.os.SystemClock.elapsedRealtime() - playStartedRealtimeMs
        val advanced = positionAnchorMs + elapsed
        return if (track.catalogId == null) advanced.mod(track.durationMs.coerceAtLeast(1L))
        else advanced.coerceAtMost(track.durationMs.coerceAtLeast(1L))
    }

    fun launchNativePlayback(nextTrack: Track, resumePositionMs: Long = 0L) {
        hasUserPlaybackRequest = true
        playbackPrepared = false
        playbackMessage = null
        val selection = selectionGeneration
        playbackRequestJob?.cancel()
        expectedPlaybackGeneration = 0L
        lastPlaybackFailure = null
        commandFailure = null
        pendingRequestedSongCatalogId = nextTrack.catalogId.takeIf { nextTrack.catalogKind == CatalogKind.Song }
        playbackRequestJob = scope.launch {
            try {
                // Authorize/prepare silently. Re-read the latest user intent after
                // token I/O, so a pause during preparation cannot be overwritten.
                val nativeGeneration = services.preparePlayback(nextTrack, resumePositionMs, playWhenReady = false)
                if (selection != selectionGeneration) return@launch
                expectedPlaybackGeneration = nativeGeneration
                if (playWhenReady) services.play() else services.pause()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (selection == selectionGeneration) {
                    commandFailure = Triple(selection, classifyPlaybackFailure(null, null, null, error),
                        error.message ?: AppText.text(R.string.app_song_load_failed))
                }
            }
        }
    }

    fun pausePlayback() {
        positionAnchorMs = currentPositionMs()
        playWhenReady = false
        pendingRestore = pendingRestore?.copy(wasPlaying = false)
        if (track.catalogId != null) services.pause()
        isPlaying = false
    }

    fun requestPlayback() {
        playWhenReady = true
        if (lyricsTransientFailureTrackId == track.id) lyricsRetryNonce++
        pendingRestore = pendingRestore?.copy(wasPlaying = true)
        if (!startupSessionLoaded && selectionGeneration == 0L) return
        // Play while Next is resolving belongs to the incoming entry, not
        // the old song still used as the queue's continuation anchor.
        if (waitingQueueEntryId != null) return
        if (track.catalogId != null) {
            if (lastPlaybackFailure != null || (expectedPlaybackGeneration == 0L && playbackRequestJob?.isActive != true)) {
                selectionGeneration++
                launchNativePlayback(track, positionAnchorMs)
            } else if (expectedPlaybackGeneration != 0L) services.play()
        } else {
            playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            isPlaying = true
        }
        playbackMessage = null
    }

    fun togglePlayback() {
        if (playWhenReady) pausePlayback() else requestPlayback()
    }

    fun retryCurrentLyrics() {
        lyricsLookupFailures = lyricsLookupFailures - track.id
        lyricsTransientFailureTrackId = null
        lyricsRetryNonce++
    }

    fun seekPlaybackBy(delta: Long) {
        positionAnchorMs = (currentPositionMs() + delta).coerceIn(0L, track.durationMs.coerceAtLeast(0L))
        playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        pendingRestore = pendingRestore?.copy(positionMs = positionAnchorMs)
        startupCursorEdited = true
        if (track.catalogId == null || expectedPlaybackGeneration == 0L) return
        if (playbackPrepared) {
            runCatching { services.seekTo(positionAnchorMs) }
                .onFailure { playbackMessage = AppText.text(R.string.app_seek_failed) }
        } else if (hasUserPlaybackRequest) {
            selectionGeneration++
            launchNativePlayback(track, positionAnchorMs)
        }
    }

    fun startSignIn() {
        if (connectionState == MusicConnectionState.Demo) {
            serviceMessage = services.setupMessage
            destination = Destination.Account
            return
        }
        scope.launch {
            runCatching { services.webAuthenticationIntent() }
                .onSuccess(webAuthenticationLauncher::launch)
                .onFailure {
                    serviceMessage = it.message ?: AppText.text(R.string.app_apple_sign_in_unavailable)
                }
        }
    }

    fun startYtmPairing() {
        if (!services.ytmPairingConfigured) {
            serviceMessage = AppText.text(R.string.app_google_sign_in_unavailable)
            return
        }
        scope.launch {
            try {
                val pairing = services.startYtmPairing()
                ytmPairing = pairing
                serviceMessage = null
                services.awaitYtmPairing(pairing)
                ytmPairing = null
                ytmSignedIn = true
                shelvesByDestination = shelvesByDestination - Destination.Home
                reloadNonce++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ytmPairing = null
                serviceMessage = error.message ?: AppText.text(R.string.app_google_pairing_failed)
            }
        }
    }

    fun executeTrackPlayback(nextTrack: Track) {
        waitingQueueEntryId = null
        selectionGeneration++
        playbackRequestJob?.cancel()
        pendingRestore = null
        if (playWhenReady && nextTrack.catalogId != null && connectionState != MusicConnectionState.SignedIn) {
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        positionAnchorMs = 0L
        val initialTrack = withCachedLyrics(nextTrack)
        track = initialTrack
        advancingEndedTrackId = null
        hasCurrentTrack = true
        playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        isPlaying = nextTrack.catalogId == null && playWhenReady
        playbackMessage = null
        playbackPrepared = false

        if (nextTrack.catalogId != null) {
            // Stop the previous song while authorization/preparation is pending.
            services.pause()
            if (playWhenReady) {
                launchNativePlayback(initialTrack)
            } else {
                expectedPlaybackGeneration = 0L
                pendingRequestedSongCatalogId = null
                lastPlaybackFailure = null
                commandFailure = null
            }
        } else {
            expectedPlaybackGeneration = 0L
            pendingRequestedSongCatalogId = null
        }
    }

    val queueController = remember {
        PlaybackQueueController(
            scope = scope,
            services = services,
            playbackPrefs = playbackPrefs,
            onPlayTrack = { nextTrack ->
                executeTrackPlayback(nextTrack)
            },
        )
    }
    val queueState by queueController.state.collectAsState()
    val playbackQueue = queueState.displayQueue
    val timelineEntries = remember(queueState.history, queueState.current, queueState.upNext, queueState.autoplay) {
        queueState.timelineEntries
    }
    val timelineTracks = remember(timelineEntries) { timelineEntries.map { it.track } }
    val timelineEntryIds = remember(timelineEntries) { timelineEntries.map { it.entryId } }

    fun waitForNextQueueEntry(entryId: Long?) {
        if (entryId == null) return
        waitingQueueEntryId = entryId
        // Consume the startup Play gate even when only metadata navigation was
        // replayed. The next entry's callback will honor the latest user intent.
        hasUserPlaybackRequest = true
        positionAnchorMs = currentPositionMs()
        isPlaying = false
        playbackPrepared = false
        expectedPlaybackGeneration = 0L
        playbackRequestJob?.cancel()
        services.pause()
        playbackMessage = null
    }

    fun applyQueueAction(action: StartupQueueAction): Boolean {
        when (action) {
            StartupQueueAction.Next -> {
                pendingRestore = null
                val next = queueController.advanceNext(manual = true)
                val live = queueController.state.value
                if (next == null && live.awaitingNext) {
                    waitForNextQueueEntry(live.current?.entryId)
                } else if (next == null) {
                    positionAnchorMs = currentPositionMs()
                    services.pause()
                    isPlaying = false
                    playWhenReady = false
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
            StartupQueueAction.CycleRepeat -> queueController.setRepeat(when (queueController.state.value.repeat) {
                PlaybackRepeatMode.Off -> PlaybackRepeatMode.All
                PlaybackRepeatMode.All -> PlaybackRepeatMode.One
                PlaybackRepeatMode.One -> PlaybackRepeatMode.Off
            })
            StartupQueueAction.ToggleShuffle -> queueController.setShuffle(!queueController.state.value.shuffle)
            StartupQueueAction.ToggleInfinite -> queueController.setInfinite(!queueController.state.value.infinite)
            StartupQueueAction.ToggleSource -> queueController.setAutoplaySource(
                if (queueController.state.value.source == AutoplaySource.YouTubeMusic) AutoplaySource.AppleMusic
                else AutoplaySource.YouTubeMusic,
            )
            is StartupQueueAction.SetSource -> queueController.setAutoplaySource(action.source)
        }
        return true
    }

    fun dispatchQueueAction(action: StartupQueueAction): Boolean =
        if (!startupSessionLoaded && selectionGeneration == 0L) startupQueueActions.enqueue(action)
        else applyQueueAction(action)

    fun supersedeStartupQueueWithNewContext() {
        // A catalog play action deliberately replaces the old rail. Keep pending
        // preference choices, but don't replay old navigation into the new context.
        startupQueueActions.preferencesForNewContext().forEach { applyQueueAction(it) }
    }

    LaunchedEffect(playbackPrefs) {
        startupCheckpoint?.let(queueController::restoreFromSession)
        val fullSession = cancellableResult {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { playbackPrefs.restoreSession() }
        }.getOrNull()?.copy(wasPlaying = false)
        if (selectionGeneration == 0L && fullSession != null) {
            val cursor = if (startupCursorEdited && track.catalogId == fullSession.queue[fullSession.currentIndex].catalogId) {
                positionAnchorMs
            } else fullSession.positionMs
            val restored = fullSession.copy(positionMs = cursor, wasPlaying = playWhenReady)
            queueController.restoreFromSession(restored)
            track = withCachedLyrics(restored.queue[restored.currentIndex])
            hasCurrentTrack = true
            positionAnchorMs = cursor
            pendingRestore = restored.takeIf { track.catalogId != null }
        }
        if (selectionGeneration == 0L) {
            // Replay metadata-only against the full rail, then honor the latest
            // Play/Pause choice once. No intermediate item prepares native audio.
            startupQueueActions.replayHydrated(
                requestedPlayIntent = playWhenReady,
                setPlayIntent = { playWhenReady = it },
                apply = ::applyQueueAction,
            )
        } else {
            startupQueueActions.drain()
        }
        startupSessionLoaded = true
    }

    // A Play press during metadata IO is deferred until the full queue/cursor is hydrated.
    LaunchedEffect(startupSessionLoaded, playWhenReady, track.id) {
        if (startupSessionLoaded && playWhenReady && !hasUserPlaybackRequest &&
            waitingQueueEntryId == null && !queueState.awaitingNext && expectedPlaybackGeneration == 0L && playbackRequestJob?.isActive != true
        ) {
            if (track.catalogId != null) {
                selectionGeneration++
                launchNativePlayback(track, positionAnchorMs)
            } else if (!services.isCatalogConfigured) {
                hasUserPlaybackRequest = true
                playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                isPlaying = true
            }
        }
    }
    LaunchedEffect(hasUserPlaybackRequest) {
        if (hasUserPlaybackRequest) queueController.activatePlaybackSession()
    }

    fun playTrack(nextTrack: Track, contextQueue: List<Track> = emptyList()) {
        if (nextTrack.openAction != CatalogOpenAction.Play) return
        if (nextTrack.catalogId != null && connectionState != MusicConnectionState.SignedIn) {
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        if (destination != Destination.NowPlaying) previousDestination = destination
        pendingRestore = null

        val fullQueue = if (contextQueue.isNotEmpty()) {
            contextQueue.map(::withCachedLyrics)
        } else if (nextTrack.catalogId == null) {
            MockCatalog.tracks
        } else {
            listOf(withCachedLyrics(nextTrack))
        }

        val trackIndex = fullQueue.indexOfFirst { it.matchesPlaybackQueueEntry(nextTrack) }.coerceAtLeast(0)
        playWhenReady = true
        queueController.playContext(
            tracks = fullQueue,
            startIndex = trackIndex,
            context = PlaybackContext(
                kind = ContextKind.SongList,
                id = nextTrack.catalogId,
                title = nextTrack.album,
            ),
        )
        destination = Destination.NowPlaying
    }

    fun playCollection(collection: Track, tracks: List<Track>, index: Int, shuffle: Boolean) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        if (connectionState != MusicConnectionState.SignedIn) {
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        supersedeStartupQueueWithNewContext()
        if (destination != Destination.NowPlaying) previousDestination = destination
        pendingRestore = null
        playWhenReady = true
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
        destination = Destination.NowPlaying
    }

    fun advanceNextTrack(isAuto: Boolean = true) {
        val next = queueController.advanceNext(manual = !isAuto)
        val live = queueController.state.value
        if (next == null && live.awaitingNext) {
            waitForNextQueueEntry(live.current?.entryId)
        } else if (next == null) {
            isPlaying = false
            playWhenReady = false
        }
    }

    fun selectQueueTrack(index: Int) {
        timelineEntries.getOrNull(index)?.let { entry ->
            val previousIntent = playWhenReady
            playWhenReady = true
            if (!dispatchQueueAction(StartupQueueAction.Select(entry.entryId))) playWhenReady = previousIntent
        }
    }

    fun skip(relative: Int) {
        dispatchQueueAction(if (relative > 0) StartupQueueAction.Next else StartupQueueAction.Previous)
    }

    fun showNowPlaying() {
        if (!hasCurrentTrack) {
            val firstCandidate = shelvesByDestination[destination]?.firstOrNull()?.items?.firstOrNull()
                ?: shelvesByDestination[Destination.Home]?.firstOrNull()?.items?.firstOrNull()
            if (firstCandidate != null) {
                playTrack(firstCandidate)
            } else {
                serviceMessage = AppText.text(R.string.app_choose_content_before_playback)
            }
            return
        }
        if (destination != Destination.NowPlaying) previousDestination = destination
        destination = Destination.NowPlaying
    }

    fun handlePlaybackFailure(selection: Long, kind: PlaybackFailureKind, message: String) {
        if (selection != selectionGeneration || failureHandledSelection == selection) return
        failureHandledSelection = selection
        lastPlaybackFailure = kind
        pendingRestore = null
        pendingRequestedSongCatalogId = null
        isPlaying = false
        playbackPrepared = false
        services.pause()
        if (kind == PlaybackFailureKind.TrackUnavailable && playWhenReady) {
            val entry = queueController.state.value.current
            val next = entry?.let { queueController.skipFailedCurrent(it.entryId) }
            // onPlayTrack can already have started another request synchronously.
            if (selection != selectionGeneration) return
            if (next == null && queueController.state.value.awaitingNext) {
                waitForNextQueueEntry(queueController.state.value.current?.entryId)
            } else if (next == null) {
                playWhenReady = false
                playbackMessage = if (queueController.state.value.failureLimitReached) {
                    AppText.text(R.string.app_multiple_songs_unavailable)
                } else message
            }
        } else {
            playWhenReady = false
            playbackMessage = message
        }
    }

    LaunchedEffect(commandFailure) {
        commandFailure?.let { handlePlaybackFailure(it.first, it.second, it.third) }
    }

    LaunchedEffect(waitingQueueEntryId, queueState.current?.entryId, queueState.awaitingNext, queueState.autoplayStatus) {
        val waitingEntry = waitingQueueEntryId ?: return@LaunchedEffect
        if (queueState.current?.entryId != waitingEntry) {
            waitingQueueEntryId = null
            return@LaunchedEffect
        }
        val message = waitingRecommendationError(
            waitingEntry, queueState.current?.entryId, queueState.awaitingNext,
            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message,
        )
        if (message != null) {
            // The failed wait is terminal. A later explicit Play may retry the
            // displayed item, but hydration must never replay it automatically.
            waitingQueueEntryId = null
            playWhenReady = false
            isPlaying = false
            playbackMessage = message
        } else if (!queueState.awaitingNext && queueState.autoplayStatus is dev.amatv.playback.AutoplayStatus.Idle) {
            waitingQueueEntryId = null
            playWhenReady = false
        }
    }

    fun publishSystemSnapshot(snapshot: PlaybackSnapshot) {
        val published = if (!playWhenReady && (snapshot.isPlaying || snapshot.isBuffering)) {
            snapshot.copy(isPlaying = false, isBuffering = false)
        } else snapshot
        systemPlaybackSnapshot.value = published
        val live = queueController.state.value
        services.publishSystemPlayback(published, live.canSkipNext, live.canSkipPrevious)
    }

    fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.requestGeneration == 0L || snapshot.requestGeneration != expectedPlaybackGeneration) return
        if (snapshot.errorMessage != null) {
            publishSystemSnapshot(snapshot)
            handlePlaybackFailure(selectionGeneration, snapshot.failureKind ?: PlaybackFailureKind.Unexpected,
                snapshot.errorMessage)
            return
        }
        val pendingCatalogId = pendingRequestedSongCatalogId
        if (pendingCatalogId != null &&
            (!snapshot.hasPlayerCurrentTrack || snapshot.currentTrack?.catalogId != pendingCatalogId)
        ) return
        publishSystemSnapshot(snapshot)
        if (!snapshot.isPrepared) return
        playbackPrepared = true
        pendingRequestedSongCatalogId = null
        pendingRestore = null
        if (snapshot.isPlaying && snapshot.positionMs > 250L && successfulSelection != selectionGeneration) {
            successfulSelection = selectionGeneration
            queueController.state.value.current?.let { queueController.markCurrentPlaybackStarted(it.entryId) }
        }
        val endedEntryKey = queueController.state.value.current?.entryId?.toString() ?: track.id
        if (!snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId) advancingEndedTrackId = null
        if (snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId &&
            advancingEndedTrackId != endedEntryKey
        ) {
            advancingEndedTrackId = endedEntryKey
            isPlaying = false
            advanceNextTrack(isAuto = true)
            return
        }
        val trackChanged = snapshot.currentTrack != null && (
            track.id != snapshot.currentTrack.id ||
            track.catalogId != snapshot.currentTrack.catalogId
        )
        snapshot.currentTrack?.let { current ->
            val provenance = queueController.state.value.current?.track?.takeIf { it.catalogId == current.catalogId }
            val withLyrics = withCachedLyrics(current.copy(
                recommendedByYtm = provenance?.recommendedByYtm == true,
                ytmVideoId = provenance?.ytmVideoId,
            ))
            if (track.id != withLyrics.id ||
                track.lyrics != withLyrics.lyrics ||
                track.title != withLyrics.title ||
                track.artist != withLyrics.artist ||
                track.durationMs != withLyrics.durationMs ||
                track.recommendedByYtm != withLyrics.recommendedByYtm
            ) {
                track = withLyrics
            }
            hasCurrentTrack = true
        }

        // Performance optimization: prevent 20fps root recomposition by checking drift > 500ms
        val currentPredictedMs = if (isPlaying) {
            positionAnchorMs + (snapshot.capturedAtElapsedRealtimeMs - playStartedRealtimeMs)
        } else {
            positionAnchorMs
        }
        val drift = kotlin.math.abs(snapshot.positionMs - currentPredictedMs)
        val seekOrDrift = drift > 500L
        val playStateChanged = snapshot.isPlaying != isPlaying
        if (trackChanged || playStateChanged || seekOrDrift) {
            positionAnchorMs = snapshot.positionMs
            playStartedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs
        }
        if (playStateChanged) {
            isPlaying = snapshot.isPlaying
        }
        canSkipNext = queueState.canSkipNext
        canSkipPrevious = queueState.canSkipPrevious
        canSetRepeat = true
        canSetShuffle = true
        playbackMessage = null
    }

    val latestSnapshotHandler by rememberUpdatedState<(PlaybackSnapshot) -> Unit>(::applyPlaybackSnapshot)
    val latestSystemPlaybackCallbacks by rememberUpdatedState(
        PlaybackMediaSession.Callbacks(
            play = ::requestPlayback,
            pause = ::pausePlayback,
            next = { skip(1) },
            previous = { skip(-1) },
            seekTo = { position -> seekPlaybackBy(position - currentPositionMs()) },
        ),
    )
    DisposableEffect(services) {
        services.setSystemPlaybackCallbacks(PlaybackMediaSession.Callbacks(
            play = { latestSystemPlaybackCallbacks.play() },
            pause = { latestSystemPlaybackCallbacks.pause() },
            next = { latestSystemPlaybackCallbacks.next() },
            previous = { latestSystemPlaybackCallbacks.previous() },
            seekTo = { latestSystemPlaybackCallbacks.seekTo(it) },
        ))
        services.setPlaybackListener { latestSnapshotHandler(it) }
        onDispose {
            services.setSystemPlaybackCallbacks(null)
            services.setPlaybackListener(null)
            services.release()
        }
    }
    LaunchedEffect(queueState.canSkipNext, queueState.canSkipPrevious) {
        systemPlaybackSnapshot.value?.takeIf {
            it.requestGeneration == expectedPlaybackGeneration && it.currentTrack?.catalogId == track.catalogId
        }?.let {
            services.publishSystemPlayback(it, queueState.canSkipNext, queueState.canSkipPrevious)
        }
    }

    val saveCurrentSession by rememberUpdatedState<(Boolean) -> Unit> saveSession@{ synchronous ->
        playbackPrefs.saveActiveTab(
            if (destination == Destination.NowPlaying) previousDestination else destination,
            synchronous,
        )
        if (!startupSessionLoaded && selectionGeneration == 0L) return@saveSession
        if (hasCurrentTrack) {
            val currentEntries = queueState.history + listOfNotNull(queueState.current) +
                queueState.upNext + queueState.autoplay
            val currentQueue = currentEntries.map { it.track }
            if (currentQueue.isNotEmpty()) {
                val currentIdx = queueState.history.size.coerceIn(0, currentQueue.lastIndex)
                playbackPrefs.saveSession(
                    PlaybackSession(
                        queue = currentQueue,
                        currentIndex = currentIdx,
                        positionMs = currentPositionMs().coerceIn(0L, track.durationMs.coerceAtLeast(1L)),
                        wasPlaying = playWhenReady,
                        destination = destination,
                        previousDestination = previousDestination,
                        radioActive = queueState.infinite,
                        radioSeed = queueState.recommendationSeed,
                        radioContinuation = queueState.ytmContinuation,
                        radioSeedVideoId = queueState.ytmSeedVideoId,
                        autoplayEntryIndices = currentEntries.withIndex()
                            .filter { it.value.origin == EntryOrigin.Autoplay }
                            .map { it.index }.toSet(),
                        autoplaySource = queueState.source,
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveCurrentSession(true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveCurrentSession(true)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(
        destination, previousDestination, track.id, playbackQueue, isPlaying, playWhenReady,
        queueState.repeat, queueState.shuffle, queueState.infinite, queueState.source,
    ) {
        delay(350L)
        saveCurrentSession(false)
    }
    LaunchedEffect(isPlaying, track.id) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            saveCurrentSession(false)
        }
    }
    LaunchedEffect(queueState.source) {
        shelvesByDestination = shelvesByDestination - Destination.Home
        reloadNonce++
    }

    // Auto-advance timer: detects track finish and smoothly advances
    LaunchedEffect(isPlaying, track.id, track.durationMs) {
        // Native playback ends only on its generation-bound SDK end event.
        if (track.catalogId != null || !isPlaying || track.durationMs <= 2_000L) return@LaunchedEffect
        while (isPlaying) {
            val currentMs = currentPositionMs()
            if (currentMs >= track.durationMs - 250L) {
                advanceNextTrack(isAuto = true)
                break
            }
            delay(250L)
        }
    }

    val lyricsRequestReady = hasUserPlaybackRequest && playbackPrepared &&
        connectionState == MusicConnectionState.SignedIn && expectedPlaybackGeneration != 0L

    val prefetchTargets = remember(queueState.current?.entryId, queueState.upNext, queueState.autoplay) {
        (queueState.upNext + queueState.autoplay).take(2).map { it.track }
    }
    LaunchedEffect(prefetchTargets, isPlaying, cacheSettings, lyricsRequestReady, track.lyrics.isEmpty(), lyricsLookupFailures) {
        if (!lyricsRequestReady || !isPlaying || !cacheSettings.enabled || !cacheSettings.prefetchNextTwo || cacheSettings.clearing) {
            return@LaunchedEffect
        }
        val cacheGeneration = services.contentCache.generation
        if (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { services.contentCache.canWrite() }) {
            return@LaunchedEffect
        }
        for (candidate in prefetchTargets) {
            if (candidate.catalogKind != CatalogKind.Song) continue
            candidate.artworkUrl?.let { ArtworkImages.prefetch(context, it) }
            if ((track.lyrics.isNotEmpty() || track.id in lyricsLookupFailures) &&
                candidate.catalogId != null && candidate.lyrics.isEmpty() &&
                candidate.id !in lyricsByTrackId && candidate.id !in lyricsLookupFailures
            ) {
                cancellableResult {
                    val lines = services.lyricsFor(candidate)
                    if (lines.isNotEmpty() && cacheGeneration == services.contentCache.generation) {
                        cacheLyrics(candidate.id, lines)
                    }
                }
            }
        }
    }
    LaunchedEffect(cacheSettings.revision) {
        // Preserve the displayed line; clearing assets must not interrupt the current song.
        lyricsByTrackId = if (track.lyrics.isEmpty()) emptyMap() else mapOf(track.id to track.lyrics)
        lyricsLookupFailures = emptySet()
        lyricsTransientFailureTrackId = null
    }

    LaunchedEffect(track.id, track.lyrics.isEmpty(), lyricsRequestReady, reloadNonce, lyricsRetryNonce, cacheSettings.revision) {
        if (!lyricsRequestReady || track.catalogId == null || track.catalogKind != CatalogKind.Song ||
            track.lyrics.isNotEmpty() || track.id in lyricsLookupFailures
        ) return@LaunchedEffect
        val lookupTrack = track
        lyricsLoadingTrackId = lookupTrack.id
        lyricsTransientFailureTrackId = null
        try {
            when (val result = requestLyricsWithRetry({ services.lyricsFor(lookupTrack) })) {
                is LyricsRequestResult.Available -> {
                    cacheLyrics(lookupTrack.id, result.lines)
                    lyricsLookupFailures = lyricsLookupFailures - lookupTrack.id
                    if (track.id == lookupTrack.id) track = track.copy(lyrics = result.lines)
                }
                LyricsRequestResult.Unavailable -> {
                    lyricsLookupFailures = lyricsLookupFailures + lookupTrack.id
                }
                is LyricsRequestResult.RetryableFailure -> {
                    // This is not a "no lyrics" cache entry. Readiness changes,
                    // reauthentication and manual refresh can retry the same song.
                    lyricsTransientFailureTrackId = lookupTrack.id
                    Log.w("AMATV-Lyrics", "Lyrics temporarily unavailable (${result.reason})")
                }
            }
        } finally {
            if (lyricsLoadingTrackId == lookupTrack.id) lyricsLoadingTrackId = null
        }
    }

    LaunchedEffect(destination, connectionState, reloadNonce, contentLanguageTag, queueState.source) {
        if (shelvesLanguageTag != contentLanguageTag) {
            // Section titles are presentation metadata. Invalidate only this
            // cache, retaining the selected song, queue, focus and account.
            shelvesByDestination = if (services.isCatalogConfigured) emptyMap() else demoShelves()
            loadingDestinations = emptySet()
            shelvesLanguageTag = contentLanguageTag
        }
        if (!services.isCatalogConfigured) return@LaunchedEffect
        val target = destination
        if (
            target != Destination.Home &&
            target != Destination.Browse &&
            target != Destination.Library
        ) {
            return@LaunchedEffect
        }
        if (shelvesByDestination[target]?.isNotEmpty() == true) return@LaunchedEffect
        if (target == Destination.Library && connectionState != MusicConnectionState.SignedIn) {
            loadingDestinations = loadingDestinations - target
            return@LaunchedEffect
        }

        loadingDestinations = loadingDestinations + target
        val errors = mutableListOf<Throwable>()
        val loadedShelves = coroutineScope {
            when (target) {
                Destination.Home -> {
                    val recommendations = async {
                        if (connectionState == MusicConnectionState.SignedIn) {
                            cancellableResult { services.recommendationShelves() }
                        } else Result.success(emptyList())
                    }
                    val recentPlayedTracks = async {
                        if (connectionState == MusicConnectionState.SignedIn) {
                            cancellableResult { services.recentlyPlayedTracks() }
                        } else Result.success(emptyList())
                    }
                    val recentContainers = async {
                        if (connectionState == MusicConnectionState.SignedIn) {
                            cancellableResult { services.recentlyPlayed() }
                        } else Result.success(emptyList())
                    }
                    val songs = async { cancellableResult { services.charts("songs") } }
                    val albums = async { cancellableResult { services.charts("albums") } }
                    val playlists = async { cancellableResult { services.charts("playlists") } }
                    val ytmShelves = async {
                        if (queueState.source == AutoplaySource.YouTubeMusic) {
                            cancellableResult { services.ytmHomeShelves() }
                        } else Result.success(emptyList())
                    }

                    buildList {
                        recommendations.await().fold(
                            onSuccess = { addAll(it) },
                            onFailure = errors::add,
                        )
                        ytmShelves.await().fold(
                            onSuccess = { addAll(it) },
                            onFailure = { Log.w("AMATV-YTM", "Home recommendations unavailable", it) },
                        )
                        recentPlayedTracks.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    recentTracks = items
                                    add(
                                        ContentShelf(
                                            id = "recent-tracks",
                                            title = AppText.text(R.string.app_recent_tracks),
                                            items = items,
                                            featured = isEmpty(),
                                        ),
                                    )
                                }
                            },
                            onFailure = errors::add,
                        )
                        recentContainers.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("recent", AppText.text(R.string.app_recent_albums_and_playlists), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        songs.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(
                                        ContentShelf(
                                            id = "top-songs",
                                            title = AppText.text(R.string.app_top_songs),
                                            items = items,
                                            featured = isEmpty(),
                                        ),
                                    )
                                }
                            },
                            onFailure = errors::add,
                        )
                        albums.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("top-albums", AppText.text(R.string.app_top_albums), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        playlists.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("top-playlists", AppText.text(R.string.app_top_playlists), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                    }
                }

                Destination.Browse -> {
                    val songs = async { cancellableResult { services.charts("songs") } }
                    val albums = async { cancellableResult { services.charts("albums") } }
                    val playlists = async { cancellableResult { services.charts("playlists") } }
                    val videos = async { cancellableResult { services.charts("music-videos") } }
                    val radio = async { cancellableResult { services.liveRadio() } }

                    buildList {
                        songs.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(
                                        ContentShelf(
                                            id = "browse-songs",
                                            title = AppText.text(R.string.app_new_top_tracks),
                                            items = items,
                                            featured = true,
                                        ),
                                    )
                                }
                            },
                            onFailure = errors::add,
                        )
                        albums.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("browse-albums", AppText.text(R.string.app_top_albums), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        playlists.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("browse-playlists", AppText.text(R.string.app_featured_playlists), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        videos.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("browse-videos", AppText.text(R.string.app_top_music_videos), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        radio.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("browse-radio", AppText.text(R.string.app_apple_music_radio), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                    }
                }

                Destination.Library -> {
                    val recentlyAdded = async { cancellableResult { services.recentlyAdded() } }
                    val playlists = async { cancellableResult { services.libraryPlaylists() } }
                    val songs = async { cancellableResult { services.librarySongs() } }
                    val recentPlayed = async { cancellableResult { services.recentlyPlayedTracks() } }

                    buildList {
                        recentlyAdded.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(
                                        ContentShelf(
                                            id = "lib-recently-added",
                                            title = AppText.text(R.string.app_recently_added),
                                            items = items,
                                            featured = true,
                                        ),
                                    )
                                }
                            },
                            onFailure = errors::add,
                        )
                        playlists.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(
                                        ContentShelf(
                                            id = "lib-playlists",
                                            title = AppText.text(R.string.app_library_playlists),
                                            items = items,
                                            featured = isEmpty(),
                                        ),
                                    )
                                }
                            },
                            onFailure = errors::add,
                        )
                        songs.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    add(ContentShelf("lib-songs", AppText.text(R.string.app_library_songs), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                        recentPlayed.await().fold(
                            onSuccess = { items ->
                                if (items.isNotEmpty()) {
                                    recentTracks = items
                                    add(ContentShelf("lib-recent", AppText.text(R.string.app_recently_played), items))
                                }
                            },
                            onFailure = errors::add,
                        )
                    }
                }

                Destination.NowPlaying,
                Destination.Sing,
                Destination.Videos,
                Destination.Radio,
                Destination.Account,
                Destination.Search,
                -> emptyList()
            }
        }
        shelvesByDestination = shelvesByDestination + (target to loadedShelves)
        loadingDestinations = loadingDestinations - target
        serviceMessage = if (loadedShelves.isEmpty()) {
            errors.firstOrNull()?.message
        } else {
            null
        }
    }

    LaunchedEffect(destination) {
        if (destination == Destination.Search && searchResults.isEmpty() && services.isCatalogConfigured) {
            searchLoading = true
            runCatching { services.charts("songs") }
                .onSuccess { searchResults = it }
                .onFailure { serviceMessage = it.message }
            searchLoading = false
        }
    }

    val isNowPlaying = destination == Destination.NowPlaying
    LaunchedEffect(isNowPlaying, isPlaying, spectrumPermissionGranted) {
        if (isNowPlaying && isPlaying && !spectrumPermissionGranted && !spectrumPermissionAsked) {
            spectrumPermissionAsked = true
            spectrumPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(lifecycleOwner, isNowPlaying, isPlaying, spectrumPermissionGranted) {
        fun updateCapture() {
            services.setSpectrumActive(isNowPlaying && isPlaying && spectrumPermissionGranted &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            services.setSpectrumActive(false)
        }
    }
    val activeBrowseDestination = if (isNowPlaying) previousDestination else destination
    val nowPlayingAlpha = animateFloatAsState(
        targetValue = if (isNowPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "now-playing-screen-transition",
    )

    val playerTransitionVisible by remember(nowPlayingAlpha) {
        derivedStateOf { nowPlayingAlpha.value > 0.001f }
    }
    val browseVisibility = remember(nowPlayingAlpha) {
        derivedStateOf { (1f - nowPlayingAlpha.value * 1.15f).coerceIn(0f, 1f) }
    }

    BackHandler(enabled = isNowPlaying) {
        destination = previousDestination
    }

    val focusFrameController = remember { FocusFrameController() }
    AmAtvTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            togglePlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            requestPlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            pausePlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            skip(1)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            skip(-1)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            positionAnchorMs = (currentPositionMs() - 10_000L).coerceAtLeast(0L)
                            playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                            if (track.catalogId != null) services.seekTo(positionAnchorMs)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            positionAnchorMs = (currentPositionMs() + 10_000L)
                                .coerceAtMost(track.durationMs)
                            playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                            if (track.catalogId != null) services.seekTo(positionAnchorMs)
                            true
                        }
                        else -> false
                    }
                },
            color = Color(0xFF080A09),
            contentColor = Color.White,
        ) {
            CompositionLocalProvider(LocalFocusFrameController provides focusFrameController) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = !isNowPlaying }
                        .graphicsLayer {
                            val browseAlpha = (1f - nowPlayingAlpha.value * 1.15f).coerceIn(0f, 1f)
                            alpha = browseAlpha
                            val scale = 1f - 0.03f * nowPlayingAlpha.value
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    CompositionLocalProvider(
                        LocalFocusFrameEnabled provides !isNowPlaying,
                        LocalFocusTargetsEnabled provides !isNowPlaying,
                        LocalFocusFrameVisibility provides browseVisibility,
                    ) {
                    BrowseShell(
                        destination = activeBrowseDestination,
                        isBrowseVisible = !isNowPlaying,
                        currentTrack = track,
                        hasCurrentTrack = hasCurrentTrack,
                        isPlaying = isPlaying,
                        searchTracks = searchResults,
                        searchLoading = searchLoading,
                        recentTracks = recentTracks,
                        shelves = shelvesByDestination[activeBrowseDestination].orEmpty(),
                        loading = activeBrowseDestination in loadingDestinations,
                        connectionState = connectionState,
                        setupMessage = serviceMessage ?: services.setupMessage,
                        onDestination = { next ->
                            if (next == Destination.NowPlaying) {
                                showNowPlaying()
                            } else {
                                previousDestination = next
                                destination = next
                            }
                        },
                        onSignIn = (::startSignIn),
                        ytmSignedIn = ytmSignedIn,
                        ytmPairing = ytmPairing,
                        ytmPairingConfigured = services.ytmPairingConfigured,
                        onYtmSignIn = (::startYtmPairing),
                        onYtmSignOut = {
                            services.signOutYtm()
                            ytmSignedIn = false
                            ytmPairing = null
                            shelvesByDestination = shelvesByDestination - Destination.Home
                            reloadNonce++
                        },
                        onSignOut = {
                            selectionGeneration++
                            playbackRequestJob?.cancel()
                            services.signOut()
                            scope.launch { cancellableResult { services.contentCache.clear { ArtworkImages.clear(context) } } }
                            isPlaying = false
                            playWhenReady = false
                            waitingQueueEntryId = null
                            playbackPrepared = false
                            hasUserPlaybackRequest = false
                            lyricsLookupFailures = emptySet()
                            lyricsTransientFailureTrackId = null
                            expectedPlaybackGeneration = 0L
                            pendingRestore = null
                            connectionState = services.connectionState
                            shelvesByDestination = emptyMap()
                            recentTracks = emptyList()
                            serviceMessage = AppText.text(R.string.app_apple_signed_out)
                            reloadNonce++
                        },
                        onRetry = (::refreshAllContent),
                        onSearch = { query ->
                            searchJob?.cancel()
                            searchJob = scope.launch {
                                searchLoading = true
                                try {
                                    cancellableResult { services.search(query) }
                                        .onSuccess { items ->
                                            searchResults = items
                                            serviceMessage = if (items.isEmpty()) {
                                                AppText.text(R.string.app_search_no_results_format, query)
                                            } else null
                                        }
                                        .onFailure { serviceMessage = it.message ?: AppText.text(R.string.app_search_failed) }
                                } finally {
                                    searchLoading = false
                                }
                            }
                        },
                        onOpenTrack = { item, q -> playTrack(item, contextQueue = q) },
                        loadCollection = services::collectionTracks,
                        onPlayCollection = ::playCollection,
                        lyricOffsetMs = lyricOffsetMs,
                        onLyricOffsetChange = { offset: Long ->
                            lyricOffsetMs = offset
                            playbackPrefs.lyricOffsetMs = offset
                        },
                        autoplaySource = queueState.source,
                        onAutoplaySourceChange = { dispatchQueueAction(StartupQueueAction.SetSource(it)) },
                        cacheSettingsContent = {
                            LanguageSettings()
                            ContentCacheSettings(services.contentCache)
                        },
                    )
                }
                }
                if (hasCurrentTrack || isNowPlaying || playerTransitionVisible) {
                    val isPlayerVisible = isNowPlaying || playerTransitionVisible
                    CompositionLocalProvider(
                        LocalFocusFrameEnabled provides isNowPlaying,
                        LocalFocusTargetsEnabled provides isNowPlaying,
                        LocalFocusFrameVisibility provides nowPlayingAlpha,
                    ) {
                    PlayerScreen(
                        track = track,
                        positionAnchorMs = positionAnchorMs,
                        playStartedRealtimeMs = playStartedRealtimeMs,
                        isPlaying = isPlaying,
                        playWhenReady = playWhenReady,
                        audioSpectrum = services::audioSpectrum,
                        isActiveScreen = isNowPlaying,
                        isVisibleOnScreen = isPlayerVisible,
                        queue = timelineTracks,
                        queueEntryIds = timelineEntryIds,
                        queueCurrentIndex = queueState.currentTimelineIndex,
                        queueSessionId = queueState.sessionId,
                        canClearQueue = queueState.upNext.isNotEmpty() || queueState.autoplay.isNotEmpty(),
                        canSkipPrevious = queueState.canSkipPrevious,
                        canSkipNext = queueState.canSkipNext,
                        repeatMode = queueState.repeat,
                        shuffleEnabled = queueState.shuffle,
                        autoplayEnabled = queueState.infinite,
                        canSetRepeat = true,
                        canSetShuffle = true,
                        canSetAutoplay = track.catalogId != null &&
                            track.catalogKind == dev.amatv.model.CatalogKind.Song,
                        autoplaySource = queueState.source,
                        onToggleAutoplaySource = { dispatchQueueAction(StartupQueueAction.ToggleSource) },
                        onClearQueue = { dispatchQueueAction(StartupQueueAction.ClearUpcoming) },
                        lyricsLoading = lyricsLoadingTrackId == track.id,
                        lyricsLookupFailed = track.id in lyricsLookupFailures,
                        lyricsTransientFailure = lyricsTransientFailureTrackId == track.id,
                        onRetryLyrics = ::retryCurrentLyrics,
                        lyricOffsetMs = lyricOffsetMs,
                        statusMessage = playbackMessage ?: if (queueState.awaitingNext) {
                            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message
                        } else null,
                        onTogglePlayback = (::togglePlayback),
                        onSkipPrevious = { skip(-1) },
                        onSkipNext = { skip(1) },
                        onSeekBy = ::seekPlaybackBy,
                        onSelectTrack = (::selectQueueTrack),
                        onCycleRepeat = { dispatchQueueAction(StartupQueueAction.CycleRepeat) },
                        onToggleShuffle = { dispatchQueueAction(StartupQueueAction.ToggleShuffle) },
                        onToggleAutoplay = { dispatchQueueAction(StartupQueueAction.ToggleInfinite) },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = isNowPlaying }
                            .graphicsLayer {
                                alpha = nowPlayingAlpha.value
                                val scale = 0.97f + 0.03f * nowPlayingAlpha.value
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                    }
                }
                FocusFrameOverlay(focusFrameController)
            }
            }
        }
    }
}

@Composable
private fun BrowseShell(
    destination: Destination,
    isBrowseVisible: Boolean,
    currentTrack: Track,
    hasCurrentTrack: Boolean,
    isPlaying: Boolean,
    searchTracks: List<Track>,
    searchLoading: Boolean,
    recentTracks: List<Track>,
    shelves: List<ContentShelf>,
    loading: Boolean,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onDestination: (Destination) -> Unit,
    onSignIn: () -> Unit,
    ytmSignedIn: Boolean,
    ytmPairing: GoogleTvPairing?,
    ytmPairingConfigured: Boolean,
    onYtmSignIn: () -> Unit,
    onYtmSignOut: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    loadCollection: suspend (Track) -> List<Track>,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: AutoplaySource = AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (AutoplaySource) -> Unit = {},
    cacheSettingsContent: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val navigationDestinations = remember {
        listOf(
            Destination.Home,
            Destination.Browse,
            Destination.Library,
            Destination.Search,
            Destination.Account,
            Destination.NowPlaying,
        )
    }
    val navigationFocus = remember {
        navigationDestinations.associateWith { FocusRequester() }
    }
    val scope = rememberCoroutineScope()
    val currentLoader by rememberUpdatedState(loadCollection)
    val browser = remember(destination) { CollectionBrowser(scope) { currentLoader(it) } }
    val collectionState by browser.state.collectAsState()
    val collection = collectionState.collection
    var station by remember(destination) { mutableStateOf<Track?>(null) }
    var returnToContent by remember(destination) { mutableIntStateOf(0) }
    val detailFocus = remember(destination) { ContentFocusEntry() }
    val detailFirstFocus = remember(destination) { FocusRequester() }
    DisposableEffect(browser) { onDispose { browser.close() } }
    val firstContentFocus = remember(destination) { FocusRequester() }
    val contentFocusEntry = remember(destination) { ContentFocusEntry() }
    fun closeCollection() {
        browser.close()
        if (destination == Destination.Home) {
            returnToContent++
        } else {
            onDestination(Destination.Home)
        }
    }
    BackHandler(enabled = isBrowseVisible && collection != null) { closeCollection() }
    LaunchedEffect(destination, isBrowseVisible, collection?.id, collectionState.loading, collectionState.error, returnToContent) {
        if (!isBrowseVisible) return@LaunchedEffect
        withFrameNanos { }
        when {
            collection != null -> detailFocus.requestFocus(detailFirstFocus)
            returnToContent > 0 -> contentFocusEntry.requestFocus(firstContentFocus)
            else -> runCatching { navigationFocus[destination]?.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF19241E),
                        Color(0xFF0B1411),
                    ),
                ),
            ),
    ) {
        CompositionLocalProvider(
            LocalFocusFrameEnabled provides (collection == null && isBrowseVisible),
            LocalFocusTargetsEnabled provides (collection == null && isBrowseVisible),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (collection != null) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = if (collection == null) 1f else 0f },
        ) {
            TopNavigation(
                selected = destination,
                hasCurrentTrack = hasCurrentTrack,
                connectionState = connectionState,
                onDestination = onDestination,
                focusRequesters = navigationFocus,
                downFocusRequester = firstContentFocus,
                contentFocusEntry = contentFocusEntry,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(start = 40.dp, end = 40.dp, top = 20.dp),
            ) {
                key(destination) {
                    val entrance = remember { Animatable(0f) }
                    val travelPx = with(density) { 10.dp.toPx() }
                    LaunchedEffect(Unit) {
                        entrance.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 190,
                                easing = MotionTokens.KaraokeLineEasing,
                            ),
                        )
                    }
                    CompositionLocalProvider(LocalContentFocusEntry provides contentFocusEntry) {
                        HomeScreen(
                            destination = destination,
                            currentTrack = currentTrack,
                            tracks = searchTracks,
                            searchLoading = searchLoading,
                            recentTracks = recentTracks,
                            homeShelves = shelves,
                            homeLoading = loading,
                            connectionState = connectionState,
                            setupMessage = setupMessage,
                            onSignIn = onSignIn,
                            ytmSignedIn = ytmSignedIn,
                            ytmPairing = ytmPairing,
                            ytmPairingConfigured = ytmPairingConfigured,
                            onYtmSignIn = onYtmSignIn,
                            onYtmSignOut = onYtmSignOut,
                            onSignOut = onSignOut,
                            onRetry = onRetry,
                            onSearch = onSearch,
                            onOpenTrack = { item, queue ->
                                when (item.openAction) {
                                    CatalogOpenAction.Collection -> browser.open(item)
                                    CatalogOpenAction.Station -> station = item
                                    CatalogOpenAction.Play -> onOpenTrack(item, queue.filter {
                                        it.openAction == CatalogOpenAction.Play
                                    })
                                }
                            },
                            lyricOffsetMs = lyricOffsetMs,
                            onLyricOffsetChange = onLyricOffsetChange,
                            autoplaySource = autoplaySource,
                            onAutoplaySourceChange = onAutoplaySourceChange,
                            cacheSettingsContent = cacheSettingsContent,
                            firstContentFocusRequester = firstContentFocus,
                            navigationFocusRequester = navigationFocus[destination],
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = entrance.value
                                    translationY = (1f - entrance.value) * travelPx
                                },
                        )
                    }
                }
            }
        }
        }
        if (collection != null) {
            Box(
                Modifier.fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (isBrowseVisible && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                            // Consume the whole press before a focused child can clear focus.
                            // Close on key-up so that this press cannot reach the Home page.
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) closeCollection()
                            true
                        } else false
                    }
                    .padding(horizontal = 40.dp, vertical = 28.dp),
            ) {
                CompositionLocalProvider(LocalContentFocusEntry provides detailFocus) {
                    key(collection.resourceType, collection.resourceId, collection.id) {
                        CollectionScreen(
                            state = collectionState,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            firstFocus = detailFirstFocus,
                            onRetry = { browser.open(collection) },
                            onPlay = { index, shuffle ->
                                onPlayCollection(collection, collectionState.tracks, index, shuffle)
                            },
                        )
                    }
                }
            }
        }
        station?.let { item ->
            StationUnavailableDialog(item, onDismiss = { station = null })
        }
    }
}

@Composable
private fun TopNavigation(
    selected: Destination,
    hasCurrentTrack: Boolean,
    connectionState: MusicConnectionState,
    onDestination: (Destination) -> Unit,
    focusRequesters: Map<Destination, FocusRequester>,
    downFocusRequester: FocusRequester,
    contentFocusEntry: ContentFocusEntry,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val primaryDestinations = remember {
        listOf(
            Destination.Home,
            Destination.Browse,
            Destination.Library,
            Destination.Search,
        )
    }
    val utilityDestinations = remember(hasCurrentTrack) {
        buildList {
            if (hasCurrentTrack) add(Destination.NowPlaying)
            add(Destination.Account)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left browsing pill: Home, Browse, Library, Search
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.055f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            primaryDestinations.forEach { destination ->
                val isSelected = selected == destination
                val requester = checkNotNull(focusRequesters[destination])
                FocusSurface(
                    onClick = { onDestination(destination) },
                    modifier = Modifier
                        .automationId("nav-${destination.name.lowercase()}")
                        .focusRequester(requester)
                        .focusProperties {
                            down = contentFocusEntry.requester ?: downFocusRequester
                            if (destination == Destination.Search) {
                                right = if (hasCurrentTrack) {
                                    focusRequesters.getValue(Destination.NowPlaying)
                                } else {
                                    focusRequesters.getValue(Destination.Account)
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            ) {
                                val moved = contentFocusEntry.requestFocus(downFocusRequester)
                                if (!moved) {
                                    scope.launch {
                                        withFrameNanos { }
                                        contentFocusEntry.requestFocus(downFocusRequester)
                                    }
                                }
                                true
                            } else false
                        }
                        .height(36.dp),
                    cornerRadius = 22.dp,
                    focusedScale = 1.02f,
                    focusedLift = 0.dp,
                    focusedElevation = 0.dp,
                    showBorder = false,
                    padding = PaddingValues(
                        horizontal = if (destination == Destination.Search) 12.dp else 16.dp,
                    ),
                    background = if (isSelected) Color.White.copy(alpha = 0.9f)
                    else Color.Transparent,
                    focusedBackground = if (isSelected) Color.White.copy(alpha = 0.96f)
                    else Color.White.copy(alpha = 0.13f),
                ) { focused ->
                    Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                        val tint = if (isSelected) Color(0xFF17191A)
                        else if (focused) Color.White else Color.White.copy(alpha = 0.65f)
                        when (destination) {
                            Destination.Search -> {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = destinationLabel(destination),
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Destination.Home -> {
                                Text(
                                    text = destinationLabel(destination),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tint,
                                )
                            }
                            Destination.Browse -> {
                                Text(
                                    text = destinationLabel(destination),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tint,
                                )
                            }
                            Destination.Library -> {
                                Text(
                                    text = destinationLabel(destination),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tint,
                                )
                            }
                            else -> {
                                Text(
                                    text = destinationLabel(destination),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tint,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Right utility pill: Now Playing (if loaded) & Account
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.055f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            utilityDestinations.forEach { destination ->
                val isSelected = selected == destination
                val requester = checkNotNull(focusRequesters[destination])
                FocusSurface(
                    onClick = { onDestination(destination) },
                    modifier = Modifier
                        .automationId("nav-${destination.name.lowercase()}")
                        .focusRequester(requester)
                        .focusProperties {
                            down = contentFocusEntry.requester ?: downFocusRequester
                            if (destination == Destination.NowPlaying) {
                                left = focusRequesters.getValue(Destination.Search)
                                right = focusRequesters.getValue(Destination.Account)
                            } else if (destination == Destination.Account) {
                                left = if (hasCurrentTrack) {
                                    focusRequesters.getValue(Destination.NowPlaying)
                                } else {
                                    focusRequesters.getValue(Destination.Search)
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            ) {
                                val moved = contentFocusEntry.requestFocus(downFocusRequester)
                                if (!moved) {
                                    scope.launch {
                                        withFrameNanos { }
                                        contentFocusEntry.requestFocus(downFocusRequester)
                                    }
                                }
                                true
                            } else false
                        }
                        .height(36.dp),
                    cornerRadius = 22.dp,
                    focusedScale = 1.02f,
                    focusedLift = 0.dp,
                    focusedElevation = 0.dp,
                    showBorder = false,
                    padding = PaddingValues(horizontal = 14.dp),
                    background = if (isSelected) Color.White.copy(alpha = 0.9f)
                    else Color.Transparent,
                    focusedBackground = if (isSelected) Color.White.copy(alpha = 0.96f)
                    else Color.White.copy(alpha = 0.13f),
                ) { focused ->
                    Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                        val tint = if (isSelected) Color(0xFF17191A)
                        else if (focused) Color.White else Color.White.copy(alpha = 0.65f)
                        when (destination) {
                            Destination.NowPlaying -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = destinationLabel(destination),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tint,
                                    )
                                }
                            }
                            Destination.Account -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val signedIn = connectionState == MusicConnectionState.SignedIn
                                    Text(
                                        text = stringResource(if (signedIn) R.string.app_account else R.string.app_sign_in),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tint,
                                    )
                                }
                            }
                            else -> {
                                Text(
                                    text = destinationLabel(destination),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
