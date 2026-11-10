package dev.amatv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.FlymePlaybackSettings.LyricStyle
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.Track
import kotlinx.coroutines.delay
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun FlymeCarLyrics(
    track: Track,
    style: LyricStyle,
    position: State<Long>,
    offsetMs: Long,
    playWhenReady: Boolean,
    visible: Boolean,
    lowPerformance: Boolean,
    canSeek: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    translation: Boolean,
    fontScale: Float,
    remoteActivity: Int,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onTranslate: () -> Unit,
    onSeek: (Long) -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var seenRemoteActivity by remember { mutableIntStateOf(remoteActivity) }
    fun reveal() {
        open = true
        interaction++
    }
    LaunchedEffect(remoteActivity) {
        if (seenRemoteActivity != remoteActivity) {
            seenRemoteActivity = remoteActivity
            reveal()
        }
    }
    LaunchedEffect(open, interaction, playWhenReady) {
        if (open && playWhenReady) {
            delay(5_000)
            open = false
        }
    }
    val controlsVisible = open || !playWhenReady
    val soak = style == LyricStyle.Soak
    FitDesign(Modifier.fillMaxSize().automationId("car-lyrics-${style.name.lowercase()}")) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { reveal() } }) {
            val lyricsLeft = maxWidth * if (soak) 0.484f else 0.422f
            val artLeft = maxWidth * 0.078f
            val palette = rememberArtworkPalette(track.artworkUrl, track.palette)
            if (soak) {
                // The background is the cover's own dominant colour, darkened, so the fade has nothing to seam against.
                val base = lerp(palette.first(), Color.Black, 0.55f)
                Box(Modifier.fillMaxSize().background(base))
                MotionArtwork(
                    track,
                    Modifier.fillMaxWidth(0.62f).fillMaxHeight().graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }.drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(
                            0f to Color.Black, 0.40f to Color.Black, 0.62f to Color.Black.copy(alpha = 0.72f),
                            0.80f to Color.Black.copy(alpha = 0.32f), 0.92f to Color.Black.copy(alpha = 0.08f),
                            1f to Color.Transparent,
                        ), blendMode = BlendMode.DstIn)
                    },
                    animate = false,
                    cornerRadius = 0.dp,
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.18f), 0.3f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.25f),
                )))
            } else {
                CarLyricFlow(track, palette, visible && !lowPerformance)
                MotionArtwork(
                    track, Modifier.padding(start = artLeft, top = 64.dp).size(340.dp)
                        .shadow(24.dp, RoundedCornerShape(14.dp)),
                    animate = false, cornerRadius = 14.dp,
                )
            }
            AnimatedVisibility(soak || !controlsVisible, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                Column(Modifier.padding(start = if (soak) lyricsLeft else artLeft, top = if (soak) 40.dp else 426.dp)
                    .padding(end = 58.dp)) {
                    Text(track.title, color = Color.White, fontSize = if (soak) 20.sp else 22.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (soak) Modifier else Modifier.width(340.dp))
                    Text(track.artist, color = Color.White.copy(alpha = 0.62f),
                        fontSize = if (soak) 16.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (soak) Modifier else Modifier.width(340.dp))
                }
            }
            Box(Modifier.fillMaxSize().padding(
                start = lyricsLeft, top = if (soak) 100.dp else 40.dp, end = 60.dp, bottom = 40.dp,
            )) {
                if (track.lyrics.isEmpty()) emptyContent()
                else CarLyricRows(track.lyrics, position, offsetMs, soften = !soak && !lowPerformance,
                    ripple = !soak && !lowPerformance, controlsVisible, canSeek, fontScale) { time ->
                    reveal()
                    onSeek((time - offsetMs).coerceAtLeast(0L))
                }
            }
            val rail = if (soak) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                else Modifier.padding(start = artLeft, top = 494.dp).width(340.dp)
            AnimatedVisibility(!controlsVisible && canSeek, rail, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRect(Color.White.copy(alpha = 0.16f))
                    val fraction = (position.value.toFloat() / track.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    drawRect(Color.White.copy(alpha = 0.85f), size = Size(size.width * fraction, size.height))
                }
            }
            AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopStart).padding(24.dp),
                enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                val circle = RoundedCornerShape(28.dp)
                FlymeGlyphButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browse_back), onBack,
                    if (soak) Modifier.clip(circle).background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.24f), circle) else Modifier,
                    size = 56.dp, iconSize = 26.dp, tint = Color.White)
            }
            val controls = if (soak) Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 36.dp).width(500.dp)
                else Modifier.padding(start = (artLeft - 20.dp).coerceAtLeast(0.dp), top = 414.dp).width(380.dp)
            AnimatedVisibility(controlsVisible, controls, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                val panel = RoundedCornerShape(30.dp)
                Box(Modifier.automationId("car-lyric-controls").clip(panel).pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        reveal()
                    }
                }) {
                    if (soak) {
                        if (!lowPerformance) MotionArtwork(track, Modifier.matchParentSize().blur(28.dp)
                            .graphicsLayer { alpha = 0.35f }, animate = false, cornerRadius = 30.dp)
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.08f)),
                        )).border(1.dp, Color.White.copy(alpha = 0.28f), panel))
                    }
                    Column(Modifier.padding(horizontal = if (soak) 26.dp else 8.dp,
                        vertical = if (soak) 20.dp else 6.dp)) {
                        FlymeSeekBar(position, track.durationMs, canSeek, onSeek)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            FlymeGlyphButton(Icons.Rounded.Translate, stringResource(R.string.player_lyrics_translation),
                                onTranslate, Modifier.automationId("car-lyric-translate"), size = 56.dp, iconSize = 24.dp,
                                selected = translation, tint = Color.White)
                            FlymeGlyphButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous_track),
                                onPrevious, size = 72.dp, iconSize = 38.dp, enabled = canSkipPrevious, tint = Color.White)
                            FlymeGlyphButton(if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                stringResource(if (playWhenReady) R.string.player_pause else R.string.player_play),
                                onPlayPause, Modifier.automationId("car-lyric-play").then(
                                    if (soak) Modifier.background(Color.White, RoundedCornerShape(44.dp)) else Modifier,
                                ), size = 88.dp, iconSize = 52.dp, tint = if (soak) Color(0xFF141218) else Color.White)
                            FlymeGlyphButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next_track),
                                onNext, size = 72.dp, iconSize = 38.dp, enabled = canSkipNext, tint = Color.White)
                            FlymeGlyphButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue),
                                onQueue, size = 56.dp, iconSize = 25.dp, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarLyricFlow(track: Track, palette: List<Color>, animate: Boolean) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(palette.first())) {
        if (track.artworkUrl != null) MotionArtwork(track,
            Modifier.fillMaxSize().blur(80.dp, BlurredEdgeTreatment.Unbounded).graphicsLayer { alpha = 0.48f },
            animate = false, cornerRadius = 0.dp)
        val angles = if (animate) {
            val transition = rememberInfiniteTransition(label = "car-lyric-flow")
            listOf(
                transition.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000, easing = LinearEasing), RepeatMode.Restart)),
                transition.animateFloat(1f, 0f, infiniteRepeatable(tween(46_000, easing = LinearEasing), RepeatMode.Restart)),
                transition.animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart)),
            )
        } else emptyList()
        Canvas(Modifier.fillMaxSize()) {
            for (index in 0..2) {
                val theta = ((if (animate) angles[index].value else 0f) + index / 3f) * 2 * PI
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
private fun CarLyricRows(
    lines: List<LyricLine>,
    position: State<Long>,
    offsetMs: Long,
    soften: Boolean,
    ripple: Boolean,
    controlsVisible: Boolean,
    canSeek: Boolean,
    fontScale: Float,
    onLineSeek: (Long) -> Unit,
) {
    val timed = remember(lines) { lines.any { !it.isStatic } }
    val fade = Modifier.fillMaxSize().clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black,
                0.82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    if (!timed) {
        LazyColumn(fade, contentPadding = PaddingValues(top = 35.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(LyricGap)) {
            itemsIndexed(lines) { _, line -> CarLyricLine(line, position, offsetMs, false, fontScale) }
        }
        return
    }
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val active by remember(schedule, offsetMs, position) {
        derivedStateOf { schedule.snapshotAt(position.value + offsetMs).anchorLineIndex.coerceAtLeast(0) }
    }
    BoxWithConstraints(fade) {
        val density = LocalDensity.current
        val anchor = with(density) { maxHeight.toPx() } * 0.40f
        val gap = with(density) { LyricGap.toPx() }
        val heights = remember(lines) { mutableStateListOf(*Array(lines.size) { 0 }) }
        val measured by remember(heights) { derivedStateOf { heights.none { it == 0 } } }
        // Every line moves by the same amount, placing the active line's centre on the anchor.
        val target by remember(heights, active, anchor, gap) {
            derivedStateOf {
                var top = 0f
                for (i in 0 until active) top += heights[i] + gap
                anchor - (top + heights[active] / 2f)
            }
        }
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(LyricGap)) {
            lines.forEachIndexed { index, line ->
                val distance = abs(active - index).coerceAtMost(4)
                // Lines below the active one follow one after another, so the list ripples instead of sliding as a block.
                val stagger = if (ripple) (index - active + 1).coerceIn(0, 8) * 45 else 0
                val shift = remember { Animatable(0f) }
                var placed by remember { mutableStateOf(false) }
                LaunchedEffect(target, measured) {
                    if (!measured) return@LaunchedEffect
                    if (!placed) {
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
                val blur = if (soften && abs(active - index) <= 6) LineBlur[distance].dp else 0.dp
                Box(Modifier.fillMaxWidth().onSizeChanged { heights[index] = it.height }
                    .graphicsLayer { translationY = shift.value; alpha = opacity }
                    .automationId("car-lyric-line-$index")
                    .clickable(enabled = controlsVisible && canSeek && !line.isStatic) { onLineSeek(line.startMs) }
                    .blur(blur)) {
                    CarLyricLine(line, position, offsetMs, distance == 0, fontScale)
                }
            }
        }
    }
}

private val LyricGap = 22.dp
private val LineOpacity = listOf(1f, 0.38f, 0.28f, 0.20f, 0.14f)
private val LineBlur = listOf(0f, 0.8f, 1.6f, 2.4f, 3f)
private val RippleEasing = CubicBezierEasing(0.22f, 0.9f, 0.3f, 1f)

@Composable
private fun CarLyricLine(line: LyricLine, position: State<Long>, offsetMs: Long, highlight: Boolean, fontScale: Float) {
    Column(Modifier.fillMaxWidth()) {
        line.pronunciationText?.let { LyricSubtitle(it, fontScale) }
        CarLyricText(line, position, offsetMs, highlight, fontScale)
        if (line.text.isNotBlank() && line.backgroundText.isNotBlank()) LyricSubtitle(line.backgroundText, fontScale)
        line.translation?.let { LyricSubtitle(it, fontScale) }
        line.backgroundTranslation?.let { LyricSubtitle(it, fontScale) }
    }
}

@Composable
private fun LyricSubtitle(text: String, fontScale: Float) {
    Text(text, Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.7f),
        fontSize = (19 * fontScale).sp, lineHeight = (25 * fontScale).sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CarLyricText(line: LyricLine, position: State<Long>, offsetMs: Long, highlight: Boolean, fontScale: Float) {
    val ranges = remember(line) { carLyricHighlights(line) }
    val now = if (highlight) position.value + offsetMs else line.endMs
    val text = buildAnnotatedString {
        append(line.text.ifBlank { line.backgroundText })
        if (highlight) for (range in ranges) {
            val amount = ((now - range.startMs).toFloat() / (range.endMs - range.startMs).coerceAtLeast(1L))
                .coerceIn(0f, 1f)
            addStyle(SpanStyle(color = Color.White.copy(alpha = 0.38f + 0.62f * amount)), range.from, range.until)
        }
    }
    Text(text, color = Color.White, fontSize = (34 * fontScale).sp,
        lineHeight = (43 * fontScale).sp, fontWeight = FontWeight.ExtraBold)
}

internal data class CarLyricHighlight(val from: Int, val until: Int, val startMs: Long, val endMs: Long)

internal fun carLyricHighlights(line: LyricLine): List<CarLyricHighlight> {
    if (line.timing == LyricTiming.Word) {
        val separator = if (line.joinWithSpaces) 1 else 0
        var offset = 0
        return line.words.map { word ->
            CarLyricHighlight(offset, offset + word.text.length, word.startMs, word.endMs)
                .also { offset += word.text.length + separator }
        }
    }
    val breaks = BreakIterator.getCharacterInstance().apply { setText(line.text) }
    val boundaries = buildList {
        add(breaks.first())
        while (true) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) break
            add(next)
        }
    }
    val count = boundaries.lastIndex
    val duration = line.endMs - line.startMs
    return List(count) { index ->
        CarLyricHighlight(boundaries[index], boundaries[index + 1],
            line.startMs + duration * index / count, line.startMs + duration * (index + 1) / count)
    }
}
