package dev.amatv.flyme

import android.animation.ValueAnimator
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.amatv.AmAtvApplication
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.core.LibraryNotice
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.PlaybackState
import dev.amatv.core.StartupQueueAction
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.model.forDisplay
import dev.amatv.artwork.ArtworkImages
import dev.amatv.artwork.rememberArtworkPaletteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen Folio 15 Now Playing layer overlay.
 *
 * Mounted unconditionally at the root so background lyric loading stays warm and full-screen <->
 * small-window switches keep [open] synchronized via [FolioPlayerSession].
 */
@Composable
internal fun BoxScope.FolioPlayerLayer(
    open: Boolean,
    statusTop: Dp,
    navBottom: Dp,
    onClose: () -> Unit,
    onOpenDetail: (Track) -> Unit,
) {
    val app = LocalContext.current.applicationContext as AmAtvApplication
    val settingsStore = app.settingsStore
    val accountStore = app.accountStore
    val lyricsStore = app.lyricsStore
    val libraryActions = app.libraryActions
    val playbackStore = app.playbackStore
    val musicServices = app.musicServices

    val settings by settingsStore.state.collectAsState()
    val accountState by accountStore.state.collectAsState()
    val lyricsState by lyricsStore.state.collectAsState()
    val libraryState by libraryActions.state.collectAsState()
    val playbackState by playbackStore.state.collectAsState()
    val queueState by playbackStore.queueController.state.collectAsState()

    val track = playbackState.track
    val signedIn = accountState.connectionState == MusicConnectionState.SignedIn
    val currentIdentity = remember(track) { track.lyricsIdentity() }

    LaunchedEffect(
        currentIdentity,
        playbackState.hasCurrentTrack,
        signedIn,
        settings.lyricTranslationEnabled,
        settings.lyricPronunciationEnabled,
        settings.lyricTranslationLanguageTag,
        settings.lyricPronunciationLanguageTag,
    ) {
        lyricsStore.setCurrentTrack(
            track = if (playbackState.hasCurrentTrack) track else null,
            isSignedIn = signedIn,
        )
    }

    AnimatedVisibility(
        visible = open && playbackState.hasCurrentTrack,
        modifier = Modifier.matchParentSize(),
        enter = if (settings.lowPerformance) EnterTransition.None else slideInVertically(
            animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(FolioMotion.FAST_MS)),
        exit = if (settings.lowPerformance) ExitTransition.None else slideOutVertically(
            animationSpec = tween(FolioMotion.FAST_MS, easing = FolioMotion.PageEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(FolioMotion.FAST_MS)),
    ) {
        BackHandler(enabled = open, onBack = onClose)

        val palette = LocalFolioPalette.current
        val tierSpec = playerSpecForTier(LocalFolioTierSpec.current.tier)
        val scope = rememberCoroutineScope()

        var queueOpen by FolioPlayerSession.queueOpenState
        var lyricsSettingsOpen by remember { mutableStateOf(false) }
        LaunchedEffect(open) {
            if (!open) lyricsSettingsOpen = false
        }
        val posterPreferred = resolveEffectivePosterPreferred(settings.lyricStyle)
        var controlsManualOpen by remember { mutableStateOf(true) }
        var revealPulse by remember { mutableLongStateOf(0L) }
        var navigationMessage by remember { mutableStateOf<String?>(null) }

        val appLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
        val effectiveLyricsLanguageTag = settings.lyricTranslationLanguageTag.ifBlank { appLanguageTag }
        val lyricsLocalization = remember(
            effectiveLyricsLanguageTag,
            settings.lyricPronunciationLanguageTag,
            settings.lyricTranslationEnabled,
            settings.lyricPronunciationEnabled,
        ) {
            LyricsLocalizationRequest(
                languageTag = effectiveLyricsLanguageTag,
                pronunciationLanguageTag = settings.lyricPronunciationLanguageTag,
                includeTranslations = settings.lyricTranslationEnabled,
                includePronunciations = settings.lyricPronunciationEnabled,
            )
        }
        val expectedLyricsVariant = remember(lyricsLocalization) {
            "${lyricsLocalization.languageTag}|trans=${lyricsLocalization.includeTranslations}" +
                "|pron=${lyricsLocalization.includePronunciations}" +
                "|pronLang=${lyricsLocalization.pronunciationLanguageTag}"
        }
        val rawLines = remember(lyricsState, currentIdentity, track.lyrics) {
            when {
                lyricsState.trackIdentity == currentIdentity && lyricsState.lines.isNotEmpty() -> lyricsState.lines
                else -> track.lyrics
            }
        }
        val enhancementsReady = !lyricsLocalization.enabled || (
            lyricsState.trackIdentity == currentIdentity &&
                lyricsState.resolvedVariant == expectedLyricsVariant &&
                lyricsState.isReady
            )
        val enhancementsFailed = lyricsLocalization.enabled &&
            lyricsState.trackIdentity == currentIdentity &&
            lyricsState.isTransientFailure &&
            rawLines.isNotEmpty()
        val displayedLines = remember(
            rawLines,
            settings.lyricTranslationEnabled,
            settings.lyricPronunciationEnabled,
            enhancementsReady,
        ) {
            rawLines.map { line ->
                line.forDisplay(
                    showTranslation = settings.lyricTranslationEnabled && enhancementsReady,
                    showPronunciation = settings.lyricPronunciationEnabled && enhancementsReady,
                )
            }
        }
        val lyricsDisplayActive = isLyricDisplayActive(
            translationEnabled = settings.lyricTranslationEnabled,
            pronunciationEnabled = settings.lyricPronunciationEnabled,
        )
        val defaultTextSize = if (BuildConfig.FLYME_AUTO) LyricTextSize.Large else LyricTextSize.Standard
        val textScaleMultiplier = folioLyricsTextScaleMultiplier(
            selected = settings.lyricTextSize,
            defaultSize = defaultTextSize,
        )
        val lyricsAvailability = resolveLyricsAvailability(
            hasCurrentTrack = playbackState.hasCurrentTrack,
            trackCatalogId = track.catalogId,
            trackCatalogKind = track.catalogKind,
            isSignedIn = signedIn,
            displayedLineCount = displayedLines.size,
            lyricsState = lyricsState,
            currentIdentity = currentIdentity,
        )
        val hasTimed = remember(displayedLines) { hasTimedLyrics(displayedLines) }
        val displayState = resolvePlayerDisplayState(
            tier = tierSpec.tier,
            queueOpen = queueOpen,
            posterPreferred = posterPreferred,
            lyricsAvailability = lyricsAvailability,
            hasTimedLyrics = hasTimed,
        )
        val underlayDisplayState = resolveQueueUnderlayDisplayState(
            tier = tierSpec.tier,
            posterPreferred = posterPreferred,
            lyricsAvailability = lyricsAvailability,
            hasTimedLyrics = hasTimed,
        )
        var lastNonQueueStyle by remember { mutableStateOf(settings.lyricStyle) }
        val effectiveStyle = resolveEffectiveBackgroundStyle(
            displayState = displayState,
            settingsStyle = settings.lyricStyle,
            lastContentBackgroundStyle = lastNonQueueStyle,
        )
        SideEffect {
            if (displayState != FolioPlayerDisplayState.Queue) {
                lastNonQueueStyle = effectiveStyle
            }
        }
        val queuePanelVisible = isQueuePanelVisible(tierSpec.tier, underlayDisplayState)
        val alwaysOpen = alwaysOpenControls(
            tier = tierSpec.tier,
            style = effectiveStyle,
            hasLyricsOrPending = lyricsAvailability != FolioLyricsAvailability.Unavailable,
            displayState = displayState,
        )
        val controlsExpanded = areControlsExpanded(
            manualOpen = controlsManualOpen,
            alwaysOpen = alwaysOpen,
            playWhenReady = playbackState.playWhenReady,
        )

        LaunchedEffect(
            open,
            controlsExpanded,
            alwaysOpen,
            playbackState.playWhenReady,
            revealPulse,
            queueOpen,
            lyricsSettingsOpen,
        ) {
            if (open && controlsExpanded && !alwaysOpen && playbackState.playWhenReady && !queueOpen && !lyricsSettingsOpen) {
                delay(5_000L)
                controlsManualOpen = false
            }
        }

        LaunchedEffect(libraryState.notice, navigationMessage) {
            if (libraryState.notice != null || navigationMessage != null) {
                delay(2_800L)
                libraryActions.dismissNotice()
                navigationMessage = null
            }
        }

        val context = LocalContext.current
        val reducedMotion = remember(context) {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            isReducedMotionEnabled(scale, ValueAnimator.areAnimatorsEnabled())
        }
        val trackSwitchState = rememberFolioTrackSwitchState(
            open = open && playbackState.hasCurrentTrack,
            liveTrack = track,
            lowPerformance = settings.lowPerformance,
            reducedMotion = reducedMotion,
        )
        val displayedTrack = trackSwitchState.displayedTrack
        val artwork = rememberArtworkPaletteResult(displayedTrack.artworkUrl, displayedTrack.palette)
        val artworkPalette = artwork.colors
        val topStripLuminance = if (!settings.lowPerformance ||
            showSoakArtworkStage(tierSpec.tier, effectiveStyle, displayState)
        ) {
            rememberArtworkTopLuminance(displayedTrack.artworkUrl, displayedTrack.palette)
        } else null
        val baseColor = remember(artworkPalette, palette.isLight) {
            folioPlayerBaseColor(artworkPalette, palette.isLight)
        }
        val duetAccentColor = remember(artworkPalette, baseColor, palette.isLight) {
            selectDuetAccentColor(artworkPalette, baseColor, palette.isLight)
        }
        val headerColors = remember(tierSpec.tier, effectiveStyle, displayState, palette.isLight, topStripLuminance) {
            val useDarkInk = resolveHeaderUseDarkInk(
                tier = tierSpec.tier,
                style = effectiveStyle,
                displayState = displayState,
                themeIsLight = palette.isLight,
                topStripLuminance = topStripLuminance,
            )
            resolveHeaderColorScheme(useDarkInk)
        }
        val fg = if (palette.isLight) Color(0xFF16141B) else Color.White
        val fg2 = if (palette.isLight) Color(0xFF16141B).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.68f)
        val controlAccent = rememberFolioControlAccent(
            appleColors = displayedTrack.artworkColors,
            palette = artwork.extractedColors(displayedTrack.artworkUrl),
            background = folioControlBackground(
                effectiveStyle, artworkPalette, baseColor, palette.isLight, settings.lowPerformance,
            ),
            neutral = fg,
            animate = !settings.lowPerformance && !reducedMotion && LocalFolioBrowseMotion.current.animated,
        )
        val accent = palette.accent
        val pullDown = rememberFolioPullDownToClose(onClose)

        val positionState = rememberFolioPlaybackPositionMs(playbackState, active = open)
        val durationMs = (playbackState.snapshot?.durationMs ?: track.durationMs).coerceAtLeast(0L)
        val snapshot = playbackState.snapshot
        val isLiveStream = snapshot?.isLiveStream == true
        val canSeek = !isLiveStream && (snapshot?.canSeek ?: (durationMs > 0L))
        val isStation = queueState.current?.track?.catalogKind == CatalogKind.Station
        val canSkipPrevious = if (isStation) snapshot?.canSkipPrevious == true else queueState.canSkipPrevious
        val canSkipNext = if (isStation) snapshot?.canSkipNext == true else queueState.canSkipNext

        val fallbackUnavailable = stringResource(R.string.player_open_unavailable)
        val openCatalogDetail: (suspend () -> Track?) -> Unit = { fetch ->
            scope.launch {
                val resolved = try {
                    fetch()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (resolved != null) {
                    onOpenDetail(resolved)
                } else {
                    navigationMessage = fallbackUnavailable
                }
            }
        }
        val openAlbumDetail: () -> Unit = { openCatalogDetail { musicServices.catalogAlbumOf(track) } }
        val openArtistDetail: () -> Unit = { openCatalogDetail { musicServices.catalogArtistOf(track) } }
        val revealControls: () -> Unit = {
            controlsManualOpen = true
            revealPulse++
        }
        val seekToMs: (Long) -> Unit = { targetMs ->
            revealControls()
            val delta = targetMs - playbackStore.currentPositionMs()
            playbackStore.seekPlaybackBy(delta)
            if (canSeek) positionState.requestedSeek(playbackStore.state.value.positionAnchorMs)
        }

        val queueSnapshot = remember(queueState, playbackState) {
            buildFolioQueueSnapshot(queueState, playbackState)
        }
        val cycleRepeat: () -> Unit = {
            if (!queueSnapshot.isStation) {
                revealControls()
                playbackStore.dispatchQueueAction(StartupQueueAction.CycleRepeat)
            }
        }
        val toggleShuffle: () -> Unit = {
            if (!queueSnapshot.isStation) {
                revealControls()
                playbackStore.dispatchQueueAction(StartupQueueAction.ToggleShuffle)
            }
        }
        val clearUpcoming: () -> Unit = {
            if (!queueSnapshot.isStation) {
                revealControls()
                playbackStore.dispatchQueueAction(StartupQueueAction.ClearUpcoming)
            }
        }

        ProvideFolioGlassEnvironment(
            track = displayedTrack,
            tierSpec = tierSpec,
            effectiveStyle = effectiveStyle,
            displayState = displayState,
            baseColor = baseColor,
            artworkPalette = artworkPalette,
            edgeBrightness = trackSwitchState.motion.edgeBrightness,
            lowPerformance = settings.lowPerformance,
        ) { glassEnv ->
            val anchorHolder = remember { FolioPlayerAnchorCoordsHolder() }
            var playerRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var showStyleToggleHint by remember { mutableStateOf(false) }
            LaunchedEffect(open, playbackState.hasCurrentTrack) {
                if (shouldShowStyleToggleHint(open, playbackState.hasCurrentTrack, app.welcomePreferences.hintStyleToggleShown)) {
                    app.welcomePreferences.hintStyleToggleShown = true
                    showStyleToggleHint = true
                }
            }
            LaunchedEffect(showStyleToggleHint) {
                if (showStyleToggleHint) {
                    delay(4_000L)
                    showStyleToggleHint = false
                }
            }
            LaunchedEffect(posterPreferred) { showStyleToggleHint = false }
            CompositionLocalProvider(
                LocalFolioControlAccent provides controlAccent,
                LocalFolioReducedMotion provides reducedMotion,
                LocalFolioLowPerformance provides settings.lowPerformance,
                LocalFolioPlayerAnchors provides anchorHolder,
                LocalFolioTrackSwitchMotion provides trackSwitchState.motion,
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        playerRootCoords = coords
                        glassEnv.coordHolder?.rootCoords = coords
                    }
                    .then(pullDown.layer),
            ) {
                FolioPlayerBackdrop(
                    track = displayedTrack,
                    artworkPalette = artworkPalette,
                    style = effectiveStyle,
                    isLight = palette.isLight,
                    lowPerformance = settings.lowPerformance,
                    reducedMotion = reducedMotion,
                    tierSpec = tierSpec,
                    displayState = displayState,
                    pullHandle = pullDown.handle,
                    onRevealTap = {
                        if (controlsExpanded && !alwaysOpen && playbackState.playWhenReady && !queueOpen) {
                            controlsManualOpen = false
                        } else {
                            revealControls()
                        }
                    },
                    modifier = if (glassEnv.layerBackdrop != null) {
                        Modifier.layerBackdrop(glassEnv.layerBackdrop)
                    } else {
                        Modifier
                    },
                )

            // Soft adaptive top scrim behind Poster header: omits milky veil over dark cover photos in Light mode.
            val scrimColor = resolvePosterTopScrimColor(
                isLight = palette.isLight,
                useDarkHeaderInk = headerColors.fg != Color.White,
                lightBaseColor = baseColor,
                darkBgColor = palette.bg,
            )
            val targetScrimAlpha = if (displayState == FolioPlayerDisplayState.Poster && scrimColor != Color.Transparent) 1f else 0f
            val posterScrimAlpha = if (settings.lowPerformance) targetScrimAlpha else animateFloatAsState(
                targetValue = targetScrimAlpha,
                animationSpec = tween(if (reducedMotion) 0 else BACKDROP_STYLE_CROSSFADE_MS),
                label = "poster-top-scrim-alpha",
            ).value
            if (posterScrimAlpha > 0.01f && scrimColor != Color.Transparent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(160.dp)
                        .graphicsLayer { alpha = posterScrimAlpha }
                        .background(
                            Brush.verticalGradient(
                                0f to scrimColor.copy(alpha = 0.55f),
                                1f to scrimColor.copy(alpha = 0f),
                            ),
                        ),
                )
            }

            val notice = navigationMessage ?: when (libraryState.notice) {
                is LibraryNotice.AddedToLibrary -> stringResource(R.string.folio_player_in_library)
                is LibraryNotice.AddedToFavorites -> stringResource(R.string.player_favorite_add)
                is LibraryNotice.FavoriteFailed -> stringResource(R.string.player_favorite_failed)
                is LibraryNotice.FavoriteRemoveUnsupported -> stringResource(R.string.player_favorite_remove_unsupported)
                is LibraryNotice.AddToLibraryFailed,
                is LibraryNotice.RemoveFromLibraryUnsupported -> stringResource(R.string.player_open_unavailable)
                null -> null
            }

            if (tierSpec.tier == FolioTier.Short) {
                ShortPlayerLayout(
                    track = displayedTrack,
                    tierSpec = tierSpec,
                    style = effectiveStyle,
                    displayState = displayState,
                    underlayDisplayState = underlayDisplayState,
                    queuePanelVisible = queuePanelVisible,
                    displayedLines = displayedLines,
                    lyricsState = lyricsState,
                    queueSnapshot = queueSnapshot,
                    positionState = positionState,
                    durationMs = durationMs,
                    canSeek = canSeek,
                    isLiveStream = isLiveStream,
                    offsetMs = settings.lyricOffsetMs,
                    lowPerformance = settings.lowPerformance,
                    translationEnabled = settings.lyricTranslationEnabled,
                    pronunciationEnabled = settings.lyricPronunciationEnabled,
                    lyricsDisplayActive = lyricsDisplayActive,
                    textScaleMultiplier = textScaleMultiplier,
                    controlsExpanded = controlsExpanded,
                    reducedMotion = reducedMotion,
                    queueOpen = queueOpen,
                    playWhenReady = playbackState.playWhenReady,
                    isPlaying = playbackState.isPlaying,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
                    isLight = palette.isLight,
                    statusTop = statusTop,
                    navBottom = navBottom,
                    fg = fg,
                    fg2 = fg2,
                    duetAccentColor = duetAccentColor,
                    accent = accent,
                    headerColors = headerColors,
                    isFavorite = libraryState.current.isFavorite == true,
                    isInLibrary = libraryState.current.isInLibrary == true,
                    favoriteInProgress = libraryState.current.isFavoriteInProgress,
                    libraryInProgress = libraryState.current.isLibraryInProgress,
                    hasTimedLyrics = hasTimed,
                    notice = notice,
                    pullHandle = pullDown.handle,
                    onClose = onClose,
                    onToggleLibrary = {
                        revealControls()
                        libraryActions.addToLibrary()
                    },
                    onToggleFavorite = {
                        revealControls()
                        libraryActions.toggleFavorite()
                    },
                    onTogglePosterMode = {
                        revealControls()
                        if (queueOpen) {
                            queueOpen = false
                        } else if (hasTimed) {
                            settingsStore.setLyricStyle(nextLyricStyleForPosterToggle(posterPreferred))
                        }
                    },
                    onReveal = revealControls,
                    onLineSeek = seekToMs,
                    onSeekTo = seekToMs,
                    onOpenLyricsSettings = {
                        revealControls()
                        lyricsSettingsOpen = true
                    },
                    onPrevious = {
                        revealControls()
                        playbackStore.skip(-1)
                    },
                    onPlayPause = {
                        revealControls()
                        playbackStore.togglePlayback()
                    },
                    onCollapsedPlayPause = playbackStore::togglePlayback,
                    onNext = {
                        revealControls()
                        playbackStore.skip(1)
                    },
                    onToggleQueue = {
                        revealControls()
                        queueOpen = !queueOpen
                    },
                    onSelectTimelineTrack = playbackStore::selectQueueTrack,
                    onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                    onCycleRepeat = cycleRepeat,
                    onToggleShuffle = toggleShuffle,
                    onClearQueue = clearUpcoming,
                    onRetryLyrics = playbackStore::retryCurrentLyrics,
                    onOpenAlbum = openAlbumDetail,
                    onOpenArtist = openArtistDetail,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = tierSpec.horizontalPad,
                            end = tierSpec.horizontalPad,
                            top = statusTop + tierSpec.topPad,
                            bottom = navBottom + tierSpec.bottomPad,
                        ),
                ) {
                    FolioPlayerHeader(
                        track = displayedTrack,
                        tierSpec = tierSpec,
                        headerColors = headerColors,
                        favoriteAccent = accent,
                        showHeaderTitle = showHeaderTitle(tierSpec.tier, controlsExpanded, displayState),
                        controlsExpanded = controlsExpanded,
                        displayState = displayState,
                        reducedMotion = reducedMotion,
                        isFavorite = libraryState.current.isFavorite == true,
                        isInLibrary = libraryState.current.isInLibrary == true,
                        favoriteInProgress = libraryState.current.isFavoriteInProgress,
                        libraryInProgress = libraryState.current.isLibraryInProgress,
                        isPosterMode = displayState == FolioPlayerDisplayState.Poster,
                        hasTimedLyrics = hasTimed,
                        pullHandle = pullDown.handle,
                        onClose = onClose,
                        onExpandControls = revealControls,
                        onToggleLibrary = {
                            revealControls()
                            libraryActions.addToLibrary()
                        },
                        onToggleFavorite = {
                            revealControls()
                            libraryActions.toggleFavorite()
                        },
                        onTogglePosterMode = {
                            revealControls()
                            if (queueOpen) {
                                queueOpen = false
                            } else if (hasTimed) {
                                settingsStore.setLyricStyle(nextLyricStyleForPosterToggle(posterPreferred))
                            }
                        },
                        onOpenAlbum = openAlbumDetail,
                        onOpenArtist = openArtistDetail,
                    )

                    if (!notice.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = notice,
                                color = fg,
                                fontFamily = LocalFolioFontFamily.current,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .folioPlayerGlass(palette.isLight, RoundedCornerShape(FolioRadii.Chip))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Main content body tailored to each tier
                    val overlayControlsInBody = tierSpec.tier == FolioTier.Wide && !alwaysOpen
                    var wideCardHeightPx by remember { mutableIntStateOf(0) }
                    val wideExpandedCardDp = with(LocalDensity.current) {
                        if (wideCardHeightPx > 0) wideCardHeightPx.toDp() else 226.dp
                    }
                    val renderControlsSlot: @Composable (Modifier, Dp, Dp) -> Unit = { slotModifier, carWidthDp, carCollapsedBarWidthDp ->
                        FolioPlayerAnimatedControlsSlot(
                            controlsExpanded = controlsExpanded,
                            reducedMotion = reducedMotion,
                            track = displayedTrack,
                            tierSpec = tierSpec,
                            displayState = displayState,
                            positionState = positionState,
                            durationMs = durationMs,
                            canSeek = canSeek,
                            isLiveStream = isLiveStream,
                            playWhenReady = playbackState.playWhenReady,
                            lyricsDisplayActive = lyricsDisplayActive,
                            queueOpen = queueOpen,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            isFavorite = libraryState.current.isFavorite == true,
                            isInLibrary = libraryState.current.isInLibrary == true,
                            favoriteInProgress = libraryState.current.isFavoriteInProgress,
                            libraryInProgress = libraryState.current.isLibraryInProgress,
                            isLight = palette.isLight,
                            fg = fg,
                            fg2 = fg2,
                            favoriteAccent = accent,
                            onExpandCapsule = revealControls,
                            onSeekTo = seekToMs,
                            onOpenLyricsSettings = {
                                revealControls()
                                lyricsSettingsOpen = true
                            },
                            onToggleLibrary = {
                                revealControls()
                                libraryActions.addToLibrary()
                            },
                            onToggleFavorite = {
                                revealControls()
                                libraryActions.toggleFavorite()
                            },
                            onPrevious = {
                                revealControls()
                                playbackStore.skip(-1)
                            },
                            onPlayPause = {
                                revealControls()
                                playbackStore.togglePlayback()
                            },
                            onCollapsedPrevious = { playbackStore.skip(-1) },
                            onCollapsedPlayPause = playbackStore::togglePlayback,
                            onCollapsedNext = { playbackStore.skip(1) },
                            onNext = {
                                revealControls()
                                playbackStore.skip(1)
                            },
                            onToggleQueue = {
                                revealControls()
                                queueOpen = !queueOpen
                            },
                            onOpenAlbum = openAlbumDetail,
                            onOpenArtist = openArtistDetail,
                            modifier = slotModifier,
                            contentAlignment = if (tierSpec.tier == FolioTier.Wide) {
                                Alignment.BottomStart
                            } else {
                                Alignment.BottomCenter
                            },
                            carControlsWidthDp = carWidthDp,
                            collapsedBarWidthDp = carCollapsedBarWidthDp,
                            onCarControlsHeightPxChanged = { wideCardHeightPx = it },
                        )
                    }

                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val wideLeftColumnWidth = maxWidth * 0.50f
                        val legacyWideCollapsedCover = computeWideCarCollapsedCoverSizeDp(
                            leftColumnWidthDp = wideLeftColumnWidth,
                            bodyHeightDp = maxHeight,
                        )
                        val flowWideCollapsedCoverDp = computeFlowLandscapeCoverLayout(
                            leftColumnWidthDp = (wideLeftColumnWidth - WIDE_CAR_COLLAPSED_BAR_START_DP.dp).coerceAtLeast(160.dp),
                            leftColumnHeightDp = maxHeight,
                            availableHeightAboveControlsDp = (maxHeight - 134.dp).coerceAtLeast(160.dp),
                            minBaselineCoverDp = legacyWideCollapsedCover,
                        ).coverSizeDp
                        val wideCollapsedCoverDp = if (effectiveStyle == LyricBackgroundStyle.Flow) {
                            flowWideCollapsedCoverDp
                        } else {
                            legacyWideCollapsedCover
                        }
                        val wideCollapsedBarWidthDp = computeWideCollapsedHairlineBarWidthDp(
                            leftColumnWidthDp = wideLeftColumnWidth,
                            coverWidthDp = flowWideCollapsedCoverDp,
                        )
                        val wideControlsWidthDp = computeWideCarControlsWidthDp(wideCollapsedCoverDp)

                        when (tierSpec.tier) {
                            FolioTier.Ultrawide -> {
                                UltrawidePlayerBody(
                                    track = displayedTrack,
                                    tierSpec = tierSpec,
                                    displayState = displayState,
                                    displayedLines = displayedLines,
                                    lyricsState = lyricsState,
                                    queueSnapshot = queueSnapshot,
                                    positionState = positionState,
                                    durationMs = durationMs,
                                    canSeek = canSeek,
                                    isLiveStream = isLiveStream,
                                    offsetMs = settings.lyricOffsetMs,
                                    lowPerformance = settings.lowPerformance,
                                    translationEnabled = settings.lyricTranslationEnabled,
                                    pronunciationEnabled = settings.lyricPronunciationEnabled,
                                    lyricsDisplayActive = lyricsDisplayActive,
                                    textScaleMultiplier = textScaleMultiplier,
                                    controlsExpanded = controlsExpanded,
                                    reducedMotion = reducedMotion,
                                    queueOpen = queueOpen,
                                    playWhenReady = playbackState.playWhenReady,
                                    isPlaying = playbackState.isPlaying,
                                    canSkipPrevious = canSkipPrevious,
                                    canSkipNext = canSkipNext,
                                    isFavorite = libraryState.current.isFavorite == true,
                                    isInLibrary = libraryState.current.isInLibrary == true,
                                    favoriteInProgress = libraryState.current.isFavoriteInProgress,
                                    libraryInProgress = libraryState.current.isLibraryInProgress,
                                    isLight = palette.isLight,
                                    fg = fg,
                                    fg2 = fg2,
                                    duetAccentColor = duetAccentColor,
                                    accent = accent,
                                    pullHandle = pullDown.handle,
                                    onReveal = revealControls,
                                    onLineSeek = seekToMs,
                                    onSeekTo = seekToMs,
                                    onOpenLyricsSettings = {
                                        revealControls()
                                        lyricsSettingsOpen = true
                                    },
                                    onToggleLibrary = {
                                        revealControls()
                                        libraryActions.addToLibrary()
                                    },
                                    onToggleFavorite = {
                                        revealControls()
                                        libraryActions.toggleFavorite()
                                    },
                                    onPrevious = { playbackStore.skip(-1) },
                                    onPlayPause = playbackStore::togglePlayback,
                                    onNext = { playbackStore.skip(1) },
                                    onToggleQueue = { revealControls() },
                                    onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                    onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                    onCycleRepeat = cycleRepeat,
                                    onToggleShuffle = toggleShuffle,
                                    onClearQueue = clearUpcoming,
                                    onRetryLyrics = playbackStore::retryCurrentLyrics,
                                    onOpenAlbum = openAlbumDetail,
                                    onOpenArtist = openArtistDetail,
                                )
                            }

                            FolioTier.Narrow, FolioTier.Medium -> {
                                VerticalPlayerBody(
                                    track = displayedTrack,
                                    tierSpec = tierSpec,
                                    style = effectiveStyle,
                                    displayState = displayState,
                                    underlayDisplayState = underlayDisplayState,
                                    queuePanelVisible = queuePanelVisible,
                                    displayedLines = displayedLines,
                                    lyricsState = lyricsState,
                                    queueSnapshot = queueSnapshot,
                                    positionState = positionState,
                                    offsetMs = settings.lyricOffsetMs,
                                    lowPerformance = settings.lowPerformance,
                                    translationEnabled = settings.lyricTranslationEnabled,
                                    pronunciationEnabled = settings.lyricPronunciationEnabled,
                                    textScaleMultiplier = textScaleMultiplier,
                                    controlsExpanded = controlsExpanded,
                                    reducedMotion = reducedMotion,
                                    canSeek = canSeek,
                                    isLight = palette.isLight,
                                    isPlaying = playbackState.isPlaying,
                                    fg = fg,
                                    fg2 = fg2,
                                    duetAccentColor = duetAccentColor,
                                    accent = accent,
                                    pullHandle = pullDown.handle,
                                    onReveal = revealControls,
                                    onLineSeek = seekToMs,
                                    onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                    onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                    onCycleRepeat = cycleRepeat,
                                    onToggleShuffle = toggleShuffle,
                                    onClearQueue = clearUpcoming,
                                    onRetryLyrics = playbackStore::retryCurrentLyrics,
                                    onOpenAlbum = openAlbumDetail,
                                    onOpenArtist = openArtistDetail,
                                )
                            }

                            else -> {
                                TwoColumnPlayerBody(
                                    track = displayedTrack,
                                    tierSpec = tierSpec,
                                    style = effectiveStyle,
                                    displayState = displayState,
                                    underlayDisplayState = underlayDisplayState,
                                    queuePanelVisible = queuePanelVisible,
                                    displayedLines = displayedLines,
                                    lyricsState = lyricsState,
                                    queueSnapshot = queueSnapshot,
                                    positionState = positionState,
                                    durationMs = durationMs,
                                    offsetMs = settings.lyricOffsetMs,
                                    lowPerformance = settings.lowPerformance,
                                    translationEnabled = settings.lyricTranslationEnabled,
                                    pronunciationEnabled = settings.lyricPronunciationEnabled,
                                    textScaleMultiplier = textScaleMultiplier,
                                    controlsExpanded = controlsExpanded,
                                    alwaysOpen = alwaysOpen,
                                    playWhenReady = playbackState.playWhenReady,
                                    reducedMotion = reducedMotion,
                                    canSeek = canSeek,
                                    isLight = palette.isLight,
                                    isPlaying = playbackState.isPlaying,
                                    expandedCarControlsHeightDp = wideExpandedCardDp,
                                    carControlsWidthDp = wideControlsWidthDp,
                                    fg = fg,
                                    fg2 = fg2,
                                    duetAccentColor = duetAccentColor,
                                    accent = accent,
                                    pullHandle = pullDown.handle,
                                    onReveal = revealControls,
                                    onCollapsedPlayPause = playbackStore::togglePlayback,
                                    onLineSeek = seekToMs,
                                    onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                    onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                    onCycleRepeat = cycleRepeat,
                                    onToggleShuffle = toggleShuffle,
                                    onClearQueue = clearUpcoming,
                                    onRetryLyrics = playbackStore::retryCurrentLyrics,
                                    onOpenAlbum = openAlbumDetail,
                                    onOpenArtist = openArtistDetail,
                                )
                            }
                        }

                        if (overlayControlsInBody) {
                            renderControlsSlot(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(),
                                wideControlsWidthDp,
                                wideCollapsedBarWidthDp,
                            )
                        }
                    }

                    // Bottom controls slot for Narrow / Medium / non-overlay Wide
                    if (tierSpec.tier != FolioTier.Ultrawide && !overlayControlsInBody) {
                        Spacer(Modifier.height(10.dp))
                        renderControlsSlot(Modifier.fillMaxWidth(), 464.dp, Dp.Unspecified)
                    }
                }
                val rootC = playerRootCoords?.takeIf { it.isAttached }
                val ttC = anchorHolder.posterButtonCoords?.takeIf { it.isAttached }
                val density = LocalDensity.current
                val bubbleModifier = if (rootC != null && ttC != null) {
                    val rect = rootC.localBoundingBoxOf(ttC, clipBounds = false)
                    val endPad = ((rootC.size.width - rect.right).coerceAtLeast(0f) / density.density).dp
                    val topOffsetPx = (rect.bottom + with(density) { 8.dp.toPx() }).roundToInt()
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = max(12.dp, endPad))
                        .offset { IntOffset(0, topOffsetPx) }
                } else {
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = statusTop + tierSpec.topPad + tierSpec.pib + 8.dp,
                            end = tierSpec.horizontalPad,
                        )
                }
                FolioHintBubble(
                    visible = showStyleToggleHint && open && !lyricsSettingsOpen,
                    text = stringResource(R.string.welcome_hint_style_toggle),
                    pointerBiasX = 0.86f,
                    lowPerformance = settings.lowPerformance,
                    onDismiss = { showStyleToggleHint = false },
                    modifier = bubbleModifier,
                )
            }

            BackHandler(enabled = open && lyricsSettingsOpen) { lyricsSettingsOpen = false }
            FolioLyricsDisplaySettingsOverlay(
                open = lyricsSettingsOpen,
                lowPerformance = settings.lowPerformance,
                reducedMotion = reducedMotion,
                tier = tierSpec.tier,
                isLight = palette.isLight,
                fg = fg,
                fg2 = fg2,
                statusTop = statusTop,
                navBottom = navBottom,
                translationEnabled = settings.lyricTranslationEnabled,
                pronunciationEnabled = settings.lyricPronunciationEnabled,
                translationAvailable = lyricTranslationAvailable(rawLines, enhancementsReady),
                pronunciationAvailable = lyricPronunciationAvailable(rawLines, enhancementsReady),
                translationLanguageTag = settings.lyricTranslationLanguageTag,
                pronunciationLanguageTag = settings.lyricPronunciationLanguageTag,
                appLanguageTag = appLanguageTag,
                enhancementsReady = enhancementsReady,
                enhancementsFailed = enhancementsFailed,
                lyricTextSize = settings.lyricTextSize,
                lyricOffsetMs = settings.lyricOffsetMs,
                onTranslationChange = settingsStore::setLyricTranslationEnabled,
                onPronunciationChange = settingsStore::setLyricPronunciationEnabled,
                onTranslationLanguageChange = settingsStore::setLyricTranslationLanguageTag,
                onPronunciationLanguageChange = settingsStore::setLyricPronunciationLanguageTag,
                onLyricTextSizeChange = settingsStore::setLyricTextSize,
                onLyricOffsetChange = settingsStore::setLyricOffsetMs,
                onDismiss = { lyricsSettingsOpen = false },
            )
        }
        }
        }
    }
}

