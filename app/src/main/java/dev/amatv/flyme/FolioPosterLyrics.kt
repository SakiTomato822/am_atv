package dev.amatv.flyme

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline

private sealed interface PosterRollKey {
    val order: Int

    data class Lead(val index: Int) : PosterRollKey {
        override val order: Int get() = index * 2 + 1
    }

    data class Gap(val gap: LyricTimeline.InstrumentalGap) : PosterRollKey {
        override val order: Int get() = gap.beforeLineIndex * 2
    }
}

private data class PosterRoles(
    val previous: PosterRollKey.Lead?,
    val focus: PosterRollKey?,
    val upcoming: List<PosterRollKey>,
) {
    val visible = listOfNotNull(previous, focus) + upcoming

    fun roleFor(key: PosterRollKey): PosterLyricRole = when (key) {
        previous -> PosterLyricRole.Previous
        focus -> PosterLyricRole.Focus
        upcoming.getOrNull(0) -> PosterLyricRole.Upcoming0
        upcoming.getOrNull(1) -> PosterLyricRole.Upcoming1
        upcoming.getOrNull(2) -> PosterLyricRole.Upcoming2
        else -> if (key.order < (focus?.order ?: 0)) PosterLyricRole.ExitingPrevious else PosterLyricRole.EnteringBelow
    }
}

private data class PosterRollStage(
    val roles: PosterRoles,
    val positions: Map<PosterRollKey, Float>,
    val exitY: Float,
    val enterY: Float,
) {
    fun yFor(key: PosterRollKey) = positions[key]
        ?: if (roles.roleFor(key) == PosterLyricRole.ExitingPrevious) exitY else enterY
}

