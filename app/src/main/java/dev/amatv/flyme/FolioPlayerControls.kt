package dev.amatv.flyme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.amatv.LocalHostWindow
import dev.amatv.R
import dev.amatv.integration.LyricTextSize
import dev.amatv.model.Track
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class FolioPlayerAnchorCoordsHolder {
    var translateButtonCoords by mutableStateOf<LayoutCoordinates?>(null)
    var queueButtonCoords by mutableStateOf<LayoutCoordinates?>(null)
}

internal val LocalFolioPlayerAnchors = compositionLocalOf<FolioPlayerAnchorCoordsHolder?> { null }

@Composable
internal fun FolioAnimatedPlayPauseIcon(
    playWhenReady: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalFolioReducedMotion.current
    val lowPerformance = LocalFolioLowPerformance.current
    val enterMs = if (reducedMotion) 0 else FolioPlayerMotionTokens.PLAY_ICON_SWITCH_MS
    val exitMs = if (reducedMotion) 0 else FolioPlayerMotionTokens.PLAY_ICON_SWITCH_OUT_MS
    val playCd = stringResource(R.string.player_play)
    val pauseCd = stringResource(R.string.player_pause)

    AnimatedContent(
        targetState = playWhenReady,
        transitionSpec = {
            if (reducedMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (lowPerformance) {
                fadeIn(tween(enterMs, easing = FolioPlayerMotionTokens.EnterEasing)) togetherWith
                    fadeOut(tween(exitMs, easing = FolioPlayerMotionTokens.ExitEasing))
            } else {
                (fadeIn(tween(enterMs, easing = FolioPlayerMotionTokens.EnterEasing)) +
                    scaleIn(
                        initialScale = FolioPlayerMotionTokens.PLAY_ICON_SWITCH_SCALE,
                        animationSpec = tween(enterMs, easing = FolioPlayerMotionTokens.EnterEasing),
                    )) togetherWith
                    (fadeOut(tween(exitMs, easing = FolioPlayerMotionTokens.ExitEasing)) +
                        scaleOut(
                            targetScale = FolioPlayerMotionTokens.PLAY_ICON_SWITCH_SCALE,
                            animationSpec = tween(exitMs, easing = FolioPlayerMotionTokens.ExitEasing),
                        ))
            }.using(SizeTransform(clip = false))
        },
        label = "play-pause-icon",
    ) { isPlaying ->
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) pauseCd else playCd,
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
internal fun FolioPlayerHeader(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    headerColors: HeaderColorScheme,
    favoriteAccent: Color,
    showHeaderTitle: Boolean,
    controlsExpanded: Boolean,
    displayState: FolioPlayerDisplayState,
    reducedMotion: Boolean,
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    isPosterMode: Boolean,
    hasTimedLyrics: Boolean,
    pullHandle: Modifier,
    onClose: () -> Unit,
    onExpandControls: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePosterMode: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val pipWindow = LocalHostWindow.current?.takeIf {
        shouldShowMiniWindowButton(
            canOpenMini = it.canOpenMini,
            isMiniWindow = it.mini,
            tier = tierSpec.tier,
        )
    }
    val accentColor = LocalFolioControlAccent.current?.value ?: Color.Transparent
    val headerTint = headerActionTint(
        isPosterMode = isPosterMode,
        accentColor = accentColor,
        defaultFg = headerColors.fg,
    )
    val collapsedMode = resolveCollapsedHeaderTitleMode(tierSpec.tier, displayState)
    val targetTitleAlpha = if (!controlsExpanded && collapsedMode != FolioCollapsedHeaderTitleMode.None) 1f else 0f
    val collapsedAlpha = if (LocalFolioLowPerformance.current) targetTitleAlpha else animateFloatAsState(
        targetValue = targetTitleAlpha,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_OPACITY_MS),
        label = "hdr-tt-alpha",
    ).value
    val showCollapsedTitle = collapsedMode != FolioCollapsedHeaderTitleMode.None &&
        (!controlsExpanded || collapsedAlpha > 0.01f)
    val expandCd = stringResource(R.string.folio_player_controls_expand)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(pullHandle),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tierSpec.tier == FolioTier.Narrow || tierSpec.tier == FolioTier.Medium || tierSpec.tier == FolioTier.Short) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(headerColors.fg.copy(alpha = 0.28f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsedMode == FolioCollapsedHeaderTitleMode.LargeWithCover) {
                        Modifier.heightIn(min = 92.dp)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerIconPillButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.folio_player_close),
                size = tierSpec.pib,
                iconTint = headerTint,
                bg = Color.Transparent,
                border = Color.Transparent,
                onClick = onClose,
            )
            val headerGlassLight = headerColors.fg != Color.White
            when {
                showHeaderTitle -> {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy((-6).dp),
                    ) {
                        FolioMarqueeText(
                            text = track.title,
                            color = headerColors.fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            resetKey = track.id,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FolioExpandedSubtitle(
                            artist = track.artist,
                            album = track.album,
                            color = headerColors.fg2,
                            fontSize = 13.sp,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            isLight = headerGlassLight,
                            resetKey = track.id,
                        )
                    }
                }

                showCollapsedTitle -> {
                    val subtitle = formatFolioPlayerSubtitle(track.artist, track.album)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { alpha = collapsedAlpha }
                            .clip(RoundedCornerShape(10.dp))
                            .folioPressable(
                                enabled = !controlsExpanded,
                                isLight = headerGlassLight,
                                role = Role.Button,
                                onClick = onExpandControls,
                            )
                            .semantics { contentDescription = expandCd }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (collapsedMode == FolioCollapsedHeaderTitleMode.LargeWithCover) {
                            FolioArtwork(
                                track = track,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .size(84.dp)
                                    .shadow(16.dp, RoundedCornerShape(8.dp)),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(
                                if (collapsedMode == FolioCollapsedHeaderTitleMode.LargeWithCover) 3.dp else 2.dp,
                            ),
                        ) {
                            val isLarge = collapsedMode == FolioCollapsedHeaderTitleMode.LargeWithCover
                            if (isLarge) {
                                Text(
                                    text = track.title,
                                    color = headerColors.fg,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                FolioMarqueeText(
                                    text = track.title,
                                    color = headerColors.fg,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 17.sp,
                                    lineHeight = 20.sp,
                                    resetKey = track.id,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (subtitle.isNotEmpty()) {
                                FolioMarqueeText(
                                    text = subtitle,
                                    color = headerColors.fg2,
                                    fontFamily = fontFamily,
                                    fontSize = if (isLarge) 15.sp else 13.5.sp,
                                    resetKey = track.id,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                else -> {
                    Spacer(Modifier.weight(1f))
                }
            }

            if (trackActionLocationForTier(tierSpec.tier) == FolioTrackActionLocation.Header) {
                AddToLibraryIconButton(
                    isInLibrary = isInLibrary,
                    inProgress = libraryInProgress,
                    size = tierSpec.pib,
                    iconTint = headerTint,
                    onClick = onToggleLibrary,
                )

                val resolvedFavoriteTint = resolveHeaderFavoriteTint(
                    isFavorite = true,
                    isPosterMode = isPosterMode,
                    controlAccent = accentColor,
                    defaultFg = headerColors.fg,
                    fallbackFavoriteAccent = favoriteAccent,
                )
                FavoriteIconButton(
                    isFavorite = isFavorite,
                    inProgress = favoriteInProgress,
                    size = tierSpec.pib,
                    favoriteAccent = resolvedFavoriteTint,
                    defaultTint = headerTint,
                    onClick = onToggleFavorite,
                )
            }

            val modeCd = stringResource(
                if (isPosterMode) R.string.folio_player_toggle_lyrics else R.string.folio_player_toggle_poster,
            )
            Box(
                modifier = Modifier
                    .size(tierSpec.pib)
                    .folioPlayerGlass(
                        isLight = headerGlassLight,
                        shape = CircleShape,
                        enabled = isPosterMode,
                    )
                    .clip(CircleShape)
                    .folioPressable(
                        enabled = hasTimedLyrics,
                        compact = true,
                        isLight = headerGlassLight,
                        role = Role.Button,
                        onClick = onTogglePosterMode,
                    )
                    .semantics { contentDescription = modeCd },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Tt",
                    color = headerTint.copy(alpha = if (hasTimedLyrics) 1f else 0.36f),
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            if (pipWindow != null) {
                PlayerIconPillButton(
                    icon = Icons.Rounded.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.flyme_open_mini_window),
                    size = tierSpec.pib,
                    iconTint = headerTint,
                    bg = headerColors.glassBg,
                    border = headerColors.glassBorder,
                    useGlass = true,
                    glassIsLight = headerGlassLight,
                    onClick = { pipWindow.switchTo(true) },
                )
            }
        }
    }
}

/**
 * Animates between `.ctl` expanded controls (`opacity 280ms`, `translateY 14dp 350ms`) and
 * `.pmeta` collapsed capsule (`opacity 250ms`, `translateY 8dp 300ms`), respecting `reducedMotion`.
 */
@Composable
internal fun FolioPlayerAnimatedControlsSlot(
    controlsExpanded: Boolean,
    reducedMotion: Boolean,
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    displayState: FolioPlayerDisplayState,
    positionState: State<Long>,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    playWhenReady: Boolean,
    lyricsDisplayActive: Boolean,
    queueOpen: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    favoriteAccent: Color,
    onExpandCapsule: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenLyricsSettings: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onCollapsedPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.BottomStart,
    carControlsWidthDp: Dp = 464.dp,
    onCarControlsHeightPxChanged: (Int) -> Unit = {},
) {
    val lowPerformance = LocalFolioLowPerformance.current
    val expandedAlpha = if (controlsExpanded) 1f else 0f
    val collapsedAlpha = 1f - expandedAlpha
    val ctlAlphaState = if (lowPerformance) null else animateFloatAsState(
        targetValue = expandedAlpha,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_OPACITY_MS),
        label = "ctl-alpha",
    )
    val ctlOffsetState = if (lowPerformance) null else animateFloatAsState(
        targetValue = if (controlsExpanded) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_TRANSLATE_MS,
            easing = FolioPlayerMotionTokens.EnterEasing,
        ),
        label = "ctl-offset",
    )
    val pmetaAlphaState = if (lowPerformance) null else animateFloatAsState(
        targetValue = collapsedAlpha,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_OPACITY_MS),
        label = "pmeta-alpha",
    )
    val pmetaOffsetState = if (lowPerformance) null else animateFloatAsState(
        targetValue = if (controlsExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_TRANSLATE_MS,
            easing = FolioPlayerMotionTokens.EnterEasing,
        ),
        label = "pmeta-offset",
    )

    val collapsedLayout = resolveCollapsedLayout(tierSpec.tier, displayState)
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        val showPmeta = collapsedLayout != FolioCollapsedLayout.UnderCoverInfo &&
            (!controlsExpanded || (pmetaAlphaState?.value ?: collapsedAlpha) > 0.01f)
        val showCtl = controlsExpanded || (ctlAlphaState?.value ?: expandedAlpha) > 0.01f

        val pmetaDeck: @Composable () -> Unit = {
            val animMod = Modifier.graphicsLayer {
                alpha = pmetaAlphaState?.value ?: collapsedAlpha
                if (pmetaOffsetState != null && !reducedMotion) {
                    val f = pmetaOffsetState.value
                    translationY = 8.dp.toPx() * f
                    val sc = 1f - 0.02f * f
                    scaleX = sc
                    scaleY = sc
                }
            }
            when (collapsedLayout) {
                FolioCollapsedLayout.Capsule -> FolioPlayerCollapsedCapsule(
                    track = track,
                    tierSpec = tierSpec,
                    showArtwork = showCollapsedCapsuleArtwork(tierSpec.tier, displayState),
                    positionState = positionState,
                    durationMs = durationMs,
                    playWhenReady = playWhenReady,
                    isLight = isLight,
                    fg = fg,
                    fg2 = fg2,
                    interactive = !controlsExpanded,
                    onExpand = onExpandCapsule,
                    onTogglePlay = onCollapsedPlayPause,
                    modifier = animMod,
                )

                FolioCollapsedLayout.CircleButtonAndHairline -> FolioCirclePlayHairlineBar(
                    buttonOnLeft = tierSpec.tier == FolioTier.Wide,
                    positionState = positionState,
                    durationMs = durationMs,
                    playWhenReady = playWhenReady,
                    isLight = isLight,
                    fg = fg,
                    interactive = !controlsExpanded,
                    onExpand = onExpandCapsule,
                    onTogglePlay = onCollapsedPlayPause,
                    modifier = animMod.fillMaxWidth(),
                )

                FolioCollapsedLayout.UnderCoverInfo -> Unit
            }
        }
        val ctlDeck: @Composable () -> Unit = {
            val widePlacement = if (tierSpec.tier == FolioTier.Wide) {
                Modifier
                    .padding(start = WIDE_CAR_CONTROLS_START_OFFSET_DP.dp, bottom = 4.dp)
                    .onSizeChanged { onCarControlsHeightPxChanged(it.height) }
            } else {
                Modifier
            }
            FolioPlayerExpandedControls(
                track = track,
                tierSpec = tierSpec,
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
                favoriteAccent = favoriteAccent,
                playEnabled = controlsExpanded,
                carControlsWidthDp = carControlsWidthDp,
                onSeekTo = onSeekTo,
                onOpenLyricsSettings = onOpenLyricsSettings,
                onToggleLibrary = onToggleLibrary,
                onToggleFavorite = onToggleFavorite,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleQueue = onToggleQueue,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                modifier = widePlacement.graphicsLayer {
                    alpha = ctlAlphaState?.value ?: expandedAlpha
                    if (ctlOffsetState != null && !reducedMotion) {
                        translationY = FolioPlayerMotionTokens.CTL_OFFSET_DP.dp.toPx() * ctlOffsetState.value
                    }
                },
            )
        }

        if (controlsExpanded) {
            if (showPmeta) pmetaDeck()
            if (showCtl) ctlDeck()
        } else {
            if (showCtl) ctlDeck()
            if (showPmeta) pmetaDeck()
        }
    }
}

@Composable
internal fun FolioCircleGlassPlayButton(
    size: Dp,
    playWhenReady: Boolean,
    isLight: Boolean,
    fg: Color,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalFolioControlAccent.current?.value ?: Color.Transparent
    val surface = LocalFolioPalette.current.capsuleBg.copy(alpha = 1f)
    Box(
        modifier = modifier
            .size(size)
            .folioPlayerGlass(isLight = isLight, shape = CircleShape)
            .clip(CircleShape)
            .background(accent)
            .folioPressable(
                enabled = enabled,
                compact = true,
                isLight = isLight,
                role = Role.Button,
                onClick = onTogglePlay,
            ),
        contentAlignment = Alignment.Center,
    ) {
        FolioAnimatedPlayPauseIcon(
            playWhenReady = playWhenReady,
            tint = if (accent.alpha > 0f) controlAccentForeground(accent.compositeOver(surface)) else fg,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

@Composable
internal fun FolioHairlineProgressBar(
    positionState: State<Long>,
    durationMs: Long,
    fg: Color,
    modifier: Modifier = Modifier,
    accent: State<Color>? = LocalFolioControlAccent.current,
) {
    Canvas(modifier = modifier.height(3.dp)) {
        val progress = if (durationMs > 0L) {
            (positionState.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = fg.copy(alpha = 0.14f),
            size = size,
            cornerRadius = radius,
        )
        if (progress > 0f) {
            drawRoundRect(
                color = accent?.value?.compositeOver(fg.copy(alpha = 0.85f)) ?: fg.copy(alpha = 0.85f),
                size = Size(size.width * progress, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** Layout ③ collapsed bottom bar: 64dp circular glass Play/Pause button + thin hairline progress bar. */
@Composable
internal fun FolioCirclePlayHairlineBar(
    buttonOnLeft: Boolean,
    positionState: State<Long>,
    durationMs: Long,
    playWhenReady: Boolean,
    isLight: Boolean,
    fg: Color,
    interactive: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandCd = stringResource(R.string.folio_player_controls_expand)
    val startPad = if (buttonOnLeft) 36.dp else 20.dp
    val endPad = if (buttonOnLeft) 28.dp else 0.dp
    Row(
        modifier = modifier.padding(start = startPad, end = endPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (buttonOnLeft) {
            FolioCircleGlassPlayButton(
                size = 64.dp,
                playWhenReady = playWhenReady,
                isLight = isLight,
                fg = fg,
                enabled = interactive,
                onTogglePlay = onTogglePlay,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = interactive,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = expandCd },
                contentAlignment = Alignment.Center,
            ) {
                FolioHairlineProgressBar(
                    positionState = positionState,
                    durationMs = durationMs,
                    fg = fg,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = interactive,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = expandCd },
                contentAlignment = Alignment.Center,
            ) {
                FolioHairlineProgressBar(
                    positionState = positionState,
                    durationMs = durationMs,
                    fg = fg,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FolioCircleGlassPlayButton(
                size = 64.dp,
                playWhenReady = playWhenReady,
                isLight = isLight,
                fg = fg,
                enabled = interactive,
                onTogglePlay = onTogglePlay,
            )
        }
    }
}

/** Layout ④ under-cover collapsed info on Wide car Scrolling Lyrics (no glass outer capsule). */
@Composable
internal fun FolioCarUnderCoverCollapsedBar(
    track: Track,
    positionState: State<Long>,
    durationMs: Long,
    playWhenReady: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    interactive: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val expandCd = stringResource(R.string.folio_player_controls_expand)
    val subtitle = formatFolioPlayerSubtitle(track.artist, track.album)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .folioPressable(
                        enabled = interactive,
                        isLight = isLight,
                        role = Role.Button,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = expandCd }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    color = fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    FolioMarqueeText(
                        text = subtitle,
                        color = fg2,
                        fontFamily = fontFamily,
                        fontSize = 17.sp,
                        resetKey = track.id,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            FolioCircleGlassPlayButton(
                size = 56.dp,
                playWhenReady = playWhenReady,
                isLight = isLight,
                fg = fg,
                enabled = interactive,
                onTogglePlay = onTogglePlay,
            )
        }
        FolioHairlineProgressBar(
            positionState = positionState,
            durationMs = durationMs,
            fg = fg,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun measureSubtitleWidthDp(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    density: androidx.compose.ui.unit.Density,
): Dp {
    val px = textMeasurer.measure(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = false,
    ).size.width
    return ceil(px / density.density).dp
}

@Composable
internal fun FolioExpandedSubtitle(
    artist: String,
    album: String,
    color: Color,
    fontSize: TextUnit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
    isLight: Boolean = LocalFolioPalette.current.isLight,
    resetKey: Any? = null,
) {
    val fontFamily = LocalFolioFontFamily.current
    val cleanArtist = artist.trim()
    val cleanAlbum = album.trim()
    if (cleanArtist.isEmpty() && cleanAlbum.isEmpty()) return

    val separator = " — "
    val baseTextStyle = LocalTextStyle.current
    val textStyle = remember(baseTextStyle, fontFamily, fontSize) {
        baseTextStyle.merge(
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = fontSize,
            ),
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxWidthDp = maxWidth
        if (cleanArtist.isNotEmpty() && cleanAlbum.isNotEmpty()) {
            val artistFullDp = remember(cleanArtist, textStyle, density, textMeasurer) {
                measureSubtitleWidthDp(textMeasurer, cleanArtist, textStyle, density)
            }
            val sepFullDp = remember(textStyle, density, textMeasurer) {
                measureSubtitleWidthDp(textMeasurer, separator, textStyle, density)
            }
            val albumFullDp = remember(cleanAlbum, textStyle, density, textMeasurer) {
                measureSubtitleWidthDp(textMeasurer, cleanAlbum, textStyle, density)
            }
            val totalDp = artistFullDp + sepFullDp + albumFullDp
            val artistWidthMod = if (totalDp <= maxWidthDp) {
                Modifier
            } else {
                val budgetForArtistAndAlbum = (maxWidthDp - sepFullDp).coerceAtLeast(16.dp)
                Modifier.widthIn(max = budgetForArtistAndAlbum * 0.45f)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = artistWidthMod
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .folioPressable(
                            isLight = isLight,
                            role = Role.Button,
                            onClick = onOpenArtist,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    FolioMarqueeText(
                        text = cleanArtist,
                        color = color,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = fontSize,
                        resetKey = resetKey ?: cleanArtist,
                    )
                }
                Text(
                    text = separator,
                    color = color,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = fontSize,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .folioPressable(
                            isLight = isLight,
                            role = Role.Button,
                            onClick = onOpenAlbum,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    FolioMarqueeText(
                        text = cleanAlbum,
                        color = color,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = fontSize,
                        resetKey = resetKey ?: cleanAlbum,
                    )
                }
            }
        } else if (cleanAlbum.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .folioPressable(
                        isLight = isLight,
                        role = Role.Button,
                        onClick = onOpenAlbum,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                FolioMarqueeText(
                    text = cleanAlbum,
                    color = color,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = fontSize,
                    resetKey = resetKey ?: cleanAlbum,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .folioPressable(
                        isLight = isLight,
                        role = Role.Button,
                        onClick = onOpenArtist,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                FolioMarqueeText(
                    text = cleanArtist,
                    color = color,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = fontSize,
                    resetKey = resetKey ?: cleanArtist,
                )
            }
        }
    }
}

@Composable
internal fun FolioPlayerCollapsedCapsule(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    showArtwork: Boolean,
    positionState: State<Long>,
    durationMs: Long,
    playWhenReady: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    interactive: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val shape = RoundedCornerShape(FolioRadii.Capsule)
    val controlAccent = LocalFolioControlAccent.current
    val accent = controlAccent?.value ?: Color.Transparent
    val playIconBg = accent.compositeOver(fg)
    val playIconTint = when {
        accent.alpha > 0f -> controlAccentForeground(playIconBg)
        isLight -> Color.White
        else -> Color(0xFF17151C)
    }
    val expandCd = stringResource(R.string.folio_player_controls_expand)
    val subtitle = formatFolioPlayerSubtitle(track.artist, track.album)

    val expandInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .then(
                if (tierSpec.tier == FolioTier.Narrow || tierSpec.tier == FolioTier.Medium) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(min = 280.dp, max = 380.dp)
                },
            )
            .height(64.dp)
            .folioPlayerGlass(isLight = isLight, shape = shape)
            .clip(shape)
            .folioPressFeedback(
                interactionSource = expandInteraction,
                enabled = interactive,
                isLight = isLight,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mirror H5 `.pmeta` (.pc + .ptx) and B1a `FolioMiniPlayerCapsule`: tapping the track
            // summary region expands the full control deck while Play/Pause stays independent and
            // does not press-scale the outer capsule.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = expandInteraction,
                        indication = null,
                        enabled = interactive,
                        role = Role.Button,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = expandCd }
                    .padding(start = collapsedCapsuleStartPaddingDp(showArtwork), end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showArtwork) {
                    FolioArtwork(
                        track = track,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(44.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FolioMarqueeText(
                        text = track.title,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        resetKey = track.id,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (subtitle.isNotEmpty()) {
                        FolioMarqueeText(
                            text = subtitle,
                            color = fg2,
                            fontFamily = fontFamily,
                            fontSize = 12.sp,
                            resetKey = track.id,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(playIconBg)
                    .folioPressable(
                        enabled = interactive,
                        compact = true,
                        isLight = !isLight,
                        role = Role.Button,
                        onClick = onTogglePlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                FolioAnimatedPlayPauseIcon(
                    playWhenReady = playWhenReady,
                    tint = playIconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // Thin hairline playback indicator along the bottom edge (mbar); read position only in Canvas.
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 3.dp)
                .height(2.5.dp),
        ) {
            val progress = if (durationMs > 0L) {
                (positionState.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val radius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
            drawRoundRect(
                color = fg.copy(alpha = 0.14f),
                size = size,
                cornerRadius = radius,
            )
            if (progress > 0f) {
                drawRoundRect(
                    color = controlAccent?.value?.compositeOver(fg.copy(alpha = 0.78f))
                        ?: fg.copy(alpha = 0.78f),
                    size = Size(size.width * progress, size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
internal fun FolioPlayerExpandedControls(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    positionState: State<Long>,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    playWhenReady: Boolean,
    lyricsDisplayActive: Boolean,
    queueOpen: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    favoriteAccent: Color,
    onSeekTo: (Long) -> Unit,
    onOpenLyricsSettings: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
    playEnabled: Boolean = true,
    carControlsWidthDp: Dp = 464.dp,
) {
    val fontFamily = LocalFolioFontFamily.current
    val cardShape = RoundedCornerShape(22.dp)
    val playBg = fg
    val playFg = if (isLight) Color.White else Color(0xFF17151C)
    val sideBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f)

    if (tierSpec.isHorizontalCarControls) {
        // C4 Wide (e) compact left-column controls card: title/subtitle (no cover), seek bar, transport row.
        Column(
            modifier = modifier
                .width(carControlsWidthDp)
                .folioPlayerGlass(isLight = isLight, shape = cardShape)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy((-6).dp),
            ) {
                FolioMarqueeText(
                    text = track.title,
                    color = fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    resetKey = track.id,
                    modifier = Modifier.fillMaxWidth(),
                )
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

            SeekSection(
                positionState = positionState,
                durationMs = durationMs,
                canSeek = canSeek,
                isLiveStream = isLiveStream,
                fg = fg,
                fg2 = fg2,
                onSeekTo = onSeekTo,
            )

            TransportRow(
                tierSpec = tierSpec,
                lyricsDisplayActive = lyricsDisplayActive,
                queueOpen = queueOpen,
                playWhenReady = playWhenReady,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                fg = fg,
                playBg = playBg,
                playFg = playFg,
                sideBg = sideBg,
                playEnabled = playEnabled,
                onOpenLyricsSettings = onOpenLyricsSettings,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleQueue = onToggleQueue,
                horizontalSpacing = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val widthMod = if (tierSpec.controlCardWidth == Dp.Unspecified) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.widthIn(max = tierSpec.controlCardWidth).fillMaxWidth()
    }

    Column(
        modifier = modifier
            .then(widthMod)
            .folioPlayerGlass(isLight = isLight, shape = cardShape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showCardMeta(tierSpec.tier) && tierSpec.tier != FolioTier.Ultrawide) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FolioArtwork(
                    track = track,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(48.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((-6).dp),
                ) {
                    FolioMarqueeText(
                        text = track.title,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        resetKey = track.id,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FolioExpandedSubtitle(
                        artist = track.artist,
                        album = track.album,
                        color = fg2,
                        fontSize = 13.sp,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        isLight = isLight,
                        resetKey = track.id,
                    )
                }
            }
        }

        if (trackActionLocationForTier(tierSpec.tier) == FolioTrackActionLocation.ControlsCard) {
            val cardAccentColor = LocalFolioControlAccent.current?.value ?: Color.Transparent
            val resolvedCardFavAccent = if (cardAccentColor.alpha > 0f) {
                cardAccentColor.compositeOver(fg)
            } else {
                favoriteAccent
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AddToLibraryIconButton(
                    isInLibrary = isInLibrary,
                    inProgress = libraryInProgress,
                    size = 40.dp,
                    iconTint = fg,
                    onClick = onToggleLibrary,
                )
                FavoriteIconButton(
                    isFavorite = isFavorite,
                    inProgress = favoriteInProgress,
                    size = 40.dp,
                    favoriteAccent = resolvedCardFavAccent,
                    defaultTint = fg,
                    onClick = onToggleFavorite,
                )
            }
        }

        SeekSection(
            positionState = positionState,
            durationMs = durationMs,
            canSeek = canSeek,
            isLiveStream = isLiveStream,
            fg = fg,
            fg2 = fg2,
            onSeekTo = onSeekTo,
        )

        TransportRow(
            tierSpec = tierSpec,
            lyricsDisplayActive = lyricsDisplayActive,
            queueOpen = queueOpen,
            playWhenReady = playWhenReady,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            fg = fg,
            playBg = playBg,
            playFg = playFg,
            sideBg = sideBg,
            playEnabled = playEnabled,
            onOpenLyricsSettings = onOpenLyricsSettings,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onToggleQueue = onToggleQueue,
            horizontalSpacing = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransportRow(
    tierSpec: FolioPlayerTierSpec,
    lyricsDisplayActive: Boolean,
    queueOpen: Boolean,
    playWhenReady: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    fg: Color,
    playBg: Color,
    playFg: Color,
    sideBg: Color,
    playEnabled: Boolean,
    onOpenLyricsSettings: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    horizontalSpacing: Dp?,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val anchors = LocalFolioPlayerAnchors.current
    val isPaletteLight = LocalFolioPalette.current.isLight
    val accent = LocalFolioControlAccent.current?.value ?: Color.Transparent
    val playFill = accent.compositeOver(playBg)
    val playIcon = if (accent.alpha > 0f) controlAccentForeground(playFill) else playFg
    val translateCd = stringResource(R.string.player_lyrics_display_settings)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (horizontalSpacing != null) {
            Arrangement.spacedBy(horizontalSpacing)
        } else {
            Arrangement.SpaceBetween
        },
    ) {
        Box(
            modifier = Modifier
                .size(tierSpec.pib)
                .onPlaced { anchors?.translateButtonCoords = it }
                .clip(CircleShape)
                .background(if (lyricsDisplayActive) sideBg else Color.Transparent)
                .folioPressable(
                    compact = true,
                    role = Role.Button,
                    onClick = onOpenLyricsSettings,
                )
                .semantics {
                    contentDescription = translateCd
                    selected = lyricsDisplayActive
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.folio_player_translate_short),
                color = fg.copy(alpha = if (lyricsDisplayActive) 1f else 0.45f),
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
        }

        PlayerIconPillButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(R.string.player_previous_track),
            size = tierSpec.rd,
            iconTint = fg,
            bg = sideBg,
            border = Color.Transparent,
            enabled = canSkipPrevious,
            onClick = onPrevious,
        )

        Box(
            modifier = Modifier
                .size(tierSpec.pp)
                .clip(CircleShape)
                .background(playFill)
                .folioPressable(
                    enabled = playEnabled,
                    compact = true,
                    isLight = !isPaletteLight,
                    role = Role.Button,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FolioAnimatedPlayPauseIcon(
                playWhenReady = playWhenReady,
                tint = playIcon,
                modifier = Modifier.size(tierSpec.pp * 0.48f),
            )
        }

        PlayerIconPillButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(R.string.player_next_track),
            size = tierSpec.rd,
            iconTint = fg,
            bg = sideBg,
            border = Color.Transparent,
            enabled = canSkipNext,
            onClick = onNext,
        )

        PlayerIconPillButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = stringResource(R.string.player_queue),
            size = tierSpec.pib,
            iconTint = if (queueOpen) playFg else fg,
            bg = if (queueOpen) playBg else sideBg,
            border = Color.Transparent,
            onClick = onToggleQueue,
            modifier = Modifier.onPlaced { anchors?.queueButtonCoords = it },
        )
    }
}

@Composable
private fun SeekSection(
    positionState: State<Long>,
    durationMs: Long,
    canSeek: Boolean,
    isLiveStream: Boolean,
    fg: Color,
    fg2: Color,
    onSeekTo: (Long) -> Unit,
) {
    val controlAccent = LocalFolioControlAccent.current
    val fontFamily = LocalFolioFontFamily.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val positionMs = positionState.value
    val safeDuration = durationMs.coerceAtLeast(1L)
    val liveFraction = if (durationMs > 0L) (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragFraction else liveFraction
    val shownPositionMs = if (dragging) (dragFraction * safeDuration).roundToLong() else positionMs.coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .then(
                    if (canSeek && durationMs > 0L) {
                        Modifier
                            .pointerInput(durationMs) {
                                detectTapGestures { offset ->
                                    val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onSeekTo((fraction * durationMs).roundToLong())
                                }
                            }
                            .pointerInput(durationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { start ->
                                        dragging = true
                                        dragFraction = (start.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    },
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    },
                                    onDragEnd = {
                                        val target = (dragFraction * durationMs).roundToLong()
                                        dragging = false
                                        onSeekTo(target)
                                    },
                                    onDragCancel = {
                                        dragging = false
                                    },
                                )
                            }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            ) {
                val trackH = size.height * 0.72f
                val trackY = (size.height - trackH) * 0.5f
                val radius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                drawRoundRect(
                    color = fg.copy(alpha = 0.18f),
                    topLeft = Offset(0f, trackY),
                    size = Size(size.width, trackH),
                    cornerRadius = radius,
                )
                val fillW = (size.width * shownFraction).coerceAtLeast(0f)
                drawRoundRect(
                    color = controlAccent?.value?.compositeOver(fg.copy(alpha = 0.88f))
                        ?: fg.copy(alpha = 0.88f),
                    topLeft = Offset(0f, trackY),
                    size = Size(fillW, trackH),
                    cornerRadius = radius,
                )
                if (canSeek && durationMs > 0L) {
                    val thumbX = fillW.coerceIn(trackH, (size.width - trackH).coerceAtLeast(trackH))
                    drawCircle(
                        color = controlAccent?.value?.compositeOver(fg) ?: fg,
                        radius = if (dragging) 7.dp.toPx() else 5.dp.toPx(),
                        center = Offset(thumbX, size.height * 0.5f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isLiveStream) stringResource(R.string.player_live_stream) else formatFolioDuration(shownPositionMs),
                color = fg2,
                fontFamily = fontFamily,
                fontSize = 11.sp,
            )
            Text(
                text = if (!isLiveStream) formatFolioRemainingTime(shownPositionMs, durationMs) else "",
                color = fg2,
                fontFamily = fontFamily,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PlayerIconPillButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconTint: Color,
    bg: Color,
    border: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimDisabled: Boolean = true,
    useGlass: Boolean = false,
    glassIsLight: Boolean = false,
) {
    val alpha = if (enabled || !dimDisabled) 1f else 0.36f
    val resolvedLight = if (useGlass) glassIsLight else LocalFolioPalette.current.isLight
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (useGlass) {
                    Modifier.folioPlayerGlass(isLight = glassIsLight, shape = CircleShape)
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .then(if (border != Color.Transparent) Modifier.border(1.dp, border, CircleShape) else Modifier)
                },
            )
            .clip(CircleShape)
            .folioPressable(
                enabled = enabled,
                compact = true,
                isLight = resolvedLight,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint.copy(alpha = iconTint.alpha * alpha),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
private fun AddToLibraryIconButton(
    isInLibrary: Boolean,
    inProgress: Boolean,
    size: Dp,
    iconTint: Color,
    onClick: () -> Unit,
) {
    PlayerIconPillButton(
        icon = if (isInLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
        contentDescription = stringResource(
            if (isInLibrary) R.string.folio_player_in_library else R.string.folio_player_add_library,
        ),
        size = size,
        iconTint = if (isInLibrary) iconTint else iconTint.copy(alpha = iconTint.alpha * 0.72f),
        bg = if (isInLibrary) iconTint.copy(alpha = 0.16f) else Color.Transparent,
        border = Color.Transparent,
        enabled = !inProgress && !isInLibrary,
        dimDisabled = inProgress,
        onClick = onClick,
    )
}

@Composable
private fun FavoriteIconButton(
    isFavorite: Boolean,
    inProgress: Boolean,
    size: Dp,
    favoriteAccent: Color,
    defaultTint: Color,
    onClick: () -> Unit,
) {
    PlayerIconPillButton(
        icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        contentDescription = stringResource(
            if (isFavorite) R.string.player_favorite_remove else R.string.player_favorite_add,
        ),
        size = size,
        iconTint = if (isFavorite) favoriteAccent else defaultTint.copy(alpha = defaultTint.alpha * 0.72f),
        bg = if (isFavorite) favoriteAccent.copy(alpha = 0.16f) else Color.Transparent,
        border = Color.Transparent,
        enabled = !inProgress,
        onClick = onClick,
    )
}

private enum class FolioLyricsLanguagePicker {
    Translation,
    Pronunciation,
}

@Composable
internal fun FolioLyricsDisplaySettingsOverlay(
    open: Boolean,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    tier: FolioTier,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    statusTop: Dp,
    navBottom: Dp,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    translationAvailable: Boolean,
    pronunciationAvailable: Boolean,
    translationLanguageTag: String,
    pronunciationLanguageTag: String,
    appLanguageTag: String,
    enhancementsReady: Boolean,
    enhancementsFailed: Boolean,
    lyricTextSize: LyricTextSize,
    lyricOffsetMs: Long,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onTranslationLanguageChange: (String) -> Unit,
    onPronunciationLanguageChange: (String) -> Unit,
    onLyricTextSizeChange: (LyricTextSize) -> Unit,
    onLyricOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val transition = updateTransition(targetState = open, label = "lyrics-menu")
    if (!transition.currentState && !transition.targetState) return

    val progressState = transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) {
                tween(0)
            } else if (targetState) {
                tween(
                    durationMillis = FolioPlayerMotionTokens.MENU_OPEN_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                )
            } else {
                tween(
                    durationMillis = FolioPlayerMotionTokens.MENU_CLOSE_MS,
                    easing = FolioPlayerMotionTokens.ExitEasing,
                )
            }
        },
        label = "lyrics-menu-progress",
    ) { if (it) 1f else 0f }

    var languagePicker by remember { mutableStateOf<FolioLyricsLanguagePicker?>(null) }
    val fontFamily = LocalFolioFontFamily.current
    val effectiveTranslationTag = translationLanguageTag.ifBlank { appLanguageTag }
    val appLocale = remember(appLanguageTag) {
        Locale.forLanguageTag(appLanguageTag).takeUnless { it.language.isBlank() } ?: Locale.getDefault()
    }
    fun languageName(tag: String): String = Locale.forLanguageTag(tag)
        .getDisplayName(appLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }

    val placement = lyricsSettingsPlacementForTier(tier)
    val sheetShape = RoundedCornerShape(24.dp)
    val rowShape = RoundedCornerShape(14.dp)
    val rowBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.06f) else Color.White.copy(alpha = 0.09f)
    val rowSelectedBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.14f) else Color.White.copy(alpha = 0.18f)
    val pillActiveBg = fg
    val pillActiveFg = if (isLight) Color.White else Color(0xFF17151C)
    val pillInactiveBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f)
    val glassBacking = if (isLight) Color.White.copy(alpha = 0.56f) else Color(0xFF16151C).copy(alpha = 0.56f)
    val anchors = LocalFolioPlayerAnchors.current
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(0.15f, 1f)) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progressState.value }
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                enabled = open,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
    ) {
        val availableHeight = (maxHeight - statusTop - navBottom - 32.dp).coerceAtLeast(220.dp)
        val panelAlignment = when (placement) {
            FolioLyricsSettingsPlacement.AnchoredPopover -> Alignment.BottomStart
            FolioLyricsSettingsPlacement.BottomSheet -> Alignment.BottomCenter
        }
        val panelModifier = when (placement) {
            FolioLyricsSettingsPlacement.AnchoredPopover -> Modifier
                .align(panelAlignment)
                .padding(start = 24.dp, bottom = navBottom + 20.dp)
                .width(min(440.dp, (maxWidth - 48.dp).coerceAtLeast(280.dp)))
                .onPlaced { panelCoords ->
                    val btn = anchors?.translateButtonCoords
                    if (btn != null && btn.isAttached && panelCoords.isAttached) {
                        val btnPos = panelCoords.localPositionOf(btn, Offset.Zero)
                        anchorOrigin = computeAnchorTransformOrigin(
                            anchorCenterX = btnPos.x + btn.size.width * 0.5f,
                            anchorCenterY = btnPos.y + btn.size.height * 0.5f,
                            panelWidth = panelCoords.size.width.toFloat(),
                            panelHeight = panelCoords.size.height.toFloat(),
                            defaultOrigin = TransformOrigin(0.15f, 1f),
                        )
                    }
                }
                .graphicsLayer {
                    val p = progressState.value
                    alpha = p
                    if (!lowPerformance && !reducedMotion) {
                        val s = FolioPlayerMotionTokens.MENU_CLOSED_SCALE +
                            (1f - FolioPlayerMotionTokens.MENU_CLOSED_SCALE) * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = anchorOrigin
                    }
                }
            FolioLyricsSettingsPlacement.BottomSheet -> Modifier
                .align(panelAlignment)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = navBottom)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val p = progressState.value
                    alpha = p
                    if (!lowPerformance && !reducedMotion) {
                        translationY = FolioPlayerMotionTokens.MENU_BOTTOM_OFFSET_DP.dp.toPx() * (1f - p)
                    }
                }
        }

        Column(
            modifier = panelModifier
                .heightIn(max = availableHeight)
                .folioPlayerGlass(isLight = isLight, shape = sheetShape)
                .background(glassBacking, sheetShape)
                .clip(sheetShape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (languagePicker) {
                            FolioLyricsLanguagePicker.Translation -> R.string.player_lyrics_choose_translation_language
                            FolioLyricsLanguagePicker.Pronunciation -> R.string.player_lyrics_choose_pronunciation_script
                            null -> R.string.player_lyrics_display_title
                        },
                    ),
                    color = fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(rowBg)
                        .folioPressable(
                            compact = true,
                            isLight = isLight,
                            role = Role.Button,
                            onClick = { if (languagePicker != null) languagePicker = null else onDismiss() },
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (languagePicker == null) R.string.player_lyrics_display_done
                            else R.string.player_lyrics_language_back,
                        ),
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (languagePicker) {
                    FolioLyricsLanguagePicker.Translation -> {
                        FolioTranslationLanguageTags.forEach { tag ->
                            val effectiveTag = tag.ifBlank { appLanguageTag }
                            val label = if (tag.isBlank()) {
                                stringResource(
                                    R.string.player_lyrics_language_follow_app_format,
                                    languageName(effectiveTag),
                                )
                            } else {
                                languageName(tag)
                            }
                            FolioLyricsChoiceRow(
                                label = label,
                                selected = tag == translationLanguageTag,
                                fg = fg,
                                selectedBg = rowSelectedBg,
                                normalBg = rowBg,
                                shape = rowShape,
                                onClick = {
                                    onTranslationLanguageChange(tag)
                                    languagePicker = null
                                },
                            )
                        }
                    }

                    FolioLyricsLanguagePicker.Pronunciation -> {
                        folioPronunciationLanguageTags(appLanguageTag).forEach { tag ->
                            val label = if (tag == "en-US") {
                                stringResource(R.string.player_lyrics_pronunciation_latin)
                            } else {
                                stringResource(
                                    R.string.player_lyrics_pronunciation_app_script_format,
                                    languageName(tag),
                                )
                            }
                            FolioLyricsChoiceRow(
                                label = label,
                                selected = tag == pronunciationLanguageTag,
                                fg = fg,
                                selectedBg = rowSelectedBg,
                                normalBg = rowBg,
                                shape = rowShape,
                                onClick = {
                                    onPronunciationLanguageChange(tag)
                                    languagePicker = null
                                },
                            )
                        }
                    }

                    null -> {
                        FolioLyricsValueRow(
                            title = stringResource(R.string.flyme_lyrics_text_size),
                            value = stringResource(
                                when (lyricTextSize) {
                                    LyricTextSize.Standard -> R.string.flyme_text_standard
                                    LyricTextSize.Large -> R.string.flyme_text_large
                                    LyricTextSize.ExtraLarge -> R.string.flyme_text_extra_large
                                },
                            ),
                            fg = fg,
                            fg2 = fg2,
                            bg = rowBg,
                            shape = rowShape,
                            onClick = { onLyricTextSizeChange(lyricTextSize.next()) },
                        )

                        FolioLyricsOffsetRow(
                            lyricOffsetMs = lyricOffsetMs,
                            fg = fg,
                            fg2 = fg2,
                            rowBg = rowBg,
                            chipBg = pillInactiveBg,
                            rowShape = rowShape,
                            onLyricOffsetChange = onLyricOffsetChange,
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.player_lyrics_source_language),
                                color = fg2,
                                fontFamily = fontFamily,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = stringResource(R.string.player_lyrics_source_auto),
                                color = fg.copy(alpha = 0.85f),
                                fontFamily = fontFamily,
                                fontSize = 13.sp,
                            )
                        }

                        val translationStatus = resolveLyricsOptionStatus(
                            optionEnabled = translationEnabled,
                            optionAvailable = translationAvailable,
                            enhancementsReady = enhancementsReady,
                            enhancementsFailed = enhancementsFailed,
                        )
                        FolioLyricsToggleRow(
                            title = stringResource(R.string.player_lyrics_translation),
                            description = stringResource(
                                when (translationStatus) {
                                    FolioLyricsOptionStatus.LoadFailed -> R.string.player_lyrics_enhancement_load_failed
                                    FolioLyricsOptionStatus.Loading -> R.string.player_lyrics_enhancement_loading
                                    FolioLyricsOptionStatus.Unavailable -> R.string.player_lyrics_translation_unavailable
                                    FolioLyricsOptionStatus.Hint -> R.string.player_lyrics_translation_hint
                                },
                            ),
                            enabled = translationEnabled,
                            fg = fg,
                            fg2 = fg2,
                            bg = rowBg,
                            pillActiveBg = pillActiveBg,
                            pillActiveFg = pillActiveFg,
                            pillInactiveBg = pillInactiveBg,
                            shape = rowShape,
                            onClick = { onTranslationChange(!translationEnabled) },
                        )

                        FolioLyricsValueRow(
                            title = stringResource(R.string.player_lyrics_translate_to),
                            value = if (translationLanguageTag.isBlank()) {
                                stringResource(
                                    R.string.player_lyrics_language_follow_app_format,
                                    languageName(effectiveTranslationTag),
                                )
                            } else {
                                languageName(effectiveTranslationTag)
                            },
                            fg = fg,
                            fg2 = fg2,
                            bg = rowBg,
                            shape = rowShape,
                            onClick = { languagePicker = FolioLyricsLanguagePicker.Translation },
                        )

                        val pronunciationStatus = resolveLyricsOptionStatus(
                            optionEnabled = pronunciationEnabled,
                            optionAvailable = pronunciationAvailable,
                            enhancementsReady = enhancementsReady,
                            enhancementsFailed = enhancementsFailed,
                        )
                        FolioLyricsToggleRow(
                            title = stringResource(R.string.player_lyrics_pronunciation),
                            description = stringResource(
                                when (pronunciationStatus) {
                                    FolioLyricsOptionStatus.LoadFailed -> R.string.player_lyrics_enhancement_load_failed
                                    FolioLyricsOptionStatus.Loading -> R.string.player_lyrics_enhancement_loading
                                    FolioLyricsOptionStatus.Unavailable -> R.string.player_lyrics_pronunciation_unavailable
                                    FolioLyricsOptionStatus.Hint -> R.string.player_lyrics_pronunciation_hint
                                },
                            ),
                            enabled = pronunciationEnabled,
                            fg = fg,
                            fg2 = fg2,
                            bg = rowBg,
                            pillActiveBg = pillActiveBg,
                            pillActiveFg = pillActiveFg,
                            pillInactiveBg = pillInactiveBg,
                            shape = rowShape,
                            onClick = { onPronunciationChange(!pronunciationEnabled) },
                        )

                        FolioLyricsValueRow(
                            title = stringResource(R.string.player_lyrics_pronunciation_script),
                            value = if (pronunciationLanguageTag == "en-US") {
                                stringResource(R.string.player_lyrics_pronunciation_latin)
                            } else {
                                stringResource(
                                    R.string.player_lyrics_pronunciation_app_script_format,
                                    languageName(pronunciationLanguageTag),
                                )
                            },
                            fg = fg,
                            fg2 = fg2,
                            bg = rowBg,
                            shape = rowShape,
                            onClick = { languagePicker = FolioLyricsLanguagePicker.Pronunciation },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolioLyricsToggleRow(
    title: String,
    description: String,
    enabled: Boolean,
    fg: Color,
    fg2: Color,
    bg: Color,
    pillActiveBg: Color,
    pillActiveFg: Color,
    pillInactiveBg: Color,
    shape: Shape,
    onClick: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .folioPressable(role = Role.Switch, onClick = onClick)
            .semantics { selected = enabled }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = fg,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = description,
                color = fg2,
                fontFamily = fontFamily,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) pillActiveBg else pillInactiveBg)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (enabled) R.string.player_lyrics_display_on else R.string.player_lyrics_display_off,
                ),
                color = if (enabled) pillActiveFg else fg.copy(alpha = 0.72f),
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FolioLyricsValueRow(
    title: String,
    value: String,
    fg: Color,
    fg2: Color,
    bg: Color,
    shape: Shape,
    onClick: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .folioPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = fg,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        )
        Text(
            text = value,
            color = fg2,
            fontFamily = fontFamily,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun FolioLyricsChoiceRow(
    label: String,
    selected: Boolean,
    fg: Color,
    selectedBg: Color,
    normalBg: Color,
    shape: Shape,
    onClick: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) selectedBg else normalBg)
            .folioPressable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = fontFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FolioLyricsOffsetRow(
    lyricOffsetMs: Long,
    fg: Color,
    fg2: Color,
    rowBg: Color,
    chipBg: Color,
    rowShape: Shape,
    onLyricOffsetChange: (Long) -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    val statusText = formatFolioLyricOffsetLabel(
        offsetMs = lyricOffsetMs,
        advanceFormat = stringResource(R.string.folio_player_offset_advance),
        delayFormat = stringResource(R.string.folio_player_offset_delay),
        unadjustedLabel = stringResource(R.string.folio_player_offset_unadjusted),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(rowBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.folio_player_offset_title),
                color = fg,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = statusText,
                color = if (lyricOffsetMs == 0L) fg2 else fg,
                fontFamily = fontFamily,
                fontWeight = if (lyricOffsetMs == 0L) FontWeight.Normal else FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val steps = listOf(-100L to "-100", -50L to "-50", 50L to "+50", 100L to "+100")
            steps.forEach { (delta, label) ->
                val enabled = if (delta < 0L) {
                    lyricOffsetMs > FOLIO_LYRIC_OFFSET_MIN_MS
                } else {
                    lyricOffsetMs < FOLIO_LYRIC_OFFSET_MAX_MS
                }
                FolioOffsetStepButton(
                    label = label,
                    enabled = enabled,
                    fg = fg,
                    bg = chipBg,
                    onClick = { onLyricOffsetChange(adjustFolioLyricOffsetMs(lyricOffsetMs, delta)) },
                    modifier = Modifier.weight(1f),
                )
            }
            FolioOffsetStepButton(
                label = stringResource(R.string.browse_reset),
                enabled = lyricOffsetMs != 0L,
                fg = fg,
                bg = chipBg,
                onClick = { onLyricOffsetChange(0L) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FolioOffsetStepButton(
    label: String,
    enabled: Boolean,
    fg: Color,
    bg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.45f))
            .folioPressable(enabled = enabled, compact = true, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg.copy(alpha = if (enabled) 1f else 0.36f),
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
