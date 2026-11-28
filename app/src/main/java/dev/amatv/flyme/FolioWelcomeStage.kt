package dev.amatv.flyme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.LyricsState
import dev.amatv.data.MockCatalog
import dev.amatv.integration.LoudnessMode
import dev.amatv.integration.LyricTextSize
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.delay

internal val WELCOME_LOUDNESS_LEVELS = mapOf(
    LoudnessMode.Off to listOf(0.30f, 1.00f, 0.45f, 0.90f, 0.25f),
    LoudnessMode.Standard to listOf(0.50f, 0.62f, 0.55f, 0.60f, 0.50f),
    LoudnessMode.Loud to listOf(0.74f, 0.80f, 0.76f, 0.79f, 0.74f),
    LoudnessMode.Loudest to listOf(0.90f, 0.93f, 0.91f, 0.92f, 0.90f),
)

@Composable
internal fun WelcomeCoverWall(
    rows: List<List<Track>>,
    isPhonePortrait: Boolean,
    pageId: WelcomePageId,
    dragOffsetPx: Float,
    animated: Boolean,
    lowPerformance: Boolean,
    frostCapture: WelcomeFrostCapture?,
) {
    val pauseMarquee = frostCapture != null
    val tileSize = if (isPhonePortrait) 27f else 11f
    val gap = if (isPhonePortrait) 2.6f else 1.2f
    val density = LocalDensity.current

    val targetWallAlpha = when (pageId) {
        WelcomePageId.Welcome -> 1f
        WelcomePageId.SignIn -> 0.28f
        else -> 0f
    }
    val targetWallScale = when (pageId) {
        WelcomePageId.Welcome, WelcomePageId.SignIn -> 1f
        else -> 1.08f
    }
    val animSpec = if (lowPerformance) {
        tween<Float>(180)
    } else {
        tween<Float>(700, easing = FolioMotion.PageEasing)
    }
    val wallAlpha by animateFloatAsState(targetWallAlpha, animSpec, label = "wall-alpha")
    val wallScale by animateFloatAsState(targetWallScale, animSpec, label = "wall-scale")

    val marqueeActive = animated && !lowPerformance && !pauseMarquee && wallAlpha > 0.01f
    val transition = rememberInfiniteTransition(label = "welcome-wall-marquee")
    val forwardFraction by if (marqueeActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
            label = "wall-fwd",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val reverseFraction by if (marqueeActive) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(140_000, easing = LinearEasing), RepeatMode.Restart),
            label = "wall-rev",
        )
    } else {
        // Hold opposing rows at the phase they will start moving from.
        remember(pauseMarquee) { mutableFloatStateOf(if (pauseMarquee) 1f else 0f) }
    }

    BoxWithConstraints(
        modifier = (frostCapture?.wallModifier ?: Modifier)
            .fillMaxSize()
            .graphicsLayer {
                alpha = wallAlpha
                scaleX = wallScale
                scaleY = wallScale
                translationX = if (lowPerformance) 0f else dragOffsetPx * 0.15f
            },
    ) {
        val u = maxWidth / 100f
        val tileDp = u * tileSize
        val gapDp = u * gap
        val cornerDp = u * (if (isPhonePortrait) 2.2f else 0.8f)
        val tilePx = with(density) { tileDp.toPx() }
        val gapPx = with(density) { gapDp.toPx() }

        if (rows.isEmpty()) return@BoxWithConstraints
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(gapDp, Alignment.CenterVertically),
        ) {
            rows.forEachIndexed { rowIdx, rowTracks ->
                key(rowIdx) {
                    val halfSpanPx = rowTracks.size * (tilePx + gapPx)
                    val progress = if (rowIdx % 2 == 1) reverseFraction else forwardFraction
                    val rowShiftPx = -halfSpanPx * progress
                    Row(
                        modifier = Modifier
                            .graphicsLayer { translationX = rowShiftPx },
                        horizontalArrangement = Arrangement.spacedBy(gapDp),
                    ) {
                        repeat(2) { copyIdx ->
                            rowTracks.forEachIndexed { colIdx, track ->
                                val tileKey = "$rowIdx-$copyIdx-$colIdx"
                                key(tileKey) {
                                    val tile = if (frostCapture != null) {
                                        remember(frostCapture, track.motionPreviewUrl ?: track.artworkUrl) {
                                            WelcomeWallTile()
                                        }
                                    } else null
                                    val visibility = if (frostCapture != null && tile != null) {
                                        DisposableEffect(frostCapture, tile) {
                                            onDispose { frostCapture.setTileVisible(tile, false) }
                                        }
                                        Modifier.onGloballyPositioned { coordinates ->
                                            frostCapture.setTileVisible(tile, !coordinates.boundsInWindow().isEmpty)
                                        }
                                    } else Modifier
                                    FolioArtwork(
                                        track = track,
                                        shape = RoundedCornerShape(cornerDp),
                                        modifier = Modifier.size(tileDp).then(visibility),
                                        onArtworkLoaded = tile?.let { { it.loaded = true } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SharedDemoPlayerStage(
    tracks: List<Track>,
    activeTrackIdx: Int,
    lyricLines: List<LyricLine>,
    showLyricsDemo: Boolean,
    style: LyricBackgroundStyle,
    lyricTextSize: LyricTextSize,
    gaplessActive: Boolean,
    gaplessProgress: Float,
    showGaplessTag: Boolean,
    showLoudnessBars: Boolean,
    loudnessMode: LoudnessMode,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    screenSize: IntSize,
    statusTop: Dp,
    navBottom: Dp,
    stageWidthDp: Dp,
    u: Dp,
    modifier: Modifier = Modifier,
) {
    val track = tracks[activeTrackIdx]
    val useShowcaseLyrics = shouldRenderWelcomeLyricsLines(showLyricsDemo, gaplessActive, activeTrackIdx)
    val lines = remember(useShowcaseLyrics, lyricLines, activeTrackIdx) {
        if (useShowcaseLyrics && hasTimedLyrics(lyricLines)) {
            lyricLines
        } else {
            rebaseWelcomeLyricSlice(MockCatalog.tracks[activeTrackIdx].lyrics)
        }
    }
    val durationMs = (lines.last().endMs + 1_200L).coerceAtLeast(3_000L)
    val position = remember(track.id, lines) { FolioPlaybackPosition(0L) }
    LaunchedEffect(position, durationMs, lowPerformance) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                position.update(((now - start) / 1_000_000L) % durationMs)
            }
            if (lowPerformance) delay(32L)
        }
    }

    val tierSpec = playerSpecForTier(LocalFolioTierSpec.current.tier)
    val displayState = resolvePlayerDisplayState(
        tier = tierSpec.tier,
        queueOpen = false,
        posterPreferred = resolveEffectivePosterPreferred(style),
        hasLyrics = true,
        hasTimedLyrics = true,
    )
    val queue = remember(tracks, activeTrackIdx) { welcomeDemoQueue(tracks, activeTrackIdx) }
    val callbacks = remember { FolioNowPlayingCallbacks() }
    val shape = RoundedCornerShape(u * 1.6f)
    val screenRatio = screenSize.width.toFloat() / screenSize.height
    Box(
        modifier = modifier
            .width(stageWidthDp)
            .aspectRatio(screenRatio)
            .shadow(elevation = u * 1.2f, shape = shape, clip = false)
            .clip(shape)
            .clearAndSetSemantics { hideFromAccessibility() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Layout(
            modifier = Modifier.matchParentSize(),
            content = {
                FolioNowPlayingSurface(
                    track = track,
                    displayedLines = lines,
                    positionState = position,
                    isPlaying = true,
                    playWhenReady = true,
                    isLoading = false,
                    effectiveStyle = style,
                    lyricTextSize = lyricTextSize,
                    isLight = LocalFolioPalette.current.isLight,
                    lowPerformance = lowPerformance,
                    reducedMotion = reducedMotion,
                    controlsExpanded = true,
                    alwaysOpen = alwaysOpenControls(tierSpec.tier, style, true, displayState),
                    tierSpec = tierSpec,
                    displayState = displayState,
                    underlayDisplayState = displayState,
                    statusTop = statusTop,
                    navBottom = navBottom,
                    durationMs = durationMs,
                    canSeek = true,
                    canSkipPrevious = true,
                    canSkipNext = true,
                    lyricsState = LyricsState(),
                    queueSnapshot = queue,
                    callbacks = callbacks,
                )
            },
        ) { measurables, constraints ->
            val player = measurables.single().measure(Constraints.fixed(screenSize.width, screenSize.height))
            val scale = welcomePlayerScale(screenSize.width, constraints.maxWidth)
            layout(constraints.maxWidth, constraints.maxHeight) {
                player.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                }
            }
        }

        val stageHeightDp = stageWidthDp / screenRatio
        // Loudness preview bars (Preferences page on Loudness change)
        val barsAlpha by animateFloatAsState(
            targetValue = if (showLoudnessBars) 1f else 0f,
            animationSpec = tween(260),
            label = "lvl-alpha",
        )
        if (barsAlpha > 0.01f) {
            val heights = WELCOME_LOUDNESS_LEVELS[loudnessMode].orEmpty()
            Row(
                modifier = Modifier
                    .offset(x = stageWidthDp * 0.45f, y = stageHeightDp * 0.54f)
                    .size(stageWidthDp * 0.49f, stageHeightDp * 0.34f)
                    .graphicsLayer { alpha = barsAlpha },
                horizontalArrangement = Arrangement.spacedBy(stageWidthDp * 0.02f),
                verticalAlignment = Alignment.Bottom,
            ) {
                heights.forEachIndexed { idx, frac ->
                    key(idx) {
                        val animFrac by animateFloatAsState(
                            targetValue = frac,
                            animationSpec = if (lowPerformance) tween(160) else spring(0.8f, 320f),
                            label = "lvl-$idx",
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(animFrac)
                                .clip(RoundedCornerShape(stageWidthDp * 0.008f))
                                .background(Color.White.copy(alpha = 0.9f)),
                        )
                    }
                }
            }
        }

        // Gapless demo progress bar + "空了 0.08 秒" badge
        if (gaplessActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = stageWidthDp * 0.06f, vertical = stageHeightDp * 0.05f)
                    .fillMaxWidth()
                    .height((stageHeightDp * 0.012f).coerceAtLeast(2.dp))
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(gaplessProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White),
                )
            }
        }

        AnimatedVisibility(
            visible = showGaplessTag,
            enter = fadeIn(tween(220)) + scaleIn(
                initialScale = 0.9f,
                animationSpec = if (lowPerformance) tween(160) else spring(0.8f, 500f),
            ),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = stageHeightDp * 0.07f, end = stageWidthDp * 0.05f),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .padding(horizontal = stageWidthDp * 0.020f, vertical = stageHeightDp * 0.022f),
            ) {
                Text(
                    text = stringResource(R.string.welcome_gapless_tag),
                    color = Color(0xFF0B0A0E),
                    fontFamily = LocalFolioFontFamily.current,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (stageWidthDp.value * 0.020f).sp,
                )
            }
        }
    }
}

/** Dark rounded tooltip bubble with an upward pointer triangle (`hint_pivot_scrub` / `hint_style_toggle`). */
@Composable
internal fun FolioHintBubble(
    visible: Boolean,
    text: String,
    pointerBiasX: Float,
    lowPerformance: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enterAnim = if (lowPerformance) {
        fadeIn(tween(150))
    } else {
        fadeIn(tween(200)) + scaleIn(
            initialScale = 0.90f,
            transformOrigin = TransformOrigin(pointerBiasX.coerceIn(0.1f, 0.9f), 0f),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f),
        )
    }
    val exitAnim = fadeOut(tween(160)) + scaleOut(
        targetScale = 0.94f,
        transformOrigin = TransformOrigin(pointerBiasX.coerceIn(0.1f, 0.9f), 0f),
        animationSpec = tween(160),
    )

    AnimatedVisibility(
        visible = visible,
        enter = enterAnim,
        exit = exitAnim,
        modifier = modifier,
    ) {
        val bubbleBg = Color(0xFF1B1922).copy(alpha = 0.95f)
        val borderCol = Color.White.copy(alpha = 0.14f)
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp),
            ) {
                val tipX = (size.width * pointerBiasX.coerceIn(0.1f, 0.9f))
                val halfBase = 7.dp.toPx()
                val path = Path().apply {
                    moveTo(tipX, 0f)
                    lineTo(tipX - halfBase, size.height)
                    lineTo(tipX + halfBase, size.height)
                    close()
                }
                drawPath(path, bubbleBg)
            }
            Box(
                modifier = Modifier
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleBg)
                    .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontFamily = LocalFolioFontFamily.current,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