@Composable
internal fun FolioPosterLyricsPanel(
    lines: List<LyricLine>,
    positionState: FolioPlaybackPosition,
    offsetMs: Long,
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    baseSp: Float,
    isLight: Boolean,
    fg: Color,
    fg2: Color,
    duetAccentColor: Color,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val timeline = remember(lines) { PosterLyricsTimeline(lines) }
    val clock = remember(positionState, timeline, offsetMs) {
        PosterPlaybackClock(Snapshot.withoutReadObservation { (positionState.value + offsetMs).coerceAtLeast(0L) })
    }
    val initialSeekVersion = remember(clock) { Snapshot.withoutReadObservation { positionState.seekVersion } }
    val drawPosition = remember(clock) { mutableLongStateOf(clock.positionMs) }
    var projection by remember(clock) { mutableStateOf(timeline.projectionAt(timeline.boundaryAt(clock.positionMs))) }
    var seekGeneration by remember(clock) { mutableLongStateOf(clock.seekGeneration) }
    LaunchedEffect(clock) {
        var boundary = timeline.boundaryAt(clock.positionMs)
        var seekVersion = initialSeekVersion
        snapshotFlow { positionState.value to positionState.seekVersion }.collect { (sample, version) ->
            val position = clock.sample((sample + offsetMs).coerceAtLeast(0L), seek = version != seekVersion)
            seekVersion = version
            val nextBoundary = timeline.boundaryAt(position)
            if (nextBoundary != boundary) {
                projection = timeline.projectionAt(nextBoundary)
                boundary = nextBoundary
            }
            seekGeneration = clock.seekGeneration
            drawPosition.longValue = position
        }
    }

    val animate = !lowPerformance && !reducedMotion
    val backdrop = LocalFolioGlassEnv.current.baseColor
    val edgeFade = if (lowPerformance) Modifier else Modifier.drawWithCache {
        val shade = Brush.verticalGradient(
            0f to backdrop,
            0.08f to backdrop.copy(alpha = 0f),
            0.90f to backdrop.copy(alpha = 0f),
            1f to backdrop,
        )
        onDrawWithContent {
            drawContent()
            drawRect(shade)
        }
    }
    CompositionLocalProvider(LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette) {
        BoxWithConstraints(modifier.fillMaxSize().clipToBounds().then(edgeFade)) {
            val density = LocalDensity.current
            val fontFamily = LocalFolioFontFamily.current
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val typo = remember(lines, maxWidth, baseSp) { posterTypographyFor(lines, maxWidth.value, baseSp) }
            val measurer = rememberTextMeasurer(cacheSize = 32)
            val leadStyle = TextStyle(
                fontFamily = fontFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = typo.mainSp.sp, lineHeight = typo.mainLineHeightSp.sp,
            )
            val localStyle = TextStyle(
                fontFamily = fontFamily, fontWeight = FontWeight.Normal,
                fontSize = typo.translationSp.sp, lineHeight = typo.translationLineHeightSp.sp,
            )
            val smallStyle = TextStyle(
                fontFamily = fontFamily, fontWeight = FontWeight.SemiBold,
                fontSize = typo.backingSp.sp, lineHeight = typo.backingLineHeightSp.sp,
            )
            val roles = PosterRoles(
                previous = projection.previousLeadIndex?.let { PosterRollKey.Lead(it) },
                focus = projection.gap?.let { PosterRollKey.Gap(it) } ?: projection.focusLeadIndex?.let { PosterRollKey.Lead(it) },
                upcoming = projection.upcomingLeadIndices.map { PosterRollKey.Lead(it) },
            )
            val leadTexts = remember(lines) { lines.map(::posterLeadText) }
            fun measureLead(item: PosterRollKey.Lead, role: PosterLyricRole): TextLayoutResult {
                val layoutWidth = computePosterRoleLayoutWidthPx(width, posterRoleScale(role, typo))
                val ellipsize = role == PosterLyricRole.Previous || role == PosterLyricRole.ExitingPrevious
                return measurer.measure(
                    text = leadTexts[item.index],
                    style = leadStyle,
                    constraints = Constraints(maxWidth = layoutWidth),
                    maxLines = if (ellipsize) 2 else Int.MAX_VALUE,
                    overflow = if (ellipsize) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
            val layouts = remember(roles, lines, width, typo, leadStyle, measurer) {
                roles.visible.filterIsInstance<PosterRollKey.Lead>().associateWith { measureLead(it, roles.roleFor(it)) }
            }
            val focusLine = (roles.focus as? PosterRollKey.Lead)?.let { lines[it.index] }
            fun measureLocal(text: String): TextLayoutResult? = text.takeIf { it.isNotEmpty() }?.let {
                measurer.measure(it, localStyle, constraints = Constraints(maxWidth = width))
            }
            val pronunciation = remember(focusLine, pronunciationEnabled, width, localStyle, measurer) {
                if (pronunciationEnabled && focusLine != null) measureLocal(posterPronunciationText(focusLine)) else null
            }
            val translation = remember(focusLine, translationEnabled, width, localStyle, measurer) {
                if (translationEnabled) measureLocal(focusLine?.translation?.trim().orEmpty()) else null
            }
            val small = remember(projection.smallLines, width, smallStyle, measurer) {
                projection.smallLines.map { line ->
                    measurer.measure(line.text, smallStyle, constraints = Constraints(maxWidth = width)) to line.isOppositeSinger
                }
            }
            val localGap = with(density) { 4.dp.toPx() }
            val previousGap = with(density) { 20.dp.toPx() }
            val upcomingGap = with(density) { 14.dp.toPx() }
            val gapHeight = with(density) { 64.dp.toPx() }
            fun heightFor(item: PosterRollKey?): Float = when (item) {
                is PosterRollKey.Lead -> layouts.getValue(item).size.height * posterRoleScale(roles.roleFor(item), typo)
                is PosterRollKey.Gap -> gapHeight
                null -> 0f
            }
            val focusHeight = heightFor(roles.focus)
            val previousLayoutHeight = roles.previous?.let { layouts.getValue(it).size.height } ?: 0
            val pronHeight = pronunciation?.let { it.size.height + localGap } ?: 0f
            val belowHeight = (listOfNotNull(translation) + small.map { it.first }).sumOf {
                (it.size.height + localGap).toDouble()
            }.toFloat()
            val focusY = height * 0.40f - (focusHeight - with(density) { typo.mainLineHeightSp.sp.toPx() }) / 2f
            val prevY = focusY - pronHeight - previousGap - heightFor(roles.previous)
            var cursor = focusY + focusHeight + belowHeight + with(density) { 28.dp.toPx() }
            val positions = buildMap {
                roles.focus?.let { put(it, focusY) }
                roles.previous?.let { put(it, prevY) }
                for (item in roles.upcoming) {
                    put(item, cursor)
                    cursor += heightFor(item) + upcomingGap
                }
            }
            val stage = PosterRollStage(roles, positions, prevY - with(density) { 28.dp.toPx() }, cursor)

            key(timeline, clock, seekGeneration) {
                val transition = if (animate) updateTransition(stage, label = "poster-roll") else null
                val items = (transition?.currentState?.roles?.visible.orEmpty() + roles.visible).distinct()
                val rollSpec = tween<Float>(POSTER_ROLL_DURATION_MS, easing = FolioPlayerMotionTokens.EnterEasing)
                val verticalPositions = items.associateWith { item ->
                    key(item) { transition?.animateFloat({ rollSpec }, label = "poster-y") { it.yFor(item) } }
                }
                val focusPosition = verticalPositions[roles.focus]
                Box(Modifier.fillMaxSize()) {
                    for (item in items) key(item) {
                        val role = roles.roleFor(item)
                        val targetScale = posterRoleScale(role, typo)
                        val targetAlpha = posterRoleAlpha(role, fg2.alpha)
                        val y = verticalPositions[item]
                        val scale = transition?.animateFloat({ rollSpec }, label = "poster-scale") {
                            posterRoleScale(it.roles.roleFor(item), typo)
                        }
                        val opacity = transition?.animateFloat({ rollSpec }, label = "poster-alpha") {
                            posterRoleAlpha(it.roles.roleFor(item), fg2.alpha)
                        }
                        Box(Modifier.fillMaxWidth().graphicsLayer {
                            val rowY = y?.value ?: stage.yFor(item)
                            translationY = if (role == PosterLyricRole.Previous) {
                                val rowHeight = previousLayoutHeight * (scale?.value ?: targetScale)
                                val focusTop = focusPosition?.value ?: focusY
                                minOf(rowY, focusTop - pronHeight - previousGap - rowHeight)
                            } else rowY
                        }) {
                            when (item) {
                                is PosterRollKey.Gap -> Box(Modifier.graphicsLayer {
                                    val zoom = scale?.value ?: targetScale
                                    scaleX = 1.65f * zoom
                                    scaleY = 1.65f * zoom
                                    alpha = opacity?.value ?: targetAlpha
                                    translationX = (-28).dp.toPx()
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }) {
                                    InstrumentalInterludeRow(item.gap, drawPosition, isUserScrolling = false)
                                }
                                is PosterRollKey.Lead -> {
                                    val line = lines[item.index]
                                    val layout = layouts[item] ?: remember(lines, item, width, role, typo, leadStyle, measurer) {
                                        measureLead(item, role)
                                    }
                                    val isFocus = role == PosterLyricRole.Focus
                                    val opposite = isOppositeSingerLine(line)
                                    val ink = if (opposite) duetAccentColor else fg
                                    PosterLeadLineText(
                                        layout, line, drawPosition, isFocus && animate && projection.hasStartedLead,
                                        if (isFocus && !projection.hasStartedLead) ink.copy(alpha = 0.40f) else ink,
                                        Modifier.graphicsLayer {
                                            val zoom = scale?.value ?: targetScale
                                            scaleX = zoom
                                            scaleY = zoom
                                            alpha = opacity?.value ?: targetAlpha
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            compositingStrategy = CompositingStrategy.ModulateAlpha
                                        },
                                    )
                                    if (isFocus) {
                                        val localInk = if (opposite) duetAccentColor.copy(alpha = 0.60f) else fg2
                                        pronunciation?.let {
                                            PosterMeasuredText(it, localInk, Modifier.graphicsLayer { translationY = -pronHeight })
                                        }
                                        PosterBelowLead(translation, localInk, small, duetAccentColor, fg2, Modifier.graphicsLayer {
                                            translationY = layout.size.height * (scale?.value ?: targetScale) + localGap
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (roles.focus == null) {
                        PosterBelowLead(null, fg2, small, duetAccentColor, fg2, Modifier.graphicsLayer {
                            translationY = focusY
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterLeadLineText(
    layout: TextLayoutResult,
    line: LyricLine,
    position: State<Long>,
    sweepEnabled: Boolean,
    ink: Color,
    modifier: Modifier,
) {
    val sweep = if (sweepEnabled) remember(layout, line) {
        posterSweepUnits(line).takeIf { it.isNotEmpty() }?.let { PosterLyricSweep(layout, it) }
    } else null
    val height = with(LocalDensity.current) { layout.size.height.toDp() }
    Canvas(modifier.fillMaxWidth().height(height).semantics { text = layout.layoutInput.text }) {
        if (sweep == null) drawText(layout, color = ink) else sweep.draw(this, position.value, ink)
    }
}

@Composable
private fun PosterBelowLead(
    translation: TextLayoutResult?,
    translationInk: Color,
    small: List<Pair<TextLayoutResult, Boolean>>,
    duetAccent: Color,
    ink: Color,
    modifier: Modifier,
) {
    if (translation == null && small.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        translation?.let { PosterMeasuredText(it, translationInk) }
        for ((layout, opposite) in small) {
            PosterMeasuredText(layout, if (opposite) duetAccent.copy(alpha = 0.78f) else ink)
        }
    }
}

@Composable
private fun PosterMeasuredText(layout: TextLayoutResult, ink: Color, modifier: Modifier = Modifier) {
    val height = with(LocalDensity.current) { layout.size.height.toDp() }
    Canvas(modifier.fillMaxWidth().height(height).semantics { text = layout.layoutInput.text }) {
        drawText(layout, color = ink)
    }
}
