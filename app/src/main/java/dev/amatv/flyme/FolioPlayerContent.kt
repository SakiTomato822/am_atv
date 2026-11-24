package dev.amatv.flyme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.LyricsState
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.playback.AutoplayStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun FolioScrollingLyricsPanel(
    lines: List<LyricLine>,
    positionState: FolioPlaybackPosition,
    offsetMs: Long,
    lowPerformance: Boolean,
    controlsVisible: Boolean,
    canSeek: Boolean,
    fontSizeSp: Float,
    anchorFraction: Float,
    isLight: Boolean,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }
    CompositionLocalProvider(
        LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            CarLyricRows(
                sourceLines = lines,
                position = positionState,
                offsetMs = offsetMs,
                lowPerformance = lowPerformance,
                controlsVisible = controlsVisible,
                canSeek = canSeek,
                fontScale = (fontSizeSp / 34f).coerceIn(0.62f, 1.80f),
                anchorFraction = anchorFraction,
                onReveal = onReveal,
                onLineSeek = onLineSeek,
            )
        }
    }
}

@Composable
internal fun FolioNoLyricsStateBox(
    track: Track,
    tier: FolioTier,
    lyricsState: LyricsState,
    fg: Color,
    fg2: Color,
    pillBg: Color,
    pillBorder: Color,
    pullHandle: Modifier,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onRetryLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val statusText = when {
        lyricsState.isLoading -> stringResource(R.string.player_loading)
        lyricsState.isTransientFailure -> stringResource(R.string.player_lyrics_load_failed)
        else -> stringResource(R.string.folio_player_no_lyrics)
    }

    if (tier == FolioTier.Narrow) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .then(pullHandle),
            contentAlignment = Alignment.Center,
        ) {
            val coverSide = min(maxWidth * 0.72f, 280.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FolioArtwork(
                    track = track,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .size(coverSide)
                        .folioTrackSwitchCover()
                        .shadow(20.dp, RoundedCornerShape(16.dp)),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy((-4).dp),
                    modifier = Modifier.widthIn(max = coverSide + 32.dp),
                ) {
                    FolioMarqueeTrackTitle(
                        track = track,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 21.sp,
                        textAlign = TextAlign.Center,
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
                            resetKey = track.id,
                        )
                    }
                }
                Text(
                    text = statusText,
                    color = fg2,
                    fontFamily = fontFamily,
                    fontSize = 14.sp,
                )
                if (lyricsState.isTransientFailure) {
                    RetryLyricsPill(
                        fg = fg,
                        pillBg = pillBg,
                        pillBorder = pillBorder,
                        onClick = onRetryLyrics,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = statusText,
                    color = fg2,
                    fontFamily = fontFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
                if (lyricsState.isTransientFailure) {
                    RetryLyricsPill(
                        fg = fg,
                        pillBg = pillBg,
                        pillBorder = pillBorder,
                        onClick = onRetryLyrics,
                    )
                }
            }
        }
    }
}

