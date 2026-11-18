package dev.amatv.flyme

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.amatv.AmAtvApplication
import dev.amatv.R
import dev.amatv.core.LibraryNotice
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.PlaybackState
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.model.forDisplay
import dev.amatv.ui.rememberArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        enter = slideInVertically(
            animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(FolioMotion.FAST_MS)),
        exit = slideOutVertically(
            animationSpec = tween(FolioMotion.FAST_MS, easing = FolioMotion.PageEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(FolioMotion.FAST_MS)),
    ) {
        BackHandler(enabled = open, onBack = onClose)

        val palette = LocalFolioPalette.current
        val tierSpec = playerSpecForTier(LocalFolioTierSpec.current.tier)
        val scope = rememberCoroutineScope()

        var queueOpen by remember { mutableStateOf(false) }
        var posterPreferred by remember { mutableStateOf(false) }
        var controlsManualOpen by remember { mutableStateOf(true) }
        var revealPulse by remember { mutableLongStateOf(0L) }
        var navigationMessage by remember { mutableStateOf<String?>(null) }

        val rawLines = remember(lyricsState, currentIdentity, track.lyrics) {
            when {
                lyricsState.trackIdentity == currentIdentity && lyricsState.lines.isNotEmpty() -> lyricsState.lines
                else -> track.lyrics
            }
        }
        val displayedLines = remember(
            rawLines,
            settings.lyricTranslationEnabled,
            settings.lyricPronunciationEnabled,
        ) {
            rawLines.map { line ->
                line.forDisplay(
                    showTranslation = settings.lyricTranslationEnabled,
                    showPronunciation = settings.lyricPronunciationEnabled,
                )
            }
        }
        val hasLyrics = displayedLines.isNotEmpty()
        val hasTimed = remember(displayedLines) { hasTimedLyrics(displayedLines) }
        val displayState = resolvePlayerDisplayState(
            tier = tierSpec.tier,
            queueOpen = queueOpen,
            posterPreferred = posterPreferred,
            hasLyrics = hasLyrics,
            hasTimedLyrics = hasTimed,
        )
        val queuePanelVisible = isQueuePanelVisible(tierSpec.tier, displayState)
        val alwaysOpen = alwaysOpenControls(
            tier = tierSpec.tier,
            style = settings.lyricStyle,
            hasLyrics = hasLyrics,
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
        ) {
            if (open && controlsExpanded && !alwaysOpen && playbackState.playWhenReady && !queueOpen) {
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

        val artworkPalette = rememberArtworkPalette(track.artworkUrl, track.palette)
        val fg = if (palette.isLight) Color(0xFF16141B) else Color.White
        val fg2 = if (palette.isLight) Color(0xFF16141B).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.68f)
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
        }

        val queueSnapshot = remember(queueState, playbackState) {
            buildFolioQueueSnapshot(queueState, playbackState)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(pullDown.layer),
        ) {
            FolioPlayerBackdrop(
                track = track,
                artworkPalette = artworkPalette,
                style = settings.lyricStyle,
                isLight = palette.isLight,
                animateFlow = !settings.lowPerformanceMode,
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
            )

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
                    track = track,
                    tierSpec = tierSpec,
                    fg = fg,
                    fg2 = fg2,
                    accent = accent,
                    isLight = palette.isLight,
                    showHeaderTitle = showHeaderTitle(tierSpec.tier, controlsExpanded, displayState),
                    isFavorite = libraryState.current.isFavorite == true,
                    isInLibrary = libraryState.current.isInLibrary == true,
                    favoriteInProgress = libraryState.current.isFavoriteInProgress,
                    libraryInProgress = libraryState.current.isLibraryInProgress,
                    isPosterMode = displayState == FolioPlayerDisplayState.Poster,
                    hasTimedLyrics = hasTimed,
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
                            posterPreferred = !posterPreferred
                        }
                    },
                    onOpenAlbum = openAlbumDetail,
                    onOpenArtist = openArtistDetail,
                )

                val libraryNoticeText = when (libraryState.notice) {
                    is LibraryNotice.AddedToLibrary -> stringResource(R.string.folio_player_in_library)
                    is LibraryNotice.AddedToFavorites -> stringResource(R.string.player_favorite_add)
                    is LibraryNotice.FavoriteFailed -> stringResource(R.string.player_favorite_failed)
                    is LibraryNotice.FavoriteRemoveUnsupported -> stringResource(R.string.player_favorite_remove_unsupported)
                    is LibraryNotice.AddToLibraryFailed,
                    is LibraryNotice.RemoveFromLibraryUnsupported -> stringResource(R.string.player_open_unavailable)
                    null -> null
                }
                val notice = navigationMessage ?: libraryNoticeText
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
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tierSpec.tier) {
                        FolioTier.Ultrawide -> {
                            UltrawidePlayerBody(
                                track = track,
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
                                lowPerformance = settings.lowPerformanceMode,
                                translationEnabled = settings.lyricTranslationEnabled,
                                controlsExpanded = controlsExpanded,
                                queueOpen = queueOpen,
                                playWhenReady = playbackState.playWhenReady,
                                isPlaying = playbackState.isPlaying,
                                canSkipPrevious = canSkipPrevious,
                                canSkipNext = canSkipNext,
                                isLight = palette.isLight,
                                fg = fg,
                                fg2 = fg2,
                                accent = accent,
                                pullHandle = pullDown.handle,
                                onReveal = revealControls,
                                onLineSeek = seekToMs,
                                onSeekTo = seekToMs,
                                onToggleTranslation = {
                                    revealControls()
                                    settingsStore.setLyricTranslationEnabled(!settings.lyricTranslationEnabled)
                                },
                                onPrevious = { playbackStore.skip(-1) },
                                onPlayPause = playbackStore::togglePlayback,
                                onNext = { playbackStore.skip(1) },
                                onToggleQueue = { revealControls() },
                                onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                onRetryLyrics = playbackStore::retryCurrentLyrics,
                                onOpenAlbum = openAlbumDetail,
                                onOpenArtist = openArtistDetail,
                            )
                        }

                        FolioTier.Narrow, FolioTier.Medium -> {
                            VerticalPlayerBody(
                                track = track,
                                tierSpec = tierSpec,
                                style = settings.lyricStyle,
                                displayState = displayState,
                                queuePanelVisible = queuePanelVisible,
                                displayedLines = displayedLines,
                                lyricsState = lyricsState,
                                queueSnapshot = queueSnapshot,
                                positionState = positionState,
                                offsetMs = settings.lyricOffsetMs,
                                lowPerformance = settings.lowPerformanceMode,
                                translationEnabled = settings.lyricTranslationEnabled,
                                controlsExpanded = controlsExpanded,
                                canSeek = canSeek,
                                isLight = palette.isLight,
                                isPlaying = playbackState.isPlaying,
                                fg = fg,
                                fg2 = fg2,
                                accent = accent,
                                pullHandle = pullDown.handle,
                                onReveal = revealControls,
                                onLineSeek = seekToMs,
                                onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                onRetryLyrics = playbackStore::retryCurrentLyrics,
                                onOpenAlbum = openAlbumDetail,
                                onOpenArtist = openArtistDetail,
                            )
                        }

                        FolioTier.Short, FolioTier.Wide -> {
                            TwoColumnPlayerBody(
                                track = track,
                                tierSpec = tierSpec,
                                style = settings.lyricStyle,
                                displayState = displayState,
                                queuePanelVisible = queuePanelVisible,
                                displayedLines = displayedLines,
                                lyricsState = lyricsState,
                                queueSnapshot = queueSnapshot,
                                positionState = positionState,
                                offsetMs = settings.lyricOffsetMs,
                                lowPerformance = settings.lowPerformanceMode,
                                translationEnabled = settings.lyricTranslationEnabled,
                                controlsExpanded = controlsExpanded,
                                canSeek = canSeek,
                                isLight = palette.isLight,
                                isPlaying = playbackState.isPlaying,
                                fg = fg,
                                fg2 = fg2,
                                accent = accent,
                                pullHandle = pullDown.handle,
                                onReveal = revealControls,
                                onLineSeek = seekToMs,
                                onSelectTimelineTrack = playbackStore::selectQueueTrack,
                                onPlayStationTrack = { s -> playbackStore.playTrack(s, listOf(s)) },
                                onRetryLyrics = playbackStore::retryCurrentLyrics,
                                onOpenAlbum = openAlbumDetail,
                                onOpenArtist = openArtistDetail,
                            )
                        }
                    }
                }

                // Bottom controls slot for all tiers except Ultrawide (which hosts controls in Column 1)
                if (tierSpec.tier != FolioTier.Ultrawide) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (tierSpec.tier == FolioTier.Short || tierSpec.tier == FolioTier.Wide) {
                            Alignment.CenterStart
                        } else {
                            Alignment.Center
                        },
                    ) {
                        if (controlsExpanded) {
                            FolioPlayerExpandedControls(
                                track = track,
                                tierSpec = tierSpec,
                                positionState = positionState,
                                durationMs = durationMs,
                                canSeek = canSeek,
                                isLiveStream = isLiveStream,
                                playWhenReady = playbackState.playWhenReady,
                                translationEnabled = settings.lyricTranslationEnabled,
                                queueOpen = queueOpen,
                                canSkipPrevious = canSkipPrevious,
                                canSkipNext = canSkipNext,
                                isLight = palette.isLight,
                                fg = fg,
                                fg2 = fg2,
                                onSeekTo = seekToMs,
                                onToggleTranslation = {
                                    revealControls()
                                    settingsStore.setLyricTranslationEnabled(!settings.lyricTranslationEnabled)
                                },
                                onPrevious = {
                                    revealControls()
                                    playbackStore.skip(-1)
                                },
                                onPlayPause = {
                                    revealControls()
                                    playbackStore.togglePlayback()
                                },
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
                            )
                        } else {
                            FolioPlayerCollapsedCapsule(
                                track = track,
                                tierSpec = tierSpec,
                                positionState = positionState,
                                durationMs = durationMs,
                                playWhenReady = playbackState.playWhenReady,
                                isLight = palette.isLight,
                                fg = fg,
                                fg2 = fg2,
                                onExpand = revealControls,
                                onTogglePlay = playbackStore::togglePlayback,
                            )
                        }
                    }
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
    positionState: State<Long>,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    controlsExpanded: Boolean,
    queueOpen: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleTranslation: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
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
                            .shadow(22.dp, RoundedCornerShape(16.dp))
                            .then(pullHandle)
                            .clickable(onClick = onOpenAlbum),
                    )
                }
                Text(
                    text = track.title,
                    color = fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenAlbum),
                )
                if (track.artist.isNotBlank()) {
                    Text(
                        text = track.artist,
                        color = fg2,
                        fontFamily = fontFamily,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenArtist),
                    )
                }
            }

            if (controlsExpanded) {
                FolioPlayerExpandedControls(
                    track = track,
                    tierSpec = tierSpec,
                    positionState = positionState,
                    durationMs = durationMs,
                    canSeek = canSeek,
                    isLiveStream = isLiveStream,
                    playWhenReady = playWhenReady,
                    translationEnabled = translationEnabled,
                    queueOpen = queueOpen,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
                    isLight = isLight,
                    fg = fg,
                    fg2 = fg2,
                    onSeekTo = onSeekTo,
                    onToggleTranslation = onToggleTranslation,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onToggleQueue = onToggleQueue,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                )
            } else {
                FolioPlayerCollapsedCapsule(
                    track = track,
                    tierSpec = tierSpec,
                    positionState = positionState,
                    durationMs = durationMs,
                    playWhenReady = playWhenReady,
                    isLight = isLight,
                    fg = fg,
                    fg2 = fg2,
                    onExpand = onReveal,
                    onTogglePlay = onPlayPause,
                )
            }
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
                    fontSizeSp = tierSpec.fs.value,
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
                    baseSp = tierSpec.pfs.value,
                    isLight = isLight,
                    fg = fg,
                    fg2 = fg2,
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
    queuePanelVisible: Boolean,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: State<Long>,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    controlsExpanded: Boolean,
    canSeek: Boolean,
    isLight: Boolean,
    isPlaying: Boolean,
    fg: Color,
    fg2: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val pillBg = if (isLight) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.14f)
    val pillBorder = if (isLight) Color(0xFF17151C).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val topCoverClearance = when {
            tierSpec.tier == FolioTier.Narrow && style == LyricBackgroundStyle.Soak &&
                displayState != FolioPlayerDisplayState.Queue &&
                displayState != FolioPlayerDisplayState.None -> maxHeight * 0.24f
            tierSpec.tier == FolioTier.Medium && style == LyricBackgroundStyle.Soak &&
                displayState != FolioPlayerDisplayState.Queue -> maxHeight * 0.20f
            else -> 0.dp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topCoverClearance),
        ) {
            if (tierSpec.tier == FolioTier.Medium && style == LyricBackgroundStyle.Flow &&
                displayState != FolioPlayerDisplayState.Queue
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
                            .shadow(16.dp, RoundedCornerShape(14.dp))
                            .then(pullHandle)
                            .clickable(onClick = onOpenAlbum),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = track.title,
                            color = fg,
                            fontFamily = LocalFolioFontFamily.current,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenAlbum),
                        )
                        if (track.artist.isNotBlank()) {
                            Text(
                                text = track.artist,
                                color = fg2,
                                fontFamily = LocalFolioFontFamily.current,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(onClick = onOpenArtist),
                            )
                        }
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
                    )

                    displayState == FolioPlayerDisplayState.Lyrics -> FolioScrollingLyricsPanel(
                        lines = displayedLines,
                        positionState = positionState,
                        offsetMs = offsetMs,
                        lowPerformance = lowPerformance,
                        controlsVisible = controlsExpanded,
                        canSeek = canSeek,
                        fontSizeSp = tierSpec.fs.value,
                        anchorFraction = tierSpec.anchorFraction,
                        isLight = isLight,
                        onReveal = onReveal,
                        onLineSeek = onLineSeek,
                    )

                    displayState == FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                        lines = displayedLines,
                        positionState = positionState,
                        offsetMs = offsetMs,
                        translationEnabled = translationEnabled,
                        baseSp = tierSpec.pfs.value,
                        isLight = isLight,
                        fg = fg,
                        fg2 = fg2,
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
    }
}

