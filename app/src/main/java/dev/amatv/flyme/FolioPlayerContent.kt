package dev.amatv.flyme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.LyricsState
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.Track
import dev.amatv.playback.AutoplayStatus
import dev.amatv.ui.CarLyricRows
import dev.amatv.ui.DarkFlymePalette
import dev.amatv.ui.InstrumentalInterludeRow
import dev.amatv.ui.KaraokeLyricLine
import dev.amatv.ui.LightFlymePalette
import dev.amatv.ui.LocalFlymePalette

@Composable
internal fun FolioScrollingLyricsPanel(
    lines: List<LyricLine>,
    positionState: State<Long>,
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
    CompositionLocalProvider(
        LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            CarLyricRows(
                lines = lines,
                position = positionState,
                offsetMs = offsetMs,
                soften = !lowPerformance,
                ripple = !lowPerformance,
                controlsVisible = controlsVisible,
                canSeek = canSeek,
                fontScale = (fontSizeSp / 34f).coerceIn(0.62f, 1.35f),
                anchorFraction = anchorFraction,
                onReveal = onReveal,
                onLineSeek = onLineSeek,
            )
        }
    }
}

@Composable
internal fun FolioPosterLyricsPanel(
    lines: List<LyricLine>,
    positionState: State<Long>,
    offsetMs: Long,
    translationEnabled: Boolean,
    baseSp: Float,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val snapshot by remember(schedule, offsetMs, positionState) {
        derivedStateOf { schedule.snapshotAt((positionState.value + offsetMs).coerceAtLeast(0L)) }
    }
    val lyricPosition = remember(positionState, offsetMs) {
        derivedStateOf { (positionState.value + offsetMs).coerceAtLeast(0L) }
    }
    val active = snapshot.focusLineIndex
    val waiting = snapshot.instrumentalScrollAnchor
    val projection by remember(lines, snapshot, lyricPosition) {
        derivedStateOf {
            projectPosterLyrics(
                lines = lines,
                positionMs = lyricPosition.value,
                activeLineIndices = snapshot.activeLineIndices,
                isInstrumentalGap = waiting != null,
                instrumentalBeforeLineIndex = waiting?.beforeLineIndex ?: 0,
            )
        }
    }
    val density = LocalDensity.current
    val fullPresence = remember { mutableFloatStateOf(1f) }
    val bgPresence = remember { mutableFloatStateOf(1f) }

    CompositionLocalProvider(
        LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette,
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val posterSizeSp = remember(lines, maxWidth, baseSp) {
                computeSongPosterFontSize(
                    lines = lines,
                    availableWidthDp = maxWidth.value,
                    baseSp = baseSp,
                )
            }
            val subSizeSp = (posterSizeSp * 0.48f).coerceIn(17f, 24f)
            val subLineHeightSp = subSizeSp * 1.32f
            val transSizeSp = (posterSizeSp * 0.42f).coerceIn(15f, 21f)
            val backingSizeSp = (baseSp * 0.5f).coerceIn(17f, 28f)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                if (projection.previousLeadText.isNotEmpty()) {
                    Text(
                        text = projection.previousLeadText,
                        color = fg.copy(alpha = 0.56f),
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = subSizeSp.sp,
                        lineHeight = subLineHeightSp.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AnimatedContent(
                    targetState = waiting ?: lines[projection.focusLeadIndex],
                    label = "folio-poster-focus",
                    transitionSpec = {
                        (fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 4 }) togetherWith
                            fadeOut(tween(180))
                    },
                ) { shown ->
                    if (shown is LyricTimeline.InstrumentalGap) {
                        Box(
                            Modifier.graphicsLayer {
                                scaleX = 1.65f
                                scaleY = 1.65f
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                        ) {
                            InstrumentalInterludeRow(
                                gap = shown,
                                positionState = lyricPosition,
                                isUserScrolling = false,
                            )
                        }
                        return@AnimatedContent
                    }
                    val line = shown as LyricLine
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(
                                density = density.density,
                                fontScale = density.fontScale * (posterSizeSp / 34f).coerceIn(0.68f, 1.6f),
                            ),
                        ) {
                            KaraokeLyricLine(
                                line = line,
                                isActive = projection.hasStartedLead,
                                retainedPresence = fullPresence,
                                retainedBackgroundPresence = bgPresence,
                                isFocused = true,
                                isStaticMode = false,
                                isUserScrolling = false,
                                positionState = lyricPosition,
                                restingUnlitAlpha = if (active < 0) 0.42f else 0.36f,
                            )
                        }
                        if (projection.activeBackingText.isNotEmpty()) {
                            Text(
                                text = projection.activeBackingText,
                                color = fg2,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = backingSizeSp.sp,
                                lineHeight = (backingSizeSp * 1.3f).sp,
                            )
                        }
                        if (translationEnabled) {
                            line.translation?.takeIf { it.isNotBlank() }?.let { trans ->
                                Text(
                                    text = trans,
                                    color = fg2,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = transSizeSp.sp,
                                    lineHeight = (transSizeSp * 1.3f).sp,
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val alphas = listOf(0.62f, 0.42f, 0.26f)
                    projection.upcomingLeadTexts.forEachIndexed { idx, nextText ->
                        val alpha = alphas.getOrElse(idx) { 0.26f }
                        Text(
                            text = nextText,
                            color = fg.copy(alpha = alpha),
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = subSizeSp.sp,
                            lineHeight = subLineHeightSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
                        .shadow(20.dp, RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenAlbum),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.widthIn(max = coverSide + 32.dp),
                ) {
                    Text(
                        text = track.title,
                        color = fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 21.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(onClick = onOpenAlbum),
                    )
                    if (track.artist.isNotBlank()) {
                        Text(
                            text = track.artist,
                            color = fg2,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable(onClick = onOpenArtist),
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
            .clickable(role = Role.Button, onClick = onClick)
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
    modifier: Modifier = Modifier,
) {
    val fontFamily = LocalFolioFontFamily.current
    val currentRowIndex = remember(snapshot.rows) {
        snapshot.rows.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentRowIndex, snapshot.rows.size) {
        if (snapshot.rows.isNotEmpty() && currentRowIndex in snapshot.rows.indices) {
            listState.scrollToItem(currentRowIndex)
        }
    }

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
        LazyRow(
            state = listState,
            modifier = modifier.fillMaxWidth(),
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
                        .clickable(enabled = clickable) {
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
                            PlayerQueueEqualizer(color = accent, animate = isPlaying)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = sectionTagLabel(row.section),
                            color = if (row.isCurrent) accent else fg2,
                            fontFamily = fontFamily,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        Text(
                            text = row.track.title,
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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 140.dp),
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
                            .clickable(enabled = clickable) {
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
                            Text(
                                text = row.track.title,
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
                            PlayerQueueEqualizer(color = accent, animate = isPlaying)
                        }
                    }
                }
            }
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
