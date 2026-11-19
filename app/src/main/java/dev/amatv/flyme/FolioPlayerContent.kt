package dev.amatv.flyme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.LyricsState
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.Track
import dev.amatv.playback.AutoplayStatus
import dev.amatv.ui.CarLyricRows
import dev.amatv.ui.DarkFlymePalette
import dev.amatv.ui.InstrumentalInterludeRow
import dev.amatv.ui.LightFlymePalette
import dev.amatv.ui.LocalFlymePalette
import kotlinx.coroutines.launch

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
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }
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

private data class GhostPosterFocus(
    val target: Any,
    val isOppositeSinger: Boolean,
    val hasStarted: Boolean,
    val frozenPosMs: Long,
)

private data class CharTimingEntry(
    val char: Char,
    val startMs: Long,
)

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
    duetAccentColor: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val fontFamily = LocalFolioFontFamily.current
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val snapshot by remember(schedule, offsetMs, positionState) {
        derivedStateOf { schedule.snapshotAt((positionState.value + offsetMs).coerceAtLeast(0L)) }
    }
    val lyricPosition = remember(positionState, offsetMs) {
        derivedStateOf { (positionState.value + offsetMs).coerceAtLeast(0L) }
    }
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

    val currentTarget: Any = waiting ?: lines[projection.focusLeadIndex]
    val mainIn = remember { Animatable(1f) }
    val subIn = remember { Animatable(1f) }
    val ghostOut = remember { Animatable(1f) }
    var shownTarget by remember(lines) { mutableStateOf(currentTarget) }
    var shownOpposite by remember(lines) { mutableStateOf(projection.isFocusOppositeSinger) }
    var shownStarted by remember(lines) { mutableStateOf(projection.hasStartedLead) }
    var ghost by remember(lines) { mutableStateOf<GhostPosterFocus?>(null) }

    LaunchedEffect(currentTarget, reducedMotion) {
        if (currentTarget == shownTarget) return@LaunchedEffect
        val outgoing = shownTarget
        val outgoingOpposite = shownOpposite
        val outgoingStarted = shownStarted
        val frozenPos = lyricPosition.value
        shownTarget = currentTarget
        shownOpposite = projection.isFocusOppositeSinger
        shownStarted = projection.hasStartedLead

        if (reducedMotion) {
            ghost = null
            mainIn.snapTo(1f)
            subIn.snapTo(1f)
            return@LaunchedEffect
        }

        ghost = GhostPosterFocus(
            target = outgoing,
            isOppositeSinger = outgoingOpposite,
            hasStarted = outgoingStarted,
            frozenPosMs = frozenPos,
        )
        launch {
            ghostOut.snapTo(0f)
            ghostOut.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = FolioPlayerMotionTokens.POSTER_LIQ_OUT_MS,
                    easing = FolioPlayerMotionTokens.ExitEasing,
                ),
            )
            ghost = null
        }
        launch {
            mainIn.snapTo(0f)
            mainIn.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = FolioPlayerMotionTokens.POSTER_LIQ_IN_MAIN_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                ),
            )
        }
        launch {
            subIn.snapTo(0f)
            subIn.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = FolioPlayerMotionTokens.POSTER_LIQ_IN_SUB_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                ),
            )
        }
    }

    CompositionLocalProvider(
        LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette,
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val typo = remember(lines, maxWidth, baseSp) {
                posterTypographyFor(
                    lines = lines,
                    availableWidthDp = maxWidth.value,
                    baseSp = baseSp,
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                projection.previousLine?.let { prev ->
                    Text(
                        text = prev.text,
                        color = if (prev.isOppositeSinger) duetAccentColor.copy(alpha = 0.72f) else fg2,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = typo.prevSp.sp,
                        lineHeight = typo.prevLineHeightSp.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.posterLiquidIn(progress = subIn.value),
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    ghost?.let { g ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .posterLiquidOut(progress = ghostOut.value),
                        ) {
                            when (val target = g.target) {
                                is LyricTimeline.InstrumentalGap -> {
                                    Box(
                                        Modifier.graphicsLayer {
                                            scaleX = 1.65f
                                            scaleY = 1.65f
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                        },
                                    ) {
                                        val frozenState = remember(g.frozenPosMs) {
                                            mutableLongStateOf(g.frozenPosMs)
                                        }
                                        InstrumentalInterludeRow(
                                            gap = target,
                                            positionState = frozenState,
                                            isUserScrolling = false,
                                        )
                                    }
                                }
                                is LyricLine -> {
                                    val frozenState = remember(g.frozenPosMs) {
                                        mutableLongStateOf(g.frozenPosMs)
                                    }
                                    PosterLeadLineText(
                                        line = target,
                                        positionState = frozenState,
                                        hasStartedLead = g.hasStarted,
                                        fontSizeSp = typo.mainSp,
                                        lineHeightSp = typo.mainLineHeightSp,
                                        fontFamily = fontFamily,
                                        baseColor = if (g.isOppositeSinger) duetAccentColor else fg,
                                        reducedMotion = reducedMotion,
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .posterLiquidIn(progress = mainIn.value),
                    ) {
                        when (val activeTarget = currentTarget) {
                            is LyricTimeline.InstrumentalGap -> {
                                Box(
                                    Modifier.graphicsLayer {
                                        scaleX = 1.65f
                                        scaleY = 1.65f
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    },
                                ) {
                                    InstrumentalInterludeRow(
                                        gap = activeTarget,
                                        positionState = lyricPosition,
                                        isUserScrolling = false,
                                    )
                                }
                            }
                            is LyricLine -> {
                                val mainColor = if (projection.isFocusOppositeSinger) duetAccentColor else fg
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PosterLeadLineText(
                                        line = activeTarget,
                                        positionState = lyricPosition,
                                        hasStartedLead = projection.hasStartedLead,
                                        fontSizeSp = typo.mainSp,
                                        lineHeightSp = typo.mainLineHeightSp,
                                        fontFamily = fontFamily,
                                        baseColor = mainColor,
                                        reducedMotion = reducedMotion,
                                    )
                                    for (small in projection.smallLines) {
                                        Text(
                                            text = small.text,
                                            color = if (small.isOppositeSinger) {
                                                duetAccentColor.copy(alpha = 0.78f)
                                            } else {
                                                fg2
                                            },
                                            fontFamily = fontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = typo.backingSp.sp,
                                            lineHeight = typo.backingLineHeightSp.sp,
                                        )
                                    }
                                    if (translationEnabled) {
                                        activeTarget.translation?.takeIf { it.isNotBlank() }?.let { trans ->
                                            Text(
                                                text = trans,
                                                color = if (projection.isFocusOppositeSinger) {
                                                    duetAccentColor.copy(alpha = 0.72f)
                                                } else {
                                                    fg2
                                                },
                                                fontFamily = fontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = typo.translationSp.sp,
                                                lineHeight = typo.translationLineHeightSp.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (projection.upcomingLines.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.posterLiquidIn(progress = subIn.value),
                    ) {
                        val alphas = listOf(0.62f, 0.42f, 0.26f)
                        projection.upcomingLines.forEachIndexed { idx, next ->
                            val alpha = alphas.getOrElse(idx) { 0.26f }
                            val lineColor = if (next.isOppositeSinger) {
                                duetAccentColor.copy(alpha = alpha.coerceAtLeast(0.38f))
                            } else {
                                fg.copy(alpha = alpha)
                            }
                            Text(
                                text = next.text,
                                color = lineColor,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = typo.nextSp.sp,
                                lineHeight = typo.nextLineHeightSp.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.posterLiquidIn(progress: Float): Modifier {
    if (progress >= 0.999f) return this
    val remaining = (1f - progress).coerceIn(0f, 1f)
    val blurDp = 16f * remaining
    val motion = graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        translationY = 14.dp.toPx() * remaining
        val scale = 0.98f + 0.02f * progress.coerceIn(0f, 1f)
        scaleX = scale
        scaleY = scale
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.25f) {
        motion.blur(blurDp.dp)
    } else {
        motion
    }
}

private fun Modifier.posterLiquidOut(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    val blurDp = 14f * p
    val motion = graphicsLayer {
        alpha = (1f - p).coerceIn(0f, 1f)
        translationY = -16.dp.toPx() * p
        val scale = 1f + 0.03f * p
        scaleX = scale
        scaleY = scale
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.25f) {
        motion.blur(blurDp.dp)
    } else {
        motion
    }
}

@Composable
private fun PosterLeadLineText(
    line: LyricLine,
    positionState: State<Long>,
    hasStartedLead: Boolean,
    fontSizeSp: Float,
    lineHeightSp: Float,
    fontFamily: FontFamily,
    baseColor: Color,
    reducedMotion: Boolean,
) {
    val charEntries = remember(line) {
        if (line.timing != LyricTiming.Word || line.words.size <= 1) {
            emptyList()
        } else {
            buildList {
                line.words.forEachIndexed { index, word ->
                    val wordText = if (word.displayText != word.text) {
                        word.displayText
                    } else if (line.joinWithSpaces && word.hasTrailingWhitespace && index < line.words.lastIndex) {
                        "${word.text} "
                    } else {
                        word.text
                    }
                    val charCount = wordText.length.coerceAtLeast(1)
                    val spanMs = (word.endMs - word.startMs).coerceAtLeast(0L)
                    for (charIdx in wordText.indices) {
                        val t = word.startMs + (spanMs * charIdx) / charCount
                        add(CharTimingEntry(wordText[charIdx], t))
                    }
                }
            }
        }
    }

    val posMs = positionState.value
    val annotated = buildAnnotatedString {
        if (!hasStartedLead || charEntries.isEmpty()) {
            val alpha = if (!hasStartedLead) 0.40f else 1f
            withStyle(SpanStyle(color = baseColor.copy(alpha = alpha))) {
                append(posterLeadText(line))
            }
        } else {
            for (entry in charEntries) {
                if (entry.char == ' ') {
                    append(' ')
                    continue
                }
                val charAlpha = posterCharAlpha(
                    posMs = posMs,
                    charStartMs = entry.startMs,
                    litAlpha = 1f,
                    unlitAlpha = 0.40f,
                    reducedMotion = reducedMotion,
                )
                withStyle(SpanStyle(color = baseColor.copy(alpha = charAlpha))) {
                    append(entry.char)
                }
            }
        }
    }
    Text(
        text = annotated,
        fontFamily = fontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
    )
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
