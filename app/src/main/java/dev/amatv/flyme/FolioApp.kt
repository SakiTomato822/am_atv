package dev.amatv.flyme

import android.app.Activity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.AmAtvApplication
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.core.PlaybackEvent
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.AuthLaunchGate
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicKitWebAuthActivity
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.openAction
import dev.amatv.ui.ArtworkImages
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
 * Root composable for AM Folio 15 responsive UI (`dev.amatv.flyme`).
 * Reads state from and dispatches actions directly to `dev.amatv.core` stores without scaling `CarLayout`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolioApp(
    onOpenPlayer: () -> Unit = {},
    onLightThemeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as AmAtvApplication }
    val contentLanguageTag = LocalConfiguration.current.locales.toLanguageTags()
    val scope = rememberCoroutineScope()

    val services = app.musicServices
    val settingsStore = app.settingsStore
    val accountStore = app.accountStore
    val contentStore = app.contentStore
    val lyricsStore = app.lyricsStore
    val playbackStore = app.playbackStore

    val settingsState by settingsStore.state.collectAsState()
    val accountState by accountStore.state.collectAsState()
    val contentState by contentStore.state.collectAsState()
    val playbackState by playbackStore.state.collectAsState()
    val queueState by playbackStore.queueController.state.collectAsState()
    val isLight = rememberFolioIsLight(settingsState)

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

    var toastMessage by remember { mutableStateOf<String?>(null) }
    var serviceMessage by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }

    fun showToast(message: String) {
        toastMessage = message
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(1_700L)
            toastMessage = null
        }
    }

    LaunchedEffect(contentStore) {
        contentStore.messages.collect { msg ->
            if (msg != null) serviceMessage = msg
        }
    }

    val initialPage = remember {
        when (playbackStore.navigation.value.first) {
            Destination.Browse -> FolioRootPage.Browse.index
            Destination.Library -> FolioRootPage.Library.index
            Destination.Search -> FolioRootPage.Search.index
            else -> FolioRootPage.Home.index
        }
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { FolioRootPage.entries.size },
    )

    val authLaunchGate = remember { AuthLaunchGate() }
    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        authLaunchGate.finish()
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            val err = data?.getStringExtra(MusicKitWebAuthActivity.EXTRA_ERROR)
                ?: AppText.text(R.string.app_apple_sign_in_cancelled)
            serviceMessage = err
            showToast(err)
        } else {
            accountStore.completeSignIn(data)
                .onSuccess {
                    playbackStore.onSignedIn()
                    lyricsStore.onCacheCleared()
                    contentStore.onSignedIn()
                    serviceMessage = null
                    scope.launch { pagerState.animateScrollToPage(FolioRootPage.Home.index) }
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
            showAccount = true
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
                PlaybackEvent.ShowNowPlaying -> Unit
            }
        }
    }

    val currentRootPage = FolioRootPage.entries[pagerState.currentPage]
    val activeDestination = currentRootPage.destination
    LaunchedEffect(activeDestination) {
        if (activeDestination != null) {
            playbackStore.setNavigation(activeDestination, activeDestination)
            if (activeDestination == Destination.Search) {
                contentStore.ensureDefaultSearch()
            }
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
            CatalogOpenAction.Collection -> {
                if (item.catalogId == null) {
                    playbackStore.playCollection(item, MockCatalog.tracks, 0, shuffle = false)
                    return
                }
                scope.launch {
                    try {
                        val tracks = services.cachedCollectionTracks(item)
                            ?: services.savedCollectionTracks(item)
                            ?: services.collectionTracks(item)
                        if (tracks.isEmpty()) {
                            showToast(context.getString(R.string.folio_collection_empty))
                        } else {
                            playbackStore.playCollection(item, tracks, 0, shuffle = false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        showToast(error.message ?: context.getString(R.string.browse_tracks_error_detail))
                    }
                }
            }
            CatalogOpenAction.OpenArtist -> {
                showToast(context.getString(R.string.folio_artist_detail_pending))
            }
        }
    }

    BackHandler(enabled = showAccount || pagerState.currentPage != FolioRootPage.Home.index) {
        if (showAccount) {
            showAccount = false
        } else {
            scope.launch {
                pagerState.animateScrollToPage(
                    FolioRootPage.Home.index,
                    animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
                )
            }
        }
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
        val renderDensity = folioRenderDensity(systemDensity.density)
        val (layoutWidthDp, layoutHeightDp) = folioLayoutDp(
            windowWidthPx = constraints.maxWidth.toFloat(),
            windowHeightPx = constraints.maxHeight.toFloat(),
            systemDensity = systemDensity.density,
        )
        val tier = tierOf(layoutWidthDp, layoutHeightDp)
        val tierSpec = specForTier(tier)

        CompositionLocalProvider(
            LocalDensity provides Density(renderDensity, systemDensity.fontScale),
        ) {
            val statusTop = maxOf(
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
            )
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            FolioTheme(
                isLight = isLight,
                tierSpec = tierSpec,
                lowPerformanceMode = settingsState.lowPerformanceMode,
            ) {
            val palette = LocalFolioPalette.current
            val pageTitles = FolioRootPage.entries.map { stringResource(it.labelRes) }
            val pageProgress = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val contentBottomPadding = navBottom + tierSpec.capsp + 28.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .folioAmbientBackground(palette),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Top bar: statusBar + 12dp top, height = tierSpec.ib
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = tierSpec.px,
                                end = tierSpec.pr,
                                top = statusTop + 12.dp,
                            )
                            .height(tierSpec.ib),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FolioCircleButton(
                            icon = Icons.Rounded.Person,
                            contentDescription = stringResource(R.string.app_account),
                            onClick = { showAccount = true },
                        )
                        if (!tierSpec.usesBottomCapsule && playbackState.hasCurrentTrack) {
                            FolioMiniPlayerDot(
                                track = playbackState.track,
                                onOpenPlayer = onOpenPlayer,
                            )
                        } else {
                            Spacer(Modifier.height(tierSpec.ib))
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Metro Pivot header
                    FolioPivotBar(
                        titles = pageTitles,
                        pageProgress = pageProgress,
                        selectedPage = pagerState.currentPage,
                        onSelectPage = { idx ->
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    idx,
                                    animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
                                )
                            }
                        },
                    )

                    // Horizontal Pager for Settings / Home / Browse / Library / Search
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val frac = abs(pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                                alpha = 1f - frac * 0.35f
                            },
                    ) { pageIdx ->
                        when (FolioRootPage.entries[pageIdx]) {
                            FolioRootPage.Settings -> FolioSettingsPage(
                                settingsStore = settingsStore,
                                settings = settingsState,
                                services = services,
                                bottomPadding = contentBottomPadding,
                                onNotice = ::showToast,
                            )
                            FolioRootPage.Home -> FolioHomePage(
                                contentState = contentState,
                                connectionState = accountState.connectionState,
                                statusMessage = serviceMessage ?: services.setupMessage,
                                bottomPadding = contentBottomPadding,
                                onRetry = ::refreshAll,
                                onSignIn = ::startSignIn,
                                onOpenItem = ::openItem,
                            )
                            FolioRootPage.Browse -> FolioBrowsePage(
                                contentState = contentState,
                                connectionState = accountState.connectionState,
                                statusMessage = serviceMessage ?: services.setupMessage,
                                bottomPadding = contentBottomPadding,
                                onRetry = ::refreshAll,
                                onSignIn = ::startSignIn,
                                onOpenItem = ::openItem,
                            )
                            FolioRootPage.Library -> FolioLibraryPage(
                                contentState = contentState,
                                connectionState = accountState.connectionState,
                                currentTrack = playbackState.track,
                                isPlaying = playbackState.playWhenReady,
                                bottomPadding = contentBottomPadding,
                                onSignIn = ::startSignIn,
                                onLoadMoreShelf = { shelfId ->
                                    contentStore.loadMoreLibraryShelf(shelfId, accountState.connectionState)
                                },
                                onOpenItem = ::openItem,
                            )
                            FolioRootPage.Search -> FolioSearchPage(
                                contentState = contentState,
                                currentTrack = playbackState.track,
                                isPlaying = playbackState.playWhenReady,
                                bottomPadding = contentBottomPadding,
                                onSearch = contentStore::search,
                                onOpenItem = ::openItem,
                            )
                        }
                    }
                }

                // Bottom floating glass capsule on Narrow / Short / Medium tiers
                if (tierSpec.usesBottomCapsule && playbackState.hasCurrentTrack) {
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
                            track = playbackState.track.let {
                                if (miniSubtitle != null) it.copy(artist = miniSubtitle) else it
                            },
                            isPlaying = playbackState.playWhenReady,
                            onTogglePlayback = playbackStore::togglePlayback,
                            onOpenPlayer = onOpenPlayer,
                        )
                    }
                }

                // Account overlay panel
                FolioAccountOverlay(
                    visible = showAccount,
                    connectionState = accountState.connectionState,
                    setupMessage = serviceMessage ?: services.setupMessage,
                    statusTop = statusTop,
                    navBottom = navBottom,
                    onClose = { showAccount = false },
                    onSignIn = ::startSignIn,
                    onSignOut = ::signOut,
                    onRefresh = ::refreshAll,
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
