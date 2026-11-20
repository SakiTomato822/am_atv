package dev.amatv.flyme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun CarLyricFlow(track: Track, palette: List<Color>) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(palette.first())) {
        if (track.artworkUrl != null) MotionArtwork(track,
            Modifier.fillMaxSize().blur(80.dp, BlurredEdgeTreatment.Unbounded).graphicsLayer { alpha = 0.48f },
            animate = false, cornerRadius = 0.dp)
        val transition = rememberInfiniteTransition(label = "car-lyric-flow")
        val angles = listOf(
            transition.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000, easing = LinearEasing), RepeatMode.Restart)),
            transition.animateFloat(1f, 0f, infiniteRepeatable(tween(46_000, easing = LinearEasing), RepeatMode.Restart)),
            transition.animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart)),
        )
        Canvas(Modifier.fillMaxSize()) {
            for (index in 0..2) {
                val theta = (angles[index].value + index / 3f) * 2 * PI
                val point = Offset(
                    size.width * (0.23f + index * 0.27f + cos(theta).toFloat() * 0.18f),
                    size.height * (0.5f + sin(theta).toFloat() * 0.32f),
                )
                drawRect(Brush.radialGradient(
                    listOf(palette[index % palette.size].copy(alpha = 0.88f), Color.Transparent),
                    center = point, radius = size.width * 0.58f,
                ))
            }
            drawRect(Color.Black.copy(alpha = 0.28f))
        }
    }
}

@Composable
internal fun CarLyricRows(
    lines: List<LyricLine>,
    position: State<Long>,
    offsetMs: Long,
    lowPerformance: Boolean,
    controlsVisible: Boolean,
    canSeek: Boolean,
    fontScale: Float,
    anchorFraction: Float,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
) {
    val timed = remember(lines) { lines.any { !it.isStatic } }
    val clipped = Modifier.fillMaxSize().clipToBounds()
    val panel = if (lowPerformance) clipped else clipped
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black,
                0.82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    if (!timed) {
        LazyColumn(panel, contentPadding = PaddingValues(top = 35.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(LyricGap)) {
            itemsIndexed(lines) { _, line -> FlymeKaraokeLine(line, false, position, fontScale, static = true) }
        }
        return
    }
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val focus by remember(lines, schedule, offsetMs, position) {
        derivedStateOf { scrollingLyricFocusAt(lines, schedule, position.value + offsetMs) }
    }
    // Before the first line and in breaks of 7 s or more the list opens a slot for the waiting dots: the
    // next line and those below it move down, and the slot takes the anchor until that line starts.
    val lyricPosition = remember(position, offsetMs) {
        derivedStateOf { (position.value + offsetMs).coerceAtLeast(0L) }
    }
    if (lowPerformance) {
        InstantScrollingLyrics(
            lines, focus, lyricPosition, fontScale, anchorFraction,
            controlsVisible, canSeek, onReveal, onLineSeek, panel,
        )
        return
    }

    val waiting = focus.waiting
    val shownGap = remember(lines) { mutableStateOf<LyricTimeline.InstrumentalGap?>(null) }
    LaunchedEffect(waiting) { if (waiting != null) shownGap.value = waiting }
    val open by animateFloatAsState(if (waiting != null) 1f else 0f, tween(600, easing = RippleEasing),
        label = "interlude-open")
    val anchorIndex = focus.anchorIndex
    val browse = remember { Animatable(0f) }
    val browseScope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    BoxWithConstraints(panel.pointerInput(Unit) {
        // A finger drag browses the lyrics; three seconds after letting go they glide back to the current line.
        detectVerticalDragGestures(
            onDragStart = { settle?.cancel() },
            onDragEnd = {
                settle = browseScope.launch {
                    delay(3_000)
                    browse.animateTo(0f, tween(600, easing = RippleEasing))
                }
            },
        ) { change, dy ->
            change.consume()
            browseScope.launch { browse.snapTo(browse.value + dy) }
        }
    }) {
        val density = LocalDensity.current
        val anchor = with(density) { maxHeight.toPx() } * anchorFraction
        val gap = with(density) { LyricGap.toPx() }
        val heights = remember(lines) { mutableStateListOf(*Array(lines.size) { 0 }) }
        val shifts = remember(lines) { List(lines.size) { Animatable(0f) } }
        var dotsHeight by remember { mutableIntStateOf(0) }
        val slot = dotsHeight + gap
        val topOf = { index: Int -> var top = 0f; for (i in 0 until index) top += heights[i] + gap; top }
        // Only the first placement waits for measurement. Some lines (background vocals alone) are 0 tall
        // until they are sung and collapse again after, so waiting on every line froze the list mid-song.
        val measured by remember(heights, anchorIndex, waiting) {
            derivedStateOf { if (waiting != null) dotsHeight > 0 else heights[anchorIndex] > 0 }
        }
        // Every line moves by the same amount, centring the anchor row or the dots.
        val target by remember(heights, anchorIndex, anchor, gap, waiting) {
            derivedStateOf {
                anchor - (topOf(anchorIndex) + (if (waiting != null) dotsHeight else heights[anchorIndex]) / 2f)
            }
        }
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(LyricGap)) {
            lines.forEachIndexed { index, line ->
                val isCurrent = focus.isCurrent(index)
                val distance = focus.styleDistance(index)
                // Lines below the anchor follow one after another, so the list ripples instead of sliding as a block.
                val stagger = (index - anchorIndex + 1).coerceIn(0, 8) * 45
                val shift = shifts[index]
                var placed by remember { mutableStateOf(false) }
                LaunchedEffect(target, measured) {
                    if (!placed) {
                        if (!measured) return@LaunchedEffect
                        shift.snapTo(target)
                        placed = true
                        return@LaunchedEffect
                    }
                    delay(stagger.toLong())
                    shift.animateTo(target, tween(750, easing = RippleEasing))
                }
                val opacity by animateFloatAsState(
                    targetValue = LineOpacity[distance],
                    animationSpec = tween(360, delayMillis = stagger), label = "lyric-opacity",
                )
                // Lines more than a screen away are not visible; skip their blur layers.
                val blur = if (abs(anchorIndex - index) <= 6) LineBlur[distance].dp else 0.dp
                Box(Modifier.fillMaxWidth().onSizeChanged { heights[index] = it.height }
                    .graphicsLayer {
                        val opened = shownGap.value?.takeIf { index >= it.beforeLineIndex }?.let { slot * open } ?: 0f
                        translationY = shift.value + browse.value + opened
                        alpha = opacity
                    }
                    .automationId("car-lyric-line-$index")
                    .clickable(interactionSource = null, indication = null) {
                        // A tap on a line while the controls are hidden only brings them back, like a tap anywhere else.
                        if (controlsVisible && canSeek && !line.isStatic) onLineSeek(line.startMs) else onReveal()
                    }
                    .blur(blur)) {
                    FlymeKaraokeLine(line, isCurrent, lyricPosition, fontScale, static = false)
                }
            }
        }
        // The dots sit in the slot just above the line they wait for, and fade with it as it closes.
        shownGap.value?.let { interlude ->
            val next = interlude.beforeLineIndex
            Box(Modifier.fillMaxWidth().onSizeChanged { dotsHeight = it.height }.graphicsLayer {
                translationY = topOf(next) + shifts[next].value + browse.value + slot * open - slot
                alpha = open
            }) {
                InstrumentalInterludeRow(
                    interlude, lyricPosition, isUserScrolling = false,
                    exitStartMs = InterludeMotion.exitStartForLineHandoff(interlude),
                )
            }
        }
    }
}