@Composable
private fun UltrawidePlayerBody(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    displayState: FolioPlayerDisplayState,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: FolioPlaybackPosition,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    lyricsDisplayActive: Boolean,
    textScaleMultiplier: Float,
    controlsExpanded: Boolean,
    reducedMotion: Boolean,
    queueOpen: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    duetAccentColor: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenLyricsSettings: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    val pillBg = if (isLight) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.14f)
    val pillBorder = if (isLight) Color(0xFF17151C).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Column 1: artwork + clickable meta + controls
        Column(
            modifier = Modifier
                .width(tierSpec.controlCardWidth)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val artSide = min(maxWidth, 236.dp)
                    FolioArtwork(
                        track = track,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .size(artSide)
                            .folioTrackSwitchCover()
                            .shadow(22.dp, RoundedCornerShape(16.dp))
                            .then(pullHandle),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy((-4).dp)) {
                    FolioMarqueeTrackTitle(
                        track = track,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (track.artist.isNotBlank() || track.album.isNotBlank()) {
                        FolioExpandedSubtitle(
                            artist = track.artist,
                            album = track.album,
                            color = fg2,
                            fontSize = 15.sp,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            isLight = isLight,
                            resetKey = track.id,
                        )
                    }
                }
            }

            FolioPlayerAnimatedControlsSlot(
                controlsExpanded = controlsExpanded,
                reducedMotion = reducedMotion,
                track = track,
                tierSpec = tierSpec,
                displayState = displayState,
                positionState = positionState,
                durationMs = durationMs,
                canSeek = canSeek,
                isLiveStream = isLiveStream,
                playWhenReady = playWhenReady,
                lyricsDisplayActive = lyricsDisplayActive,
                queueOpen = queueOpen,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                isFavorite = isFavorite,
                isInLibrary = isInLibrary,
                favoriteInProgress = favoriteInProgress,
                libraryInProgress = libraryInProgress,
                isLight = isLight,
                fg = fg,
                fg2 = fg2,
                favoriteAccent = accent,
                onExpandCapsule = onReveal,
                onSeekTo = onSeekTo,
                onOpenLyricsSettings = onOpenLyricsSettings,
                onToggleLibrary = onToggleLibrary,
                onToggleFavorite = onToggleFavorite,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onCollapsedPlayPause = onPlayPause,
                onNext = onNext,
                onToggleQueue = onToggleQueue,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Column 2: scrolling lyrics / poster / no lyrics
        Box(modifier = Modifier.weight(1.25f).fillMaxHeight()) {
            when (displayState) {
                FolioPlayerDisplayState.Lyrics -> FolioScrollingLyricsPanel(
                    lines = displayedLines,
                    positionState = positionState,
                    offsetMs = offsetMs,
                    lowPerformance = lowPerformance,
                    controlsVisible = controlsExpanded,
                    canSeek = canSeek,
                    fontSizeSp = tierSpec.fs.value * textScaleMultiplier,
                    anchorFraction = tierSpec.anchorFraction,
                    isLight = isLight,
                    onReveal = onReveal,
                    onLineSeek = onLineSeek,
                )

                FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                    lines = displayedLines,
                    positionState = positionState,
                    offsetMs = offsetMs,
                    translationEnabled = translationEnabled,
                    pronunciationEnabled = pronunciationEnabled,
                    baseSp = tierSpec.pfs.value * textScaleMultiplier,
                    isLight = isLight,
                    fg = fg,
                    fg2 = fg2,
                    duetAccentColor = duetAccentColor,
                    lowPerformance = lowPerformance,
                    reducedMotion = reducedMotion,
                )

                else -> FolioNoLyricsStateBox(
                    track = track,
                    tier = tierSpec.tier,
                    lyricsState = lyricsState,
                    fg = fg,
                    fg2 = fg2,
                    pillBg = pillBg,
                    pillBorder = pillBorder,
                    pullHandle = pullHandle,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onRetryLyrics = onRetryLyrics,
                )
            }
        }

        // Column 3: always-on vertical queue
        Box(modifier = Modifier.weight(0.95f).fillMaxHeight()) {
            FolioQueuePanel(
                snapshot = queueSnapshot,
                tierSpec = tierSpec,
                fg = fg,
                fg2 = fg2,
                accent = accent,
                isLight = isLight,
                isPlaying = isPlaying,
                onSelectTimelineTrack = onSelectTimelineTrack,
                onPlayStationTrack = onPlayStationTrack,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onClearQueue = onClearQueue,
                lowPerformance = lowPerformance,
                reducedMotion = reducedMotion,
            )
        }
    }
}

