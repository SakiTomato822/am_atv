package dev.amatv.flyme

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.LoudnessMode
import dev.amatv.integration.LyricTextSize
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    rootCoords: LayoutCoordinates?,
    hiddenFlyTileKey: String?,
    onReportFlyCandidate: (String, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    val marqueeActive = animated && !lowPerformance && wallAlpha > 0.01f
    val transition = rememberInfiniteTransition(label = "welcome-wall-marquee")
    val forwardFraction by if (marqueeActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
            label = "wall-fwd",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    val reverseFraction by if (marqueeActive) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(140_000, easing = LinearEasing), RepeatMode.Restart),
            label = "wall-rev",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    BoxWithConstraints(
        modifier = modifier
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
                                    val reportCandidate = !lowPerformance && wallAlpha > 0.2f && rowIdx in 1..3 &&
                                        rootCoords != null && rootCoords.isAttached
                                    FolioArtwork(
                                        track = track,
                                        shape = RoundedCornerShape(cornerDp),
                                        modifier = Modifier
                                            .size(tileDp)
                                            .graphicsLayer {
                                                alpha = if (hiddenFlyTileKey == tileKey) 0f else 1f
                                            }
                                            .then(
                                                if (reportCandidate) {
                                                    Modifier.onGloballyPositioned { coords ->
                                                        val root = rootCoords.takeIf { it.isAttached } ?: return@onGloballyPositioned
                                                        if (!coords.isAttached) return@onGloballyPositioned
                                                        val rect = root.localBoundingBoxOf(coords, clipBounds = false)
                                                        val rw = root.size.width.toFloat()
                                                        val rh = root.size.height.toFloat()
                                                        if (rect.left > rw * 0.52f &&
                                                            rect.right < rw - 10f &&
                                                            rect.top > rh * 0.18f &&
                                                            rect.bottom < rh * 0.80f
                                                        ) {
                                                            onReportFlyCandidate(tileKey, rect)
                                                        }
                                                    }
                                                } else Modifier,
                                            ),
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
    posterMode: Boolean,
    lyricTextSize: LyricTextSize,
    gaplessActive: Boolean,
    gaplessProgress: Float,
    showGaplessTag: Boolean,
    showLoudnessBars: Boolean,
    loudnessMode: LoudnessMode,
    beatActive: Boolean,
    lowPerformance: Boolean,
    animated: Boolean,
    stageWidthDp: Dp,
    u: Dp,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    val safeTrackIdx = activeTrackIdx.coerceIn(0, tracks.lastIndex)
    val activeTrack = tracks[safeTrackIdx]
    val shape = RoundedCornerShape(u * 1.6f)
    val springSpec = if (lowPerformance || !animated) {
        tween<Float>(180)
    } else {
        spring(dampingRatio = 0.82f, stiffness = 280f)
    }
    val posterFraction by animateFloatAsState(
        targetValue = if (posterMode) 1f else 0f,
        // A 0..1 morph: a spring would overshoot past 1 and turn the cover's corner radius negative (crash).
        animationSpec = if (lowPerformance || !animated) tween(160) else tween(560, easing = FolioMotion.PageEasing),
        label = "demo-poster-frac",
    )

    // 64 BPM beat pulse (~937ms) on cover when BGM plays and not lowPerformance
    val beatScale = remember { Animatable(1f) }
    LaunchedEffect(beatActive, lowPerformance, animated) {
        if (!beatActive || lowPerformance || !animated) {
            beatScale.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            beatScale.animateTo(1.018f, tween(135, easing = FolioMotion.PageEasing))
            beatScale.animateTo(1f, tween(315, easing = FolioMotion.PageEasing))
            delay(487L)
        }
    }

    Box(
        modifier = modifier
            .width(stageWidthDp)
            .aspectRatio(16f / 9f)
            .shadow(elevation = u * 2.4f, shape = shape, clip = false)
            .clip(shape)
            .background(Color(0xFF0D0C12))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape),
    ) {
        val stageHeightDp = stageWidthDp * 9f / 16f

        // Background: Flow blurred cover vs Poster solid panel
        if (posterFraction < 0.99f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - posterFraction },
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lowPerformance) {
                    FolioArtwork(
                        track = activeTrack,
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(u * 3.2f, BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer { alpha = 0.55f },
                    )
                } else {
                    val blurred = rememberFolioBlurredCoverBitmap(
                        artworkUrl = activeTrack.artworkUrl,
                        active = true,
                    )
                    if (blurred != null) {
                        Image(
                            bitmap = blurred,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.55f,
                        )
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(0xFF08070A).copy(alpha = 0.45f)))
            }
        }
        if (posterFraction > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = posterFraction }
                    .background(Color(0xFF0A090C)),
            )
        }

        // Cover strip morphing cleanly between Flow tile and Poster left column
        val covLeftDp = stageWidthDp * (0.06f * (1f - posterFraction))
        val covTopDp = stageHeightDp * (0.12f * (1f - posterFraction))
        val covWidthDp = stageWidthDp * (0.32f + 0.06f * posterFraction)
        val covHeightDp = stageHeightDp * (0.569f + 0.431f * posterFraction)
        val covCornerDp = u * 0.9f * (1f - posterFraction)
        val covShape = RoundedCornerShape(covCornerDp)

        val stripShiftPx = with(LocalDensity.current) { -(covWidthDp.toPx()) * safeTrackIdx }
        val animatedStripShift by animateFloatAsState(
            targetValue = stripShiftPx,
            animationSpec = springSpec,
            label = "cover-strip-x",
        )

        Box(
            modifier = Modifier
                .offset(x = covLeftDp, y = covTopDp)
                .size(covWidthDp, covHeightDp)
                .graphicsLayer {
                    val s = beatScale.value
                    scaleX = s
                    scaleY = s
                }
                .shadow(u * (1f * (1f - posterFraction)), covShape)
                .clip(covShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .graphicsLayer { translationX = animatedStripShift },
            ) {
                tracks.forEachIndexed { trackIdx, t ->
                    key(trackIdx, t.id) {
                        FolioArtwork(
                            track = t,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.size(covWidthDp, covHeightDp),
                        )
                    }
                }
            }
        }

        // Track metadata below cover in Flow style
        if (posterFraction < 0.98f) {
            Column(
                modifier = Modifier
                    .offset(x = stageWidthDp * 0.06f, y = stageHeightDp * 0.74f)
                    .width(stageWidthDp * 0.34f)
                    .graphicsLayer { alpha = 1f - posterFraction },
            ) {
                Text(
                    text = activeTrack.title,
                    color = Color.White,
                    fontFamily = LocalFolioFontFamily.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = (stageWidthDp.value * 0.024f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activeTrack.artist,
                    color = Color.White.copy(alpha = 0.62f),
                    fontFamily = LocalFolioFontFamily.current,
                    fontWeight = FontWeight.Normal,
                    fontSize = (stageWidthDp.value * 0.019f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right content area: lead song karaoke lyrics, or large title & artist when signed out / gapless switched
        val lyricsStartDp = stageWidthDp * (0.45f - 0.02f * posterFraction)
        val lyricsWidthDp = stageWidthDp * (0.50f + 0.02f * posterFraction)
        val renderLyricsLines = shouldRenderWelcomeLyricsLines(
            showLyricsDemo = showLyricsDemo,
            gaplessActive = gaplessActive,
            activeTrackIndex = safeTrackIdx,
        )
        val lyricsAlpha by animateFloatAsState(
            targetValue = welcomeRightPanelAlpha(showLoudnessBars = showLoudnessBars, gaplessActive = gaplessActive),
            animationSpec = tween(if (lowPerformance) 150 else 280),
            label = "stage-right-alpha",
        )
        Box(
            modifier = Modifier
                .offset(x = lyricsStartDp)
                .width(lyricsWidthDp)
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = lyricsAlpha
                    if (!lowPerformance) compositingStrategy = CompositingStrategy.Offscreen
                }
                .then(
                    if (renderLyricsLines && !lowPerformance) {
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.16f to Color.Black,
                                    0.76f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    } else Modifier,
                ),
        ) {
            Crossfade(
                targetState = renderLyricsLines to safeTrackIdx,
                animationSpec = tween(if (lowPerformance) 140 else 260),
                label = "demo-right-content",
            ) { (showLines, trackIdxNow) ->
                val shownTrack = tracks[trackIdxNow.coerceIn(0, tracks.lastIndex)]
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (showLines) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (showLines) {
                        WelcomeStageLyricsPreview(
                            lines = lyricLines,
                            posterMode = posterMode,
                            lyricTextSize = lyricTextSize,
                            stageWidthDp = stageWidthDp,
                            stageHeightDp = stageHeightDp,
                            lowPerformance = lowPerformance,
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(end = stageWidthDp * 0.05f),
                            verticalArrangement = Arrangement.spacedBy(stageHeightDp * 0.03f),
                        ) {
                            Text(
                                text = shownTrack.title,
                                color = Color.White,
                                fontFamily = LocalFolioFontFamily.current,
                                fontWeight = FontWeight.Bold,
                                fontSize = (stageWidthDp.value * if (posterMode) 0.056f else 0.044f).sp,
                                lineHeight = (stageWidthDp.value * if (posterMode) 0.062f else 0.050f).sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = shownTrack.artist,
                                color = Color.White.copy(alpha = 0.65f),
                                fontFamily = LocalFolioFontFamily.current,
                                fontWeight = FontWeight.Medium,
                                fontSize = (stageWidthDp.value * if (posterMode) 0.032f else 0.026f).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

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

@Composable
private fun WelcomeStageLyricsPreview(
    lines: List<LyricLine>,
    posterMode: Boolean,
    lyricTextSize: LyricTextSize,
    stageWidthDp: Dp,
    stageHeightDp: Dp,
    lowPerformance: Boolean,
) {
    if (lines.isEmpty()) return
    val totalMs = (lines.last().endMs + 1_200L).coerceAtLeast(3_000L)
    val positionState = remember(lines) { mutableLongStateOf(0L) }

    LaunchedEffect(lines) {
        var elapsed = 0L
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    elapsed = (elapsed + (frameNanos - lastNanos) / 1_000_000L) % totalMs
                }
                lastNanos = frameNanos
                positionState.longValue = elapsed
            }
        }
    }

    val posMs = positionState.longValue
    val activeIdx = remember(lines, posMs) {
        val found = lines.indexOfLast { posMs >= it.startMs }
        found.coerceIn(0, lines.lastIndex)
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val topInsetDp = stageHeightDp * 0.24f
    LaunchedEffect(activeIdx, lowPerformance) {
        if (lowPerformance) {
            listState.scrollToItem(activeIdx)
        } else {
            listState.animateScrollToItem(activeIdx)
        }
    }

    val userSizeFactor = when (lyricTextSize) {
        LyricTextSize.Standard -> 0.85f
        LyricTextSize.Large -> 1.0f
        LyricTextSize.ExtraLarge -> 1.18f
    }
    val stageScale = (stageWidthDp.value / 760f) * userSizeFactor * (if (posterMode) 1.38f else 1f)
    val stageDensity = Density(density.density, density.fontScale * stageScale)

    CompositionLocalProvider(LocalDensity provides stageDensity) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInsetDp, bottom = stageHeightDp * 0.35f),
        ) {
            itemsIndexed(lines, key = { idx, _ -> idx }) { idx, line ->
                KaraokeLyricLine(
                    line = line,
                    isActive = idx == activeIdx,
                    isStaticMode = false,
                    positionState = positionState,
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

/**
 * Dark atmospheric dual-fog + faint ridge aura behind the welcome stage, matching W1-WELCOME-V4-H5 `.aura`.
 * Each fog/ridge caches its elliptical gradient in [drawWithCache]; motion/breath/parallax mutate only [graphicsLayer].
 */
@Composable
internal fun WelcomeAuraLayer(
    target: WelcomeAuraTarget,
    dragOffsetPx: Float,
    animated: Boolean,
    lowPerformance: Boolean,
    gatherScale: Float,
    ridgeAlphaOverride: Float,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = isWelcomeAuraMotionEnabled(animated, lowPerformance)
    val layerAlpha by animateFloatAsState(
        targetValue = target.alpha,
        animationSpec = tween(if (motionEnabled) 1_100 else FolioGatherMotion.LOW_PERF_FADE_MS),
        label = "aura-layer-alpha",
    )
    val colorSpec = tween<Color>(if (motionEnabled) 1_400 else FolioGatherMotion.LOW_PERF_FADE_MS)
    val colorA by animateColorAsState(target.primaryColor, colorSpec, label = "aura-c1")
    val colorB by animateColorAsState(target.secondaryColor, colorSpec, label = "aura-c2")

    val driftAProgress: Float
    val driftBProgress: Float
    val ridgeBreath: Float
    if (motionEnabled) {
        val infinite = rememberInfiniteTransition(label = "welcome-aura-drift")
        val aProg by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(15_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "aura-fa",
        )
        val bProg by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(18_750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "aura-fb",
        )
        val rBreath by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(3_750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "aura-ridge",
        )
        driftAProgress = aProg
        driftBProgress = bProg
        ridgeBreath = rBreath
    } else {
        driftAProgress = 0f
        driftBProgress = 0f
        ridgeBreath = 1f
    }

    val effectiveRidgeAlpha = if (motionEnabled) {
        (ridgeAlphaOverride * ridgeBreath).coerceIn(0f, 1f)
    } else {
        ridgeAlphaOverride
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = layerAlpha
                translationX = dragOffsetPx * 0.25f
            },
        contentAlignment = Alignment.Center,
    ) {
        // Extend 10% beyond every edge (`inset: -10%`) so drifting and drag parallax never clip
        val auraW = maxWidth * 1.2f
        val auraH = maxHeight * 1.2f

        Box(
            modifier = Modifier
                .requiredSize(auraW, auraH)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.62f, 0.42f)
                    scaleX = gatherScale
                    scaleY = gatherScale
                },
        ) {
            val faW = auraW * 0.78f
            val faH = auraH * 0.58f
            WelcomeAuraEllipticalGlow(
                color = colorA,
                modifier = Modifier
                    .offset(x = auraW * 0.38f, y = auraH * 0.02f)
                    .size(faW, faH)
                    .graphicsLayer {
                        rotationZ = -16f
                        translationX = -size.width * 0.07f * driftAProgress
                        translationY = size.height * 0.05f * driftAProgress
                    },
            )

            val fbW = auraW * 0.86f
            val fbH = auraH * 0.62f
            WelcomeAuraEllipticalGlow(
                color = colorB,
                modifier = Modifier
                    .offset(x = -auraW * 0.06f, y = auraH * 0.44f)
                    .size(fbW, fbH)
                    .graphicsLayer {
                        rotationZ = -16f
                        translationX = size.width * 0.06f * driftBProgress
                        translationY = -size.height * 0.04f * driftBProgress
                    },
            )

            val ridgeW = auraW * 1.20f
            val ridgeH = auraH * 0.15f
            WelcomeAuraEllipticalGlow(
                color = WELCOME_RIDGE_COLOR,
                modifier = Modifier
                    .offset(x = -auraW * 0.10f, y = auraH * 0.41f)
                    .size(ridgeW, ridgeH)
                    .graphicsLayer {
                        rotationZ = -13f
                        alpha = effectiveRidgeAlpha
                    },
            )
        }
    }
}

@Composable
private fun WelcomeAuraEllipticalGlow(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val center = Offset(w * 0.5f, h * 0.5f)
            val brush = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = center,
                radius = w * 0.5f,
            )
            val scaleY = h / w
            onDrawBehind {
                scale(scaleX = 1f, scaleY = scaleY, pivot = center) {
                    drawCircle(
                        brush = brush,
                        radius = w * 0.5f,
                        center = center,
                    )
                }
            }
        },
    )
}