@Composable
private fun InstantScrollingLyrics(
    lines: List<LyricLine>,
    focus: ScrollingLyricFocus,
    position: State<Long>,
    fontScale: Float,
    anchorFraction: Float,
    controlsVisible: Boolean,
    canSeek: Boolean,
    onReveal: () -> Unit,
    onLineSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val finalLeadIndex = remember(lines) { lines.indexOfLast { !it.isStatic && it.words.any { word -> !word.isBackgroundVocal } } }
    BoxWithConstraints(modifier) {
        val anchor = maxHeight * anchorFraction
        val waiting = focus.waiting
        val anchorKey = if (waiting == null) focus.anchorIndex else InterludeItemKey
        val state = rememberLazyListState(initialFirstVisibleItemIndex = focus.anchorIndex)
        val dragged by state.interactionSource.collectIsDraggedAsState()
        var browsing by remember(lines) { mutableStateOf(false) }
        LaunchedEffect(lines, dragged) {
            if (dragged) {
                browsing = true
            } else if (browsing) {
                snapshotFlow { state.isScrollInProgress }.first { !it }
                delay(3_000)
                browsing = false
            }
        }
        // Padding puts the target item's top at the anchor. Its own measured height
        // moves the centre to the same anchor without measuring intervening lyrics.
        val anchorHeight by remember(state, anchorKey) {
            derivedStateOf { state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }?.size ?: 0 }
        }
        LaunchedEffect(lines, anchorKey, focus.anchorIndex, anchorHeight, browsing, dragged) {
            if (!browsing && !dragged) state.requestScrollToItem(focus.anchorIndex, anchorHeight / 2)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight - anchor),
            verticalArrangement = Arrangement.spacedBy(LyricGap),
        ) {
            fun lyricItems(start: Int, end: Int) {
                items(end - start, key = { start + it }, contentType = { "lyric" }) { slot ->
                    val index = start + slot
                    val line = lines[index]
                    val active = focus.isCurrent(index)
                    val startedFinalLead = if (index == finalLeadIndex) {
                        val started by remember(line, position) { derivedStateOf { position.value >= line.startMs } }
                        started
                    } else false
                    val highlight = focus.wholeLineHighlight(index, startedFinalLead)
                    val opacity = LineOpacity[if (highlight == KaraokeRowHighlight.Lit) 0 else focus.styleDistance(index)]
                    Box(
                        Modifier.fillMaxWidth()
                            .graphicsLayer {
                                alpha = opacity
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .automationId("car-lyric-line-$index")
                            .clickable(interactionSource = null, indication = null) {
                                if (controlsVisible && canSeek && !line.isStatic) onLineSeek(line.startMs) else onReveal()
                            },
                    ) {
                        FlymeKaraokeLine(line, active, position, fontScale, static = false, wholeLineHighlight = highlight)
                    }
                }
            }
            if (waiting == null) {
                lyricItems(0, lines.size)
            } else {
                lyricItems(0, waiting.beforeLineIndex)
                item(key = InterludeItemKey, contentType = "interlude") {
                    InstrumentalInterludeRow(
                        waiting, position, isUserScrolling = false,
                        exitStartMs = InterludeMotion.exitStartForLineHandoff(waiting),
                        animateHandoff = false,
                    )
                }
                lyricItems(waiting.beforeLineIndex, lines.size)
            }
        }
    }
}