@Composable
private fun VerticalPlayerBody(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    underlayDisplayState: FolioPlayerDisplayState,
    queuePanelVisible: Boolean,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: FolioPlaybackPosition,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    textScaleMultiplier: Float,
    controlsExpanded: Boolean,
    reducedMotion: Boolean,
    canSeek: Boolean,
    isLight: Boolean,
    isPlaying: Boolean,
    fg: Color,
    fg2: Color,
    duetAccentColor: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val pillBg = if (isLight) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.14f)
    val pillBorder = if (isLight) Color(0xFF17151C).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val underlayTopClearance = when {
            tierSpec.tier == FolioTier.Narrow && style == LyricBackgroundStyle.Soak &&
                underlayDisplayState != FolioPlayerDisplayState.None -> maxHeight * 0.24f
            tierSpec.tier == FolioTier.Medium && style == LyricBackgroundStyle.Soak -> maxHeight * 0.20f
            else -> 0.dp
        }

        FolioQueuePopoverContainer(
            queueOpenSelected = displayState == FolioPlayerDisplayState.Queue,
            lowPerformance = lowPerformance,
            reducedMotion = reducedMotion,
            underlay = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = underlayTopClearance),
                ) {
                    if (tierSpec.tier == FolioTier.Medium && style == LyricBackgroundStyle.Flow &&
                        underlayDisplayState == FolioPlayerDisplayState.None
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            FolioArtwork(
                                track = track,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .size(116.dp)
                                    .folioTrackSwitchCover()
                                    .shadow(16.dp, RoundedCornerShape(14.dp))
                                    .then(pullHandle),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy((-4).dp),
                            ) {
                                FolioMarqueeTrackTitle(
                                    track = track,
                                    color = fg,
                                    fontFamily = LocalFolioFontFamily.current,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 22.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                FolioExpandedSubtitle(
                                    artist = track.artist,
                                    album = track.album,
                                    color = fg2,
                                    fontSize = 15.sp,
                                    onOpenArtist = onOpenArtist,
                                    onOpenAlbum = onOpenAlbum,
                                    isLight = isLight,
                                    resetKey = track.id,
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            queuePanelVisible -> FolioQueuePanel(
                                snapshot = queueSnapshot,
                                tierSpec = tierSpec,
                                fg = fg,
                                fg2 = fg2,
                                accent = accent,
                                isLight = isLight,
                                isPlaying = isPlaying,
                                onSelectTimelineTrack = onSelectTimelineTrack,
                                onPlayStationTrack = onPlayStationTrack,
                                onCycleRepeat = onCycleRepeat,
                                onToggleShuffle = onToggleShuffle,
                                onClearQueue = onClearQueue,
                                lowPerformance = lowPerformance,
                                reducedMotion = reducedMotion,
                            )

                            underlayDisplayState == FolioPlayerDisplayState.Lyrics -> FolioScrollingLyricsPanel(
                                lines = displayedLines,
                                positionState = positionState,
                                offsetMs = offsetMs,
                                lowPerformance = lowPerformance,
                                controlsVisible = controlsExpanded,
                                canSeek = canSeek,
                                fontSizeSp = tierSpec.fs.value * textScaleMultiplier,
                                anchorFraction = tierSpec.anchorFraction,
                                isLight = isLight,
                                onReveal = onReveal,
                                onLineSeek = onLineSeek,
                            )

                            underlayDisplayState == FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                                lines = displayedLines,
                                positionState = positionState,
                                offsetMs = offsetMs,
                                translationEnabled = translationEnabled,
                                pronunciationEnabled = pronunciationEnabled,
                                baseSp = tierSpec.pfs.value * textScaleMultiplier,
                                isLight = isLight,
                                fg = fg,
                                fg2 = fg2,
                                duetAccentColor = duetAccentColor,
                                lowPerformance = lowPerformance,
                                reducedMotion = reducedMotion,
                            )

                            else -> FolioNoLyricsStateBox(
                                track = track,
                                tier = tierSpec.tier,
                                lyricsState = lyricsState,
                                fg = fg,
                                fg2 = fg2,
                                pillBg = pillBg,
                                pillBorder = pillBorder,
                                pullHandle = pullHandle,
                                onOpenAlbum = onOpenAlbum,
                                onOpenArtist = onOpenArtist,
                                onRetryLyrics = onRetryLyrics,
                            )
                        }
                    }
                }
            },
            queueOverlay = {
                FolioQueuePanel(
                    snapshot = queueSnapshot,
                    tierSpec = tierSpec,
                    fg = fg,
                    fg2 = fg2,
                    accent = accent,
                    isLight = isLight,
                    isPlaying = isPlaying,
                    onSelectTimelineTrack = onSelectTimelineTrack,
                    onPlayStationTrack = onPlayStationTrack,
                    onCycleRepeat = onCycleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onClearQueue = onClearQueue,
                    lowPerformance = lowPerformance,
                    reducedMotion = reducedMotion,
                )
            },
        )
    }
}