@Composable
private fun RetryLyricsPill(
    fg: Color,
    pillBg: Color,
    pillBorder: Color,
    onClick: () -> Unit,
) {
    val fontFamily = LocalFolioFontFamily.current
    val shape = RoundedCornerShape(FolioRadii.Chip)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(pillBg)
            .border(1.dp, pillBorder, shape)
            .folioPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.folio_player_retry_lyrics),
            color = fg,
            fontFamily = fontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun FolioQueuePanel(
    snapshot: FolioQueueSnapshot,
    tierSpec: FolioPlayerTierSpec,
    fg: Color,
    fg2: Color,
    accent: Color,
    isLight: Boolean,
    isPlaying: Boolean,
    onSelectTimelineTrack: (Int) -> Unit,
    onPlayStationTrack: (Track) -> Unit,
    onCycleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    lowPerformance: Boolean = false,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val currentMarker = LocalFolioControlAccent.current?.value?.compositeOver(accent) ?: accent
    val fontFamily = LocalFolioFontFamily.current
    val targetScrollIndex = remember(snapshot, tierSpec.isHorizontalQueue) {
        computeQueueInitialScrollIndex(
            snapshot = snapshot,
            isHorizontalQueue = tierSpec.isHorizontalQueue,
        )
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetScrollIndex)
    var lastScrolledTarget by remember {
        mutableStateOf(
            if (snapshot.rows.isNotEmpty()) targetScrollIndex to snapshot.rows.size else null,
        )
    }
    LaunchedEffect(targetScrollIndex, snapshot.rows.size) {
        val nextTarget = targetScrollIndex to snapshot.rows.size
        if (snapshot.rows.isNotEmpty() && lastScrolledTarget != nextTarget) {
            if (lastScrolledTarget == null || reducedMotion || lowPerformance) {
                listState.scrollToItem(targetScrollIndex)
            } else {
                listState.animateScrollToItem(targetScrollIndex)
            }
            lastScrolledTarget = nextTarget
        } else if (snapshot.rows.isEmpty()) {
            lastScrolledTarget = null
        }
    }
    var previousHorizontalViewportWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(tierSpec.isHorizontalQueue, listState, targetScrollIndex, snapshot.rows.size) {
        if (!tierSpec.isHorizontalQueue) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.width }
            .collect { viewportWidth ->
                if (viewportWidth > 0 && previousHorizontalViewportWidth > 0 && viewportWidth != previousHorizontalViewportWidth) {
                    val info = listState.layoutInfo
                    val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == targetScrollIndex }
                    val inView = visibleItem != null &&
                        visibleItem.offset >= info.viewportStartOffset &&
                        visibleItem.offset + visibleItem.size <= info.viewportEndOffset
                    if (!inView && targetScrollIndex in snapshot.rows.indices) {
                        if (reducedMotion || lowPerformance) {
                            listState.scrollToItem(targetScrollIndex)
                        } else {
                            listState.animateScrollToItem(targetScrollIndex)
                        }
                    }
                }
                if (viewportWidth > 0) previousHorizontalViewportWidth = viewportWidth
            }
    }

    val availability = resolveQueueControlAvailability(
        isStation = snapshot.isStation,
        upNextCount = snapshot.upNextCount,
        autoplayCount = snapshot.autoplayCount,
    )
    val rowBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.06f) else Color.White.copy(alpha = 0.12f)
    val rowBorder = if (isLight) Color(0xFF17151C).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)

    if (snapshot.rows.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.folio_player_queue_empty),
                color = fg2,
                fontFamily = fontFamily,
                fontSize = 15.sp,
            )
        }
        return
    }

    if (tierSpec.isHorizontalQueue) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .folioPlayerGlass(isLight = isLight, shape = RoundedCornerShape(22.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FolioQueueControlBar(
                repeatMode = snapshot.repeatMode,
                shuffleEnabled = snapshot.shuffleEnabled,
                availability = availability,
                isLight = isLight,
                fg = fg,
                accent = accent,
                reducedMotion = reducedMotion,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onClearQueue = onClearQueue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(snapshot.rows, key = { it.key }) { row ->
                    val clickable = !row.isCurrent && (row.timelineIndex != null || snapshot.isStation)
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        modifier = Modifier
                            .width(138.dp)
                            .clip(shape)
                            .then(
                                if (row.isCurrent) {
                                    Modifier
                                        .background(rowBg)
                                        .border(1.dp, rowBorder, shape)
                                } else {
                                    Modifier.background(Color.Transparent)
                                },
                            )
                            .folioPressable(enabled = clickable, role = Role.Button) {
                                val idx = row.timelineIndex
                                if (idx != null) {
                                    onSelectTimelineTrack(idx)
                                } else if (snapshot.isStation) {
                                    onPlayStationTrack(row.track)
                                }
                            }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FolioArtwork(
                                track = row.track,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(tierSpec.qc),
                            )
                            if (row.isCurrent) {
                                PlayerQueueEqualizer(color = currentMarker, animate = isPlaying && !lowPerformance)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = sectionTagLabel(row.section),
                                color = if (row.isCurrent) currentMarker else fg2,
                                fontFamily = fontFamily,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                            FolioTrackTitle(
                                track = row.track,
                                color = fg,
                                fontFamily = fontFamily,
                                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = tierSpec.qt,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = row.track.artist,
                                color = fg2,
                                fontFamily = fontFamily,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val groupedSections = remember(snapshot) {
        buildList {
            for (kind in FolioQueueSectionKind.entries) {
                val sectionRows = snapshot.rows.filter { it.section == kind }
                if (sectionRows.isNotEmpty() || (kind == FolioQueueSectionKind.Autoplay && snapshot.autoplayEnabled)) {
                    add(kind to sectionRows)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .folioPlayerGlass(isLight = isLight, shape = RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FolioQueueControlBar(
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = snapshot.shuffleEnabled,
            availability = availability,
            isLight = isLight,
            fg = fg,
            accent = accent,
            reducedMotion = reducedMotion,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
            onClearQueue = onClearQueue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        groupedSections.forEach { (kind, rows) ->
            item(key = "header-${kind.name}") {
                val label = sectionHeaderLabel(kind, snapshot)
                Text(
                    text = label,
                    color = if (kind == FolioQueueSectionKind.NowPlaying) accent else fg2,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (rows.isEmpty() && kind == FolioQueueSectionKind.Autoplay && snapshot.autoplayStatus == AutoplayStatus.Loading) {
                item(key = "autoplay-loading") {
                    Text(
                        text = stringResource(R.string.folio_player_queue_loading),
                        color = fg2,
                        fontFamily = fontFamily,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            } else {
                items(rows, key = { it.key }) { row ->
                    val clickable = !row.isCurrent && (row.timelineIndex != null || snapshot.isStation)
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (row.isCurrent) {
                                    Modifier
                                        .background(rowBg)
                                        .border(1.dp, rowBorder, shape)
                                } else {
                                    Modifier
                                },
                            )
                            .folioPressable(enabled = clickable, role = Role.Button) {
                                val idx = row.timelineIndex
                                if (idx != null) {
                                    onSelectTimelineTrack(idx)
                                } else if (snapshot.isStation) {
                                    onPlayStationTrack(row.track)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FolioArtwork(
                            track = row.track,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(tierSpec.qc),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            FolioTrackTitle(
                                track = row.track,
                                color = fg.copy(alpha = if (row.section == FolioQueueSectionKind.Played) 0.72f else 1f),
                                fontFamily = fontFamily,
                                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = tierSpec.qt,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.track.artist.isNotBlank()) {
                                Text(
                                    text = row.track.artist,
                                    color = fg2,
                                    fontFamily = fontFamily,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (row.isCurrent) {
                            PlayerQueueEqualizer(color = currentMarker, animate = isPlaying && !lowPerformance)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun FolioQueueControlBar(
    repeatMode: PlaybackRepeatMode,
    shuffleEnabled: Boolean,
    availability: FolioQueueControlAvailability,
    isLight: Boolean,
    fg: Color,
    accent: Color,
    reducedMotion: Boolean,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    var pendingClearConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(pendingClearConfirm) {
        if (pendingClearConfirm) {
            delay(3_000L)
            pendingClearConfirm = false
        }
    }
    LaunchedEffect(availability.canClearQueue) {
        if (!availability.canClearQueue) {
            pendingClearConfirm = false
        }
    }

    val inactiveBg = if (isLight) Color(0xFF17151C).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f)
    val activeBg = fg
    val activeFg = if (isLight) Color.White else Color(0xFF17151C)
    val repeatActive = repeatMode != PlaybackRepeatMode.Off
    val controlAccent = LocalFolioControlAccent.current?.value ?: Color.Transparent
    val repeatAccent = if (repeatActive && availability.canSetRepeat) controlAccent else Color.Transparent
    val shuffleAccent = if (shuffleEnabled && availability.canSetShuffle) controlAccent else Color.Transparent
    val toggleFadeMs = if (reducedMotion) 0 else FolioPlayerMotionTokens.TOGGLE_PILL_MS
    val repeatBg by animateColorAsState(
        targetValue = if (repeatActive && availability.canSetRepeat) activeBg else inactiveBg,
        animationSpec = tween(toggleFadeMs),
        label = "queue-repeat-bg",
    )
    val repeatTint by animateColorAsState(
        targetValue = when {
            !availability.canSetRepeat -> fg.copy(alpha = 0.34f)
            repeatActive -> activeFg
            else -> fg
        },
        animationSpec = tween(toggleFadeMs),
        label = "queue-repeat-tint",
    )
    val shuffleBg by animateColorAsState(
        targetValue = if (shuffleEnabled && availability.canSetShuffle) activeBg else inactiveBg,
        animationSpec = tween(toggleFadeMs),
        label = "queue-shuffle-bg",
    )
    val shuffleTint by animateColorAsState(
        targetValue = when {
            !availability.canSetShuffle -> fg.copy(alpha = 0.34f)
            shuffleEnabled -> activeFg
            else -> fg
        },
        animationSpec = tween(toggleFadeMs),
        label = "queue-shuffle-tint",
    )
    val repeatLabel = stringResource(
        when (repeatMode) {
            PlaybackRepeatMode.Off -> R.string.player_repeat_off
            PlaybackRepeatMode.One -> R.string.player_repeat_one
            PlaybackRepeatMode.All -> R.string.player_repeat_all
        },
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(repeatBg.copy(alpha = repeatBg.alpha * (1f - repeatAccent.alpha)))
                    .folioPressable(
                        enabled = availability.canSetRepeat,
                        compact = true,
                        role = Role.Button,
                        onClick = onCycleRepeat,
                    )
                    .semantics { selected = repeatActive },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (repeatMode == PlaybackRepeatMode.One) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = repeatLabel,
                    tint = repeatAccent.compositeOver(repeatTint),
                    modifier = Modifier.size(20.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(shuffleBg.copy(alpha = shuffleBg.alpha * (1f - shuffleAccent.alpha)))
                    .folioPressable(
                        enabled = availability.canSetShuffle,
                        compact = true,
                        role = Role.Button,
                        onClick = onToggleShuffle,
                    )
                    .semantics { selected = shuffleEnabled },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.player_shuffle),
                    tint = shuffleAccent.compositeOver(shuffleTint),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        val clearBg = when {
            pendingClearConfirm && availability.canClearQueue -> accent.copy(alpha = 0.24f)
            else -> inactiveBg
        }
        val clearFg = when {
            !availability.canClearQueue -> fg.copy(alpha = 0.34f)
            pendingClearConfirm -> fg
            else -> fg
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(clearBg)
                .then(
                    if (pendingClearConfirm && availability.canClearQueue) {
                        Modifier.border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                    },
                )
                .folioPressable(enabled = availability.canClearQueue, role = Role.Button) {
                    if (!pendingClearConfirm) {
                        pendingClearConfirm = true
                    } else {
                        pendingClearConfirm = false
                        onClearQueue()
                    }
                }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (pendingClearConfirm) {
                        R.string.folio_player_queue_clear_confirm
                    } else {
                        R.string.folio_player_queue_clear
                    },
                ),
                color = clearFg,
                fontFamily = fontFamily,
                fontWeight = if (pendingClearConfirm) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun sectionTagLabel(kind: FolioQueueSectionKind): String = when (kind) {
    FolioQueueSectionKind.Played -> stringResource(R.string.folio_player_queue_now).let {
        stringResource(R.string.folio_player_queue_played, 1).substringBefore("·").trim()
    }
    FolioQueueSectionKind.NowPlaying -> stringResource(R.string.folio_player_queue_now)
    FolioQueueSectionKind.UpNext -> stringResource(R.string.queue_up_next)
    FolioQueueSectionKind.Autoplay -> stringResource(R.string.folio_player_queue_similar)
}

@Composable
private fun sectionHeaderLabel(
    kind: FolioQueueSectionKind,
    snapshot: FolioQueueSnapshot,
): String = when (kind) {
    FolioQueueSectionKind.Played -> stringResource(R.string.folio_player_queue_played, snapshot.playedCount)
    FolioQueueSectionKind.NowPlaying -> stringResource(R.string.folio_player_queue_now)
    FolioQueueSectionKind.UpNext -> stringResource(R.string.folio_player_queue_up_next, snapshot.upNextCount)
    FolioQueueSectionKind.Autoplay -> stringResource(R.string.folio_player_queue_autoplay)
}

@Composable
private fun PlayerQueueEqualizer(
    color: Color,
    animate: Boolean,
) {
    val heights = if (animate) {
        val transition = rememberInfiniteTransition(label = "queue-eq")
        listOf(
            transition.animateFloat(0.28f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "q1").value,
            transition.animateFloat(0.95f, 0.32f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "q2").value,
            transition.animateFloat(0.45f, 0.88f, infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "q3").value,
        )
    } else {
        listOf(0.42f, 0.85f, 0.56f)
    }
    Row(
        modifier = Modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
    ) {
        heights.forEach { frac ->
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(frac)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
