package dev.amatv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.BuildConfig
import dev.amatv.integration.toSessionTrack
import dev.amatv.integration.toSessionJson
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.voice.VoiceService
import dev.amatv.integration.voice.ListenControl
import dev.amatv.integration.voice.bestTarget
import dev.amatv.integration.voice.scrollTarget
import dev.amatv.integration.parseVoiceSteps
import dev.amatv.integration.VoiceCommand
import dev.amatv.integration.voice.VoiceEvent
import dev.amatv.integration.voice.VoiceFailure
import dev.amatv.integration.parseVoiceCommand
import dev.amatv.integration.ChineseText
import dev.amatv.integration.songsMatch
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.integration.AuthLaunchGate
import dev.amatv.integration.retryCatalogRequest
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.GoogleTvPairing
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.LyricsLocalizationRequest
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
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.playback.ContextKind
import dev.amatv.playback.EntryOrigin
import dev.amatv.playback.PlaybackContext
import dev.amatv.playback.PlaybackQueueController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Words that make a spoken name sound like one of the user's lists rather than a song. */
private val LIBRARY_LIST_HINT = Regex("喜好|收藏|喜欢|歌单|播放列表|列表|资料库")

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

internal enum class LyricsLoadPhase { Idle, Loading, Ready, Unavailable, Failed }

internal data class ActiveLyricsState(
    val trackIdentity: String,
    val lines: List<LyricLine>,
    val resolvedVariant: String? = null,
    val phase: LyricsLoadPhase = LyricsLoadPhase.Idle,
)

/** Catalog identity survives MusicKit's different browse and now-playing wrapper ids. */
internal fun Track.lyricsIdentity(): String = catalogId?.takeIf(String::isNotBlank) ?: id

internal fun lyricsCacheKey(trackIdentity: String, variant: String): String = "$trackIdentity|$variant"

