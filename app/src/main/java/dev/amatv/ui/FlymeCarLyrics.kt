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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.amatv.integration.LyricTextSize
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun FlymeCarLyrics(
    track: Track,
    style: LyricStyle,
    poster: Boolean,
    onPosterChange: (Boolean) -> Unit,
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
    if (LocalCarPortrait.current) {
        PortraitLyrics(
            track, soak, poster, position, offsetMs, playWhenReady, controlsVisible, visible, lowPerformance,
            canSeek, canSkipPrevious, canSkipNext, translation, fontScale, ::reveal, onBack, onPlayPause, onPrevious,
            onNext, onQueue, onTranslate, onPosterChange, onSeek, emptyContent,
        )
        return
    }
    FitDesign(Modifier.fillMaxSize().automationId("car-lyrics-${style.name.lowercase()}")
        .then(rememberPullDownToClose(onBack))) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { reveal() } }) {
            val lyricsLeft = maxWidth * if (soak) 0.484f else 0.422f
            val artLeft = maxWidth * 0.078f
            val palette = rememberArtworkPalette(track.artworkUrl, track.palette)
            if (soak) {
                // The background is the cover's own dominant colour, darkened. The cover fades by painting
                // that same colour over it (a DstIn mask did not reach the decoded cover bitmap), so the
                // fade always ends exactly on the background with no seam.
                val base = lerp(palette.first(), Color.Black, 0.55f)
                Box(Modifier.fillMaxSize().background(base))
                Box(Modifier.fillMaxWidth(0.62f).fillMaxHeight().automationId("car-lyrics-soak-cover")) {
                    MotionArtwork(track, Modifier.fillMaxSize(), animate = false, cornerRadius = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        0f to Color.Transparent, 0.40f to Color.Transparent, 0.62f to base.copy(alpha = 0.28f),
                        0.80f to base.copy(alpha = 0.68f), 0.92f to base.copy(alpha = 0.92f), 1f to base,
                    )))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.18f), 0.3f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.25f),
                )))
                SoakGlow(palette)
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
                    ripple = !soak && !lowPerformance, controlsVisible, canSeek, carTextScale(fontScale),
                    anchorFraction = 0.40f, onReveal = ::reveal) { time ->
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
            val controls = if (soak) Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 36.dp).width(380.dp)
                else Modifier.padding(start = artLeft, top = 420.dp).width(340.dp)
            AnimatedVisibility(controlsVisible, controls, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                LyricControlCard(
                    position, track.durationMs, playWhenReady, canSeek, canSkipPrevious, canSkipNext, translation,
                    onPlayPause, onPrevious, onNext, onQueue, onTranslate, onSeek,
                    modifier = Modifier.automationId("car-lyric-controls").pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            reveal()
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun CarLyricFlow(track: Track, palette: List<Color>, animate: Boolean) {
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
internal fun CarLyricRows(
    lines: List<LyricLine>,
    position: State<Long>,
    offsetMs: Long,
    soften: Boolean,
    ripple: Boolean,
    controlsVisible: Boolean,
    canSeek: Boolean,
    fontScale: Float,
    anchorFraction: Float,
    onReveal: () -> Unit,
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
            itemsIndexed(lines) { _, line -> FlymeKaraokeLine(line, false, position, fontScale, static = true) }
        }
        return
    }
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val active by remember(schedule, offsetMs, position) {
        derivedStateOf { schedule.snapshotAt(position.value + offsetMs).anchorLineIndex.coerceAtLeast(0) }
    }
    val lyricPosition = remember(position, offsetMs) {
        derivedStateOf { (position.value + offsetMs).coerceAtLeast(0L) }
    }
    val browse = remember { Animatable(0f) }
    val browseScope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    BoxWithConstraints(fade.pointerInput(Unit) {
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
        // Only the first placement waits for measurement. Some lines (background vocals alone) are 0 tall
        // until they are sung and collapse again after, so waiting on every line froze the list mid-song.
        val measured by remember(heights, active) { derivedStateOf { heights.getOrElse(active) { 0 } > 0 } }
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
                val blur = if (soften && abs(active - index) <= 6) LineBlur[distance].dp else 0.dp
                Box(Modifier.fillMaxWidth().onSizeChanged { heights[index] = it.height }
                    .graphicsLayer { translationY = shift.value + browse.value; alpha = opacity }
                    .automationId("car-lyric-line-$index")
                    .clickable(interactionSource = null, indication = null) {
                        // A tap on a line while the controls are hidden only brings them back, like a tap anywhere else.
                        if (controlsVisible && canSeek && !line.isStatic) onLineSeek(line.startMs) else onReveal()
                    }
                    .blur(blur)) {
                    FlymeKaraokeLine(line, distance == 0, lyricPosition, fontScale, static = false)
                }
            }
        }
    }
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
private fun FlymeKaraokeLine(line: LyricLine, active: Boolean, position: State<Long>, textScale: Float, static: Boolean) {
    val density = LocalDensity.current
    val noRetention = remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        KaraokeLyricLine(
            line = line,
            isActive = active,
            retainedPresence = noRetention,
            retainedBackgroundPresence = noRetention,
            isStaticMode = static,
            isUserScrolling = false,
            positionState = position,
            // Unsung text at Apple Music's secondary level; the Flyme.12 tertiary 18% was too faint to read
            // in a car, especially for the lines around the current one.
            restingUnlitAlpha = MotionTokens.KaraokeSecondaryAlpha,
        )
    }
}

