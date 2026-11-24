package dev.amatv.flyme

import android.app.Activity
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.amatv.AmAtvApplication
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.core.PlaybackEvent
import dev.amatv.core.shouldShowWelcome
import dev.amatv.i18n.AppText
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.integration.FlymeVoiceDestination
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicKitWebAuthActivity
import dev.amatv.integration.PlaybackPreferences
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.openAction
import dev.amatv.artwork.ArtworkImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class FolioRootPage(val index: Int, val labelRes: Int, val destination: Destination?) {
    Settings(0, R.string.folio_tab_settings, null),
    Home(1, R.string.nav_home, Destination.Home),
    Browse(2, R.string.nav_browse, Destination.Browse),
    Library(3, R.string.nav_library, Destination.Library),
    Search(4, R.string.nav_search, Destination.Search),
}

/**
 * Process-scoped, non-persisted page cursor for the new UI: cold launch starts at Listen Now
 * (`Home`), while full-screen <-> small-window activity recreation keeps the active page.
 */
internal object FolioUiSession {
    val HOME_PAGE_INDEX: Int = FolioRootPage.Home.index

    @Volatile
    var currentPage: Int = HOME_PAGE_INDEX
        private set

    fun initialPage(): Int = currentPage

    fun onPageChanged(page: Int) {
        currentPage = page.coerceIn(0, FolioRootPage.entries.lastIndex)
    }

    fun reset() {
        currentPage = HOME_PAGE_INDEX
    }
}