private const val InterludeItemKey = -1

internal data class ScrollingLyricFocus(
    val anchorIndex: Int,
    val waiting: LyricTimeline.InstrumentalGap?,
    private val singing: List<Int>,
) {
    fun isCurrent(index: Int): Boolean = index in singing

    fun wholeLineHighlight(index: Int, isStartedFinalLead: Boolean): KaraokeRowHighlight =
        wholeLyricHighlight(isCurrent(index) || isStartedFinalLead)

    fun styleDistance(index: Int): Int {
        if (isCurrent(index)) return 0
        val distance = if (waiting != null && index >= anchorIndex) index - anchorIndex + 1
            else abs(index - anchorIndex)
        return distance.coerceIn(1, 4)
    }
}

internal fun scrollingLyricFocusAt(
    lines: List<LyricLine>,
    schedule: LyricTimeline.Schedule,
    positionMs: Long,
): ScrollingLyricFocus {
    val snapshot = schedule.snapshotAt(positionMs)
    val waiting = schedule.instrumentalGaps.firstOrNull {
        positionMs.coerceAtLeast(0L) >= it.startMs && positionMs < it.endMs
    }
    return ScrollingLyricFocus(
        anchorIndex = waiting?.beforeLineIndex ?: snapshot.focusLineIndex.coerceAtLeast(0),
        waiting = waiting,
        // The timeline's lookahead can position a row before its start. Only actual vocals can light it.
        singing = snapshot.activeLineIndices.filter { positionMs >= lines[it].startMs },
    )
}

private val LyricGap = 30.dp
// The Flyme.12 line renderer already dims unsung lines, so distance only fades them a little further.
private val LineOpacity = listOf(1f, 0.95f, 0.80f, 0.65f, 0.50f)
private val LineBlur = listOf(0f, 0.4f, 0.8f, 1.2f, 1.6f)
private val RippleEasing = CubicBezierEasing(0.22f, 0.9f, 0.3f, 1f)

/**
 * One lyric line drawn by the Flyme.12 karaoke renderer, so word-timed lines get the same smooth sweep,
 * per-character lift and glow. The new layouts only place, fade and blur the lines around it.
 */
@Composable
private fun FlymeKaraokeLine(
    line: LyricLine,
    active: Boolean,
    position: State<Long>,
    textScale: Float,
    static: Boolean,
    wholeLineHighlight: KaraokeRowHighlight? = null,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        KaraokeLyricLine(
            line = line,
            isActive = active,
            isStaticMode = static,
            positionState = position,
            wholeLineHighlight = wholeLineHighlight,
        )
    }
}

/**
 * Two faint glows in the cover's secondary colours over the flat background, so the side away from the
 * cover is not one solid block. Drawn over everything, so there is nothing for them to seam against.
 */
@Composable
internal fun SoakGlow(palette: List<Color>) {
    Canvas(Modifier.fillMaxSize()) {
        val warm = palette[1 % palette.size]
        val cool = palette[2 % palette.size]
        drawRect(Brush.radialGradient(listOf(warm.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(size.width * 0.86f, size.height * 0.80f), radius = size.width * 0.42f))
        drawRect(Brush.radialGradient(listOf(cool.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.74f, size.height * 0.08f), radius = size.width * 0.34f))
    }
}