/** The lyrics page's text size setting, as a multiplier where 1 is the Flyme.12 34sp line. */
internal fun carTextScale(fontScale: Float) = fontScale / LyricTextSize.Standard.scale

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

/**
 * The glass transport card from the H5 design: progress with times, then translate, previous and next
 * in soft circles, play in a white circle, and the queue. Shared by every lyrics layout.
 */
@Composable
internal fun LyricControlCard(
    position: State<Long>,
    durationMs: Long,
    playWhenReady: Boolean,
    canSeek: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    translation: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onTranslate: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = RoundedCornerShape(26.dp)
    val play = 72.dp
    val skip = 56.dp
    val side = 48.dp
    Column(modifier.clip(panel)
        .background(Color.Black.copy(alpha = 0.22f))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f))))
        .border(1.dp, Color.White.copy(alpha = 0.26f), panel)
        .padding(horizontal = 20.dp, vertical = 16.dp)) {
        FlymeSeekBar(position, durationMs, canSeek, onSeek)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            // On and off show as full and faint ink, so translate stays as plain as the queue glyph opposite it.
            Box(Modifier.size(side).clip(CircleShape)
                .clickable(onClickLabel = stringResource(R.string.player_lyrics_translation), onClick = onTranslate)
                .automationId("car-lyric-translate"), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.flyme_lyric_translate_short),
                    color = Color.White.copy(alpha = if (translation) 1f else 0.5f),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            FlymeGlyphButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous_track), onPrevious,
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                size = skip, iconSize = skip * 0.5f, enabled = canSkipPrevious, tint = Color.White)
            FlymeGlyphButton(if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playWhenReady) R.string.player_pause else R.string.player_play), onPlayPause,
                Modifier.automationId("car-lyric-play").clip(CircleShape).background(Color.White),
                size = play, iconSize = play * 0.5f, tint = Color(0xFF141218))
            FlymeGlyphButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next_track), onNext,
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                size = skip, iconSize = skip * 0.5f, enabled = canSkipNext, tint = Color.White)
            FlymeGlyphButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), onQueue,
                size = side, iconSize = side * 0.45f, tint = Color.White)
        }
    }
}

/**
 * Pull down to close Now Playing. The page follows the finger, shrinking and fading a little; past
 * 120dp it closes on release, otherwise it springs back. Upward drags meet resistance. The lyrics list
 * consumes its own drags, so this only acts on the cover, the title and empty space.
 */
@Composable
internal fun rememberPullDownToClose(onClose: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val close by rememberUpdatedState(onClose)
    return Modifier
        .pointerInput(Unit) {
            val distance = 120.dp.toPx()
            detectVerticalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (pull.value > distance) close()
                        pull.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                    }
                },
                onDragCancel = { scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) } },
            ) { change, dragAmount ->
                change.consume()
                scope.launch {
                    val next = pull.value + dragAmount
                    pull.snapTo(if (next < 0f) next * 0.25f else next)
                }
            }
        }
        .graphicsLayer {
            val fraction = (pull.value / size.height).coerceIn(0f, 1f)
            translationY = pull.value
            scaleX = 1f - 0.08f * fraction
            scaleY = 1f - 0.08f * fraction
            alpha = 1f - 0.6f * fraction
        }
}