/** Visible baseline text is not proof that a translation/pronunciation variant finished. */
internal fun ActiveLyricsState.resolvedCacheEntries(): Map<String, List<LyricLine>> =
    resolvedVariant?.takeIf { phase == LyricsLoadPhase.Ready && lines.isNotEmpty() }
        ?.let { mapOf(lyricsCacheKey(trackIdentity, it) to lines) }.orEmpty()

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
    val lyricsLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val scope = rememberCoroutineScope()
    val shelfLoads = remember(scope) { ShelfLoads(scope) }
    val services = remember(context) { MusicServices(context) }
    val cacheSettings by services.contentCache.settings.collectAsState()
    val playbackPrefs = remember(context) { PlaybackPreferences(context) }
    var lyricsPreferred by remember(playbackPrefs) { mutableStateOf(playbackPrefs.lyricsPreferred) }
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

    val currentView = androidx.compose.ui.platform.LocalView.current
    val shouldKeepScreenOn = !BuildConfig.FLYME_AUTO && (isPlaying || playWhenReady)
    DisposableEffect(shouldKeepScreenOn) {
        currentView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            currentView.keepScreenOn = false
        }
    }
    var playbackPrepared by remember { mutableStateOf(false) }
    var hasUserPlaybackRequest by remember { mutableStateOf(false) }
    var lowPerformanceMode by remember { mutableStateOf(playbackPrefs.lowPerformanceMode) }
    var playbackMessage by remember { mutableStateOf<String?>(null) }
    var waitingQueueEntryId by remember { mutableStateOf<Long?>(null) }
    var expectedPlaybackGeneration by remember { mutableLongStateOf(0L) }
    var lastPlaybackFailure by remember { mutableStateOf<PlaybackFailureKind?>(null) }
    var commandFailure by remember { mutableStateOf<Triple<Long, PlaybackFailureKind, String>?>(null) }
    var failureHandledSelection by remember { mutableLongStateOf(-1L) }
    var successfulSelection by remember { mutableLongStateOf(-1L) }
    var networkRetryJob by remember { mutableStateOf<Job?>(null) }
    var networkRetryCount by remember(track.id) { mutableIntStateOf(0) }
    var connectionState by remember { mutableStateOf(services.connectionState) }
    var serviceMessage by remember { mutableStateOf<String?>(null) }
    var lastPlaybackNotice by remember { mutableStateOf<String?>(null) }
    var stationHistory by remember { mutableStateOf(loadStationHistory(context)) }
    // Favorite Songs (Apple's "love" rating) for the songs seen on Now Playing.
    var favoriteState by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var favoriteCatalogId by remember { mutableStateOf<String?>(null) }
    // A short Now Playing notice (favorites); playback snapshots clear playbackMessage too often.
    var playerNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playerNotice) {
        if (playerNotice != null) {
            delay(3_000L)
            playerNotice = null
        }
    }
    // Gapless: the queue entry preloaded into MusicKit, and the entry being adopted without a prepare.
    var gaplessLookahead by remember { mutableStateOf<Pair<Long, Track>?>(null) }
    var adoptingGaplessEntryId by remember { mutableStateOf<Long?>(null) }
    // An album or artist to open in the browse shell, requested from the player.
    var browseDetailRequest by remember { mutableStateOf<Track?>(null) }
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
    // Destinations showing shelves saved by an earlier launch, still to be refreshed.
    var savedShelfDestinations by remember { mutableStateOf(emptySet<Destination>()) }
    var loadingDestinations by remember {
        mutableStateOf(
            if (services.isCatalogConfigured) setOf(Destination.Home) else emptySet(),
        )
    }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var libraryNextPages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var libraryVisitedPages by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var libraryLoadingShelves by remember { mutableStateOf<Set<String>>(emptySet()) }
    var libraryFailedShelves by remember { mutableStateOf<Set<String>>(emptySet()) }
    var libraryPagingGeneration by remember { mutableLongStateOf(0L) }
    val libraryPageJobs = remember { mutableMapOf<String, Job>() }
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
    var repeatMode by remember { mutableStateOf(playbackPrefs.repeatMode) }
    var shuffleEnabled by remember { mutableStateOf(playbackPrefs.shuffleEnabled) }
    var autoplayEnabled by remember { mutableStateOf(playbackPrefs.autoplayEnabled) }
    var lyricOffsetMs by remember { mutableLongStateOf(playbackPrefs.lyricOffsetMs) }
    var lyricTextSize by remember { mutableStateOf(playbackPrefs.lyricTextSize) }
    var lyricTranslationEnabled by remember {
        mutableStateOf(playbackPrefs.lyricTranslationEnabled)
    }
    var lyricPronunciationEnabled by remember {
        mutableStateOf(playbackPrefs.lyricPronunciationEnabled)
    }
    var lyricTranslationLanguageTag by remember {
        mutableStateOf(playbackPrefs.lyricTranslationLanguageTag)
    }
    var lyricPronunciationLanguageTag by remember {
        mutableStateOf(playbackPrefs.lyricPronunciationLanguageTag)
    }
    val effectiveLyricsLanguageTag = lyricTranslationLanguageTag.ifBlank { lyricsLanguageTag }
    val lyricsLocalization = remember(
        effectiveLyricsLanguageTag, lyricPronunciationLanguageTag,
        lyricTranslationEnabled, lyricPronunciationEnabled,
    ) {
        LyricsLocalizationRequest(
            languageTag = effectiveLyricsLanguageTag,
            pronunciationLanguageTag = lyricPronunciationLanguageTag,
            includeTranslations = lyricTranslationEnabled,
            includePronunciations = lyricPronunciationEnabled,
        )
    }
    val lyricsVariant = remember(lyricsLocalization) {
        "${lyricsLocalization.languageTag}|trans=${lyricsLocalization.includeTranslations}" +
            "|pron=${lyricsLocalization.includePronunciations}" +
            "|pronLang=${lyricsLocalization.pronunciationLanguageTag}"
    }
    var pendingRestore by remember {
        mutableStateOf(startupCheckpoint?.takeIf { track.catalogId != null })
    }
    var lyricsByTrackId by remember {
        mutableStateOf<Map<String, List<LyricLine>>>(emptyMap())
    }
    var lyricsLookupFailures by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeLyrics by remember {
        mutableStateOf(
            ActiveLyricsState(
                trackIdentity = track.lyricsIdentity(),
                lines = track.lyrics,
                resolvedVariant = lyricsVariant.takeUnless { lyricsLocalization.enabled },
                phase = if (track.lyrics.isEmpty()) LyricsLoadPhase.Idle else LyricsLoadPhase.Ready,
            ),
        )
    }
    var lyricsRetryNonce by remember { mutableIntStateOf(0) }
    val authLaunchGate = remember { AuthLaunchGate() }
    var playbackRequestJob by remember { mutableStateOf<Job?>(null) }
    var selectionGeneration by remember { mutableLongStateOf(0L) }
    var spectrumPermissionAsked by remember { mutableStateOf(false) }
    var spectrumPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val spectrumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        spectrumPermissionGranted = it
    }
    // Voice: a chosen recognizer (downloaded model or cloud API), then commands (see VoiceCommand)
    // and anything named on screen (VoiceScreen).
    val voice = remember { VoiceService.get(context.applicationContext) }
    val voiceScreen = remember(currentView) { VoiceScreen(currentView) }
    var voiceState by remember { mutableStateOf<VoiceUiState?>(null) }
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var voiceStartRequests by remember { mutableIntStateOf(0) }
    /** The walkie-talkie listen in progress (released -> finish), and which session is current. */
    var voiceHold by remember { mutableStateOf<ListenControl?>(null) }
    var voiceGeneration by remember { mutableIntStateOf(0) }
    val voiceEnabled = services.playbackOptions.settings.collectAsState().value.voiceEnabled
    /** The list on screen that "第三个" counts in; the browse pages report it. */
    var voicePickable by remember { mutableStateOf(emptyList<Track>()) }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceStartRequests++ else voiceState = VoiceUiState.Message(AppText.text(R.string.voice_permission_needed))
    }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }
    val voiceEngineId = voice.settings.collectAsState().value.engine
    LaunchedEffect(voice, voiceEnabled, voiceEngineId) {
        // Load the chosen model before the first tap, so the microphone opens at once.
        if (BuildConfig.FLYME_AUTO && voiceEnabled) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { voice.preload() }
        }
    }
    var pendingRequestedSongCatalogId by remember { mutableStateOf<String?>(null) }
    // Read only by callbacks/effects: native position polls do not recompose the app.
    val systemPlaybackSnapshot = remember { mutableStateOf<PlaybackSnapshot?>(null) }
    lateinit var queueController: PlaybackQueueController

    fun stationQueueTrack(): Track? = queueController.state.value.current?.track
        ?.takeIf { it.catalogKind == CatalogKind.Station }

    fun isStationPlayback(): Boolean = stationQueueTrack() != null

    fun requestedPlaybackTrack(): Track = stationQueueTrack() ?: track

    fun requestedPlaybackPositionMs(): Long = if (isStationPlayback()) 0L else positionAnchorMs

    fun currentPlaybackSnapshot(): PlaybackSnapshot? = systemPlaybackSnapshot.value
        ?.takeIf { it.requestGeneration != 0L && it.requestGeneration == expectedPlaybackGeneration }

    fun withCachedLyrics(candidate: Track): Track {
        val identity = candidate.lyricsIdentity()
        val cached = lyricsByTrackId[lyricsCacheKey(identity, lyricsVariant)].orEmpty()
        val active = activeLyrics.lines.takeIf {
            activeLyrics.trackIdentity == identity && it.isNotEmpty()
        }.orEmpty()
        val lines = cached.ifEmpty { active }.ifEmpty { candidate.lyrics }
        return if (lines == candidate.lyrics) candidate else candidate.copy(lyrics = lines)
    }

    fun cacheLyrics(identity: String, variant: String, lines: List<LyricLine>) {
        // The persistent TTML store owns history; don't grow TV heap usage all night.
        val key = lyricsCacheKey(identity, variant)
        val recent = (lyricsByTrackId - key) + (key to lines)
        lyricsByTrackId = recent.entries.toList().takeLast(24).associate { it.toPair() }
    }

    fun acceptResolvedTrack(original: Track, resolved: Track): Track {
        if (original.catalogId == resolved.catalogId) return resolved
        val oldIdentity = original.lyricsIdentity()
        val newIdentity = resolved.lyricsIdentity()
        val currentLines = activeLyrics.lines.takeIf { activeLyrics.trackIdentity == oldIdentity }
            ?: original.lyrics
        val updated = resolved.copy(lyrics = currentLines)
        queueController.updateCatalogIdentity(original, updated)
        if (track.lyricsIdentity() == oldIdentity && track.resourceId == original.resourceId) {
            lyricsByTrackId = lyricsByTrackId.entries.associate { (key, lines) ->
                val migrated = if (key.startsWith("$oldIdentity|")) newIdentity + key.removePrefix(oldIdentity) else key
                migrated to lines
            }
            lyricsLookupFailures = lyricsLookupFailures - oldIdentity
            if (activeLyrics.trackIdentity == oldIdentity) {
                activeLyrics = activeLyrics.copy(trackIdentity = newIdentity)
            }
            track = updated
        }
        return updated
    }

    fun resetLibraryPagination() {
        libraryPagingGeneration++
        libraryPageJobs.values.forEach(Job::cancel)
        libraryPageJobs.clear()
        libraryNextPages = emptyMap()
        libraryVisitedPages = emptyMap()
        libraryLoadingShelves = emptySet()
        libraryFailedShelves = emptySet()
    }

    fun rememberLibraryPage(shelfId: String, page: LibraryPage) {
        val visited = libraryVisitedPages[shelfId].orEmpty() + page.requestedPage
        check(page.nextPage == null || page.nextPage !in visited) {
            AppText.text(R.string.services_playlist_page_repeated)
        }
        libraryVisitedPages = libraryVisitedPages + (shelfId to visited)
        libraryNextPages = page.nextPage?.let { libraryNextPages + (shelfId to it) }
            ?: (libraryNextPages - shelfId)
    }

    fun loadMoreLibraryShelf(shelfId: String) {
        val nextPage = libraryNextPages[shelfId]
        if (nextPage == null && shelfId !in libraryFailedShelves) return
        if (connectionState != MusicConnectionState.SignedIn || shelfId in libraryLoadingShelves) return
        val generation = libraryPagingGeneration
        libraryLoadingShelves = libraryLoadingShelves + shelfId
        libraryFailedShelves = libraryFailedShelves - shelfId
        libraryPageJobs[shelfId] = scope.launch {
            try {
                val page = retryCatalogRequest({
                    when (shelfId) {
                        "lib-artists" -> services.libraryArtistsPage(nextPage)
                        "lib-albums" -> services.libraryAlbumsPage(nextPage)
                        "lib-playlists" -> services.libraryPlaylistsPage(nextPage)
                        "lib-songs" -> services.librarySongsPage(nextPage)
                        else -> error("Unknown library shelf")
                    }
                })
                if (generation != libraryPagingGeneration) return@launch
                check(page.requestedPage !in libraryVisitedPages[shelfId].orEmpty()) {
                    AppText.text(R.string.services_playlist_page_repeated)
                }
                rememberLibraryPage(shelfId, page)
                val shelves = shelvesByDestination[Destination.Library].orEmpty().map { shelf ->
                    if (shelf.id == shelfId) shelf.copy(items = mergeLibraryPage(shelf.items, page.items)) else shelf
                }
                shelvesByDestination = shelvesByDestination + (Destination.Library to shelves)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == libraryPagingGeneration) {
                    libraryFailedShelves = libraryFailedShelves + shelfId
                    Log.w("AMATV-Library", "Library page failed (${error.javaClass.simpleName})")
                }
            } finally {
                if (generation == libraryPagingGeneration) {
                    libraryLoadingShelves = libraryLoadingShelves - shelfId
                    libraryPageJobs.remove(shelfId)
                }
            }
        }
    }

    fun refreshAllContent() {
        resetLibraryPagination()
        connectionState = services.connectionState
        shelvesByDestination = emptyMap()
        loadingDestinations = setOf(destination)
        serviceMessage = null
        lyricsLookupFailures = emptySet()
        if (activeLyrics.phase == LyricsLoadPhase.Failed) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
        reloadNonce++
    }

    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        authLaunchGate.finish()
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
                    lyricsByTrackId = emptyMap()
                    lyricsLookupFailures = emptySet()
                    activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
                    connectionState = MusicConnectionState.SignedIn
                    resetLibraryPagination()
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
        networkRetryJob?.cancel()
        networkRetryJob = null
        val selection = selectionGeneration
        playbackRequestJob?.cancel()
        expectedPlaybackGeneration = 0L
        lastPlaybackFailure = null
        commandFailure = null
        // Stations intentionally resolve to individual song items; only gate exact song requests.
        pendingRequestedSongCatalogId = nextTrack.catalogId.takeIf { nextTrack.catalogKind == CatalogKind.Song }
        playbackRequestJob = scope.launch {
            try {
                val resolved = services.resolvePlayableTrack(nextTrack)
                if (selection != selectionGeneration) return@launch
                val playableTrack = acceptResolvedTrack(nextTrack, resolved)
                pendingRequestedSongCatalogId = playableTrack.catalogId.takeIf {
                    playableTrack.catalogKind == CatalogKind.Song
                }
                // Authorize/prepare silently. Re-read the latest user intent after
                // token I/O, so a pause during preparation cannot be overwritten.
                val nativeGeneration = services.preparePlayback(playableTrack, resumePositionMs, playWhenReady = false)
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
        networkRetryJob?.cancel()
        networkRetryJob = null
        pendingRestore = pendingRestore?.copy(wasPlaying = false)
        if (track.catalogId != null) services.pause()
        isPlaying = false
    }

    fun requestPlayback() {
        playWhenReady = true
        if (activeLyrics.trackIdentity == track.lyricsIdentity() &&
            activeLyrics.phase == LyricsLoadPhase.Failed
        ) lyricsRetryNonce++
        pendingRestore = pendingRestore?.copy(wasPlaying = true)
        if (!startupSessionLoaded && selectionGeneration == 0L) return
        // Play while Next is resolving belongs to the incoming entry, not
        // the old song still used as the queue's continuation anchor.
        if (waitingQueueEntryId != null) return
        val requestedTrack = requestedPlaybackTrack()
        if (requestedTrack.catalogId != null) {
            if (lastPlaybackFailure != null || (expectedPlaybackGeneration == 0L && playbackRequestJob?.isActive != true)) {
                selectionGeneration++
                launchNativePlayback(requestedTrack, requestedPlaybackPositionMs())
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
        val identity = track.lyricsIdentity()
        lyricsLookupFailures = lyricsLookupFailures - identity
        if (activeLyrics.trackIdentity == identity) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
        lyricsRetryNonce++
    }

    fun seekPlaybackBy(delta: Long) {
        val snapshot = currentPlaybackSnapshot()
        if (snapshot?.isLiveStream == true || snapshot?.canSeek == false) return
        if (isStationPlayback() && snapshot?.isPrepared != true) return
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
            launchNativePlayback(requestedPlaybackTrack(), requestedPlaybackPositionMs())
        }
    }

    fun startSignIn() {
        if (connectionState == MusicConnectionState.Demo) {
            serviceMessage = services.setupMessage
            destination = Destination.Account
            return
        }
        if (!authLaunchGate.tryBegin()) return
        scope.launch {
            try {
                webAuthenticationLauncher.launch(services.webAuthenticationIntent())
            } catch (cancelled: CancellationException) {
                authLaunchGate.finish()
                throw cancelled
            } catch (error: Exception) {
                authLaunchGate.finish()
                serviceMessage = error.message ?: AppText.text(R.string.app_apple_sign_in_unavailable)
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
        val nextIdentity = nextTrack.lyricsIdentity()
        val hasResolvedVariant = lyricsCacheKey(nextIdentity, lyricsVariant) in lyricsByTrackId
        if (activeLyrics.trackIdentity != nextIdentity || activeLyrics.lines != initialTrack.lyrics) {
            activeLyrics = ActiveLyricsState(
                trackIdentity = nextIdentity,
                lines = initialTrack.lyrics,
                resolvedVariant = lyricsVariant.takeIf {
                    hasResolvedVariant || (!lyricsLocalization.enabled && initialTrack.lyrics.isNotEmpty())
                },
                phase = if (initialTrack.lyrics.isEmpty()) LyricsLoadPhase.Idle else LyricsLoadPhase.Ready,
            )
        }
        track = initialTrack
        advancingEndedTrackId = null
        hasCurrentTrack = true
        playStartedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        isPlaying = nextTrack.catalogId == null && playWhenReady
        playbackMessage = null
        playbackPrepared = false

        val adopted = adoptingGaplessEntryId != null &&
            adoptingGaplessEntryId == queueController.state.value.current?.entryId
        if (adopted) {
            // MusicKit already continued into this song: keep its generation, no pause or prepare.
            isPlaying = playWhenReady
            playbackPrepared = true
            pendingRequestedSongCatalogId = null
        } else if (nextTrack.catalogId != null) {
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

    val latestTrackPlayback by rememberUpdatedState<(Track) -> Unit>(::executeTrackPlayback)
    queueController = remember {
        PlaybackQueueController(
            scope = scope,
            services = services,
            playbackPrefs = playbackPrefs,
            onPlayTrack = { nextTrack ->
                latestTrackPlayback(nextTrack)
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

    // Gapless (Flyme): once the current song is ready, mirror the app's next song into MusicKit so it
    // can preload it and continue without a gap. Any change of current or next replaces it.
    // Load the Favorite Songs state of each song shown on Now Playing (Flyme star button).
    LaunchedEffect(track.catalogId, connectionState) {
        val catalogId = track.catalogId?.takeIf { it.matches(Regex("[0-9]+")) }
        favoriteCatalogId = catalogId
        if (!BuildConfig.FLYME_AUTO || catalogId == null || track.catalogKind != CatalogKind.Song ||
            connectionState != MusicConnectionState.SignedIn || catalogId in favoriteState
        ) return@LaunchedEffect
        cancellableResult { services.isFavorite(catalogId) }.getOrNull()?.let {
            favoriteState = favoriteState + (catalogId to it)
        }
    }

    // Fade at gapless song changes, chosen per kind of queue in Settings.
    val playbackOptionSettings by services.playbackOptions.settings.collectAsState()
    LaunchedEffect(queueState.context?.kind, queueState.stationFeed, playbackOptionSettings) {
        if (!BuildConfig.FLYME_AUTO) return@LaunchedEffect
        services.setFadeEnabled(when {
            queueState.stationFeed != null -> playbackOptionSettings.fadeStations
            queueState.context?.kind == ContextKind.Album -> playbackOptionSettings.fadeAlbums
            else -> playbackOptionSettings.fadePlaylists
        })
    }

    val gaplessNext = remember(queueState) { if (BuildConfig.FLYME_AUTO) queueController.peekAutoNext() else null }
    LaunchedEffect(queueState.current?.entryId, gaplessNext?.entryId, gaplessNext?.track?.catalogId, playbackPrepared) {
        if (!BuildConfig.FLYME_AUTO) return@LaunchedEffect
        val next = gaplessNext
        val current = queueState.current?.track
        if (next == null || !playbackPrepared || isStationPlayback() || current?.catalogKind != CatalogKind.Song ||
            next.track.catalogKind != CatalogKind.Song || next.track.catalogId == current.catalogId
        ) {
            if (gaplessLookahead != null) services.setLookahead(null)
            gaplessLookahead = null
            return@LaunchedEffect
        }
        // Let the current song settle before loading another stream.
        delay(1_500L)
        val resolved = cancellableResult { services.resolvePlayableTrack(next.track) }.getOrNull() ?: return@LaunchedEffect
        if (resolved.catalogId != next.track.catalogId) queueController.updateCatalogIdentity(next.track, resolved)
        gaplessLookahead = next.entryId to resolved
        services.setLookahead(resolved)
    }

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

    fun playStationAsSongs(station: Track, stationId: String, songs: List<Track>) {
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        if (destination != Destination.NowPlaying) previousDestination = destination
        pendingRestore = null
        playWhenReady = true
        queueController.playStationSongs(
            stationId = stationId,
            songs = songs.map(::withCachedLyrics),
            context = PlaybackContext(kind = ContextKind.SongList, id = stationId, title = station.title),
        )
        destination = Destination.NowPlaying
    }

    fun playTrack(nextTrack: Track, contextQueue: List<Track> = emptyList(), webStation: Boolean = false) {
        val isStation = nextTrack.catalogKind == CatalogKind.Station
        if (nextTrack.openAction != CatalogOpenAction.Play && !isStation) return
        if (nextTrack.catalogId != null && connectionState != MusicConnectionState.SignedIn) {
            serviceMessage = AppText.text(R.string.app_apple_sign_in_required)
            startSignIn()
            return
        }
        // Without a usable system Widevine the web player only gets 30-second previews: play the
        // station's songs on the native player instead. Live radio has no songs and stays on the web.
        val stationId = nextTrack.catalogId
        if (isStation && BuildConfig.FLYME_AUTO && !webStation && stationId != null) {
            scope.launch {
                val asSongs = services.playbackOptions.settings.value.stationsAsSongs ||
                    !kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { services.widevineReady }
                val songs = if (asSongs) {
                    cancellableResult { services.stationSongs(stationId) }.getOrNull().orEmpty()
                } else emptyList()
                if (songs.isNotEmpty()) playStationAsSongs(nextTrack, stationId, songs)
                else playTrack(nextTrack, contextQueue, webStation = true)
            }
            return
        }
        supersedeStartupQueueWithNewContext()
        ++selectionGeneration
        if (destination != Destination.NowPlaying) previousDestination = destination
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
        playWhenReady = true
        queueController.playContext(
            tracks = fullQueue,
            startIndex = trackIndex,
            context = PlaybackContext(
                kind = if (isStation) ContextKind.Single else ContextKind.SongList,
                id = nextTrack.catalogId,
                title = if (isStation) nextTrack.title else nextTrack.album,
            ),
        )
        destination = Destination.NowPlaying
    }

    /** Leaves Now Playing for the song's artist or album page once Apple Music names it. */
    fun openFromPlayer(lookup: suspend (Track) -> Track?) {
        val song = track
        scope.launch {
            val target = cancellableResult { lookup(song) }.getOrNull()
            if (target == null) {
                serviceMessage = AppText.text(R.string.player_open_unavailable)
                return@launch
            }
            browseDetailRequest = target
            destination = previousDestination
        }
    }

    /**
     * The star on Now Playing adds to Favorite Songs (optimistic, reverted if Apple refuses).
     * Apple lets only its own apps remove a favorite, so a filled star explains that instead.
     */
    fun toggleFavorite() {
        val catalogId = track.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return
        if (favoriteState[catalogId] == true) {
            playerNotice = AppText.text(R.string.player_favorite_remove_unsupported)
            return
        }
        favoriteState = favoriteState + (catalogId to true)
        scope.launch {
            cancellableResult { services.addFavorite(catalogId) }.onFailure {
                android.util.Log.w("AMATV-Favorite", "Favorite add failed: ${it.javaClass.simpleName} " +
                    "status=${(it as? dev.amatv.integration.CatalogRequestException)?.httpStatus}")
                favoriteState = favoriteState + (catalogId to false)
                playerNotice = AppText.text(R.string.player_favorite_failed)
            }
        }
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
        if (isStationPlayback()) {
            val snapshot = currentPlaybackSnapshot() ?: return
            val canSkip = if (relative > 0) snapshot.canSkipNext else snapshot.canSkipPrevious
            if (!canSkip) return
            if (relative > 0) services.skipToNext() else services.skipToPrevious()
            return
        }
        // Next onto the gapless lookahead: MusicKit already buffered it, so no new prepare.
        val queued = gaplessLookahead
        val lookaheadId = queued?.second?.catalogId
        if (relative > 0 && lookaheadId != null && playbackPrepared && isPlaying &&
            queueController.peekAutoNext()?.entryId == queued.first &&
            services.skipToQueuedLookahead(lookaheadId)
        ) {
            playWhenReady = true
            return
        }
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
        networkRetryJob?.cancel()
        networkRetryJob = null
        if (kind == PlaybackFailureKind.TrackUnavailable && playWhenReady && !isStationPlayback()) {
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
        } else if (kind == PlaybackFailureKind.Network && playWhenReady && networkRetryCount < 2) {
            networkRetryCount++
            val retrySelection = selection
            val retryTrack = requestedPlaybackTrack()
            val retryPosition = requestedPlaybackPositionMs()
            networkRetryJob = scope.launch {
                for (countdown in 3 downTo 1) {
                    if (retrySelection != selectionGeneration || !playWhenReady) return@launch
                    playbackMessage = AppText.text(R.string.services_song_connection_retry, countdown)
                    delay(1_000L)
                }
                if (retrySelection != selectionGeneration || !playWhenReady) return@launch
                playbackMessage = null
                selectionGeneration++
                launchNativePlayback(retryTrack, retryPosition)
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
        val playback = if (!playWhenReady && (snapshot.isPlaying || snapshot.isBuffering)) {
            snapshot.copy(isPlaying = false, isBuffering = false)
        } else snapshot
        val published = playback.withSystemLyrics(activeLyrics.trackIdentity, activeLyrics.lines)
        systemPlaybackSnapshot.value = published
        val live = queueController.state.value
        val stationPlayback = live.current?.track?.catalogKind == CatalogKind.Station
        services.publishSystemPlayback(
            published,
            if (stationPlayback) published.canSkipNext else live.canSkipNext,
            if (stationPlayback) published.canSkipPrevious else live.canSkipPrevious,
        )
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
        // A Station request may report its current Song, so it must never pass this song-only gate.
        if (pendingCatalogId != null &&
            (!snapshot.hasPlayerCurrentTrack || snapshot.currentTrack?.catalogId != pendingCatalogId)
        ) return
        publishSystemSnapshot(snapshot)
        if (snapshot.notice != lastPlaybackNotice) {
            snapshot.notice?.let { serviceMessage = it }
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
            adoptingGaplessEntryId = queued.first
            try {
                queueController.advanceNext(manual = false)
            } finally {
                adoptingGaplessEntryId = null
            }
            return
        }
        playbackPrepared = true
        pendingRequestedSongCatalogId = null
        pendingRestore = null
        if (snapshot.isPlaying && snapshot.positionMs > 250L && successfulSelection != selectionGeneration) {
            successfulSelection = selectionGeneration
            networkRetryCount = 0
            networkRetryJob?.cancel()
            networkRetryJob = null
            queueController.state.value.current?.let { queueController.markCurrentPlaybackStarted(it.entryId) }
        }
        val endedEntryKey = queueController.state.value.current?.entryId?.toString() ?: track.id
        if (!snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId) advancingEndedTrackId = null
        if (snapshot.isEnded && snapshot.currentTrack?.catalogId == track.catalogId &&
            advancingEndedTrackId != endedEntryKey
        ) {
            advancingEndedTrackId = endedEntryKey
            isPlaying = false
            if (isStationPlayback()) {
                playWhenReady = false
                playbackPrepared = false
                expectedPlaybackGeneration = 0L
            } else {
                advanceNextTrack(isAuto = true)
            }
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
    LaunchedEffect(queueState.canSkipNext, queueState.canSkipPrevious, queueState.current?.entryId) {
        systemPlaybackSnapshot.value?.takeIf {
            it.requestGeneration == expectedPlaybackGeneration
        }?.let {
            publishSystemSnapshot(it)
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

    // Lyrics use the account API, not the native audio engine. Wait for the full
    // session (including deferred queue selection) before resolving the current
    // song; lyricsFor then awaits the account storefront and bearer internally.
    val lyricsRequestReady = startupSessionLoaded && hasCurrentTrack &&
        connectionState == MusicConnectionState.SignedIn
    val currentLyricsIdentity = track.lyricsIdentity()
    val currentLyricsLines = if (activeLyrics.trackIdentity == currentLyricsIdentity) {
        activeLyrics.lines
    } else {
        track.lyrics
    }

    // Lyrics arrive independently of MusicKit's playback clock. Publish their
    // completion/clearing immediately, rather than waiting for the next SDK event.
    LaunchedEffect(currentLyricsIdentity, currentLyricsLines, hasCurrentTrack) {
        if (!hasCurrentTrack) return@LaunchedEffect
        systemPlaybackSnapshot.value?.takeIf {
            it.requestGeneration == expectedPlaybackGeneration &&
                it.currentTrack?.lyricsIdentity() == currentLyricsIdentity
        }?.let(::publishSystemSnapshot)
    }

    val prefetchTargets = remember(queueState.current?.entryId, queueState.upNext, queueState.autoplay) {
        (queueState.upNext + queueState.autoplay).take(2).map { it.track }
    }
    LaunchedEffect(
        prefetchTargets, isPlaying, cacheSettings, lyricsRequestReady, currentLyricsLines.isEmpty(),
        lyricsLookupFailures, lyricsVariant,
    ) {
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
            val candidateIdentity = candidate.lyricsIdentity()
            val candidateLyricsKey = lyricsCacheKey(candidateIdentity, lyricsVariant)
            if ((currentLyricsLines.isNotEmpty() || currentLyricsIdentity in lyricsLookupFailures) &&
                candidate.catalogId != null &&
                (candidate.lyrics.isEmpty() || lyricsLocalization.enabled) &&
                candidateLyricsKey !in lyricsByTrackId && candidateIdentity !in lyricsLookupFailures
            ) {
                when (val result = requestLyricsWithRetry({
                    services.lyricsFor(candidate, lyricsLocalization)
                })) {
                    is LyricsRequestResult.Available -> {
                        if (cacheGeneration == services.contentCache.generation) {
                            cacheLyrics(candidateIdentity, lyricsVariant, result.lines)
                        }
                    }
                    LyricsRequestResult.Unavailable -> {
                        // A 200 response without TTML is a definitive miss. Remember it so
                        // unstable queue projections cannot restart this prefetch every poll.
                        lyricsLookupFailures = lyricsLookupFailures + candidateIdentity
                    }
                    is LyricsRequestResult.RetryableFailure -> Unit
                }
            }
        }
    }
    LaunchedEffect(cacheSettings.revision) {
        // Preserve the displayed line; clearing assets must not interrupt the current song.
        lyricsByTrackId = activeLyrics.resolvedCacheEntries()
        lyricsLookupFailures = emptySet()
        if (activeLyrics.phase == LyricsLoadPhase.Failed) {
            activeLyrics = activeLyrics.copy(phase = LyricsLoadPhase.Idle)
        }
    }

    LaunchedEffect(
        currentLyricsIdentity, track.catalogKind, lyricsRequestReady, reloadNonce, lyricsRetryNonce,
        cacheSettings.revision, lyricsVariant,
    ) {
        if (!lyricsRequestReady || track.catalogId == null || track.catalogKind != CatalogKind.Song) {
            return@LaunchedEffect
        }
        val lookupIdentity = currentLyricsIdentity
        val originalTrack = track
        if (activeLyrics.trackIdentity != lookupIdentity || activeLyrics.lines.isEmpty()) {
            activeLyrics = ActiveLyricsState(lookupIdentity, originalTrack.lyrics, phase = LyricsLoadPhase.Loading)
        }
        val resolvedTrack = cancellableResult {
            retryCatalogRequest({ services.resolvePlayableTrack(originalTrack) })
        }.getOrElse {
            activeLyrics = activeLyrics.copy(phase = if (
                classifyPlaybackFailure(null, null, null, it) == PlaybackFailureKind.TrackUnavailable
            ) LyricsLoadPhase.Unavailable else LyricsLoadPhase.Failed)
            Log.w("AMATV-Lyrics", "Library catalog resolution failed (${it.javaClass.simpleName})")
            return@LaunchedEffect
        }
        if (resolvedTrack.catalogId != originalTrack.catalogId) {
            // Re-key the same library song before any request/cache lookup. The
            // changed identity reruns this effect without flashing empty lyrics.
            acceptResolvedTrack(originalTrack, resolvedTrack)
            return@LaunchedEffect
        }
        val currentLyricsKey = lyricsCacheKey(lookupIdentity, lyricsVariant)
        lyricsByTrackId[currentLyricsKey]?.let { cached ->
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = cached,
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Ready,
            )
            return@LaunchedEffect
        }
        if (activeLyrics.trackIdentity == lookupIdentity &&
            activeLyrics.resolvedVariant == lyricsVariant &&
            activeLyrics.phase == LyricsLoadPhase.Ready
        ) return@LaunchedEffect

        // The canonical lines are the stable UI baseline. Changing language never
        // clears them and never exposes a half-parsed localization document.
        val baseline = (if (activeLyrics.trackIdentity == lookupIdentity) {
            activeLyrics.lines
        } else {
            track.lyrics
        }).map { it.forDisplay(showTranslation = false, showPronunciation = false) }

        if (!lyricsLocalization.enabled && baseline.isNotEmpty()) {
            cacheLyrics(lookupIdentity, lyricsVariant, baseline)
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = baseline,
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Ready,
            )
            return@LaunchedEffect
        }
        if (lookupIdentity in lyricsLookupFailures && baseline.isEmpty()) {
            activeLyrics = ActiveLyricsState(
                trackIdentity = lookupIdentity,
                lines = emptyList(),
                resolvedVariant = lyricsVariant,
                phase = LyricsLoadPhase.Unavailable,
            )
            return@LaunchedEffect
        }
        val lookupTrack = track
        activeLyrics = ActiveLyricsState(
            trackIdentity = lookupIdentity,
            lines = baseline,
            resolvedVariant = null,
            phase = LyricsLoadPhase.Loading,
        )
        Log.i("AMATV-Lyrics", "Loading current lyrics: $lookupIdentity " +
            "source=${lookupTrack.resourceType}:${lookupTrack.resourceId} (audioPrepared=$playbackPrepared)")
        when (val result = requestLyricsWithRetry({
            services.lyricsFor(lookupTrack, lyricsLocalization)
        })) {
            is LyricsRequestResult.Available -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                cacheLyrics(lookupIdentity, lyricsVariant, result.lines)
                lyricsLookupFailures = lyricsLookupFailures - lookupIdentity
                activeLyrics = ActiveLyricsState(
                    trackIdentity = lookupIdentity,
                    lines = result.lines,
                    resolvedVariant = lyricsVariant,
                    phase = LyricsLoadPhase.Ready,
                )
            }
            LyricsRequestResult.Unavailable -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                if (baseline.isEmpty()) {
                    lyricsLookupFailures = lyricsLookupFailures + lookupIdentity
                    activeLyrics = ActiveLyricsState(
                        trackIdentity = lookupIdentity,
                        lines = emptyList(),
                        resolvedVariant = lyricsVariant,
                        phase = LyricsLoadPhase.Unavailable,
                    )
                } else {
                    // Lyrics exist but this translation/pronunciation variant does not.
                    cacheLyrics(lookupIdentity, lyricsVariant, baseline)
                    activeLyrics = ActiveLyricsState(
                        trackIdentity = lookupIdentity,
                        lines = baseline,
                        resolvedVariant = lyricsVariant,
                        phase = LyricsLoadPhase.Ready,
                    )
                }
            }
            is LyricsRequestResult.RetryableFailure -> {
                if (track.lyricsIdentity() != lookupIdentity) return@LaunchedEffect
                // Keep the canonical lines visible and expose a retryable state only.
                activeLyrics = ActiveLyricsState(
                    trackIdentity = lookupIdentity,
                    lines = baseline,
                    resolvedVariant = null,
                    phase = LyricsLoadPhase.Failed,
                )
                Log.w("AMATV-Lyrics", "Lyrics temporarily unavailable (${result.reason})")
            }
        }
    }

    LaunchedEffect(destination, connectionState, reloadNonce, contentLanguageTag, queueState.source) {
        if (shelvesLanguageTag != contentLanguageTag) {
            resetLibraryPagination()
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
        if (shelvesByDestination[target]?.isNotEmpty() == true && target !in savedShelfDestinations) return@LaunchedEffect
        if (target == Destination.Library && connectionState != MusicConnectionState.SignedIn) {
            loadingDestinations = loadingDestinations - target
            return@LaunchedEffect
        }

        loadingDestinations = loadingDestinations + target
        // Show the last session's shelves at once; the network load below replaces them.
        val snapshotName = target.name.takeIf { target == Destination.Home || target == Destination.Browse }
        if (snapshotName != null && shelvesByDestination[target].isNullOrEmpty() &&
            connectionState == MusicConnectionState.SignedIn
        ) {
            cancellableResult { services.savedShelves(snapshotName, contentLanguageTag) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && shelvesByDestination[target].isNullOrEmpty() }
                ?.let { saved ->
                    shelvesByDestination = shelvesByDestination + (target to saved)
                    savedShelfDestinations = savedShelfDestinations + target
                }
        }
        val shelfKey = listOf(target, connectionState, reloadNonce, contentLanguageTag, queueState.source)
        val (loadedShelves, errors) = shelfLoads.load(shelfKey) {
        val errors = mutableListOf<Throwable>()
        coroutineScope {
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
                    val artists = async { cancellableResult { services.libraryArtistsPage() } }
                    val albums = async { cancellableResult { services.libraryAlbumsPage() } }
                    val playlists = async { cancellableResult { services.libraryPlaylistsPage() } }
                    val songs = async { cancellableResult { services.librarySongsPage() } }
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
                        artists.await().fold(
                            onSuccess = { page ->
                                rememberLibraryPage("lib-artists", page)
                                add(ContentShelf("lib-artists", AppText.text(R.string.app_library_artists), page.items))
                            },
                            onFailure = { error ->
                                errors += error
                                libraryFailedShelves = libraryFailedShelves + "lib-artists"
                                add(ContentShelf("lib-artists", AppText.text(R.string.app_library_artists), emptyList()))
                            },
                        )
                        albums.await().fold(
                            onSuccess = { page ->
                                rememberLibraryPage("lib-albums", page)
                                if (page.items.isNotEmpty() || page.nextPage != null) {
                                    add(ContentShelf("lib-albums", AppText.text(R.string.app_library_albums),
                                        page.items, featured = isEmpty()))
                                }
                            },
                            onFailure = { error ->
                                errors += error
                                libraryFailedShelves = libraryFailedShelves + "lib-albums"
                                add(ContentShelf("lib-albums", AppText.text(R.string.app_library_albums), emptyList()))
                            },
                        )
                        playlists.await().fold(
                            onSuccess = { page ->
                                rememberLibraryPage("lib-playlists", page)
                                if (page.items.isNotEmpty() || page.nextPage != null) {
                                    add(
                                        ContentShelf(
                                            id = "lib-playlists",
                                            title = AppText.text(R.string.app_library_playlists),
                                            items = page.items,
                                            featured = isEmpty(),
                                        ),
                                    )
                                }
                            },
                            onFailure = { error ->
                                errors += error
                                libraryFailedShelves = libraryFailedShelves + "lib-playlists"
                                add(ContentShelf("lib-playlists", AppText.text(R.string.app_library_playlists), emptyList()))
                            },
                        )
                        songs.await().fold(
                            onSuccess = { page ->
                                rememberLibraryPage("lib-songs", page)
                                if (page.items.isNotEmpty() || page.nextPage != null) {
                                    add(ContentShelf("lib-songs", AppText.text(R.string.app_library_songs), page.items))
                                }
                            },
                            onFailure = { error ->
                                errors += error
                                libraryFailedShelves = libraryFailedShelves + "lib-songs"
                                add(ContentShelf("lib-songs", AppText.text(R.string.app_library_songs), emptyList()))
                            },
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
        } to errors
        }
        val keepSaved = loadedShelves.isEmpty() && target in savedShelfDestinations
        if (!keepSaved) {
            shelvesByDestination = shelvesByDestination + (target to loadedShelves)
            savedShelfDestinations = savedShelfDestinations - target
            if (snapshotName != null && loadedShelves.isNotEmpty() && connectionState == MusicConnectionState.SignedIn) {
                scope.launch { cancellableResult { services.saveShelves(snapshotName, contentLanguageTag, loadedShelves) } }
            }
        }
        loadingDestinations = loadingDestinations - target
        serviceMessage = if (loadedShelves.isEmpty() && !keepSaved) {
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

    val stationQueueContext = queueState.current?.track?.catalogKind == CatalogKind.Station
    // Station history (Flyme): the songs a station played, kept across launches, like Apple Music's
    // played list. The queue view shows them before the current song while a station plays.
    LaunchedEffect(track.catalogId, stationQueueContext) {
        if (!BuildConfig.FLYME_AUTO || !stationQueueContext) return@LaunchedEffect
        val song = track.takeIf { it.catalogKind == CatalogKind.Song && it.catalogId != null } ?: return@LaunchedEffect
        if (stationHistory.lastOrNull()?.catalogId == song.catalogId) return@LaunchedEffect
        stationHistory = (stationHistory.filter { it.catalogId != song.catalogId } + song.copy(lyrics = emptyList()))
            .takeLast(STATION_HISTORY_LIMIT)
        saveStationHistory(context, stationHistory)
    }
    val stationTimeline = if (BuildConfig.FLYME_AUTO && stationQueueContext) {
        if (stationHistory.lastOrNull()?.catalogId == track.catalogId) stationHistory else stationHistory + track
    } else emptyList()
    val activePlaybackSnapshot = currentPlaybackSnapshot()
    // The native SDK briefly reports live while a song loads; only a station item can be a live stream.
    val isLiveStream = activePlaybackSnapshot?.isLiveStream == true && stationQueueContext
    val canSeekPlayback = !isLiveStream && activePlaybackSnapshot?.canSeek != false &&
        (!stationQueueContext || activePlaybackSnapshot?.isPrepared == true)
    val canSkipPrevious = if (stationQueueContext) {
        activePlaybackSnapshot?.canSkipPrevious == true
    } else {
        queueState.canSkipPrevious
    }
    val canSkipNext = if (stationQueueContext) {
        activePlaybackSnapshot?.canSkipNext == true
    } else {
        queueState.canSkipNext
    }

    // The station the current song came from; a live stream reports the station itself.
    val stationName = queueState.current?.track?.takeIf { it.catalogKind == CatalogKind.Station }?.title
    val liveLabel = stringResource(R.string.player_live_stream)
    val nowPlayingSubtitle = when {
        isLiveStream -> liveLabel
        stationName != null && track.catalogKind != CatalogKind.Station -> stationName
        else -> null
    }

    fun dismissVoice() {
        voiceJob?.cancel()
        voiceJob = null
        voiceState = null
    }

    /** Steps after which the music is the user's choice, not ours to resume. */
    fun VoiceCommand.decidesPlayback(): Boolean = this is VoiceCommand.Pause || this is VoiceCommand.Resume ||
        this is VoiceCommand.Next || this is VoiceCommand.Previous || this is VoiceCommand.Play ||
        this is VoiceCommand.Pick || this is VoiceCommand.PersonalStation

    /** Names worth favouring in recognition: what is on screen and what was played lately. */
    fun voiceHotwords(): List<String> = buildList {
        addAll(voicePickable.flatMap { listOf(it.title, it.artist) })
        addAll(recentTracks.flatMap { listOf(it.title, it.artist, it.album) })
        add(track.artist)
        add(track.album)
    }.map { it.trim() }.filter { it.length in 2..16 }.distinct().take(200)

    val normalizeLabel: (String) -> String = { label ->
        ChineseText.simplified(label).lowercase().replace(Regex("[\\s，。、·・:：()（）\\-]"), "")
    }
    val labelPinyin: (String) -> String? = { ChineseText.pinyin(it) }

    /** Press the visible control named [label]; false if nothing on screen matches. */
    fun tapOnScreen(label: String): Boolean {
        val target = bestTarget(label, voiceScreen.read(), normalizeLabel, labelPinyin) ?: return false
        return voiceScreen.click(target)
    }

    fun goTo(page: VoiceCommand.Page) {
        destination = when (page) {
            VoiceCommand.Page.Home -> Destination.Home
            VoiceCommand.Page.Browse -> Destination.Browse
            VoiceCommand.Page.Library -> Destination.Library
            VoiceCommand.Page.Search -> Destination.Search
        }
    }

    /** Songs for a spoken name; sound-alike characters are retried by pinyin. */
    suspend fun voiceSongs(query: String): List<Track> {
        val direct = cancellableResult { services.searchSongs(query) }.getOrNull().orEmpty()
        if (songsMatch(query, direct, ChineseText::simplified)) return direct
        return ChineseText.pinyin(query)
            ?.let { latin -> cancellableResult { services.searchSongs(latin) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: direct
    }

    /** Runs one step; returns a message to show when it could not be done. */
    suspend fun runVoiceStep(command: VoiceCommand): String? {
        when (command) {
            VoiceCommand.Pause -> pausePlayback()
            VoiceCommand.Resume -> requestPlayback()
            VoiceCommand.Next, VoiceCommand.Previous -> {
                skip(if (command == VoiceCommand.Next) 1 else -1)
                if (!playWhenReady) requestPlayback()
            }
            is VoiceCommand.Seek -> seekPlaybackBy(command.seconds * 1_000L)
            VoiceCommand.SeekDirectionUnclear -> return AppText.text(R.string.voice_seek_direction)
            VoiceCommand.NowPlaying -> if (track.catalogId != null) destination = Destination.NowPlaying
            is VoiceCommand.Go -> goTo(command.page)
            VoiceCommand.CurrentArtist -> openFromPlayer(services::catalogArtistOf)
            VoiceCommand.CurrentAlbum -> openFromPlayer(services::catalogAlbumOf)
            VoiceCommand.Favorite -> if (favoriteState[track.catalogId] != true) toggleFavorite()
            VoiceCommand.Shuffle -> if (!tapOnScreen("随机播放")) dispatchQueueAction(StartupQueueAction.ToggleShuffle)
            is VoiceCommand.Scroll -> {
                val horizontal = command.direction == VoiceCommand.Direction.Left ||
                    command.direction == VoiceCommand.Direction.Right
                val list = scrollTarget(command.section, horizontal, voiceScreen.read(), normalizeLabel, labelPinyin)
                    ?: return AppText.text(R.string.voice_nothing_to_scroll)
                val sign = if (command.direction == VoiceCommand.Direction.Down ||
                    command.direction == VoiceCommand.Direction.Right) 1f else -1f
                voiceScreen.scroll(list, if (horizontal) sign else 0f, if (horizontal) 0f else sign)
            }
            VoiceCommand.PersonalStation -> {
                val station = cancellableResult { services.personalStation() }.getOrNull()
                    ?: return AppText.text(R.string.voice_no_personal_station)
                playTrack(station, listOf(station))
            }
            is VoiceCommand.Pick -> {
                // A list just opened by the previous step may still be loading.
                var list = emptyList<Track>()
                for (attempt in 0 until 15) {
                    list = if (destination == Destination.NowPlaying) emptyList() else voicePickable
                    if (list.size >= command.number) break
                    delay(200L)
                }
                val picked = list.getOrNull(command.number - 1)
                    ?: return AppText.text(R.string.voice_no_item_format, command.number)
                playTrack(picked, contextQueue = list.filter { it.openAction == CatalogOpenAction.Play })
            }
            is VoiceCommand.Open -> {
                if (tapOnScreen(command.label)) return null
                // Not on this page: the library holds most named lists ("喜好歌曲").
                if (destination != Destination.Library) {
                    destination = Destination.Library
                    delay(900L)
                    if (tapOnScreen(command.label)) return null
                }
                return runVoiceStep(VoiceCommand.Play(command.label))
            }
            is VoiceCommand.Play -> {
                // "播放喜好歌曲": a list on screen is opened and its first song played; a song on
                // screen plays when pressed. Lists not on this page are looked for in the library.
                suspend fun playFromScreen(): Boolean {
                    val listBefore = voicePickable
                    val playingBefore = track.id
                    if (!tapOnScreen(command.query)) return false
                    for (attempt in 0 until 12) {
                        delay(200L)
                        if (track.id != playingBefore) return true
                        val list = voicePickable
                        if (list.isNotEmpty() && list != listBefore) {
                            playTrack(list.first(), contextQueue = list.filter { it.openAction == CatalogOpenAction.Play })
                            return true
                        }
                    }
                    return true
                }
                if (playFromScreen()) return null
                if (LIBRARY_LIST_HINT.containsMatchIn(command.query) && destination != Destination.Library) {
                    destination = Destination.Library
                    delay(900L)
                    if (playFromScreen()) return null
                }
                voiceState = VoiceUiState.Searching(command.query)
                if (command.query.endsWith("电台")) {
                    val station = cancellableResult { services.search(command.query) }.getOrNull().orEmpty()
                        .firstOrNull { it.catalogKind == CatalogKind.Station }
                    if (station != null) {
                        playTrack(station, listOf(station))
                        return null
                    }
                }
                val songs = voiceSongs(command.query)
                if (songs.isEmpty()) return AppText.text(R.string.voice_not_found_format, command.query)
                playTrack(songs.first(), contextQueue = songs)
            }
            VoiceCommand.Nothing -> return AppText.text(R.string.voice_not_understood)
        }
        return null
    }

    /**
     * Acts on one recognized sentence: the rules first; a sentence they would only open or search
     * goes to the chat model (if on), which also sees the screen, the page, the song playing and
     * [previousTurn]. Returns a message when something could not be done, and the steps taken.
     */
    suspend fun actOnSentence(said: String, previousTurn: String?): Pair<String?, List<VoiceCommand>> {
        voiceState = VoiceUiState.Heard(said)
        val ruled = parseVoiceSteps(said)
        val unsure = ruled.all { it is VoiceCommand.Play || it is VoiceCommand.Open || it == VoiceCommand.Nothing }
        val steps = if (unsure && voice.intentReady()) {
            voiceState = VoiceUiState.Understanding(said)
            val labels = voiceScreen.read().filter { it.clickable }.map { it.label }.filter { it.isNotBlank() }
            val context = buildMap {
                put("page", destination.name)
                if (track.catalogId != null) put("now_playing", "${track.title} - ${track.artist} (${track.album})")
                if (!previousTurn.isNullOrBlank()) put("previous_turn", previousTurn)
            }
            voice.understand(said, labels, voiceHotwords(), ChineseText.pinyin(said), context) ?: ruled
        } else ruled
        voiceState = VoiceUiState.Heard(said)
        var problem: String? = null
        for ((index, step) in steps.withIndex()) {
            if (index > 0) delay(700L) // let the page the previous step opened appear
            problem = runVoiceStep(step)
            if (problem != null) break
        }
        if (BuildConfig.FLYME_UI_PREVIEW) {
            android.util.Log.i("AMATV-Voice", "said=\"$said\" ruled=$ruled steps=$steps problem=$problem page=$destination")
        }
        return problem to steps
    }

    /** Whether voice can start now; asks for the microphone or explains what is missing. */
    fun voiceUsable(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        if (!voice.ready()) {
            voiceState = VoiceUiState.Message(AppText.text(R.string.voice_engine_not_ready))
            scope.launch {
                delay(3_000L)
                if (voiceState is VoiceUiState.Message && voiceJob?.isActive != true) voiceState = null
            }
            return false
        }
        return true
    }

    fun voiceFailureText(failure: VoiceFailure?): String = AppText.text(when (failure) {
        VoiceFailure.MicUnavailable -> R.string.voice_mic_unavailable
        VoiceFailure.ModelMissing, VoiceFailure.NotConfigured -> R.string.voice_engine_not_ready
        VoiceFailure.Network -> R.string.voice_network_failed
        else -> R.string.voice_not_understood
    })

    /**
     * A voice session. Tap ([holdToTalk] false): keeps listening, one sentence after another, until
     * the user taps again, stays silent, or a sentence starts music (the mic would hear it). Hold:
     * one sentence, recorded until the button is released. Our music pauses meanwhile and carries
     * on afterwards unless a command decided otherwise.
     */
    fun startVoiceSession(holdToTalk: Boolean) {
        if (!voiceUsable()) return
        val generation = ++voiceGeneration
        val resumeAfter = playWhenReady
        if (resumeAfter) pausePlayback()
        val hotwords = voiceHotwords()
        voiceState = VoiceUiState.Listening("", ready = false)
        voiceJob = scope.launch {
            var playbackDecided = false
            var problem: String? = null
            // Within one session only: the last sentence and what it did, for "第二个呢" follow-ups.
            var previousTurn: String? = null
            fun finishPlayback() {
                if (generation == voiceGeneration && resumeAfter && !playbackDecided && !playWhenReady) requestPlayback()
            }
            try {
                while (true) {
                    val control = ListenControl(holdToTalk)
                    if (holdToTalk) voiceHold = control
                    var heard: String? = null
                    var failure: VoiceFailure? = null
                    voice.listen(hotwords, control).collect { event ->
                        when (event) {
                            VoiceEvent.Listening -> voiceState = VoiceUiState.Listening("")
                            is VoiceEvent.Partial -> voiceState = VoiceUiState.Listening(event.text)
                            VoiceEvent.Recognizing -> voiceState = VoiceUiState.Recognizing
                            is VoiceEvent.Final -> heard = event.text
                            is VoiceEvent.Failed -> failure = event.reason
                        }
                    }
                    voiceHold = null
                    val said = heard
                    if (said == null) {
                        // A tap session ends quietly when the user stops talking.
                        if (holdToTalk || failure != VoiceFailure.NoSpeech) problem = voiceFailureText(failure)
                        break
                    }
                    val (sentenceProblem, steps) = actOnSentence(said, previousTurn)
                    problem = sentenceProblem
                    previousTurn = "$said -> ${steps.joinToString()}" + (problem?.let { " ($it)" } ?: "")
                    if (problem == null && steps.any { it.decidesPlayback() }) playbackDecided = true
                    if (holdToTalk || playbackDecided) break
                    if (problem != null) {
                        voiceState = VoiceUiState.Message(problem)
                        problem = null
                        delay(1_800L)
                    } else delay(500L)
                    voiceState = VoiceUiState.Listening("", ready = false)
                }
            } catch (cancelled: CancellationException) {
                voiceHold = null
                finishPlayback()
                throw cancelled
            }
            finishPlayback()
            val shown = problem
            if (shown != null) {
                voiceState = VoiceUiState.Message(shown)
                delay(2_400L)
            } else delay(500L)
            if (generation == voiceGeneration) {
                voiceState = null
                voiceJob = null
            }
        }
    }

    /** Tap: start a listening session, or end the one in progress. */
    fun onVoiceTap() {
        if (voiceJob?.isActive == true) dismissVoice() else startVoiceSession(holdToTalk = false)
    }

    /** Hold: walkie-talkie; replaces a session in progress. */
    fun onVoiceHoldStart() {
        voiceJob?.cancel()
        startVoiceSession(holdToTalk = true)
    }

    fun onVoiceHoldEnd() {
        voiceHold?.finish = true
    }
    LaunchedEffect(voiceStartRequests) {
        if (voiceStartRequests > 0) startVoiceSession(holdToTalk = false)
    }
    // Preview builds only: `adb shell am broadcast -a dev.amatv.debug.VOICE_TEXT --es text 播放我的喜好歌曲`
    // runs a sentence as if it had just been recognized, to test the commands without a voice.
    if (BuildConfig.FLYME_UI_PREVIEW) {
        DisposableEffect(context) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: android.content.Context, intent: android.content.Intent) {
                    val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
                    scope.launch {
                        val (problem, _) = actOnSentence(text, intent.getStringExtra("previous"))
                        voiceState = problem?.let { VoiceUiState.Message(it) }
                        delay(2_000L)
                        if (voiceJob?.isActive != true) voiceState = null
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter("dev.amatv.debug.VOICE_TEXT"),
                ContextCompat.RECEIVER_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    val isNowPlaying = destination == Destination.NowPlaying
    LaunchedEffect(isNowPlaying, isPlaying, spectrumPermissionGranted) {
        // Flyme has no spectrum display, so it never asks for the microphone.
        if (BuildConfig.FLYME_AUTO) return@LaunchedEffect
        if (isNowPlaying && isPlaying && !spectrumPermissionGranted && !spectrumPermissionAsked) {
            spectrumPermissionAsked = true
            spectrumPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(lifecycleOwner, isNowPlaying, isPlaying, spectrumPermissionGranted) {
        fun updateCapture() {
            services.setSpectrumActive(!BuildConfig.FLYME_AUTO && isNowPlaying && isPlaying && spectrumPermissionGranted &&
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
        // Flyme: Now Playing grows out of the mini player and shrinks back into it.
        animationSpec = if (BuildConfig.FLYME_AUTO) {
            tween(durationMillis = 340, easing = CubicBezierEasing(0.2f, 0.9f, 0.22f, 1f))
        } else tween(
            durationMillis = 320,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "now-playing-screen-transition",
    )
    var miniPlayerCenter by remember { mutableStateOf<Offset?>(null) }

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
                            seekPlaybackBy(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            seekPlaybackBy(10_000L)
                            true
                        }
                        else -> false
                    }
                },
            color = if (BuildConfig.FLYME_AUTO) LocalFlymePalette.current.page else Color(0xFF080A09),
            contentColor = FlymeInk,
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
                        onVoice = null,
                        onVoicePickable = { voicePickable = it },
                        voiceActive = voiceState is VoiceUiState.Listening,
                        onMiniPlayerPlaced = { miniPlayerCenter = it },
                        detailRequest = browseDetailRequest,
                        onDetailRequestHandled = { browseDetailRequest = null },
                        loadArtistOf = services::catalogArtistOf,
                        destination = activeBrowseDestination,
                        isBrowseVisible = !isNowPlaying,
                        currentTrack = track,
                        hasCurrentTrack = hasCurrentTrack,
                        isPlaying = isPlaying,
                        playWhenReady = playWhenReady,
                        nowPlayingSubtitle = nowPlayingSubtitle,
                        onTogglePlayback = ::togglePlayback,
                        searchTracks = searchResults,
                        searchLoading = searchLoading,
                        recentTracks = recentTracks,
                        shelves = shelvesByDestination[activeBrowseDestination].orEmpty(),
                        shelfPagination = (libraryNextPages.keys + libraryLoadingShelves + libraryFailedShelves)
                            .associateWith { id -> LibraryShelfPagination(
                                id in libraryNextPages, id in libraryLoadingShelves, id in libraryFailedShelves,
                            ) },
                        onLoadMoreShelf = ::loadMoreLibraryShelf,
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
                            resetLibraryPagination()
                            selectionGeneration++
                            playbackRequestJob?.cancel()
                            services.signOut()
                            queueController.reset()
                            playbackPrefs.clearSession()
                            scope.launch { cancellableResult { services.contentCache.clear { ArtworkImages.clear(context) } } }
                            isPlaying = false
                            playWhenReady = false
                            waitingQueueEntryId = null
                            playbackPrepared = false
                            hasUserPlaybackRequest = false
                            hasCurrentTrack = false
                            lyricsByTrackId = emptyMap()
                            lyricsLookupFailures = emptySet()
                            activeLyrics = ActiveLyricsState(
                                trackIdentity = track.lyricsIdentity(),
                                lines = emptyList(),
                            )
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
                                        .onFailure {
                                            searchResults = emptyList()
                                            serviceMessage = it.message ?: AppText.text(R.string.app_search_failed)
                                        }
                                } finally {
                                    searchLoading = false
                                }
                            }
                        },
                        onOpenTrack = { item, q -> playTrack(item, contextQueue = q) },
                        loadCollection = services::collectionTracks,
                        loadArtistAlbums = services::libraryArtistAlbumsPage,
                        loadArtistCatalog = services::catalogArtistPage,
                        onPlayCollection = ::playCollection,
                        lyricOffsetMs = lyricOffsetMs,
                        onLyricOffsetChange = { offset: Long ->
                            lyricOffsetMs = offset
                            playbackPrefs.lyricOffsetMs = offset
                        },
                        autoplaySource = queueState.source,
                        onAutoplaySourceChange = { dispatchQueueAction(StartupQueueAction.SetSource(it)) },
                        lowPerformanceMode = lowPerformanceMode,
                        onLowPerformanceModeChange = {
                            lowPerformanceMode = it
                            playbackPrefs.lowPerformanceMode = it
                        },
                        cacheSettingsContent = {
                            LanguageSettings()
                            FlymeMediaSettings(services)
                            if (BuildConfig.FLYME_AUTO) FlymeAppearanceSettings(services.playbackOptions)
                            if (BuildConfig.FLYME_AUTO) FlymePlaybackOptionsSettings(services.playbackOptions)
                            if (BuildConfig.FLYME_AUTO) FlymePlaybackDiagnostics(services.playbackDiagnostics)
                            ContentCacheSettings(services.contentCache)
                        },
                    )
                }
                }
                // Retained native player views can intercept touches even at
                // alpha=0. The car mounts the player only while it is visible.
                if (isNowPlaying || playerTransitionVisible || (!BuildConfig.FLYME_AUTO && hasCurrentTrack)) {
                    val isPlayerVisible = isNowPlaying || playerTransitionVisible
                    CompositionLocalProvider(
                        LocalFocusFrameEnabled provides isNowPlaying,
                        LocalFocusTargetsEnabled provides isNowPlaying,
                        LocalFocusFrameVisibility provides nowPlayingAlpha,
                    ) {
                    PlayerScreen(
                        track = track.copy(lyrics = currentLyricsLines),
                        lyricsPreferred = lyricsPreferred,
                        onLyricsPreferredChange = {
                            lyricsPreferred = it
                            playbackPrefs.lyricsPreferred = it
                        },
                        positionAnchorMs = positionAnchorMs,
                        playStartedRealtimeMs = playStartedRealtimeMs,
                        isPlaying = isPlaying,
                        playWhenReady = playWhenReady,
                        audioSpectrum = services::audioSpectrum,
                        isActiveScreen = isNowPlaying,
                        isVisibleOnScreen = isPlayerVisible,
                        queue = stationTimeline.ifEmpty { timelineTracks },
                        queueEntryIds = if (stationTimeline.isNotEmpty()) {
                            stationTimeline.indices.map { -1_000L - it }
                        } else timelineEntryIds,
                        queueCurrentIndex = if (stationTimeline.isNotEmpty()) stationTimeline.lastIndex
                            else queueState.currentTimelineIndex,
                        queueSessionId = queueState.sessionId,
                        canClearQueue = !stationQueueContext &&
                            (queueState.upNext.isNotEmpty() || queueState.autoplay.isNotEmpty()),
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        repeatMode = queueState.repeat,
                        shuffleEnabled = queueState.shuffle,
                        autoplayEnabled = queueState.infinite,
                        canSetRepeat = !stationQueueContext,
                        canSetShuffle = !stationQueueContext,
                        canSetAutoplay = !stationQueueContext && track.catalogId != null &&
                            track.catalogKind == dev.amatv.model.CatalogKind.Song,
                        autoplaySource = queueState.source,
                        onToggleAutoplaySource = {
                            if (!stationQueueContext) dispatchQueueAction(StartupQueueAction.ToggleSource)
                        },
                        onClearQueue = {
                            if (!stationQueueContext) dispatchQueueAction(StartupQueueAction.ClearUpcoming)
                        },
                        lyricsLoading = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Loading,
                        lyricsLookupFailed = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Unavailable,
                        lyricsTransientFailure = activeLyrics.trackIdentity == currentLyricsIdentity &&
                            activeLyrics.phase == LyricsLoadPhase.Failed,
                        onRetryLyrics = ::retryCurrentLyrics,
                        lyricOffsetMs = lyricOffsetMs,
                        statusMessage = playerNotice ?: playbackMessage ?: if (queueState.awaitingNext) {
                            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message
                        } else null,
                        onTogglePlayback = (::togglePlayback),
                        onSkipPrevious = { skip(-1) },
                        onSkipNext = { skip(1) },
                        onSeekBy = ::seekPlaybackBy,
                        canSeek = canSeekPlayback,
                        isLiveStream = isLiveStream,
                        stationName = stationName.takeIf { !isLiveStream && track.catalogKind != CatalogKind.Station },
                        onSelectTrack = { index ->
                            // A station's played song plays on its own, leaving the station.
                            if (stationTimeline.isNotEmpty()) {
                                stationTimeline.getOrNull(index)?.takeIf { index != stationTimeline.lastIndex }
                                    ?.let { song -> playWhenReady = true; playTrack(song, listOf(song)) }
                            } else selectQueueTrack(index)
                        },
                        onBack = { destination = previousDestination },
                        onOpenArtist = { openFromPlayer(services::catalogArtistOf) },
                        isFavorite = favoriteState.takeIf { BuildConfig.FLYME_AUTO }?.get(track.catalogId)
                            ?.takeIf { favoriteCatalogId == track.catalogId },
                        onToggleFavorite = ::toggleFavorite,
                        onOpenAlbum = { openFromPlayer(services::catalogAlbumOf) },
                        lyricTextSize = lyricTextSize,
                        onLyricTextSizeChange = {
                            lyricTextSize = it
                            playbackPrefs.lyricTextSize = it
                        },
                        lowPerformanceMode = lowPerformanceMode,
                        lyricTranslationEnabled = lyricTranslationEnabled,
                        lyricPronunciationEnabled = lyricPronunciationEnabled,
                        lyricTranslationLanguageTag = lyricTranslationLanguageTag,
                        lyricPronunciationLanguageTag = lyricPronunciationLanguageTag,
                        appLanguageTag = lyricsLanguageTag,
                        lyricsEnhancementsReady = !lyricsLocalization.enabled ||
                            (activeLyrics.trackIdentity == currentLyricsIdentity &&
                                activeLyrics.resolvedVariant == lyricsVariant &&
                                activeLyrics.phase == LyricsLoadPhase.Ready),
                        onLyricTranslationEnabledChange = { enabled ->
                            lyricTranslationEnabled = enabled
                            playbackPrefs.lyricTranslationEnabled = enabled
                        },
                        onLyricPronunciationEnabledChange = { enabled ->
                            lyricPronunciationEnabled = enabled
                            playbackPrefs.lyricPronunciationEnabled = enabled
                        },
                        onLyricTranslationLanguageChange = { languageTag ->
                            lyricTranslationLanguageTag = languageTag
                            playbackPrefs.lyricTranslationLanguageTag = languageTag
                        },
                        onLyricPronunciationLanguageChange = { languageTag ->
                            lyricPronunciationLanguageTag = languageTag
                            playbackPrefs.lyricPronunciationLanguageTag = languageTag
                        },
                        onCycleRepeat = {
                            if (!stationQueueContext) dispatchQueueAction(StartupQueueAction.CycleRepeat)
                        },
                        onToggleShuffle = {
                            if (!stationQueueContext) dispatchQueueAction(StartupQueueAction.ToggleShuffle)
                        },
                        onToggleAutoplay = {
                            if (!stationQueueContext) dispatchQueueAction(StartupQueueAction.ToggleInfinite)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = isNowPlaying }
                            .graphicsLayer {
                                val t = nowPlayingAlpha.value
                                if (BuildConfig.FLYME_AUTO) {
                                    val center = miniPlayerCenter
                                    transformOrigin = if (center != null && size.width > 0f && size.height > 0f) {
                                        TransformOrigin(center.x / size.width, center.y / size.height)
                                    } else TransformOrigin(0.86f, 0.06f)
                                    // A gentle zoom toward the capsule, not a full morph.
                                    val scale = 0.92f + 0.08f * t
                                    scaleX = scale
                                    scaleY = scale
                                    shape = RoundedCornerShape((1f - t) * 28.dp.toPx())
                                    clip = t < 1f
                                    alpha = t
                                } else {
                                    alpha = t
                                    val scale = 0.97f + 0.03f * t
                                    scaleX = scale
                                    scaleY = scale
                                }
                            },
                    )
                    }
                }
                FocusFrameOverlay(focusFrameController)
                if (BuildConfig.FLYME_AUTO) {
                    FlymeVoiceGlow(visible = voiceJob?.isActive == true)
                    FlymeVoicePanel(voiceState, onDismiss = ::dismissVoice)
                }
                // Drawn last: stays usable above the voice bubble, to end or restart a session.
                if (BuildConfig.FLYME_AUTO && voiceEnabled) {
                    val micPosition = remember(voice) { voice.micPosition().let { Offset(it.first, it.second) } }
                    FlymeFloatingVoiceButton(
                        active = voiceState is VoiceUiState.Listening,
                        position = micPosition,
                        onMoved = { voice.saveMicPosition(it.x, it.y) },
                        onTap = ::onVoiceTap,
                        onHoldStart = ::onVoiceHoldStart,
                        onHoldEnd = ::onVoiceHoldEnd,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun BrowseShell(
    onVoice: (() -> Unit)?,
    onVoicePickable: (List<Track>) -> Unit,
    voiceActive: Boolean,
    onMiniPlayerPlaced: (Offset) -> Unit = {},
    destination: Destination,
    isBrowseVisible: Boolean,
    currentTrack: Track,
    hasCurrentTrack: Boolean,
    isPlaying: Boolean,
    playWhenReady: Boolean = isPlaying,
    nowPlayingSubtitle: String? = null,
    onTogglePlayback: () -> Unit = {},
    searchTracks: List<Track>,
    searchLoading: Boolean,
    recentTracks: List<Track>,
    shelves: List<ContentShelf>,
    shelfPagination: Map<String, LibraryShelfPagination>,
    onLoadMoreShelf: (String) -> Unit,
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
    loadArtistAlbums: suspend (Track, String?) -> LibraryPage,
    loadArtistCatalog: suspend (Track) -> dev.amatv.integration.CatalogArtistPage = { dev.amatv.integration.CatalogArtistPage() },
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    detailRequest: Track? = null,
    onDetailRequestHandled: () -> Unit = {},
    loadArtistOf: suspend (Track) -> Track? = { null },
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: AutoplaySource = AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    cacheSettingsContent: @Composable () -> Unit = {},
) {
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
    val currentArtistLoader by rememberUpdatedState(loadArtistAlbums)
    val currentArtistCatalogLoader by rememberUpdatedState(loadArtistCatalog)
    val artistBrowser = remember(destination) {
        LibraryArtistBrowser(scope, { currentArtistCatalogLoader(it) }) { item, next -> currentArtistLoader(item, next) }
    }
    val artistState by artistBrowser.state.collectAsState()
    val artist = artistState.artist
    val artistFirstFocus = remember(destination) { FocusRequester() }
    val detailVisible = collection != null || artist != null
    // What "第三个" means here: the open album/playlist, the artist's top songs, or the songs found.
    val voicePickable = when {
        collection != null -> collectionState.tracks
        artist != null -> artistState.catalog.topSongs
        destination == Destination.Search -> searchTracks.drop(1)
            .filter { it.catalogKind == CatalogKind.Song || it.catalogKind == CatalogKind.MusicVideo }.take(8)
        else -> emptyList()
    }.filter { it.openAction == CatalogOpenAction.Play }
    LaunchedEffect(voicePickable) { onVoicePickable(voicePickable) }
    var returnToContent by remember(destination) { mutableIntStateOf(0) }
    val detailFocus = remember(destination) { ContentFocusEntry() }
    val detailFirstFocus = remember(destination) { FocusRequester() }
    DisposableEffect(browser) { onDispose { browser.close() } }
    LaunchedEffect(detailRequest, browser, artistBrowser) {
        val request = detailRequest ?: return@LaunchedEffect
        when (request.openAction) {
            CatalogOpenAction.OpenArtist -> { browser.close(); artistBrowser.open(request) }
            CatalogOpenAction.Collection -> browser.open(request)
            else -> Unit
        }
        onDetailRequestHandled()
    }
    val currentArtistOf by rememberUpdatedState(loadArtistOf)
    fun openArtistOf(album: Track) {
        scope.launch {
            val artist = runCatching { currentArtistOf(album) }.getOrNull() ?: return@launch
            browser.close()
            artistBrowser.open(artist)
        }
    }
    DisposableEffect(artistBrowser) { onDispose { artistBrowser.close() } }
    val firstContentFocus = remember(destination) { FocusRequester() }
    val contentFocusEntry = remember(destination) { ContentFocusEntry() }
    fun closeCollection() {
        browser.close()
        returnToContent++
    }
    fun closeArtist() { artistBrowser.close(); returnToContent++ }
    BackHandler(enabled = isBrowseVisible && detailVisible) {
        if (collection != null) closeCollection() else closeArtist()
    }
    LaunchedEffect(destination, isBrowseVisible, collection?.id, artist?.id, collectionState.loading, collectionState.error, returnToContent) {
        if (!isBrowseVisible) return@LaunchedEffect
        withFrameNanos { }
        when {
            collection != null -> detailFocus.requestFocus(detailFirstFocus)
            artist != null -> runCatching { artistFirstFocus.requestFocus() }
            returnToContent > 0 -> contentFocusEntry.requestFocus(firstContentFocus)
            else -> runCatching { navigationFocus[destination]?.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (BuildConfig.FLYME_AUTO) listOf(LocalFlymePalette.current.pageTop, LocalFlymePalette.current.page)
                    else listOf(
                        Color(0xFF19241E),
                        Color(0xFF0B1411),
                    ),
                ),
            ),
    ) {
        if (BuildConfig.FLYME_AUTO && hasCurrentTrack) {
            // Ambient colour from the playing artwork, like Apple TV: brightest at the top,
            // fading into the page so content stays readable. Still in low-performance mode.
            PlayerBackdrop(currentTrack, animate = isBrowseVisible && !lowPerformanceMode,
                modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to LocalFlymePalette.current.scrim.copy(alpha = 0.42f),
                0.45f to LocalFlymePalette.current.scrim.copy(alpha = 0.78f),
                1f to LocalFlymePalette.current.scrim.copy(alpha = 0.92f),
            )))
        }
        CompositionLocalProvider(
            LocalFocusFrameEnabled provides (!detailVisible && isBrowseVisible),
            LocalFocusTargetsEnabled provides (!detailVisible && isBrowseVisible),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (detailVisible) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = if (detailVisible) 0f else 1f },
        ) {
            if (BuildConfig.FLYME_AUTO) {
                FlymeTopBar(
                    selected = destination,
                    hasCurrentTrack = hasCurrentTrack,
                    currentTrack = currentTrack,
                    nowPlayingSubtitle = nowPlayingSubtitle,
                    isPlaying = playWhenReady,
                    onTogglePlayback = onTogglePlayback,
                    onDestination = onDestination,
                    focusRequesters = navigationFocus,
                    downFocus = { contentFocusEntry.requester ?: firstContentFocus },
                    onMiniPlayerPlaced = onMiniPlayerPlaced,
                    onVoice = onVoice,
                    voiceActive = voiceActive,
                )
            } else TopNavigation(
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
                CompositionLocalProvider(LocalContentFocusEntry provides contentFocusEntry) {
                    BrowseContentMotion(
                        targetState = destination,
                        modifier = Modifier.fillMaxSize(),
                        label = "browse-destination",
                    ) { visibleDestination ->
                        val visibleFirstFocus = remember(visibleDestination) { firstContentFocus }
                        HomeScreen(
                            destination = visibleDestination,
                            currentTrack = currentTrack,
                            tracks = searchTracks,
                            searchLoading = searchLoading,
                            recentTracks = recentTracks,
                            homeShelves = shelves,
                            homeShelfPagination = shelfPagination,
                            onLoadMoreShelf = onLoadMoreShelf,
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
                                    CatalogOpenAction.OpenArtist -> artistBrowser.open(item)
                                    CatalogOpenAction.Collection -> browser.open(item)
                                    CatalogOpenAction.Station -> onOpenTrack(item, listOf(item))
                                    CatalogOpenAction.Play -> onOpenTrack(item, queue.filter {
                                        it.openAction == CatalogOpenAction.Play
                                    })
                                }
                            },
                            lyricOffsetMs = lyricOffsetMs,
                            onLyricOffsetChange = onLyricOffsetChange,
                            autoplaySource = autoplaySource,
                            onAutoplaySourceChange = onAutoplaySourceChange,
                            lowPerformanceMode = lowPerformanceMode,
                            onLowPerformanceModeChange = onLowPerformanceModeChange,
                            cacheSettingsContent = cacheSettingsContent,
                            firstContentFocusRequester = visibleFirstFocus,
                            navigationFocusRequester = navigationFocus[visibleDestination],
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        }
        if (artist != null && collection == null) {
            CompositionLocalProvider(
                LocalFocusFrameEnabled provides isBrowseVisible,
                LocalFocusTargetsEnabled provides isBrowseVisible,
            ) {
                val openArtistItem: (Track, List<Track>) -> Unit = { item, queue ->
                    when (item.openAction) {
                        CatalogOpenAction.Collection -> browser.open(item)
                        CatalogOpenAction.Station -> onOpenTrack(item, listOf(item))
                        CatalogOpenAction.Play -> onOpenTrack(item, queue.filter {
                            it.openAction == CatalogOpenAction.Play
                        })
                        // Similar artists open their own page.
                        CatalogOpenAction.OpenArtist -> artistBrowser.open(item)
                    }
                }
                if (BuildConfig.FLYME_AUTO) FlymeArtistScreen(
                    state = artistState,
                    firstFocus = artistFirstFocus,
                    onBack = ::closeArtist,
                    onLoadMoreLibrary = artistBrowser::loadMore,
                    onOpenAlbum = browser::open,
                    onOpenTrack = openArtistItem,
                ) else LibraryArtistScreen(state = artistState, currentTrack = currentTrack,
                    firstFocus = artistFirstFocus, onBack = ::closeArtist,
                    onRetry = artistBrowser::retry, onLoadMore = artistBrowser::loadMore,
                    onOpenAlbum = browser::open,
                    onOpenTrack = openArtistItem)
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
                            onBack = ::closeCollection,
                            state = collectionState,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            firstFocus = detailFirstFocus,
                            onRetry = browser::retry,
                            onPlay = { index, shuffle ->
                                onPlayCollection(collectionState.playbackCollection ?: collection,
                                    collectionState.tracks, index, shuffle)
                            },
                            onFullAlbum = browser::setFullAlbum,
                            onOpenArtist = { openArtistOf(collection) },
                        )
                    }
                }
            }
        }
        // Album and artist pages cover the top bar; keep the mini player where it always is.
        if (BuildConfig.FLYME_AUTO && detailVisible && hasCurrentTrack && isBrowseVisible) {
            val capsuleFocus = remember { Destination.entries.associateWith { FocusRequester() } }
            FlymeTopBar(
                selected = destination,
                hasCurrentTrack = true,
                currentTrack = currentTrack,
                nowPlayingSubtitle = nowPlayingSubtitle,
                isPlaying = playWhenReady,
                onTogglePlayback = onTogglePlayback,
                onDestination = onDestination,
                focusRequesters = capsuleFocus,
                downFocus = { firstContentFocus },
                onMiniPlayerPlaced = onMiniPlayerPlaced,
                capsuleOnly = true,
            )
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
                .background(FlymeInk.copy(alpha = 0.055f))
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
                    background = if (isSelected) FlymeInk.copy(alpha = 0.9f)
                    else Color.Transparent,
                    focusedBackground = if (isSelected) FlymeInk.copy(alpha = 0.96f)
                    else FlymeInk.copy(alpha = 0.13f),
                ) { focused ->
                    Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                        val tint = if (isSelected) FlymeOnInk
                        else if (focused) FlymeInk else FlymeInk.copy(alpha = 0.65f)
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
                .background(FlymeInk.copy(alpha = 0.055f))
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
                    background = if (isSelected) FlymeInk.copy(alpha = 0.9f)
                    else Color.Transparent,
                    focusedBackground = if (isSelected) FlymeInk.copy(alpha = 0.96f)
                    else FlymeInk.copy(alpha = 0.13f),
                ) { focused ->
                    Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                        val tint = if (isSelected) FlymeOnInk
                        else if (focused) FlymeInk else FlymeInk.copy(alpha = 0.65f)
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

private const val STATION_HISTORY_LIMIT = 50

/** Station history stays on the head unit. */
private fun loadStationHistory(context: android.content.Context): List<Track> = runCatching {
    val raw = context.getSharedPreferences("am_atv_station_history", android.content.Context.MODE_PRIVATE)
        .getString("songs", null) ?: return emptyList()
    val array = org.json.JSONArray(raw)
    List(array.length()) { array.getJSONObject(it).toSessionTrack() }
}.getOrDefault(emptyList())

private fun saveStationHistory(context: android.content.Context, songs: List<Track>) {
    val array = org.json.JSONArray().apply { songs.forEach { put(it.toSessionJson()) } }
    context.getSharedPreferences("am_atv_station_history", android.content.Context.MODE_PRIVATE)
        .edit().putString("songs", array.toString()).apply()
}

/**
 * One shelf load per destination and inputs. Startup recomposition can restart the loading effect
 * with unchanged inputs; the restarted effect awaits the running load instead of requesting every
 * Apple Music endpoint again. A load for the same destination with other inputs is superseded.
 */
private class ShelfLoads(private val scope: CoroutineScope) {
    private val running = mutableMapOf<List<Any?>, Deferred<Pair<List<ContentShelf>, List<Throwable>>>>()

    suspend fun load(
        key: List<Any?>,
        block: suspend () -> Pair<List<ContentShelf>, List<Throwable>>,
    ): Pair<List<ContentShelf>, List<Throwable>> {
        val deferred = running[key]?.takeIf { it.isActive } ?: run {
            running.keys.filter { it.first() == key.first() && it != key }.forEach { running.remove(it)?.cancel() }
            scope.async { block() }.also { running[key] = it }
        }
        return try {
            deferred.await()
        } finally {
            if (!deferred.isActive && running[key] === deferred) running.remove(key)
        }
    }
}
