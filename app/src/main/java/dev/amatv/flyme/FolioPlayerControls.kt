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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    isFavorite: Boolean,
    isInLibrary: Boolean,
    favoriteInProgress: Boolean,
    libraryInProgress: Boolean,
    isPosterMode: Boolean,
    hasTimedLyrics: Boolean,
    pullHandle: Modifier,
    onClose: () -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
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
            if (showHeaderTitle) {
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
                    if (track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
                            color = headerColors.fg2,
                            fontFamily = fontFamily,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenArtist),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
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
    onNext: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.BottomStart,
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

    Box(modifier = modifier, contentAlignment = contentAlignment) {
        val showPmeta = !controlsExpanded || pmetaAlpha > 0.01f
        val showCtl = controlsExpanded || ctlAlpha > 0.01f

        val pmetaDeck: @Composable () -> Unit = {
            FolioPlayerCollapsedCapsule(
                track = track,
                tierSpec = tierSpec,
                showArtwork = showCollapsedCapsuleArtwork(tierSpec.tier, displayState),
                positionState = positionState,
                durationMs = durationMs,
                playWhenReady = playWhenReady,
                isLight = isLight,
                fg = fg,
                fg2 = fg2,
                onExpand = onExpandCapsule,
                onTogglePlay = onPlayPause,
                modifier = Modifier.graphicsLayer {
                    alpha = pmetaAlpha
                    translationY = 8.dp.toPx() * pmetaOffsetFactor
                    val sc = 1f - 0.02f * pmetaOffsetFactor
                    scaleX = sc
                    scaleY = sc
                },
            )
        }
        val ctlDeck: @Composable () -> Unit = {
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
                modifier = Modifier.graphicsLayer {
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
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val shape = RoundedCornerShape(FolioRadii.Capsule)
    val playIconBg = fg
    val playIconTint = if (isLight) Color.White else Color(0xFF17151C)
    val expandCd = stringResource(R.string.folio_player_controls_expand)

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
                    .clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = expandCd }
                    .padding(start = if (showArtwork) 10.dp else 18.dp, end = 4.dp),
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
                    if (track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
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
                    .clickable(role = Role.Button, onClick = onTogglePlay),
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
) {
    val fontFamily = LocalFolioFontFamily.current
    val cardShape = RoundedCornerShape(22.dp)
    val playBg = fg
    val playFg = if (isLight) Color.White else Color(0xFF17151C)
    val sideBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f)

    if (tierSpec.isHorizontalCarControls) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .folioPlayerGlass(isLight = isLight, shape = cardShape)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Driver (left) side transport buttons
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
                onToggleTranslation = onToggleTranslation,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleQueue = onToggleQueue,
                horizontalSpacing = 14.dp,
            )

            // Right side track summary + seek bar
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FolioArtwork(
                        track = track,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .size(52.dp)
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
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenAlbum),
                        )
                        if (track.artist.isNotBlank()) {
                            Text(
                                text = track.artist,
                                color = fg2,
                                fontFamily = fontFamily,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(onClick = onOpenArtist),
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
            }
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
                    if (track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
                            color = fg2,
                            fontFamily = fontFamily,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenArtist),
                        )
                    }
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
                .clickable(role = Role.Button, onClick = onPlayPause),
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