/**
 * Root composable for AM Folio 15 responsive UI (`dev.amatv.flyme`).
 * Reads state from and dispatches actions directly to `dev.amatv.core` stores without scaling `CarLayout`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolioApp(
    onLightThemeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as AmAtvApplication }
    val contentLanguageTag = LocalConfiguration.current.locales.toLanguageTags()
    val scope = rememberCoroutineScope()

    val services = app.musicServices
    val settingsStore = app.settingsStore
    val welcomePrefs = app.welcomePreferences
    val accountStore = app.accountStore
    val contentStore = app.contentStore
    val lyricsStore = app.lyricsStore
    val playHistoryStore = app.playHistoryStore
    val playbackStore = app.playbackStore

    val settingsState by settingsStore.state.collectAsState()
    val accountState by accountStore.state.collectAsState()
    val contentState by contentStore.state.collectAsState()
    val playHistoryEntries by playHistoryStore.entries.collectAsState()
    val playbackState by playbackStore.state.collectAsState()
    val queueState by playbackStore.queueController.state.collectAsState()
    val isLight = rememberFolioIsLight(settingsState)
    val browseMotion = rememberFolioBrowseMotion(settingsState.lowPerformance)

    LaunchedEffect(isLight) {
        onLightThemeChanged(isLight)
    }

    val currentView = LocalView.current
    val keepScreenOn = !BuildConfig.FLYME_AUTO && (playbackState.isPlaying || playbackState.playWhenReady)
    DisposableEffect(keepScreenOn) {
        currentView.keepScreenOn = keepScreenOn
        onDispose { currentView.keepScreenOn = false }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playbackStore.saveSession(true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // B1b: the player sheet. B2: the detail stack (albums, playlists, artists). Both are UI state only.
    var welcomeOpen by remember { mutableStateOf(shouldShowWelcome(welcomePrefs.welcomeVersion)) }
    var playerOpen by remember { FolioPlayerSession.openState }
    var detailStack by FolioDetailSession.stack
    var playHistoryOpen by remember { mutableStateOf(false) }
    val browseNavigation = remember { FolioBrowseNavigation() }
    DisposableEffect(browseNavigation) { onDispose { browseNavigation.root = null } }
    var playerOrigin by remember { mutableStateOf<FolioArtworkOrigin?>(null) }
    val openPlayer = {
        playerOrigin = browseNavigation.clickedArtwork
        playerOpen = true
    }

    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastDurationMs by remember { mutableLongStateOf(1_700L) }
    var serviceMessage by remember { mutableStateOf<String?>(null) }

    fun showToast(message: String) {
        toastDurationMs = 1_700L
        toastMessage = message
    }

    LaunchedEffect(toastMessage, toastDurationMs) {
        if (toastMessage != null) {
            delay(toastDurationMs)
            toastMessage = null
        }
    }

    val lowPerfHintText = stringResource(R.string.welcome_hint_low_perf)
    LaunchedEffect(welcomeOpen, settingsState.lowPerformanceMode, settingsState.autoLowPerformanceReason) {
        if (!welcomeOpen && shouldTriggerAutoLowPerfHint(settingsState, welcomePrefs.hintLowPerfShown)) {
            welcomePrefs.hintLowPerfShown = true
            toastDurationMs = 3_000L
            toastMessage = lowPerfHintText
        }
    }

    LaunchedEffect(contentStore) {
        contentStore.messages.collect { msg ->
            if (msg != null) serviceMessage = msg
        }
    }

    val initialPage = remember { FolioUiSession.initialPage() }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { FolioRootPage.entries.size },
    )
    var titleTransition by remember { mutableStateOf<FolioBrowseTabTransition?>(null) }
    val selectedPage = titleTransition?.to ?: pagerState.currentPage

    LaunchedEffect(app, pagerState) {
        app.voiceDestination.collect { request ->
            if (request == null) return@collect
            when (request) {
                is FlymeVoiceDestination.NowPlaying -> {
                    if (request.lyrics) {
                        FolioPlayerSession.queueOpenState.value = false
                        settingsStore.setLyricStyle(LyricBackgroundStyle.Flow)
                    }
                    playerOrigin = null
                    playerOpen = true
                }
                is FlymeVoiceDestination.Search -> {
                    playerOpen = false
                    detailStack = emptyList()
                    playHistoryOpen = false
                    pagerState.scrollToPage(FolioRootPage.Search.index)
                    folioSearchSession.submit(request.query, services::search)
                }
            }
            app.voiceDestination.compareAndSet(request, null)
        }
    }

    fun openDetail(item: Track) {
        playHistoryOpen = false
        val origin = browseNavigation.clickedArtwork
        val bounds = if (browseMotion.spatial) origin?.boundsIn(browseNavigation, selectedPage) else null
        detailStack = detailStack + FolioDetailEntry(item, origin, bounds)
    }

    fun closeDetail() {
        val entry = detailStack.last()
        val header = entry.header
        entry.returnBounds = if (browseMotion.spatial && header != null && header.isAttached &&
            !header.boundsInWindow().isEmpty
        ) {
            entry.origin?.boundsIn(browseNavigation, selectedPage)
        } else null
        detailStack = popFolioDetail(detailStack)
    }

    fun selectTab(index: Int) {
        if (titleTransition != null || index == pagerState.currentPage) return
        if (!browseMotion.animated) {
            pagerState.requestScrollToPage(index)
            return
        }
        val transition = FolioBrowseTabTransition(pagerState.currentPage, index)
        titleTransition = transition
        scope.launch {
            transition.progress.animateTo(1f, tween(180, easing = FolioMotion.PageEasing))
            pagerState.scrollToPage(index)
            titleTransition = null
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        FolioUiSession.onPageChanged(pagerState.currentPage)
    }
    var rootSwipeCount by remember { mutableIntStateOf(0) }
    var showPivotScrubHint by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, welcomeOpen) {
        var settledPage = pagerState.currentPage
        var sawUserDrag = false
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .collect { (scrolling, curPage) ->
                if (scrolling && titleTransition == null && !welcomeOpen) {
                    sawUserDrag = true
                } else if (!scrolling) {
                    if (sawUserDrag && curPage != settledPage) {
                        rootSwipeCount++
                        if (shouldShowPivotScrubHint(rootSwipeCount, welcomePrefs.hintPivotScrubShown)) {
                            welcomePrefs.hintPivotScrubShown = true
                            showPivotScrubHint = true
                        }
                    }
                    settledPage = curPage
                    sawUserDrag = false
                }
            }
    }
    LaunchedEffect(showPivotScrubHint) {
        if (showPivotScrubHint) {
            delay(4_000L)
            showPivotScrubHint = false
        }
    }

    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val attempt = data.getStringExtra(MusicKitWebAuthActivity.EXTRA_ATTEMPT)
            ?: return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            if (!accountStore.cancelSignIn(attempt)) return@rememberLauncherForActivityResult
            val err = data.getStringExtra(MusicKitWebAuthActivity.EXTRA_ERROR)
                ?: AppText.text(R.string.app_apple_sign_in_cancelled)
            serviceMessage = err
            showToast(err)
        } else {
            val completion = accountStore.completeSignIn(attempt, data)
                ?: return@rememberLauncherForActivityResult
            completion.onSuccess {
                    playbackStore.onSignedIn()
                    folioSearchSession.invalidateStorefront()
                    lyricsStore.onCacheCleared()
                    contentStore.onSignedIn()
                    serviceMessage = null
                    if (!welcomeOpen) selectTab(FolioRootPage.Home.index)
                }
                .onFailure {
                    val err = it.message ?: AppText.text(R.string.app_sign_in_failed)
                    serviceMessage = err
                    showToast(err)
                }
        }
    }

    fun startSignIn() {
        if (accountState.connectionState == MusicConnectionState.Demo) {
            services.setupMessage?.let {
                serviceMessage = it
                showToast(it)
            }
            selectTab(FolioRootPage.Settings.index)
            return
        }
        val attempt = accountStore.beginSignIn() ?: return
        scope.launch {
            try {
                val intent = services.webAuthenticationIntent(attempt)
                if (accountStore.isSignInCurrent(attempt)) webAuthenticationLauncher.launch(intent)
            } catch (cancelled: CancellationException) {
                accountStore.cancelSignIn(attempt)
                throw cancelled
            } catch (error: Exception) {
                if (!accountStore.cancelSignIn(attempt)) return@launch
                val msg = error.message ?: AppText.text(R.string.app_apple_sign_in_unavailable)
                serviceMessage = msg
                showToast(msg)
            }
        }
    }

    fun refreshAll() {
        accountStore.syncConnectionState()
        val activeDest = FolioRootPage.entries[pagerState.currentPage].destination ?: Destination.Home
        contentStore.refreshAll(activeDest)
        serviceMessage = null
        lyricsStore.onCacheCleared()
    }

    fun signOut() {
        contentStore.resetLibraryPagination()
        playbackStore.onSignedOut()
        folioSearchSession.invalidateStorefront()
        playHistoryStore.clear()
        PlaybackPreferences(context).clearSession()
        scope.launch {
            services.contentCache.clear { ArtworkImages.clear(context) }
        }
        lyricsStore.onCacheCleared()
        contentStore.onSignedOut()
        serviceMessage = AppText.text(R.string.app_apple_signed_out)
    }

    LaunchedEffect(playbackStore) {
        playbackStore.events.collect { event ->
            when (event) {
                is PlaybackEvent.Message -> event.text?.let {
                    serviceMessage = it
                    showToast(it)
                }
                PlaybackEvent.SignInRequired -> startSignIn()
                PlaybackEvent.ShowNowPlaying -> openPlayer()
            }
        }
    }

    val currentRootPage = FolioRootPage.entries[selectedPage]
    val activeDestination = currentRootPage.destination
    LaunchedEffect(activeDestination) {
        if (activeDestination != null) {
            playbackStore.setNavigation(activeDestination, activeDestination)
        }
    }

    LaunchedEffect(
        activeDestination,
        accountState.connectionState,
        contentState.reloadGeneration,
        contentLanguageTag,
    ) {
        val target = activeDestination ?: return@LaunchedEffect
        contentStore.loadDestination(
            destination = target,
            connectionState = accountState.connectionState,
            reloadGeneration = contentState.reloadGeneration,
            contentLanguageTag = contentLanguageTag,
        )
    }

    val currentQueueIsStation = queueState.current?.track?.catalogKind == dev.amatv.model.CatalogKind.Station
    val miniSubtitle = when {
        playbackState.snapshot?.isLiveStream == true && currentQueueIsStation -> stringResource(R.string.player_live_stream)
        currentQueueIsStation && playbackState.track.catalogKind != dev.amatv.model.CatalogKind.Station ->
            queueState.current?.track?.title
        else -> null
    }

    fun openItem(item: Track, shelfItems: List<Track>) {
        when (item.openAction) {
            CatalogOpenAction.Play -> {
                playbackStore.playTrack(
                    item,
                    contextQueue = shelfItems.filter { it.openAction == CatalogOpenAction.Play },
                )
            }
            CatalogOpenAction.Station -> {
                playbackStore.playTrack(item, contextQueue = listOf(item))
            }
            CatalogOpenAction.Collection -> openDetail(item)
            CatalogOpenAction.OpenArtist -> openDetail(item)
        }
    }

    BackHandler(enabled = pagerState.currentPage != FolioRootPage.Home.index) {
        selectTab(FolioRootPage.Home.index)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_UP) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { playbackStore.togglePlayback(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { playbackStore.requestPlayback(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { playbackStore.pausePlayback(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { playbackStore.skip(1); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playbackStore.skip(-1); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { playbackStore.seekPlaybackBy(-10_000L); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playbackStore.seekPlaybackBy(10_000L); true }
                    else -> false
                }
            },
    ) {
        val systemDensity = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val windowWidth = constraints.maxWidth
        val windowHeight = constraints.maxHeight
        val renderDensity = folioRenderDensity(systemDensity.density)
        val (layoutWidthDp, layoutHeightDp) = folioLayoutDp(
            windowWidthPx = windowWidth.toFloat(),
            windowHeightPx = windowHeight.toFloat(),
            systemDensity = systemDensity.density,
        )
        val tier = tierOf(layoutWidthDp, layoutHeightDp)
        val tierSpec = specForTier(tier)

        CompositionLocalProvider(
            LocalDensity provides Density(renderDensity, systemDensity.fontScale),
            LocalFolioBrowseMotion provides browseMotion,
            LocalFolioBrowseNavigation provides browseNavigation,
        ) {
            val statusTop = maxOf(
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
            )
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            FolioTheme(
                isLight = isLight,
                tierSpec = tierSpec,
                lowPerformanceMode = settingsState.lowPerformance,
            ) {
                val palette = LocalFolioPalette.current
                val pageTitles = FolioRootPage.entries.map { stringResource(it.labelRes) }
                val contentBottomPadding = navBottom + tierSpec.capsp + 28.dp
                val backdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !settingsState.lowPerformance && playbackState.hasCurrentTrack
                ) rememberLayerBackdrop() else null

                var pivotRightEdgePx by remember { mutableIntStateOf(0) }

                Box(Modifier.fillMaxSize()) {
                    FolioDetailLayer(
                        stack = detailStack,
                        navigation = browseNavigation,
                        modifier = Modifier.matchParentSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .folioAmbientBackground(palette),
                        homeShelves = contentState.shelvesFor(Destination.Home),
                        statusTop = statusTop,
                        navBottom = navBottom,
                        onOpenItem = ::openItem,
                        onBack = ::closeDetail,
                        services = services,
                        playback = playbackState,
                        onPlayCollection = playbackStore::playCollection,
                        handleBack = !playerOpen,
                    ) {
                        val scrollToTopHandlers = remember { mutableStateMapOf<Int, suspend () -> Unit>() }
                        Column(Modifier.fillMaxSize()) {
                            // Metro Pivot header anchored directly below status bar (aligns with top-right MiniPlayer on Wide/Ultrawide)
                            FolioPivotBar(
                                titles = pageTitles,
                                pageProgress = titleTransition?.let {
                                    it.from + (it.to - it.from) * it.progress.value
                                } ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction),
                                selectedPage = selectedPage,
                                onSelectPage = ::selectTab,
                                onScrollCurrentPageToTop = {
                                    scrollToTopHandlers[pagerState.currentPage]?.let { fn ->
                                        scope.launch { fn() }
                                    }
                                },
                                onRightEdgePxChanged = { rightPx ->
                                    if (pivotRightEdgePx != rightPx) pivotRightEdgePx = rightPx
                                },
                                modifier = Modifier.padding(top = statusTop + folioDetailTopMargin),
                            )

                            // Horizontal Pager for Settings / Home / Browse / Library / Search
                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = maxOf(1, titleTransition?.distance ?: 0),
                                userScrollEnabled = titleTransition == null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        val frac = abs(pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                                        alpha = if (titleTransition == null) 1f - frac * 0.35f else 1f
                                    },
                            ) { pageIdx ->
                                val switch = titleTransition
                                Box(
                                    Modifier.fillMaxSize().then(
                                        switch?.pageModifier(pageIdx, layoutDirection) {
                                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                                        } ?: Modifier,
                                    ).zIndex(if (pageIdx == selectedPage) 1f else 0f),
                                ) {
                                    CompositionLocalProvider(LocalFolioBrowseTab provides pageIdx) {
                                        val registerScrollToTop: (suspend () -> Unit) -> Unit = { fn ->
                                            scrollToTopHandlers[pageIdx] = fn
                                        }
                                        when (FolioRootPage.entries[pageIdx]) {
                                            FolioRootPage.Settings -> FolioSettingsPage(
                                                settingsStore = settingsStore,
                                                settings = settingsState,
                                                services = services,
                                                connectionState = accountState.connectionState,
                                                setupMessage = serviceMessage ?: services.setupMessage,
                                                bottomPadding = contentBottomPadding,
                                                onSignIn = ::startSignIn,
                                                onSignOut = ::signOut,
                                                onRefresh = ::refreshAll,
                                                onNotice = ::showToast,
                                                onRegisterScrollToTop = registerScrollToTop,
                                                onOpenWelcome = { welcomeOpen = true },
                                            )
                                            FolioRootPage.Home -> FolioHomePage(
                                                contentState = contentState,
                                                connectionState = accountState.connectionState,
                                                statusMessage = serviceMessage ?: services.setupMessage,
                                                bottomPadding = contentBottomPadding,
                                                onRetry = ::refreshAll,
                                                onSignIn = ::startSignIn,
                                                onOpenItem = ::openItem,
                                                onRegisterScrollToTop = registerScrollToTop,
                                            )
                                            FolioRootPage.Browse -> FolioBrowsePage(
                                                contentState = contentState,
                                                connectionState = accountState.connectionState,
                                                statusMessage = serviceMessage ?: services.setupMessage,
                                                bottomPadding = contentBottomPadding,
                                                onRetry = ::refreshAll,
                                                onSignIn = ::startSignIn,
                                                onOpenItem = ::openItem,
                                                onRegisterScrollToTop = registerScrollToTop,
                                            )
                                            FolioRootPage.Library -> FolioLibraryPage(
                                                contentState = contentState,
                                                active = activeDestination == Destination.Library,
                                                connectionState = accountState.connectionState,
                                                currentTrack = playbackState.track,
                                                isPlaying = playbackState.playWhenReady,
                                                bottomPadding = contentBottomPadding,
                                                onSignIn = ::startSignIn,
                                                onLoadMoreShelf = { shelfId ->
                                                    contentStore.loadMoreLibraryShelf(shelfId, accountState.connectionState)
                                                },
                                                onOpenItem = ::openItem,
                                                onOpenHistory = { playHistoryOpen = true },
                                                onRegisterScrollToTop = registerScrollToTop,
                                            )
                                            FolioRootPage.Search -> FolioSearchPage(
                                                currentTrack = playbackState.track,
                                                isPlaying = playbackState.playWhenReady,
                                                bottomPadding = contentBottomPadding,
                                                search = services::search,
                                                suggest = services::searchSuggestions,
                                                onOpenItem = ::openItem,
                                                recentPlayEntries = playHistoryEntries,
                                                onOpenAllHistory = { playHistoryOpen = true },
                                                onRegisterScrollToTop = registerScrollToTop,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    FolioPlayHistoryLayer(
                        open = playHistoryOpen,
                        handleBack = !playerOpen && detailStack.isEmpty(),
                        entries = playHistoryEntries,
                        currentTrack = playbackState.track,
                        isPlaying = playbackState.playWhenReady,
                        statusTop = statusTop,
                        bottomPadding = contentBottomPadding,
                        onBack = { playHistoryOpen = false },
                        onPlayTrack = ::openItem,
                        onClearHistory = { playHistoryStore.clear() },
                    )

                    CompositionLocalProvider(LocalFolioBrowseBackdrop provides backdrop) {
                        val displayTrack = playbackState.track.let {
                            if (miniSubtitle != null) it.copy(artist = miniSubtitle) else it
                        }
                        if (playbackState.hasCurrentTrack && !tierSpec.usesBottomCapsule) {
                            val pivotRightEdgeDp = if (pivotRightEdgePx > 0) {
                                pivotRightEdgePx / renderDensity
                            } else {
                                layoutWidthDp
                            }
                            val topMiniLayout = computeTopRightMiniPlayerLayout(
                                windowWidthDp = layoutWidthDp,
                                pivotRightEdgeDp = pivotRightEdgeDp,
                                endPaddingDp = tierSpec.pr.value,
                            )
                            val miniHeightDp = if (topMiniLayout.showCapsule) {
                                TOP_MINI_CAPSULE_HEIGHT_DP.dp
                            } else {
                                tierSpec.ib
                            }
                            val topMiniModifier = Modifier.align(Alignment.TopEnd)
                                .padding(
                                    top = statusTop + folioDetailTopMargin +
                                        ((tierSpec.pvh - miniHeightDp) / 2f).coerceAtLeast(0.dp),
                                    end = tierSpec.pr,
                                )
                            if (topMiniLayout.showCapsule) {
                                FolioTopMiniPlayerCapsule(
                                    track = displayTrack,
                                    isPlaying = playbackState.playWhenReady,
                                    onTogglePlayback = playbackStore::togglePlayback,
                                    onOpenPlayer = openPlayer,
                                    capsuleWidthDp = topMiniLayout.widthDp.dp,
                                    modifier = topMiniModifier,
                                )
                            } else {
                                FolioMiniPlayerDot(
                                    track = playbackState.track,
                                    onOpenPlayer = openPlayer,
                                    modifier = topMiniModifier,
                                )
                            }
                        } else if (playbackState.hasCurrentTrack) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(
                                        start = tierSpec.px,
                                        end = tierSpec.pr,
                                        bottom = navBottom + 14.dp,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                FolioMiniPlayerCapsule(
                                    track = displayTrack,
                                    isPlaying = playbackState.playWhenReady,
                                    onTogglePlayback = playbackStore::togglePlayback,
                                    onOpenPlayer = openPlayer,
                                )
                            }
                        }
                    }

                    if (playerOpen && playbackState.hasCurrentTrack && settingsState.shouldMeasurePlaybackFrames) {
                        FolioPlaybackFrameMonitor(settingsStore)
                    }
                    FolioBrowsePlayerEntrance(
                        open = playerOpen,
                        hasCurrentTrack = playbackState.hasCurrentTrack,
                        origin = playerOrigin?.boundsIn(browseNavigation, selectedPage),
                        windowWidth = windowWidth,
                        windowHeight = windowHeight,
                        statusTop = statusTop,
                        navBottom = navBottom,
                        onClose = { playerOpen = false },
                        onOpenDetail = { item ->
                            playerOpen = false
                            openDetail(item)
                        },
                    )

                    FolioHintBubble(
                        visible = showPivotScrubHint && !welcomeOpen && !playerOpen && detailStack.isEmpty(),
                        text = stringResource(R.string.welcome_hint_pivot_scrub),
                        pointerBiasX = 0.25f,
                        lowPerformance = settingsState.lowPerformance,
                        onDismiss = { showPivotScrubHint = false },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                top = statusTop + folioDetailTopMargin + tierSpec.pvh + 6.dp,
                                start = tierSpec.px,
                            ),
                    )

                    FolioWelcomeOverlay(
                        open = welcomeOpen,
                        settingsStore = settingsStore,
                        settingsState = settingsState,
                        accountState = accountState,
                        contentState = contentState,
                        playHistoryEntries = playHistoryEntries,
                        playbackState = playbackState,
                        services = services,
                        welcomePrefs = welcomePrefs,
                        statusTop = statusTop,
                        navBottom = navBottom,
                        onStartSignIn = ::startSignIn,
                        onNotice = { msg -> showToast(msg) },
                        onRequestBrowseLoad = {
                            contentStore.loadDestination(
                                destination = Destination.Browse,
                                connectionState = accountState.connectionState,
                                reloadGeneration = contentState.reloadGeneration,
                                contentLanguageTag = contentLanguageTag,
                            )
                        },
                        onClose = { welcomeOpen = false },
                    )

                    // Bottom transient toast matching `.toast` in H5
                    AnimatedVisibility(
                        visible = toastMessage != null,
                        enter = fadeIn(tween(FolioMotion.FAST_MS)),
                        exit = fadeOut(tween(FolioMotion.FAST_MS)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = navBottom + tierSpec.capsp + 24.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.80f))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = toastMessage.orEmpty(),
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
