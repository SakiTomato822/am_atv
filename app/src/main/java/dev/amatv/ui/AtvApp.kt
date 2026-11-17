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
import dev.amatv.core.LibraryNotice
import dev.amatv.core.LibraryShelfPagination
import dev.amatv.core.LyricsLoadPhase
import dev.amatv.core.LyricsState
import dev.amatv.core.PlaybackEvent
import dev.amatv.core.PlaybackStore
import dev.amatv.core.StartupQueueAction
import dev.amatv.core.lyricsCacheKey
import dev.amatv.core.resolvedCacheEntries
import dev.amatv.LocalHostWindow
import dev.amatv.WindowHosts
import dev.amatv.integration.toSessionTrack
import dev.amatv.integration.toSessionJson
import dev.amatv.i18n.AppText
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.integration.AuthLaunchGate
import dev.amatv.integration.retryCatalogRequest
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.requestLyricsWithRetry
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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


@Composable
fun AmAtvApp() {
    val context = LocalContext.current
    val contentLanguageTag = LocalConfiguration.current.locales.toLanguageTags()
    val lyricsLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val scope = rememberCoroutineScope()
    val services = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).musicServices }
    val cacheSettings by services.contentCache.settings.collectAsState()
    val playbackPrefs = remember(context) { PlaybackPreferences(context) }
    val settingsStore = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).settingsStore }
    val settingsState by settingsStore.state.collectAsState()
    val libraryActions = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).libraryActions }
    val libraryState by libraryActions.state.collectAsState()
    val lyricsStore = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).lyricsStore }
    val lyricsState by lyricsStore.state.collectAsState()
    val accountStore = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).accountStore }
    val accountState by accountStore.state.collectAsState()
    val connectionState = accountState.connectionState
    val contentStore = remember(context) { (context.applicationContext as dev.amatv.AmAtvApplication).contentStore }
    val contentState by contentStore.state.collectAsState()
    val shelvesByDestination = contentState.shelvesByDestination
    val loadingDestinations = contentState.loadingDestinations
    val searchResults = contentState.searchResults
    val searchLoading = contentState.searchLoading
    val recentTracks = contentState.recentTracks
    // Full screen or the Flyme small window; see dev.amatv.HostWindow.
    val hostWindow = LocalHostWindow.current
    val playbackStore = remember(context, services, playbackPrefs) {
        PlaybackStore(
            player = services,
            sessions = playbackPrefs,
            accountStore = accountStore,
            settingsStore = settingsStore,
            lyricsStore = lyricsStore,
            libraryActions = libraryActions,
            coroutineScope = scope,
            clock = android.os.SystemClock::elapsedRealtime,
            initialPlayWhenReady = hostWindow?.resume == true,
        )
    }
    DisposableEffect(playbackStore) {
        playbackStore.attach()
        onDispose { playbackStore.dispose() }
    }
    val playbackState by playbackStore.state.collectAsState()
    val queueState by playbackStore.queueController.state.collectAsState()
    val timelineEntries = remember(queueState.history, queueState.current, queueState.upNext, queueState.autoplay) {
        queueState.timelineEntries
    }
    val timelineTracks = remember(timelineEntries) { timelineEntries.map { it.track } }
    val timelineEntryIds = remember(timelineEntries) { timelineEntries.map { it.entryId } }

    var destination by remember {
        mutableStateOf(playbackStore.startupCheckpoint?.destination ?: playbackPrefs.activeTab)
    }
    var previousDestination by remember {
        mutableStateOf(playbackStore.startupCheckpoint?.previousDestination ?: playbackPrefs.activeTab)
    }
    val track = playbackState.track
    val hasCurrentTrack = playbackState.hasCurrentTrack
    val positionAnchorMs = playbackState.positionAnchorMs
    val playStartedRealtimeMs = playbackState.playStartedRealtimeMs
    val isPlaying = playbackState.isPlaying
    val playWhenReady = playbackState.playWhenReady
    val playbackMessage = playbackState.playbackMessage
    val stationHistory = playbackState.stationHistory
    val startupSessionLoaded = playbackState.startupSessionLoaded

    val currentView = androidx.compose.ui.platform.LocalView.current
    val shouldKeepScreenOn = !BuildConfig.FLYME_AUTO && (isPlaying || playWhenReady)
    DisposableEffect(shouldKeepScreenOn) {
        currentView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            currentView.keepScreenOn = false
        }
    }
    var serviceMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contentStore) {
        contentStore.messages.collect { serviceMessage = it }
    }
    LaunchedEffect(playbackStore, destination, previousDestination) {
        playbackStore.setNavigation(destination, previousDestination)
    }
    // A short Now Playing notice (favorites); playback snapshots clear playbackMessage too often.
    var playerNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playerNotice) {
        if (playerNotice != null) {
            delay(3_000L)
            playerNotice = null
        }
    }
    LaunchedEffect(libraryState.notice) {
        when (libraryState.notice) {
            is LibraryNotice.FavoriteRemoveUnsupported -> {
                playerNotice = AppText.text(R.string.player_favorite_remove_unsupported)
                libraryActions.dismissNotice()
            }
            is LibraryNotice.FavoriteFailed -> {
                playerNotice = AppText.text(R.string.player_favorite_failed)
                libraryActions.dismissNotice()
            }
            is LibraryNotice.RemoveFromLibraryUnsupported -> {
                libraryActions.dismissNotice()
            }
            is LibraryNotice.AddToLibraryFailed -> {
                libraryActions.dismissNotice()
            }
            is LibraryNotice.AddedToLibrary,
            is LibraryNotice.AddedToFavorites,
            null -> {
            }
        }
    }
    // An album or artist to open in the browse shell, requested from the player.
    var browseDetailRequest by remember { mutableStateOf<Track?>(null) }
    val effectiveLyricsLanguageTag = settingsState.lyricTranslationLanguageTag.ifBlank { lyricsLanguageTag }
    val lyricsLocalization = remember(
        effectiveLyricsLanguageTag, settingsState.lyricPronunciationLanguageTag,
        settingsState.lyricTranslationEnabled, settingsState.lyricPronunciationEnabled,
    ) {
        LyricsLocalizationRequest(
            languageTag = effectiveLyricsLanguageTag,
            pronunciationLanguageTag = settingsState.lyricPronunciationLanguageTag,
            includeTranslations = settingsState.lyricTranslationEnabled,
            includePronunciations = settingsState.lyricPronunciationEnabled,
        )
    }
    val lyricsVariant = remember(lyricsLocalization) {
        "${lyricsLocalization.languageTag}|trans=${lyricsLocalization.includeTranslations}" +
            "|pron=${lyricsLocalization.includePronunciations}" +
            "|pronLang=${lyricsLocalization.pronunciationLanguageTag}"
    }
    val authLaunchGate = remember { AuthLaunchGate() }
    var spectrumPermissionAsked by remember { mutableStateOf(false) }
    var spectrumPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val spectrumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        spectrumPermissionGranted = it
    }

    fun refreshAllContent() {
        accountStore.syncConnectionState()
        contentStore.refreshAll(destination)
        serviceMessage = null
        lyricsStore.onCacheCleared()
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
            accountStore.completeSignIn(data)
                .onSuccess {
                    playbackStore.onSignedIn()
                    lyricsStore.onCacheCleared()
                    contentStore.onSignedIn()
                    destination = Destination.Home
                    serviceMessage = null
                }
                .onFailure { serviceMessage = it.message ?: AppText.text(R.string.app_sign_in_failed) }
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

    LaunchedEffect(playbackStore) {
        playbackStore.events.collect { event ->
            when (event) {
                is PlaybackEvent.Message -> serviceMessage = event.text
                PlaybackEvent.SignInRequired -> startSignIn()
                PlaybackEvent.ShowNowPlaying -> {
                    if (destination != Destination.NowPlaying) previousDestination = destination
                    destination = Destination.NowPlaying
                }
            }
        }
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

    fun showNowPlaying() {
        if (!hasCurrentTrack) {
            val firstCandidate = shelvesByDestination[destination]?.firstOrNull()?.items?.firstOrNull()
                ?: shelvesByDestination[Destination.Home]?.firstOrNull()?.items?.firstOrNull()
            if (firstCandidate != null) {
                playbackStore.playTrack(firstCandidate)
            } else {
                serviceMessage = AppText.text(R.string.app_choose_content_before_playback)
            }
            return
        }
        if (destination != Destination.NowPlaying) previousDestination = destination
        destination = Destination.NowPlaying
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playbackStore.saveSession(true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        hostWindow?.saveNow = { playbackStore.saveSession(true) }
        onDispose {
            hostWindow?.saveNow = null
            playbackStore.saveSession(true)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(playWhenReady) { if (hostWindow != null) WindowHosts.playing = playWhenReady }

    // Lyrics use the account API, not the native audio engine. Wait for the full
    // session (including deferred queue selection) before resolving the current
    // song; lyricsFor then awaits the account storefront and bearer internally.
    val lyricsRequestReady = startupSessionLoaded && hasCurrentTrack &&
        connectionState == MusicConnectionState.SignedIn
    val currentLyricsIdentity = track.lyricsIdentity()
    val currentLyricsLines = if (lyricsState.trackIdentity == currentLyricsIdentity) {
        lyricsState.lines
    } else {
        track.lyrics
    }

    val prefetchTargets = remember(queueState.current?.entryId, queueState.upNext, queueState.autoplay) {
        (queueState.upNext + queueState.autoplay).take(2).map { it.track }
    }
    LaunchedEffect(
        prefetchTargets, isPlaying, cacheSettings, lyricsRequestReady, lyricsVariant,
    ) {
        if (!lyricsRequestReady) return@LaunchedEffect
        lyricsStore.setPrefetchTargets(prefetchTargets, isPlaying)
    }
    LaunchedEffect(cacheSettings.revision) {
        lyricsStore.onCacheCleared()
    }

    LaunchedEffect(
        currentLyricsIdentity,
        track.catalogKind,
        lyricsRequestReady,
        contentState.reloadGeneration,
        cacheSettings.revision,
        lyricsVariant,
    ) {
        lyricsStore.setCurrentTrack(
            track = track.takeIf { lyricsRequestReady },
            isSignedIn = connectionState == MusicConnectionState.SignedIn,
            variant = lyricsVariant,
        )
    }

    LaunchedEffect(destination, connectionState, contentState.reloadGeneration, contentLanguageTag) {
        contentStore.loadDestination(
            destination = destination,
            connectionState = connectionState,
            reloadGeneration = contentState.reloadGeneration,
            contentLanguageTag = contentLanguageTag,
        )
    }

    LaunchedEffect(destination) {
        if (destination == Destination.Search) {
            contentStore.ensureDefaultSearch()
        }
    }

    val stationQueueContext = queueState.current?.track?.catalogKind == CatalogKind.Station
    val stationTimeline = if (BuildConfig.FLYME_AUTO && stationQueueContext) {
        if (stationHistory.lastOrNull()?.catalogId == track.catalogId) stationHistory else stationHistory + track
    } else emptyList()
    val activePlaybackSnapshot = playbackState.snapshot
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
                            playbackStore.togglePlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            playbackStore.requestPlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            playbackStore.pausePlayback()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            playbackStore.skip(1)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            playbackStore.skip(-1)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            playbackStore.seekPlaybackBy(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            playbackStore.seekPlaybackBy(10_000L)
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
                        onTogglePlayback = playbackStore::togglePlayback,
                        searchTracks = searchResults,
                        searchLoading = searchLoading,
                        recentTracks = recentTracks,
                        shelves = shelvesByDestination[activeBrowseDestination].orEmpty(),
                        shelfPagination = contentState.shelfPagination,
                        onLoadMoreShelf = { shelfId -> contentStore.loadMoreLibraryShelf(shelfId, connectionState) },
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
                        onSignOut = {
                            contentStore.resetLibraryPagination()
                            playbackStore.onSignedOut()
                            accountStore.signOut()
                            playbackPrefs.clearSession()
                            scope.launch { cancellableResult { services.contentCache.clear { ArtworkImages.clear(context) } } }
                            lyricsStore.onCacheCleared()
                            contentStore.onSignedOut()
                            serviceMessage = AppText.text(R.string.app_apple_signed_out)
                        },
                        onRetry = (::refreshAllContent),
                        onSearch = contentStore::search,
                        onOpenTrack = { item, q -> playbackStore.playTrack(item, contextQueue = q) },
                        loadCollection = services::collectionTracks,
                        cachedCollection = services::cachedCollectionTracks,
                        savedCollection = services::savedCollectionTracks,
                        collectionPartials = services::collectionPartials,
                        loadArtistAlbums = services::libraryArtistAlbumsPage,
                        cachedArtistAlbums = services::cachedArtistAlbums,
                        savedArtistAlbums = services::savedArtistAlbums,
                        loadArtistCatalog = services::catalogArtistPage,
                        cachedArtistCatalog = services::cachedArtistPage,
                        savedArtistCatalog = services::savedArtistPage,
                        loadArtistViewMore = services::catalogArtistViewMore,
                        onPlayCollection = playbackStore::playCollection,
                        lyricOffsetMs = settingsState.lyricOffsetMs,
                        onLyricOffsetChange = settingsStore::setLyricOffsetMs,
                        lowPerformanceMode = settingsState.lowPerformanceMode,
                        onLowPerformanceModeChange = { settingsStore.lowPerformanceMode = it },
                        cacheSettingsContent = {
                            LanguageSettings()
                            FlymeMediaSettings(services)
                            if (BuildConfig.FLYME_AUTO) FlymeAppearanceSettings(settingsStore)
                            if (BuildConfig.FLYME_AUTO) FlymePlaybackOptionsSettings(settingsStore)
                            if (BuildConfig.FLYME_AUTO) FlymePlaybackDiagnostics(services.playbackDiagnostics)
                            ContentCacheSettings(services.contentCache, settingsStore)
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
                        lyricsPreferred = settingsState.lyricsPreferred,
                        onLyricsPreferredChange = settingsStore::setLyricsPreferred,
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
                        onClearQueue = {
                            if (!stationQueueContext) playbackStore.dispatchQueueAction(StartupQueueAction.ClearUpcoming)
                        },
                        lyricsLoading = lyricsState.trackIdentity == currentLyricsIdentity &&
                            lyricsState.isLoading,
                        lyricsLookupFailed = lyricsState.trackIdentity == currentLyricsIdentity &&
                            lyricsState.isLookupFailed,
                        lyricsTransientFailure = lyricsState.trackIdentity == currentLyricsIdentity &&
                            lyricsState.isTransientFailure,
                        onRetryLyrics = playbackStore::retryCurrentLyrics,
                        lyricOffsetMs = settingsState.lyricOffsetMs,
                        statusMessage = playerNotice ?: playbackMessage ?: if (queueState.awaitingNext) {
                            (queueState.autoplayStatus as? dev.amatv.playback.AutoplayStatus.Error)?.message
                        } else null,
                        onTogglePlayback = playbackStore::togglePlayback,
                        onSkipPrevious = { playbackStore.skip(-1) },
                        onSkipNext = { playbackStore.skip(1) },
                        onSeekBy = playbackStore::seekPlaybackBy,
                        canSeek = canSeekPlayback,
                        isLiveStream = isLiveStream,
                        stationName = stationName.takeIf { !isLiveStream && track.catalogKind != CatalogKind.Station },
                        onSelectTrack = { index ->
                            // A station's played song plays on its own, leaving the station.
                            if (stationTimeline.isNotEmpty()) {
                                stationTimeline.getOrNull(index)?.takeIf { index != stationTimeline.lastIndex }
                                    ?.let { song -> playbackStore.playTrack(song, listOf(song)) }
                            } else playbackStore.selectQueueTrack(index)
                        },
                        onBack = { destination = previousDestination },
                        onOpenArtist = { openFromPlayer(services::catalogArtistOf) },
                        isFavorite = libraryState.current.isFavorite
                            ?.takeIf { BuildConfig.FLYME_AUTO }
                            ?.takeIf { libraryState.current.catalogId == track.catalogId },
                        onToggleFavorite = libraryActions::toggleFavorite,
                        onOpenAlbum = { openFromPlayer(services::catalogAlbumOf) },
                        lyricTextSize = settingsState.lyricTextSize,
                        onLyricTextSizeChange = settingsStore::setLyricTextSize,
                        lowPerformanceMode = settingsState.lowPerformanceMode,
                        lyricTranslationEnabled = settingsState.lyricTranslationEnabled,
                        lyricPronunciationEnabled = settingsState.lyricPronunciationEnabled,
                        lyricTranslationLanguageTag = settingsState.lyricTranslationLanguageTag,
                        lyricPronunciationLanguageTag = settingsState.lyricPronunciationLanguageTag,
                        appLanguageTag = lyricsLanguageTag,
                        lyricsEnhancementsReady = !lyricsLocalization.enabled ||
                            (lyricsState.trackIdentity == currentLyricsIdentity &&
                                lyricsState.resolvedVariant == lyricsVariant &&
                                lyricsState.isReady),
                        onLyricTranslationEnabledChange = settingsStore::setLyricTranslationEnabled,
                        onLyricPronunciationEnabledChange = settingsStore::setLyricPronunciationEnabled,
                        onLyricTranslationLanguageChange = settingsStore::setLyricTranslationLanguageTag,
                        onLyricPronunciationLanguageChange = settingsStore::setLyricPronunciationLanguageTag,
                        onCycleRepeat = {
                            if (!stationQueueContext) playbackStore.dispatchQueueAction(StartupQueueAction.CycleRepeat)
                        },
                        onToggleShuffle = {
                            if (!stationQueueContext) playbackStore.dispatchQueueAction(StartupQueueAction.ToggleShuffle)
                        },
                        onToggleAutoplay = {
                            if (!stationQueueContext) playbackStore.dispatchQueueAction(StartupQueueAction.ToggleInfinite)
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
            }
            }
        }
    }
}

@Composable
private fun BrowseShell(
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
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    loadCollection: suspend (Track) -> List<Track>,
    cachedCollection: (Track) -> List<Track>? = { null },
    savedCollection: suspend (Track) -> List<Track>?,
    collectionPartials: (Track) -> kotlinx.coroutines.flow.Flow<List<Track>?>,
    loadArtistAlbums: suspend (Track, String?) -> LibraryPage,
    cachedArtistAlbums: (Track) -> LibraryPage? = { null },
    savedArtistAlbums: suspend (Track) -> List<Track>?,
    loadArtistCatalog: suspend (Track) -> dev.amatv.integration.CatalogArtistPage = { dev.amatv.integration.CatalogArtistPage() },
    cachedArtistCatalog: (Track) -> dev.amatv.integration.CatalogArtistPage? = { null },
    savedArtistCatalog: suspend (Track) -> dev.amatv.integration.CatalogArtistPage?,
    loadArtistViewMore: suspend (String) -> List<Track> = { emptyList() },
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    detailRequest: Track? = null,
    onDetailRequestHandled: () -> Unit = {},
    loadArtistOf: suspend (Track) -> Track? = { null },
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
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
    val currentCachedCollection by rememberUpdatedState(cachedCollection)
    val currentSavedCollection by rememberUpdatedState(savedCollection)
    val currentCollectionPartials by rememberUpdatedState(collectionPartials)
    val browser = remember(destination) {
        CollectionBrowser(scope, { currentCachedCollection(it) }, { currentSavedCollection(it) },
            { currentCollectionPartials(it) }) { currentLoader(it) }
    }
    val collectionState by browser.state.collectAsState()
    val collection = collectionState.collection
    val currentArtistLoader by rememberUpdatedState(loadArtistAlbums)
    val currentArtistCatalogLoader by rememberUpdatedState(loadArtistCatalog)
    val currentCachedArtistCatalog by rememberUpdatedState(cachedArtistCatalog)
    val currentCachedArtistAlbums by rememberUpdatedState(cachedArtistAlbums)
    val currentSavedArtistCatalog by rememberUpdatedState(savedArtistCatalog)
    val currentSavedArtistAlbums by rememberUpdatedState(savedArtistAlbums)
    val artistBrowser = remember(destination) {
        LibraryArtistBrowser(scope, { currentArtistCatalogLoader(it) },
            { currentCachedArtistCatalog(it) }, { currentCachedArtistAlbums(it) },
            { currentSavedArtistCatalog(it) }, { currentSavedArtistAlbums(it) }) { item, next ->
            currentArtistLoader(item, next)
        }
    }
    val artistState by artistBrowser.state.collectAsState()
    val artist = artistState.artist
    val artistFirstFocus = remember(destination) { FocusRequester() }
    val detailVisible = collection != null || artist != null
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
                    .then(if (BuildConfig.FLYME_AUTO) rememberMetroPivotSwipe(destination, onDestination) else Modifier)
                    .fillMaxSize()
                    .padding(
                        start = if (BuildConfig.FLYME_AUTO) flymePageInset(LocalCarPortrait.current) else 40.dp,
                        end = if (BuildConfig.FLYME_AUTO) flymePageInset(LocalCarPortrait.current) else 40.dp,
                        top = 20.dp,
                    ),
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
                    loadShelfMore = loadArtistViewMore,
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
