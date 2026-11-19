package dev.amatv.flyme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
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

private sealed interface PosterRollKey {
    val sortOrder: Int

    data class Gap(
        val beforeLineIndex: Int,
        val startMs: Long,
    ) : PosterRollKey {
        override val sortOrder: Int get() = beforeLineIndex * 2
    }

    data class Lead(
        val lineIndex: Int,
    ) : PosterRollKey {
        override val sortOrder: Int get() = lineIndex * 2 + 1
    }
}

private data class PosterStageKey(
    val prevKey: PosterRollKey?,
    val focusKey: PosterRollKey,
    val upcomingKeys: List<PosterRollKey>,
) {
    fun visibleKeys(): List<PosterRollKey> = buildList {
        prevKey?.let(::add)
        add(focusKey)
        addAll(upcomingKeys)
    }

    fun roleFor(key: PosterRollKey): PosterLyricRole = when {
        key == prevKey -> PosterLyricRole.Previous
        key == focusKey -> PosterLyricRole.Focus
        upcomingKeys.getOrNull(0) == key -> PosterLyricRole.Upcoming0
        upcomingKeys.getOrNull(1) == key -> PosterLyricRole.Upcoming1
        upcomingKeys.getOrNull(2) == key -> PosterLyricRole.Upcoming2
        key.sortOrder < focusKey.sortOrder -> PosterLyricRole.ExitingPrevious
        else -> PosterLyricRole.EnteringBelow
    }
}

