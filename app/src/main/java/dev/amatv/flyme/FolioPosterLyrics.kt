package dev.amatv.flyme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
        else -> error("No visible poster role for $key")
    }
}

private sealed interface PosterDrawing {
    val width: Float
    val height: Float

    data class Lead(
        val index: Int,
        val layout: TextLayoutResult,
        val focus: Boolean,
        val previous: Boolean,
    ) : PosterDrawing {
        override val width: Float get() = layout.size.width.toFloat()
        override val height: Float get() = layout.size.height.toFloat()
    }

    data class Local(val layout: TextLayoutResult, val ink: Color, val slot: Int) : PosterDrawing {
        override val width: Float get() = layout.size.width.toFloat()
        override val height: Float get() = layout.size.height.toFloat()
    }

    data class Gap(
        val gap: LyricTimeline.InstrumentalGap,
        override val width: Float,
        override val height: Float,
    ) : PosterDrawing
}

private data class PosterRollRow(
    val bounds: PosterRollBounds,
    val drawings: Map<PosterDrawing, PosterInkPose>,
)

private fun posterRollRow(
    top: Float,
    parts: List<PosterDrawing>,
    scale: Float,
    opacity: Float,
    panelWidth: Float,
    gap: Float,
): PosterRollRow {
    val fit = minOf(scale, panelWidth / parts.maxOf { it.width })
    var cursor = top
    val drawings = buildMap {
        for (part in parts) {
            if (isNotEmpty()) cursor += gap * fit
            put(part, PosterInkPose(cursor, fit, opacity))
            cursor += part.height * fit
        }
    }
    return PosterRollRow(PosterRollBounds(top, cursor), drawings)
}