/**
 * Dedicated Short (`m`, phone landscape) layout matching H5 `.t-m`:
 * - Right column `.lyr`: pinned at `left: 384dp .. right: 28dp`, `top: sb+4dp .. bottom: nb+2dp`
 *   so toggling controls never jumps or resizes the lyrics/poster container.
 * - Left column: `left: 16dp`, `width: 336dp` glass `.ctl` / `.pmeta`, plus `128dp` Flow cover above.
 */
@Composable
private fun ShortPlayerLayout(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    underlayDisplayState: FolioPlayerDisplayState,
    queuePanelVisible: Boolean,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: FolioPlaybackPosition,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    lyricsDisplayActive: Boolean,
    textScaleMultiplier: Float,
    controlsExpanded: Boolean,
    reducedMotion: Boolean,
    queueOpen: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLight: Boolean,
    statusTop: Dp,
    navBottom: Dp,
    fg: Color,
    fg2: Color,
    duetAccentColor: Color,
    accent: Color,
    headerColors: HeaderColorScheme,
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    hasTimedLyrics: Boolean,
    notice: String?,
    pullHandle: Modifier,
    onClose: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePosterMode: () -> Unit,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenLyricsSettings: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onCollapsedPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val lyricsStart = shortTierLyricsStartDp(maxWidth.value)

        // Right full-height column (unaffected by control expansion/collapse)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusTop + 4.dp, bottom = navBottom + 2.dp),
        ) {
            FolioQueuePopoverContainer(
                queueOpenSelected = displayState == FolioPlayerDisplayState.Queue,
                lowPerformance = lowPerformance,
                reducedMotion = reducedMotion,
                underlay = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = lyricsStart, end = 28.dp),
                    ) {
                        when {
                            queuePanelVisible -> Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            ) {
                                Text(
                                    text = if (lyricsState.isLoading) {
                                        stringResource(R.string.player_loading)
                                    } else {
                                        stringResource(R.string.folio_player_no_lyrics)
                                    },
                                    color = fg2,
                                    fontFamily = fontFamily,
                                    fontSize = 15.sp,
                                )
                                FolioQueuePanel(
                                    snapshot = queueSnapshot,
                                    tierSpec = tierSpec,
                                    fg = fg,
                                    fg2 = fg2,
                                    accent = accent,
                                    isLight = isLight,
                                    isPlaying = isPlaying,
                                    onSelectTimelineTrack = onSelectTimelineTrack,
                                    onPlayStationTrack = onPlayStationTrack,
                                    onCycleRepeat = onCycleRepeat,
                                    onToggleShuffle = onToggleShuffle,
                                    onClearQueue = onClearQueue,
                                    lowPerformance = lowPerformance,
                                    reducedMotion = reducedMotion,
                                )
                            }

                            underlayDisplayState == FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                                lines = displayedLines,
                                positionState = positionState,
                                offsetMs = offsetMs,
                                translationEnabled = translationEnabled,
                                pronunciationEnabled = pronunciationEnabled,
                                baseSp = tierSpec.pfs.value * textScaleMultiplier,
                                isLight = isLight,
                                fg = fg,
                                fg2 = fg2,
                                duetAccentColor = duetAccentColor,
                                lowPerformance = lowPerformance,
                                reducedMotion = reducedMotion,
                            )

                            else -> FolioScrollingLyricsPanel(
                                lines = displayedLines,
                                positionState = positionState,
                                offsetMs = offsetMs,
                                lowPerformance = lowPerformance,
                                controlsVisible = controlsExpanded,
                                canSeek = canSeek,
                                fontSizeSp = tierSpec.fs.value * textScaleMultiplier,
                                anchorFraction = tierSpec.anchorFraction,
                                isLight = isLight,
                                onReveal = onReveal,
                                onLineSeek = onLineSeek,
                            )
                        }
                    }
                },
                queueOverlay = {
                    FolioQueuePanel(
                        snapshot = queueSnapshot,
                        tierSpec = tierSpec,
                        fg = fg,
                        fg2 = fg2,
                        accent = accent,
                        isLight = isLight,
                        isPlaying = isPlaying,
                        onSelectTimelineTrack = onSelectTimelineTrack,
                        onPlayStationTrack = onPlayStationTrack,
                        onCycleRepeat = onCycleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onClearQueue = onClearQueue,
                        lowPerformance = lowPerformance,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.padding(start = lyricsStart - 8.dp, end = 16.dp),
                    )
                },
            )
        }

        // Left artwork in Flow centered cleanly above controls/capsule without colliding when expanded
        val density = LocalDensity.current
        var shortControlsHeightPx by remember { mutableIntStateOf(0) }
        val measuredControlsDp = with(density) { shortControlsHeightPx.toDp() }
        val activeControlsReservedDp = if (controlsExpanded) {
            if (measuredControlsDp > 100.dp) measuredControlsDp else 216.dp
        } else {
            64.dp
        }
        val topReservedDp = statusTop + 28.dp
        val bottomReservedDp = navBottom + 8.dp + activeControlsReservedDp + 12.dp
        val coverLayout = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = tierSpec.controlCardWidth,
            leftColumnHeightDp = (maxHeight - statusTop - navBottom).coerceAtLeast(0.dp),
            availableHeightAboveControlsDp = (maxHeight - topReservedDp - bottomReservedDp).coerceAtLeast(0.dp),
            minBaselineCoverDp = 120.dp,
            minVisibleCoverDp = 96.dp,
        )
        val targetCoverAlpha = if (style == LyricBackgroundStyle.Flow &&
            displayState != FolioPlayerDisplayState.Queue &&
            coverLayout.showCover
        ) {
            1f
        } else {
            0f
        }
        val coverAlpha = if (lowPerformance || reducedMotion) {
            targetCoverAlpha
        } else {
            animateFloatAsState(
                targetValue = targetCoverAlpha,
                animationSpec = tween(FolioPlayerMotionTokens.CTL_OPACITY_MS),
                label = "short-flow-cover-alpha",
            ).value
        }
        if (coverAlpha > 0.01f && coverLayout.coverSizeDp > 0.dp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = topReservedDp, bottom = bottomReservedDp)
                    .width(tierSpec.controlCardWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = coverAlpha },
                contentAlignment = Alignment.Center,
            ) {
                FolioArtwork(
                    track = track,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .size(coverLayout.coverSizeDp)
                        .folioTrackSwitchCover()
                        .shadow(20.dp, RoundedCornerShape(14.dp))
                        .then(pullHandle),
                )
            }
        }

        // Left controls `.ctl` (`left: 16dp, width: 336dp`) / `.pmeta`
        FolioPlayerAnimatedControlsSlot(
            controlsExpanded = controlsExpanded,
            reducedMotion = reducedMotion,
            track = track,
            tierSpec = tierSpec,
            displayState = displayState,
            positionState = positionState,
            durationMs = durationMs,
            canSeek = canSeek,
            isLiveStream = isLiveStream,
            playWhenReady = playWhenReady,
            lyricsDisplayActive = lyricsDisplayActive,
            queueOpen = queueOpen,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            isFavorite = isFavorite,
            isInLibrary = isInLibrary,
            favoriteInProgress = favoriteInProgress,
            libraryInProgress = libraryInProgress,
            isLight = isLight,
            fg = fg,
            fg2 = fg2,
            favoriteAccent = accent,
            onExpandCapsule = onReveal,
            onSeekTo = onSeekTo,
            onOpenLyricsSettings = onOpenLyricsSettings,
            onToggleLibrary = onToggleLibrary,
            onToggleFavorite = onToggleFavorite,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onCollapsedPrevious = { },
            onCollapsedPlayPause = onCollapsedPlayPause,
            onCollapsedNext = { },
            onNext = onNext,
            onToggleQueue = onToggleQueue,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = navBottom + 8.dp)
                .width(tierSpec.controlCardWidth)
                .onSizeChanged { shortControlsHeightPx = it.height },
            contentAlignment = Alignment.BottomStart,
        )

        // Overlay header at top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = statusTop + 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FolioPlayerHeader(
                track = track,
                tierSpec = tierSpec,
                headerColors = headerColors,
                favoriteAccent = accent,
                showHeaderTitle = false,
                controlsExpanded = controlsExpanded,
                displayState = displayState,
                reducedMotion = reducedMotion,
                isFavorite = isFavorite,
                isInLibrary = isInLibrary,
                favoriteInProgress = favoriteInProgress,
                libraryInProgress = libraryInProgress,
                isPosterMode = displayState == FolioPlayerDisplayState.Poster,
                hasTimedLyrics = hasTimedLyrics,
                pullHandle = pullHandle,
                onClose = onClose,
                onExpandControls = onReveal,
                onToggleLibrary = onToggleLibrary,
                onToggleFavorite = onToggleFavorite,
                onTogglePosterMode = onTogglePosterMode,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
            )
            if (!notice.isNullOrBlank()) {
                Text(
                    text = notice,
                    color = fg,
                    fontFamily = fontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .folioPlayerGlass(isLight, RoundedCornerShape(FolioRadii.Chip))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TwoColumnPlayerBody(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    underlayDisplayState: FolioPlayerDisplayState,
    queuePanelVisible: Boolean,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: FolioPlaybackPosition,
    durationMs: Long,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    textScaleMultiplier: Float,
    controlsExpanded: Boolean,
    alwaysOpen: Boolean,
    playWhenReady: Boolean,
    reducedMotion: Boolean,
    canSeek: Boolean,
    isLight: Boolean,
    isPlaying: Boolean,
    expandedCarControlsHeightDp: Dp = 226.dp,
    carControlsWidthDp: Dp = 464.dp,
    fg: Color,
    fg2: Color,
    duetAccentColor: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onCollapsedPlayPause: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    val queueTransitionState = rememberFolioQueueVisibilityState(
        queueOpenSelected = displayState == FolioPlayerDisplayState.Queue,
        lowPerformance = lowPerformance,
        reducedMotion = reducedMotion,
        label = "wide-queue-panel",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (queueTransitionState.showUnderlay) {
            val isWideCar = tierSpec.tier == FolioTier.Wide
            // H5 `.t-e .lyr { left: 50%; right: 64px }`: Wide car tier splits 50/50 so lyrics stay in the right half.
            val leftColumnWidth = if (isWideCar) maxWidth * 0.50f else maxWidth * 0.38f
            val bodyHeight = maxHeight
            val showUnderCoverCollapsed = isWideCar &&
                !alwaysOpen &&
                resolveCollapsedLayout(tierSpec.tier, underlayDisplayState) == FolioCollapsedLayout.UnderCoverInfo
            val targetPmetaAlpha = if (showUnderCoverCollapsed && !controlsExpanded) 1f else 0f
            val pmetaAlpha = if (lowPerformance) targetPmetaAlpha else animateFloatAsState(
                targetValue = targetPmetaAlpha,
                animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_OPACITY_MS),
                label = "wide-undercover-alpha",
            ).value

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = queueTransitionState.underlayAlpha },
                horizontalArrangement = Arrangement.spacedBy(if (isWideCar) 0.dp else 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left column: Wide car rolling lyrics show large cover (plus under-cover collapsed info when Soak & collapsed);
                // Wide car Poster keeps left clean for backdrop color glow.
                Box(
                    modifier = Modifier
                        .width(leftColumnWidth)
                        .fillMaxHeight()
                        .then(pullHandle),
                    contentAlignment = if (showUnderCoverCollapsed && style == LyricBackgroundStyle.Flow) {
                        Alignment.TopStart
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    if (isWideCar) {
                        val innerLeftWidth = (leftColumnWidth - 36.dp).coerceAtLeast(160.dp)
                        if (showUnderCoverCollapsed) {
                            val legacyCollapsedCoverSize = computeWideCarCollapsedCoverSizeDp(
                                leftColumnWidthDp = leftColumnWidth,
                                bodyHeightDp = bodyHeight,
                            )
                            val collapsedCoverSize = if (style == LyricBackgroundStyle.Flow) {
                                computeFlowLandscapeCoverLayout(
                                    leftColumnWidthDp = innerLeftWidth,
                                    leftColumnHeightDp = bodyHeight,
                                    availableHeightAboveControlsDp = (bodyHeight - 134.dp).coerceAtLeast(160.dp),
                                    minBaselineCoverDp = legacyCollapsedCoverSize,
                                ).coverSizeDp
                            } else {
                                legacyCollapsedCoverSize
                            }
                            val expandedCoverSize = computeWideCarExpandedCoverSizeDp(
                                collapsedCoverDp = collapsedCoverSize,
                                bodyHeightDp = bodyHeight,
                                controlsCardHeightDp = expandedCarControlsHeightDp,
                            )
                            val targetShrink = if (controlsExpanded) 1f else 0f
                            val coverShrinkFraction = if (lowPerformance) targetShrink else animateFloatAsState(
                                targetValue = targetShrink,
                                animationSpec = tween(
                                    durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_TRANSLATE_MS,
                                    easing = FolioPlayerMotionTokens.EnterEasing,
                                ),
                                label = "wide-cover-shrink",
                            ).value
                            val targetScale = (expandedCoverSize / collapsedCoverSize).coerceIn(0.35f, 1f)
                            val coverScale = 1f + (targetScale - 1f) * coverShrinkFraction

                            val topOffsetDp = if (style == LyricBackgroundStyle.Flow) {
                                val collapsedBlockHeight = collapsedCoverSize + 130.dp
                                val collapsedTop = ((bodyHeight - collapsedBlockHeight) * 0.5f).coerceAtLeast(0.dp)
                                val expandedAvailable = (bodyHeight - expandedCarControlsHeightDp - 4.dp - 24.dp)
                                    .coerceAtLeast(expandedCoverSize)
                                val expandedTop = ((expandedAvailable - expandedCoverSize) * 0.5f).coerceAtLeast(8.dp)
                                collapsedTop + (expandedTop - collapsedTop) * coverShrinkFraction
                            } else {
                                8.dp
                            }

                            Column(
                                modifier = Modifier.padding(start = 36.dp, top = topOffsetDp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                FolioArtwork(
                                    track = track,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .size(collapsedCoverSize)
                                        .graphicsLayer {
                                            scaleX = coverScale
                                            scaleY = coverScale
                                            transformOrigin = if (style == LyricBackgroundStyle.Flow) {
                                                TransformOrigin(0.5f, 0f)
                                            } else {
                                                TransformOrigin(0f, 0f)
                                            }
                                        }
                                        .folioTrackSwitchCover()
                                        .shadow(24.dp, RoundedCornerShape(14.dp))
                                        .clip(RoundedCornerShape(14.dp))
                                        .folioPressable(
                                            enabled = !controlsExpanded,
                                            isLight = isLight,
                                            onClick = onReveal,
                                        ),
                                )
                                if (!controlsExpanded || pmetaAlpha > 0.01f) {
                                    FolioCarUnderCoverCollapsedBar(
                                        track = track,
                                        positionState = positionState,
                                        durationMs = durationMs,
                                        playWhenReady = playWhenReady,
                                        isLight = isLight,
                                        fg = fg,
                                        fg2 = fg2,
                                        interactive = !controlsExpanded,
                                        onExpand = onReveal,
                                        onTogglePlay = onCollapsedPlayPause,
                                        modifier = Modifier
                                            .width(collapsedCoverSize)
                                            .graphicsLayer { alpha = pmetaAlpha },
                                    )
                                }
                            }
                        } else if (underlayDisplayState == FolioPlayerDisplayState.Lyrics ||
                            (underlayDisplayState == FolioPlayerDisplayState.None && style == LyricBackgroundStyle.Flow)
                        ) {
                            val bottomReserved = if (controlsExpanded) expandedCarControlsHeightDp + 28.dp else 76.dp
                            val availableHeight = (bodyHeight - bottomReserved - 16.dp).coerceAtLeast(120.dp)
                            val baselineCover = min((bodyHeight - 16.dp).coerceAtLeast(120.dp), min(leftColumnWidth * 0.78f, 400.dp))
                            val coverSize = if (style == LyricBackgroundStyle.Flow) {
                                computeFlowLandscapeCoverLayout(
                                    leftColumnWidthDp = innerLeftWidth,
                                    leftColumnHeightDp = bodyHeight,
                                    availableHeightAboveControlsDp = availableHeight,
                                    minBaselineCoverDp = baselineCover,
                                ).coverSizeDp
                            } else {
                                baselineCover
                            }
                            if (coverSize > 0.dp) {
                                val legacySlotWidth = computeWideCarCollapsedCoverSizeDp(leftColumnWidth, bodyHeight)
                                val extraCenterStart = ((legacySlotWidth - coverSize) * 0.5f).coerceAtLeast(0.dp)
                                FolioArtwork(
                                    track = track,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .padding(start = 36.dp + extraCenterStart, bottom = bottomReserved)
                                        .size(coverSize)
                                        .folioTrackSwitchCover()
                                        .shadow(24.dp, RoundedCornerShape(14.dp)),
                                )
                            }
                        }
                    } else if (style == LyricBackgroundStyle.Flow) {
                        val coverLayout = computeFlowLandscapeCoverLayout(
                            leftColumnWidthDp = leftColumnWidth,
                            leftColumnHeightDp = bodyHeight,
                            availableHeightAboveControlsDp = (bodyHeight - 96.dp).coerceAtLeast(120.dp),
                            minBaselineCoverDp = min(bodyHeight * 0.72f, 200.dp),
                        )
                        val coverSize = coverLayout.coverSizeDp
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FolioArtwork(
                                track = track,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .size(coverSize)
                                    .folioTrackSwitchCover()
                                    .shadow(24.dp, RoundedCornerShape(16.dp)),
                            )
                            Column(
                                modifier = Modifier.widthIn(max = coverSize),
                                verticalArrangement = Arrangement.spacedBy((-4).dp),
                            ) {
                                FolioMarqueeTrackTitle(
                                    track = track,
                                    color = fg,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (track.artist.isNotBlank() || track.album.isNotBlank()) {
                                    FolioExpandedSubtitle(
                                        artist = track.artist,
                                        album = track.album,
                                        color = fg2,
                                        fontSize = 14.sp,
                                        onOpenArtist = onOpenArtist,
                                        onOpenAlbum = onOpenAlbum,
                                        isLight = isLight,
                                        resetKey = track.id,
                                    )
                                }
                            }
                        }
                    }
                }

                // Right column: lyrics / poster / queue fallback when no lyrics
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (isWideCar) Modifier.padding(end = 28.dp) else Modifier),
                ) {
                    when {
                        queuePanelVisible -> Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                        ) {
                            val noLyricsLabel = when {
                                lyricsState.isLoading -> stringResource(R.string.player_loading)
                                else -> stringResource(R.string.folio_player_no_lyrics)
                            }
                            Text(
                                text = noLyricsLabel,
                                color = fg2,
                                fontFamily = fontFamily,
                                fontSize = 15.sp,
                            )
                            FolioQueuePanel(
                                snapshot = queueSnapshot,
                                tierSpec = tierSpec,
                                fg = fg,
                                fg2 = fg2,
                                accent = accent,
                                isLight = isLight,
                                isPlaying = isPlaying,
                                onSelectTimelineTrack = onSelectTimelineTrack,
                                onPlayStationTrack = onPlayStationTrack,
                                onCycleRepeat = onCycleRepeat,
                                onToggleShuffle = onToggleShuffle,
                                onClearQueue = onClearQueue,
                                lowPerformance = lowPerformance,
                                reducedMotion = reducedMotion,
                            )
                        }

                        underlayDisplayState == FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                            lines = displayedLines,
                            positionState = positionState,
                            offsetMs = offsetMs,
                            translationEnabled = translationEnabled,
                            pronunciationEnabled = pronunciationEnabled,
                            baseSp = tierSpec.pfs.value * textScaleMultiplier,
                            isLight = isLight,
                            fg = fg,
                            fg2 = fg2,
                            duetAccentColor = duetAccentColor,
                            lowPerformance = lowPerformance,
                            reducedMotion = reducedMotion,
                        )

                        else -> FolioScrollingLyricsPanel(
                            lines = displayedLines,
                            positionState = positionState,
                            offsetMs = offsetMs,
                            lowPerformance = lowPerformance,
                            controlsVisible = controlsExpanded,
                            canSeek = canSeek,
                            fontSizeSp = tierSpec.fs.value * textScaleMultiplier,
                            anchorFraction = tierSpec.anchorFraction,
                            isLight = isLight,
                            onReveal = onReveal,
                            onLineSeek = onLineSeek,
                        )
                    }
                }
            }
        }

        if (queueTransitionState.showQueueOverlay) {
            val targetQueueStartDp = computeWideQueueStartPaddingDp(
                tier = tierSpec.tier,
                controlsExpanded = controlsExpanded,
                controlsCardWidthDp = carControlsWidthDp,
            )
            val queueStartDp = if (lowPerformance) targetQueueStartDp else animateDpAsState(
                targetValue = targetQueueStartDp,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_TRANSLATE_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                ),
                label = "wide-queue-start-pad",
            ).value
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = queueStartDp)
                    .graphicsLayer {
                        val p = queueTransitionState.queueOverlayAlpha
                        alpha = p
                        if (!lowPerformance && !reducedMotion) {
                            translationX = -FolioPlayerMotionTokens.QUEUE_WIDE_SLIDE_DP.dp.toPx() * (1f - p)
                        }
                    },
                verticalArrangement = Arrangement.Center,
            ) {
                FolioQueuePanel(
                    snapshot = queueSnapshot,
                    tierSpec = tierSpec,
                    fg = fg,
                    fg2 = fg2,
                    accent = accent,
                    isLight = isLight,
                    isPlaying = isPlaying,
                    onSelectTimelineTrack = onSelectTimelineTrack,
                    onPlayStationTrack = onPlayStationTrack,
                    onCycleRepeat = onCycleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onClearQueue = onClearQueue,
                    lowPerformance = lowPerformance,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }
}