@Composable
private fun TwoColumnPlayerBody(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    queuePanelVisible: Boolean,
    displayedLines: List<dev.amatv.model.LyricLine>,
    lyricsState: dev.amatv.core.LyricsState,
    queueSnapshot: FolioQueueSnapshot,
    positionState: State<Long>,
    offsetMs: Long,
    lowPerformance: Boolean,
    translationEnabled: Boolean,
    controlsExpanded: Boolean,
    canSeek: Boolean,
    isLight: Boolean,
    isPlaying: Boolean,
    fg: Color,
    fg2: Color,
    accent: Color,
    pullHandle: Modifier,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current

    if (displayState == FolioPlayerDisplayState.Queue) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
            )
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideCar = tierSpec.tier == FolioTier.Wide
        // H5 `.t-e .lyr { left: 50%; right: 64px }`: Wide car tier splits 50/50 so lyrics stay in the right half.
        val leftColumnWidth = if (isWideCar) maxWidth * 0.50f else maxWidth * 0.38f
        val bodyHeight = maxHeight
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (isWideCar) 0.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left column: Wide car rolling lyrics show only the large cover (Flow & Soak, no duplicate title/artist);
            // Wide car Poster keeps left clean for backdrop color glow. Short tier keeps its existing layout.
            Box(
                modifier = Modifier
                    .width(leftColumnWidth)
                    .fillMaxHeight()
                    .then(pullHandle),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isWideCar) {
                    if (displayState == FolioPlayerDisplayState.Lyrics ||
                        (displayState == FolioPlayerDisplayState.None && style == LyricBackgroundStyle.Flow)
                    ) {
                        val coverSize = min((bodyHeight - 16.dp).coerceAtLeast(120.dp), min(leftColumnWidth * 0.78f, 400.dp))
                        FolioArtwork(
                            track = track,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .padding(start = 36.dp)
                                .size(coverSize)
                                .shadow(24.dp, RoundedCornerShape(14.dp))
                                .clickable(onClick = onOpenAlbum),
                        )
                    }
                } else if (style == LyricBackgroundStyle.Flow) {
                    val coverSize = min(bodyHeight * 0.72f, 200.dp)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FolioArtwork(
                            track = track,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .size(coverSize)
                                .shadow(24.dp, RoundedCornerShape(16.dp))
                                .clickable(onClick = onOpenAlbum),
                        )
                        Text(
                            text = track.title,
                            color = fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = coverSize)
                                .clickable(onClick = onOpenAlbum),
                        )
                        if (track.artist.isNotBlank()) {
                            Text(
                                text = track.artist,
                                color = fg2,
                                fontFamily = fontFamily,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = coverSize)
                                    .clickable(onClick = onOpenArtist),
                            )
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
                        )
                    }

                    displayState == FolioPlayerDisplayState.Poster -> FolioPosterLyricsPanel(
                        lines = displayedLines,
                        positionState = positionState,
                        offsetMs = offsetMs,
                        translationEnabled = translationEnabled,
                        baseSp = tierSpec.pfs.value,
                        isLight = isLight,
                        fg = fg,
                        fg2 = fg2,
                    )

                    else -> FolioScrollingLyricsPanel(
                        lines = displayedLines,
                        positionState = positionState,
                        offsetMs = offsetMs,
                        lowPerformance = lowPerformance,
                        controlsVisible = controlsExpanded,
                        canSeek = canSeek,
                        fontSizeSp = tierSpec.fs.value,
                        anchorFraction = tierSpec.anchorFraction,
                        isLight = isLight,
                        onReveal = onReveal,
                        onLineSeek = onLineSeek,
                    )
                }
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

@Composable
private fun rememberFolioPlaybackPositionMs(
    playbackState: PlaybackState,
    active: Boolean,
): State<Long> {
    val state = remember { mutableLongStateOf(playbackState.positionAnchorMs) }
    LaunchedEffect(
        active,
        playbackState.positionAnchorMs,
        playbackState.playStartedRealtimeMs,
        playbackState.isPlaying,
    ) {
        state.longValue = if (playbackState.isPlaying) {
            playbackState.positionAnchorMs + (SystemClock.elapsedRealtime() - playbackState.playStartedRealtimeMs).coerceAtLeast(0L)
        } else {
            playbackState.positionAnchorMs
        }
        if (!active || !playbackState.isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                state.longValue = playbackState.positionAnchorMs +
                    (SystemClock.elapsedRealtime() - playbackState.playStartedRealtimeMs).coerceAtLeast(0L)
            }
        }
    }
    return state
}