/** Both cached versions of a lyric stay within its interpolated row. */
private class PosterRoll(
    start: Map<Int, PosterRollRow>,
    end: Map<Int, PosterRollRow>,
    panelWidth: Float,
    panelHeight: Float,
    gap: Float,
) {
    val progress = Animatable(0f)
    private val orders = (start.keys + end.keys).sorted()
    private val startVisible = start.mapValues { it.value.bounds }
    private val endVisible = end.mapValues { it.value.bounds }
    private val startBounds = posterRollBounds(orders, startVisible, endVisible, panelHeight, gap)
    private val endBounds = posterRollBounds(orders, endVisible, startVisible, panelHeight, gap)

    // Common easing keeps every rectangle within its own row and preserves the order of the rows.
    val drawings = orders.associateWith { order ->
        val from = start[order]?.drawings.orEmpty()
        val to = end[order]?.drawings.orEmpty()
        (from.keys + to.keys).associateWith { drawing ->
            val b = to[drawing]
            val startSlot = startBounds.getValue(order)
            val endSlot = endBounds.getValue(order)
            val first = from[drawing] ?: to.getValue(drawing).hiddenIn(endSlot, startSlot, drawing.width, panelWidth)
            val last = b ?: first.hiddenIn(startSlot, endSlot, drawing.width, panelWidth)
            first to last
        }
    }

    fun sample(): Map<Int, PosterRollRow> {
        val time = progress.value
        val movement = FolioPlayerMotionTokens.EnterEasing.transform(time)
        return buildMap {
            for ((order, tracks) in drawings) {
                val poses = tracks.mapValues { (_, track) -> track.first.interpolate(track.second, time, movement) }
                    .filterValues { it.opacity > 0f && it.scale > 0f }
                if (poses.isNotEmpty()) {
                    val bounds = startBounds.getValue(order).interpolate(endBounds.getValue(order), movement)
                    put(order, PosterRollRow(bounds, poses))
                }
            }
        }
    }
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
    CompositionLocalProvider(LocalFlymePalette provides if (isLight) LightFlymePalette else DarkFlymePalette) {
        BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val fontFamily = LocalFolioFontFamily.current
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            if (width == 0 || height == 0) return@BoxWithConstraints
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
                val ellipsize = role == PosterLyricRole.Previous
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
            val panelWidth = width.toFloat()
            val panelHeight = height.toFloat()
            val localGap = with(density) { 4.dp.toPx() }
            val previousGap = with(density) { 20.dp.toPx() }
            val upcomingGap = with(density) { 14.dp.toPx() }
            val focusGap = with(density) { 28.dp.toPx() }
            val smallDrawings = small.mapIndexed { index, (layout, opposite) ->
                PosterDrawing.Local(layout, if (opposite) duetAccentColor.copy(alpha = 0.78f) else fg2, index + 1)
            }
            fun row(item: PosterRollKey, top: Float): PosterRollRow {
                val role = roles.roleFor(item)
                val scale = posterRoleScale(role, typo)
                // Previous ink fades in its Canvas so this pose stays independent of the playback clock.
                val opacity = if (role == PosterLyricRole.Previous) 1f else posterRoleAlpha(role, fg2.alpha)
                val parts = when (item) {
                    is PosterRollKey.Gap -> listOf(with(density) {
                        val dotWidth = 66.dp.toPx()
                        val rowHeight = (18.dp.roundToPx() + 20.dp.roundToPx() + 26.dp.roundToPx()).toFloat()
                        PosterDrawing.Gap(item.gap, dotWidth, rowHeight)
                    })
                    is PosterRollKey.Lead -> {
                        val isFocus = role == PosterLyricRole.Focus
                        val lead = PosterDrawing.Lead(
                            item.index, layouts.getValue(item), isFocus, previous = role == PosterLyricRole.Previous,
                        )
                        if (isFocus) buildList<PosterDrawing> {
                            val localInk = if (isOppositeSingerLine(lines[item.index])) {
                                duetAccentColor.copy(alpha = 0.60f)
                            } else fg2
                            pronunciation?.let { add(PosterDrawing.Local(it, localInk, -1)) }
                            add(lead)
                            translation?.let { add(PosterDrawing.Local(it, localInk, 0)) }
                            addAll(smallDrawings)
                        } else listOf(lead)
                    }
                }
                val zoom = if (item is PosterRollKey.Gap) scale * 1.65f else scale
                return posterRollRow(top, parts, zoom, opacity, panelWidth, localGap)
            }
            val stage = remember(roles, layouts, pronunciation, translation, smallDrawings, density, typo, width, height, lines, fg2, duetAccentColor) {
                buildMap {
                    val focus = roles.focus
                    val anchor = panelHeight * 0.40f + with(density) { typo.mainLineHeightSp.sp.toPx() } / 2f
                    var nextTop = anchor
                    if (focus != null) {
                        val unplaced = row(focus, 0f)
                        val lead = unplaced.drawings.entries.first { it.key !is PosterDrawing.Local }
                        val leadTop = anchor - lead.key.height * lead.value.scale / 2f
                        val focusRow = row(focus, leadTop - lead.value.top)
                        roles.previous?.let { previous ->
                            val previousHeight = row(previous, 0f).bounds.height
                            put(previous.order, row(previous, focusRow.bounds.top - previousGap - previousHeight))
                        }
                        put(focus.order, focusRow)
                        nextTop = focusRow.bounds.bottom + focusGap
                    } else if (smallDrawings.isNotEmpty()) {
                        put(-1, posterRollRow(anchor, smallDrawings, 1f, 1f, panelWidth, localGap))
                    }
                    for (item in roles.upcoming) {
                        val upcoming = row(item, nextTop)
                        put(item.order, upcoming)
                        nextTop = upcoming.bounds.bottom + upcomingGap
                    }
                }
            }

            key(timeline, clock, seekGeneration, width, height, density, leadStyle, animate) {
                var settled by remember { mutableStateOf(roles to stage) }
                var running by remember { mutableStateOf<PosterRoll?>(null) }
                val roll = running
                val currentFocus = (roles.focus as? PosterRollKey.Lead)?.index
                val hasStarted = projection.hasStartedLead
                if (animate) {
                    val latestStage by rememberUpdatedState(stage)
                    LaunchedEffect(roles) {
                        val from = running?.sample() ?: settled.second
                        val target = latestStage
                        if (from != target) {
                            val next = PosterRoll(from, target, panelWidth, panelHeight, upcomingGap)
                            running = next
                            next.progress.animateTo(1f, tween(POSTER_ROLL_DURATION_MS, easing = LinearEasing))
                        }
                        settled = roles to latestStage
                        running = null
                    }
                    SideEffect {
                        // Backing-word boundaries can arrive faster than the fade-in window.
                        // Refresh the packed rows without restarting an active lyric handoff.
                        if (running == null && settled.first == roles && settled.second != stage) {
                            settled = roles to stage
                        }
                    }
                }
                val staticStage = if (animate && settled.first != roles) settled.second else stage
                val tracks = roll?.drawings ?: staticStage.mapValues { (_, row) ->
                    row.drawings.mapValues { (_, pose) -> pose to pose }
                }
                for ((order, drawings) in tracks) {
                    for ((drawing, poses) in drawings) key(order, drawing) {
                        val layer = Modifier.graphicsLayer {
                            val pose = if (roll == null) poses.first else {
                                val time = roll.progress.value
                                poses.first.interpolate(poses.second, time, FolioPlayerMotionTokens.EnterEasing.transform(time))
                            }
                            scaleX = pose.scale
                            scaleY = pose.scale
                            translationY = pose.top
                            if (drawing is PosterDrawing.Gap) translationX = -28.dp.toPx() * pose.scale
                            val center = pose.top + drawing.height * pose.scale / 2f
                            alpha = pose.opacity * posterEdgeOpacity(center, panelHeight)
                            transformOrigin = TransformOrigin(0f, 0f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        when (drawing) {
                            is PosterDrawing.Gap -> Box(layer) {
                                InstrumentalInterludeRow(drawing.gap, drawPosition, isUserScrolling = false)
                            }
                            is PosterDrawing.Lead -> {
                                val line = lines[drawing.index]
                                val isCurrent = drawing.focus && drawing.index == currentFocus
                                val previousSweepEnd = projection.previousLeadSweepEndMs.takeIf {
                                    animate && drawing.index == projection.previousLeadIndex && (drawing.focus || drawing.previous)
                                }
                                val ink = if (isOppositeSingerLine(line)) duetAccentColor else fg
                                PosterLeadLineText(
                                    drawing.layout, line, drawPosition,
                                    (isCurrent && animate && hasStarted) || previousSweepEnd != null,
                                    if (isCurrent && !hasStarted) ink.copy(alpha = 0.40f) else ink,
                                    layer.posterTextSemantics(drawing.layout, poses.second.opacity > 0f),
                                    previousAlpha = if (drawing.previous) fg2.alpha else 1f,
                                    previousSweepEnd = previousSweepEnd,
                                )
                            }
                            is PosterDrawing.Local -> PosterMeasuredText(
                                drawing.layout, drawing.ink,
                                layer.posterTextSemantics(drawing.layout, poses.second.opacity > 0f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.posterTextSemantics(layout: TextLayoutResult, visibleInTarget: Boolean): Modifier =
    if (visibleInTarget) semantics { text = layout.layoutInput.text } else this

@Composable
private fun PosterLeadLineText(
    layout: TextLayoutResult,
    line: LyricLine,
    position: State<Long>,
    sweepEnabled: Boolean,
    ink: Color,
    modifier: Modifier,
    previousAlpha: Float,
    previousSweepEnd: Long?,
) {
    val sweep = if (sweepEnabled) remember(layout, line) {
        val featherPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() * 0.6f }
        PosterLyricSweep(layout, line, featherPx)
    } else null
    val height = with(LocalDensity.current) { layout.size.height.toDp() }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (sweep == null) {
            drawText(layout, color = ink.copy(alpha = ink.alpha * previousAlpha))
        } else {
            val positionMs = position.value
            val fade = if (previousSweepEnd != null) {
                ((positionMs - previousSweepEnd).toFloat() / POSTER_PREVIOUS_FADE_MS).coerceIn(0f, 1f)
            } else 0f
            val opacity = 1f + (previousAlpha - 1f) * fade
            sweep.draw(this, positionMs, ink.copy(alpha = ink.alpha * opacity))
        }
    }
}

@Composable
private fun PosterMeasuredText(layout: TextLayoutResult, ink: Color, modifier: Modifier = Modifier) {
    val height = with(LocalDensity.current) { layout.size.height.toDp() }
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawText(layout, color = ink)
    }
}