private data class FolioQueueVisibilityState(
    val showUnderlay: Boolean,
    val showQueueOverlay: Boolean,
    val underlayAlpha: Float,
    val queueOverlayAlpha: Float,
)

@Composable
private fun rememberFolioQueueVisibilityState(
    queueOpenSelected: Boolean,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    label: String,
): FolioQueueVisibilityState {
    if (lowPerformance || reducedMotion) {
        return FolioQueueVisibilityState(
            showUnderlay = !queueOpenSelected,
            showQueueOverlay = queueOpenSelected,
            underlayAlpha = 1f,
            queueOverlayAlpha = 1f,
        )
    }
    val transition = updateTransition(targetState = queueOpenSelected, label = label)
    val progressState = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    durationMillis = FolioPlayerMotionTokens.QUEUE_POPOVER_OPEN_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                )
            } else {
                tween(
                    durationMillis = FolioPlayerMotionTokens.QUEUE_POPOVER_CLOSE_MS,
                    easing = FolioPlayerMotionTokens.ExitEasing,
                )
            }
        },
        label = "$label-progress",
    ) { if (it) 1f else 0f }
    return FolioQueueVisibilityState(
        showUnderlay = !transition.currentState || transition.currentState != transition.targetState,
        showQueueOverlay = transition.currentState || transition.targetState,
        underlayAlpha = 1f - progressState.value,
        queueOverlayAlpha = progressState.value,
    )
}