private data class PosterRollStage(
    val key: PosterStageKey,
    val gapTarget: LyricTimeline.InstrumentalGap?,
    val focusTopY: Float,
    val prevY: Float,
    val upcomingYs: List<Float>,
    val exitAboveY: Float,
    val enterBelowY: Float,
) {
    fun targetY(itemKey: PosterRollKey): Float = when (key.roleFor(itemKey)) {
        PosterLyricRole.ExitingPrevious -> exitAboveY
        PosterLyricRole.Previous -> prevY
        PosterLyricRole.Focus -> focusTopY
        PosterLyricRole.Upcoming0 -> upcomingYs.getOrElse(0) { enterBelowY }
        PosterLyricRole.Upcoming1 -> upcomingYs.getOrElse(1) { enterBelowY }
        PosterLyricRole.Upcoming2 -> upcomingYs.getOrElse(2) { enterBelowY }
        PosterLyricRole.EnteringBelow -> enterBelowY
    }

    override fun equals(other: Any?): Boolean = other is PosterRollStage && other.key == key
    override fun hashCode(): Int = key.hashCode()
}

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
    val lyricPosition = remember(positionState, offsetMs) {
        derivedStateOf { (positionState.value + offsetMs).coerceAtLeast(0L) }
    }
    val snapshot by remember(schedule, lyricPosition) {
        derivedStateOf { schedule.snapshotAt(lyricPosition.value) }
    }
    val leadIndices = remember(lines) {
        lines.indices.filter { idx -> !isPosterBackingOnlyLine(lines[idx]) && posterLeadText(lines[idx]).isNotEmpty() }
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

    CompositionLocalProvider(
        LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.08f to Color.Black,
                            0.90f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.coerceAtLeast(1)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val typo = remember(lines, maxWidth, baseSp) {
                posterTypographyFor(
                    lines = lines,
                    availableWidthDp = maxWidth.value,
                    baseSp = baseSp,
                )
            }
            val textMeasurer = rememberTextMeasurer()
            val leadTextStyle = remember(fontFamily, typo) {
                TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = typo.mainSp.sp,
                    lineHeight = typo.mainLineHeightSp.sp,
                )
            }
            val transTextStyle = remember(fontFamily, typo) {
                TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = typo.translationSp.sp,
                    lineHeight = typo.translationLineHeightSp.sp,
                )
            }
            val backingTextStyle = remember(fontFamily, typo) {
                TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = typo.backingSp.sp,
                    lineHeight = typo.backingLineHeightSp.sp,
                )
            }
            val leadHeightCache = remember(lines, widthPx, leadTextStyle) { HashMap<Int, Float>() }
            val transHeightCache = remember(lines, widthPx, transTextStyle) { HashMap<Int, Float>() }
            val gapHeightPx = with(density) { 56.dp.toPx() }
            val transGapPx = with(density) { 4.dp.toPx() }
            val smallLineGapPx = with(density) { 6.dp.toPx() }
            val prevGapPx = with(density) { 20.dp.toPx() }
            val nextBlockGapPx = with(density) { 28.dp.toPx() }
            val upcomingGapPx = with(density) { 14.dp.toPx() }
            val exitSlidePx = with(density) { 28.dp.toPx() }
            val enterSlidePx = with(density) { 24.dp.toPx() }
            val focusTopY = heightPx * 0.40f
            val prevScale = posterRoleScale(PosterLyricRole.Previous, typo)
            val nextScale = posterRoleScale(PosterLyricRole.Upcoming0, typo)

            fun measureLeadHeightPx(lineIndex: Int): Float = leadHeightCache.getOrPut(lineIndex) {
                textMeasurer.measure(
                    text = posterLeadText(lines[lineIndex]),
                    style = leadTextStyle,
                    constraints = Constraints(maxWidth = widthPx),
                ).size.height.toFloat()
            }

            fun measureItemHeightPx(itemKey: PosterRollKey): Float = when (itemKey) {
                is PosterRollKey.Gap -> gapHeightPx
                is PosterRollKey.Lead -> measureLeadHeightPx(itemKey.lineIndex)
            }

            fun measureFocusTransHeightPx(itemKey: PosterRollKey): Float {
                if (!translationEnabled || itemKey !is PosterRollKey.Lead) return 0f
                val trans = lines[itemKey.lineIndex].translation?.trim().orEmpty()
                if (trans.isEmpty()) return 0f
                return transHeightCache.getOrPut(itemKey.lineIndex) {
                    transGapPx + textMeasurer.measure(
                        text = trans,
                        style = transTextStyle,
                        constraints = Constraints(maxWidth = widthPx),
                    ).size.height.toFloat()
                }
            }

            // Compute discrete role assignment from live projection synchronously (no coroutine delay).
            val stageKey = run {
                val activeLeadPos = leadIndices.indexOf(projection.focusLeadIndex)
                val focusKey: PosterRollKey = if (waiting != null) {
                    PosterRollKey.Gap(waiting.beforeLineIndex, waiting.startMs)
                } else {
                    PosterRollKey.Lead(projection.focusLeadIndex)
                }
                val prevKey: PosterRollKey? = if (
                    waiting == null &&
                    projection.hasStartedLead &&
                    activeLeadPos > 0
                ) {
                    PosterRollKey.Lead(leadIndices[activeLeadPos - 1])
                } else {
                    null
                }
                val activeLeads = leadIndices
                    .filter { it in snapshot.activeLineIndices }
                    .sortedWith(compareBy<Int>({ lines[it].startMs }, { it }))
                val maxConsumedActiveLead = activeLeads.maxOrNull()
                val upcomingIndices = when {
                    waiting != null -> leadIndices.filter { it >= waiting.beforeLineIndex }.take(3)
                    maxConsumedActiveLead != null && activeLeads.size > 1 ->
                        leadIndices.filter { it > maxConsumedActiveLead }.take(3)
                    projection.hasStartedLead && activeLeadPos >= 0 ->
                        leadIndices.drop(activeLeadPos + 1).take(3)
                    else -> leadIndices.drop(1).take(3)
                }
                PosterStageKey(
                    prevKey = prevKey,
                    focusKey = focusKey,
                    upcomingKeys = upcomingIndices.map { PosterRollKey.Lead(it) },
                )
            }

            val currentStage = remember(stageKey, widthPx, heightPx, typo, translationEnabled) {
                val focusMainH = measureItemHeightPx(stageKey.focusKey)
                val focusTransH = measureFocusTransHeightPx(stageKey.focusKey)
                val prevH = stageKey.prevKey?.let { measureItemHeightPx(it) * prevScale } ?: 0f
                val prevY = focusTopY - prevGapPx - prevH
                val exitAboveY = (if (stageKey.prevKey != null) prevY else focusTopY - prevGapPx) - exitSlidePx
                val upcoming0Y = focusTopY + focusMainH + focusTransH + nextBlockGapPx
                var cursorY = upcoming0Y
                val upcomingYs = stageKey.upcomingKeys.map { uKey ->
                    val y = cursorY
                    cursorY += measureItemHeightPx(uKey) * nextScale + upcomingGapPx
                    y
                }
                val enterBelowY = (cursorY - upcomingGapPx).coerceAtLeast(upcoming0Y) + enterSlidePx
                PosterRollStage(
                    key = stageKey,
                    gapTarget = waiting,
                    focusTopY = focusTopY,
                    prevY = prevY,
                    upcomingYs = upcomingYs,
                    exitAboveY = exitAboveY,
                    enterBelowY = enterBelowY,
                )
            }

            // Smooth offset when active backing/overlap smallLines chime in mid-line so Upcoming never snaps.
            val targetSmallLinesExtraPx = remember(projection.smallLines, widthPx, backingTextStyle) {
                if (projection.smallLines.isEmpty()) {
                    0f
                } else {
                    projection.smallLines.sumOf { small ->
                        (smallLineGapPx + textMeasurer.measure(
                            text = small.text,
                            style = backingTextStyle,
                            constraints = Constraints(maxWidth = widthPx),
                        ).size.height.toFloat()).toDouble()
                    }.toFloat()
                }
            }
            val smoothSmallLinesExtraPx by animateFloatAsState(
                targetValue = targetSmallLinesExtraPx,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else FolioPlayerMotionTokens.CTL_OPACITY_MS,
                    easing = FolioPlayerMotionTokens.EnterEasing,
                ),
                label = "poster-small-extra",
            )

            key(lines) {
                val transition = updateTransition(targetState = currentStage, label = "poster-roll")
                val itemsToRender = remember(
                    transition.currentState.key,
                    transition.targetState.key,
                    reducedMotion,
                ) {
                    if (reducedMotion || transition.currentState == transition.targetState) {
                        transition.targetState.key.visibleKeys()
                    } else {
                        (transition.currentState.key.visibleKeys() + transition.targetState.key.visibleKeys()).distinct()
                    }
                }
                val animDuration = if (reducedMotion) 0 else POSTER_ROLL_DURATION_MS
                val rollSpec = tween<Float>(durationMillis = animDuration, easing = FolioPlayerMotionTokens.EnterEasing)
                val extrasFadeOutSpec = tween<Float>(
                    durationMillis = if (reducedMotion) 0 else (POSTER_ROLL_DURATION_MS / 2),
                    easing = FolioPlayerMotionTokens.ExitEasing,
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    for (itemKey in itemsToRender) {
                        key(itemKey) {
                            val isOpposite = when (itemKey) {
                                is PosterRollKey.Gap -> false
                                is PosterRollKey.Lead -> isOppositeSingerLine(lines[itemKey.lineIndex])
                            }
                            val targetRole = transition.targetState.key.roleFor(itemKey)

                            val animatedY by transition.animateFloat(
                                transitionSpec = { rollSpec },
                                label = "poster-y",
                            ) { st -> st.targetY(itemKey) }

                            val animatedScale by transition.animateFloat(
                                transitionSpec = { rollSpec },
                                label = "poster-scale",
                            ) { st -> posterRoleScale(st.key.roleFor(itemKey), typo) }

                            val animatedAlpha by transition.animateFloat(
                                transitionSpec = { rollSpec },
                                label = "poster-alpha",
                            ) { st -> posterRoleAlpha(st.key.roleFor(itemKey), fg2.alpha) }

                            val focusWeight by transition.animateFloat(
                                transitionSpec = {
                                    if (targetRole == PosterLyricRole.Focus) rollSpec else extrasFadeOutSpec
                                },
                                label = "poster-focus-weight",
                            ) { st -> if (st.key.roleFor(itemKey) == PosterLyricRole.Focus) 1f else 0f }

                            val exitBlurDp by transition.animateFloat(
                                transitionSpec = { rollSpec },
                                label = "poster-exit-blur",
                            ) { st ->
                                if (st.key.roleFor(itemKey) == PosterLyricRole.ExitingPrevious) 12f else 0f
                            }

                            val isUpcomingRank = targetRole == PosterLyricRole.Upcoming0 ||
                                targetRole == PosterLyricRole.Upcoming1 ||
                                targetRole == PosterLyricRole.Upcoming2 ||
                                targetRole == PosterLyricRole.EnteringBelow
                            val smallPushPx = if (isUpcomingRank) smoothSmallLinesExtraPx else 0f

                            // Only the exiting previous line uses blur on API 31+; API 30 car units rely cleanly on slide/scale/alpha.
                            val blurMod = if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                !reducedMotion &&
                                targetRole == PosterLyricRole.ExitingPrevious &&
                                exitBlurDp > 0.25f
                            ) {
                                Modifier.blur(exitBlurDp.dp)
                            } else {
                                Modifier
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        translationY = animatedY + smallPushPx
                                    },
                            ) {
                                when (itemKey) {
                                    is PosterRollKey.Gap -> {
                                        val gap = transition.targetState.gapTarget
                                            ?: transition.currentState.gapTarget
                                            ?: waiting
                                        if (gap != null) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .graphicsLayer {
                                                        alpha = animatedAlpha
                                                        scaleX = 1.65f * animatedScale
                                                        scaleY = 1.65f * animatedScale
                                                        translationX = (-28).dp.toPx()
                                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                                    }
                                                    .then(blurMod),
                                            ) {
                                                InstrumentalInterludeRow(
                                                    gap = gap,
                                                    positionState = lyricPosition,
                                                    isUserScrolling = false,
                                                )
                                            }
                                        }
                                    }

                                    is PosterRollKey.Lead -> {
                                        val line = lines[itemKey.lineIndex]
                                        val mainColor = if (isOpposite) duetAccentColor else fg
                                        val mainScaledH = measureLeadHeightPx(itemKey.lineIndex) * animatedScale
                                        val transText = line.translation?.trim().orEmpty()
                                        val showTrans = translationEnabled && transText.isNotEmpty() && focusWeight > 0.01f
                                        val showSmallLines = targetRole == PosterLyricRole.Focus &&
                                            projection.smallLines.isNotEmpty() &&
                                            focusWeight > 0.01f

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer {
                                                    alpha = animatedAlpha
                                                    scaleX = animatedScale
                                                    scaleY = animatedScale
                                                    transformOrigin = TransformOrigin(0f, 0f)
                                                }
                                                .then(blurMod),
                                        ) {
                                            PosterLeadLineText(
                                                line = line,
                                                positionState = lyricPosition,
                                                hasStartedLead = if (itemKey == stageKey.focusKey) {
                                                    projection.hasStartedLead
                                                } else {
                                                    true
                                                },
                                                focusWeight = focusWeight,
                                                textStyle = leadTextStyle,
                                                baseColor = mainColor,
                                                reducedMotion = reducedMotion,
                                            )
                                        }

                                        if (showTrans || showSmallLines) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .graphicsLayer {
                                                        translationY = mainScaledH + transGapPx
                                                        alpha = focusWeight
                                                    },
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                if (showTrans) {
                                                    Text(
                                                        text = transText,
                                                        color = if (isOpposite) {
                                                            duetAccentColor.copy(alpha = 0.60f)
                                                        } else {
                                                            fg2
                                                        },
                                                        style = transTextStyle,
                                                    )
                                                }
                                                if (showSmallLines) {
                                                    for (small in projection.smallLines) {
                                                        Text(
                                                            text = small.text,
                                                            color = if (small.isOppositeSinger) {
                                                                duetAccentColor.copy(alpha = 0.78f)
                                                            } else {
                                                                fg2
                                                            },
                                                            style = backingTextStyle,
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
                }
            }
        }
    }
}

