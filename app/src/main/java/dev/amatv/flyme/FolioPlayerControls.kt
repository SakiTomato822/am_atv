package dev.amatv.flyme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.LocalHostWindow
import dev.amatv.R
import dev.amatv.model.Track
import kotlin.math.roundToLong

internal fun Modifier.folioPlayerGlass(
    isLight: Boolean,
    shape: Shape,
): Modifier {
    val fill = if (isLight) {
        Color.White.copy(alpha = 0.86f)
    } else {
        Color(0xE0211D26)
    }
    val stroke = if (isLight) {
        Color(0xFF17151C).copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.22f)
    }
    return clip(shape)
        .background(fill)
        .border(1.dp, stroke, shape)
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
        it.canOpenMini && (
            it.mini ||
                tierSpec.tier == FolioTier.Wide ||
                tierSpec.tier == FolioTier.Ultrawide ||
                tierSpec.tier == FolioTier.Short
            )
    }
    val collapsedMode = resolveCollapsedHeaderTitleMode(tierSpec.tier, displayState)
    val collapsedAlpha by animateFloatAsState(
        targetValue = if (!controlsExpanded && collapsedMode != FolioCollapsedHeaderTitleMode.None) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_OPACITY_MS),
        label = "hdr-tt-alpha",
    )
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
                iconTint = headerColors.fg,
                bg = Color.Transparent,
                border = Color.Transparent,
                onClick = onClose,
            )
            when {
                showHeaderTitle -> {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = track.title,
                            color = headerColors.fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenAlbum),
                        )
                        FolioExpandedSubtitle(
                            artist = track.artist,
                            album = track.album,
                            color = headerColors.fg2,
                            fontSize = 13.sp,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
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
                            .clickable(
                                enabled = !controlsExpanded,
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
                            Text(
                                text = track.title,
                                color = headerColors.fg,
                                fontFamily = fontFamily,
                                fontWeight = if (isLarge) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = if (isLarge) 24.sp else 17.sp,
                                lineHeight = if (isLarge) 28.sp else 20.sp,
                                maxLines = if (isLarge) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    color = headerColors.fg2,
                                    fontFamily = fontFamily,
                                    fontSize = if (isLarge) 15.sp else 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                else -> {
                    Spacer(Modifier.weight(1f))
                }
            }

            PlayerIconPillButton(
                icon = if (isInLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = stringResource(
                    if (isInLibrary) R.string.folio_player_in_library else R.string.folio_player_add_library,
                ),
                size = tierSpec.pib,
                iconTint = headerColors.fg,
                bg = Color.Transparent,
                border = Color.Transparent,
                enabled = !libraryInProgress && !isInLibrary,
                dimDisabled = libraryInProgress,
                onClick = onToggleLibrary,
            )

            PlayerIconPillButton(
                icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.player_favorite_remove else R.string.player_favorite_add,
                ),
                size = tierSpec.pib,
                iconTint = if (isFavorite) favoriteAccent else headerColors.fg,
                bg = Color.Transparent,
                border = Color.Transparent,
                enabled = !favoriteInProgress,
                onClick = onToggleFavorite,
            )

            val modeBg by animateColorAsState(
                targetValue = if (isPosterMode) headerColors.posterActiveBg else Color.Transparent,
                animationSpec = tween(FolioMotion.FAST_MS),
                label = "player-mode-bg",
            )
            val modeCd = stringResource(
                if (isPosterMode) R.string.folio_player_toggle_lyrics else R.string.folio_player_toggle_poster,
            )
            Box(
                modifier = Modifier
                    .size(tierSpec.pib)
                    .clip(CircleShape)
                    .background(modeBg)
                    .clickable(enabled = hasTimedLyrics, role = Role.Button, onClick = onTogglePosterMode)
                    .semantics { contentDescription = modeCd },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Tt",
                    color = headerColors.fg.copy(alpha = if (hasTimedLyrics) 1f else 0.36f),
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            if (pipWindow != null) {
                PlayerIconPillButton(
                    icon = if (pipWindow.mini) Icons.Rounded.Fullscreen else Icons.Rounded.PictureInPictureAlt,
                    contentDescription = stringResource(
                        if (pipWindow.mini) R.string.flyme_open_full_screen else R.string.flyme_open_mini_window,
                    ),
                    size = tierSpec.pib,
                    iconTint = headerColors.fg,
                    bg = headerColors.glassBg,
                    border = headerColors.glassBorder,
                    onClick = { pipWindow.switchTo(!pipWindow.mini) },
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
    translationEnabled: Boolean,
    queueOpen: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    onExpandCapsule: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleTranslation: () -> Unit,
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
    val ctlAlpha by animateFloatAsState(
        targetValue = if (controlsExpanded) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_OPACITY_MS),
        label = "ctl-alpha",
    )
    val ctlOffsetFactor by animateFloatAsState(
        targetValue = if (controlsExpanded) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_TRANSLATE_MS,
            easing = FolioPlayerMotionTokens.EnterEasing,
        ),
        label = "ctl-offset",
    )
    val pmetaAlpha by animateFloatAsState(
        targetValue = if (controlsExpanded) 0f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else FolioPlayerMotionTokens.PMETA_OPACITY_MS),
        label = "pmeta-alpha",
    )
    val pmetaOffsetFactor by animateFloatAsState(
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
            (!controlsExpanded || pmetaAlpha > 0.01f)
        val showCtl = controlsExpanded || ctlAlpha > 0.01f

        val pmetaDeck: @Composable () -> Unit = {
            val animMod = Modifier.graphicsLayer {
                alpha = pmetaAlpha
                translationY = 8.dp.toPx() * pmetaOffsetFactor
                val sc = 1f - 0.02f * pmetaOffsetFactor
                scaleX = sc
                scaleY = sc
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
                    .padding(start = 4.dp, bottom = 4.dp)
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
                translationEnabled = translationEnabled,
                queueOpen = queueOpen,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                isLight = isLight,
                fg = fg,
                fg2 = fg2,
                playEnabled = controlsExpanded,
                carControlsWidthDp = carControlsWidthDp,
                onSeekTo = onSeekTo,
                onToggleTranslation = onToggleTranslation,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleQueue = onToggleQueue,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                modifier = widePlacement.graphicsLayer {
                    alpha = ctlAlpha
                    translationY = FolioPlayerMotionTokens.CTL_OFFSET_DP.dp.toPx() * ctlOffsetFactor
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
    Box(
        modifier = modifier
            .size(size)
            .folioPlayerGlass(isLight = isLight, shape = CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onTogglePlay),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(
                if (playWhenReady) R.string.player_pause else R.string.player_play,
            ),
            tint = fg,
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
                color = fg.copy(alpha = 0.85f),
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
                    .clickable(
                        enabled = interactive,
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
                    Text(
                        text = subtitle,
                        color = fg2,
                        fontFamily = fontFamily,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

@Composable
internal fun FolioExpandedSubtitle(
    artist: String,
    album: String,
    color: Color,
    fontSize: TextUnit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val cleanArtist = artist.trim()
    val cleanAlbum = album.trim()
    if (cleanArtist.isEmpty() && cleanAlbum.isEmpty()) return

    val baseSpan = remember(color) {
        SpanStyle(
            color = color,
            fontWeight = FontWeight.Normal,
            textDecoration = TextDecoration.None,
        )
    }
    val pressedSpan = remember(color) {
        SpanStyle(
            color = color.copy(alpha = (color.alpha * 0.45f).coerceIn(0.15f, 1f)),
            fontWeight = FontWeight.Normal,
            textDecoration = TextDecoration.None,
        )
    }
    val linkStyles = remember(baseSpan, pressedSpan) {
        TextLinkStyles(
            style = baseSpan,
            focusedStyle = baseSpan,
            hoveredStyle = baseSpan,
            pressedStyle = pressedSpan,
        )
    }
    val annotated = remember(cleanArtist, cleanAlbum, linkStyles, onOpenArtist, onOpenAlbum) {
        buildAnnotatedString {
            if (cleanArtist.isNotEmpty()) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "artist",
                        styles = linkStyles,
                        linkInteractionListener = { onOpenArtist() },
                    ),
                ) {
                    append(cleanArtist)
                }
            }
            if (cleanArtist.isNotEmpty() && cleanAlbum.isNotEmpty()) {
                append(" — ")
            }
            if (cleanAlbum.isNotEmpty()) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "album",
                        styles = linkStyles,
                        linkInteractionListener = { onOpenAlbum() },
                    ),
                ) {
                    append(cleanAlbum)
                }
            }
        }
    }
    Text(
        text = annotated,
        color = color,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
    val playIconBg = fg
    val playIconTint = if (isLight) Color.White else Color(0xFF17151C)
    val expandCd = stringResource(R.string.folio_player_controls_expand)
    val subtitle = formatFolioPlayerSubtitle(track.artist, track.album)

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
            .folioPlayerGlass(isLight = isLight, shape = shape),
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
            // summary region expands the full control deck while Play/Pause stays independent.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 999.dp, bottomStart = 999.dp, topEnd = 16.dp, bottomEnd = 16.dp))
                    .clickable(enabled = interactive, role = Role.Button, onClick = onExpand)
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
                    Text(
                        text = track.title,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = fg2,
                            fontFamily = fontFamily,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(playIconBg)
                    .clickable(enabled = interactive, role = Role.Button, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (playWhenReady) R.string.player_pause else R.string.player_play,
                    ),
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
                    color = fg.copy(alpha = 0.78f),
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
    translationEnabled: Boolean,
    queueOpen: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    onSeekTo: (Long) -> Unit,
    onToggleTranslation: () -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    color = fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = playEnabled, onClick = onOpenAlbum),
                )
                FolioExpandedSubtitle(
                    artist = track.artist,
                    album = track.album,
                    color = fg2,
                    fontSize = 14.sp,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
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
                translationEnabled = translationEnabled,
                queueOpen = queueOpen,
                playWhenReady = playWhenReady,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                fg = fg,
                playBg = playBg,
                playFg = playFg,
                sideBg = sideBg,
                playEnabled = playEnabled,
                onToggleTranslation = onToggleTranslation,
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
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onOpenAlbum),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = track.title,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenAlbum),
                    )
                    FolioExpandedSubtitle(
                        artist = track.artist,
                        album = track.album,
                        color = fg2,
                        fontSize = 13.sp,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
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
            translationEnabled = translationEnabled,
            queueOpen = queueOpen,
            playWhenReady = playWhenReady,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            fg = fg,
            playBg = playBg,
            playFg = playFg,
            sideBg = sideBg,
            playEnabled = playEnabled,
            onToggleTranslation = onToggleTranslation,
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
    translationEnabled: Boolean,
    queueOpen: Boolean,
    playWhenReady: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    fg: Color,
    playBg: Color,
    playFg: Color,
    sideBg: Color,
    playEnabled: Boolean,
    onToggleTranslation: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    horizontalSpacing: Dp?,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val translateCd = stringResource(R.string.folio_player_translate_toggle)
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
                .clip(CircleShape)
                .background(if (translationEnabled) sideBg else Color.Transparent)
                .clickable(role = Role.Button, onClick = onToggleTranslation)
                .semantics { contentDescription = translateCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.folio_player_translate_short),
                color = fg.copy(alpha = if (translationEnabled) 1f else 0.45f),
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
                .background(playBg)
                .clickable(enabled = playEnabled, role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (playWhenReady) R.string.player_pause else R.string.player_play,
                ),
                tint = playFg,
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
                    color = fg.copy(alpha = 0.88f),
                    topLeft = Offset(0f, trackY),
                    size = Size(fillW, trackH),
                    cornerRadius = radius,
                )
                if (canSeek && durationMs > 0L) {
                    val thumbX = fillW.coerceIn(trackH, (size.width - trackH).coerceAtLeast(trackH))
                    drawCircle(
                        color = fg,
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
    enabled: Boolean = true,
    dimDisabled: Boolean = true,
) {
    val alpha = if (enabled || !dimDisabled) 1f else 0.36f
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint.copy(alpha = alpha),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}