@Composable
private fun FolioQueuePopoverContainer(
    queueOpenSelected: Boolean,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    underlay: @Composable () -> Unit,
    queueOverlay: @Composable () -> Unit,
) {
    val visibility = rememberFolioQueueVisibilityState(
        queueOpenSelected = queueOpenSelected,
        lowPerformance = lowPerformance,
        reducedMotion = reducedMotion,
        label = "queue-popover",
    )
    val anchors = LocalFolioPlayerAnchors.current
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(0.88f, 1.05f)) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (visibility.showUnderlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = visibility.underlayAlpha },
            ) {
                underlay()
            }
        }
        if (visibility.showQueueOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPlaced { panelCoords ->
                        val btn = anchors?.queueButtonCoords
                        if (btn != null && btn.isAttached && panelCoords.isAttached) {
                            val btnPos = panelCoords.localPositionOf(btn, Offset.Zero)
                            anchorOrigin = computeAnchorTransformOrigin(
                                anchorCenterX = btnPos.x + btn.size.width * 0.5f,
                                anchorCenterY = btnPos.y + btn.size.height * 0.5f,
                                panelWidth = panelCoords.size.width.toFloat(),
                                panelHeight = panelCoords.size.height.toFloat(),
                                defaultOrigin = TransformOrigin(0.88f, 1.05f),
                            )
                        }
                    }
                    .graphicsLayer {
                        val p = visibility.queueOverlayAlpha
                        alpha = p
                        if (!lowPerformance && !reducedMotion) {
                            val s = FolioPlayerMotionTokens.MENU_CLOSED_SCALE +
                                (1f - FolioPlayerMotionTokens.MENU_CLOSED_SCALE) * p
                            scaleX = s
                            scaleY = s
                            transformOrigin = anchorOrigin
                        }
                    },
            ) {
                queueOverlay()
            }
        }
    }
}