@Composable
private fun PosterLeadLineText(
    line: LyricLine,
    positionState: State<Long>,
    hasStartedLead: Boolean,
    focusWeight: Float,
    textStyle: TextStyle,
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

    val fw = focusWeight.coerceIn(0f, 1f)
    val posMs = positionState.value
    val annotated = buildAnnotatedString {
        if (fw <= 0.001f) {
            withStyle(SpanStyle(color = baseColor)) {
                append(posterLeadText(line))
            }
        } else if (!hasStartedLead || charEntries.isEmpty()) {
            val rawAlpha = if (!hasStartedLead) 0.40f else 1f
            val alpha = rawAlpha + (1f - rawAlpha) * (1f - fw)
            withStyle(SpanStyle(color = baseColor.copy(alpha = alpha))) {
                append(posterLeadText(line))
            }
        } else {
            for (entry in charEntries) {
                if (entry.char == ' ') {
                    append(' ')
                    continue
                }
                val rawCharAlpha = posterCharAlpha(
                    posMs = posMs,
                    charStartMs = entry.startMs,
                    litAlpha = 1f,
                    unlitAlpha = 0.40f,
                    reducedMotion = reducedMotion,
                )
                val blendedAlpha = rawCharAlpha + (1f - rawCharAlpha) * (1f - fw)
                withStyle(SpanStyle(color = baseColor.copy(alpha = blendedAlpha))) {
                    append(entry.char)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = textStyle,
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