private class FolioPullDownToClose(
    val handle: Modifier,
    val layer: Modifier,
)

@Composable
private fun rememberFolioPullDownToClose(onClose: () -> Unit): FolioPullDownToClose {
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val close by rememberUpdatedState(onClose)
    return remember(scope, pull) {
        FolioPullDownToClose(
            handle = Modifier.pointerInput(Unit) {
                val threshold = 120.dp.toPx()
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (pull.value > threshold) {
                                close()
                            }
                            pull.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f))
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            pull.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f))
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        val next = pull.value + dragAmount
                        pull.snapTo(if (next < 0f) next * 0.25f else next)
                    }
                }
            },
            layer = Modifier.graphicsLayer {
                val h = size.height.coerceAtLeast(1f)
                val fraction = (pull.value / h).coerceIn(0f, 1f)
                translationY = pull.value
                scaleX = 1f - 0.08f * fraction
                scaleY = 1f - 0.08f * fraction
                alpha = 1f - 0.6f * fraction
            },
        )
    }
}

private val topStripLumaCache = android.util.LruCache<String, Float>(64)

@Composable
private fun rememberArtworkTopLuminance(url: String?, fallback: List<Color>): Float? {
    val context = LocalContext.current.applicationContext
    val fallbackLuma = remember(fallback) {
        if (fallback.isEmpty()) null else relativeLuminance(fallback.first())
    }
    return produceState(initialValue = url?.let(topStripLumaCache::get) ?: fallbackLuma, url, fallbackLuma) {
        if (url == null || !url.startsWith("https://")) {
            value = fallbackLuma
            return@produceState
        }
        topStripLumaCache.get(url)?.let { value = it; return@produceState }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(48)
            .allowHardware(false)
            .memoryCacheKey("palette:$url")
            .build()
        val image = ArtworkImages.loader(context).execute(request).image ?: return@produceState
        val luma = withContext(ArtworkImages.decodeDispatcher) {
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            computeTopStripLuminance(pixels, bitmap.width, bitmap.height)
        }
        topStripLumaCache.put(url, luma)
        value = luma
    }.value
}

@Composable
internal fun rememberFolioPlaybackPositionMs(
    playbackState: PlaybackState,
    active: Boolean,
): FolioPlaybackPosition {
    val state = remember { FolioPlaybackPosition(playbackState.positionAnchorMs) }
    LaunchedEffect(
        active,
        playbackState.positionAnchorMs,
        playbackState.playStartedRealtimeMs,
        playbackState.isPlaying,
    ) {
        val positionMs = if (playbackState.isPlaying) {
            playbackState.positionAnchorMs + (SystemClock.elapsedRealtime() - playbackState.playStartedRealtimeMs).coerceAtLeast(0L)
        } else {
            playbackState.positionAnchorMs
        }
        state.updateAnchor(playbackState.positionAnchorMs, positionMs)
        if (!active || !playbackState.isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                state.update(playbackState.positionAnchorMs +
                    (SystemClock.elapsedRealtime() - playbackState.playStartedRealtimeMs).coerceAtLeast(0L))
            }
        }
    }
    return state
}
