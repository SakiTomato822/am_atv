package dev.amatv.flyme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PIVOT_SCRUB_HOLD_MS: Long = 300L
private const val PIVOT_SCRUB_BAR_SCALE: Float = 1.03f
private const val PIVOT_TAP_BOUNCE_SCALE: Float = 0.96f
internal const val FOLIO_SCROLL_TOP_JUMP_INDEX: Int = 8

internal data class FolioPivotPlacement(
    val translationX: Float,
    val isPeekingLeft: Boolean,
)

internal data class FolioPivotScrubLayout(
    val fitScale: Float,
    val tabStartXs: List<Float>,
    val tabCentersPx: List<Float>,
)

internal enum class FolioScrollToTopAction {
    AlreadyAtTop,
    AnimateFromCurrent,
    JumpThenAnimate,
}

internal fun resolveScrollToTopAction(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    jumpIndex: Int = FOLIO_SCROLL_TOP_JUMP_INDEX,
): FolioScrollToTopAction = when {
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 -> FolioScrollToTopAction.AlreadyAtTop
    firstVisibleItemIndex > jumpIndex -> FolioScrollToTopAction.JumpThenAnimate
    else -> FolioScrollToTopAction.AnimateFromCurrent
}

internal suspend fun LazyListState.folioScrollToTop() {
    when (resolveScrollToTopAction(firstVisibleItemIndex, firstVisibleItemScrollOffset)) {
        FolioScrollToTopAction.AlreadyAtTop -> Unit
        FolioScrollToTopAction.AnimateFromCurrent -> animateScrollToItem(0)
        FolioScrollToTopAction.JumpThenAnimate -> {
            scrollToItem(FOLIO_SCROLL_TOP_JUMP_INDEX)
            animateScrollToItem(0)
        }
    }
}

internal class FolioTapBounceState(
    private val scaleAnim: Animatable<Float, *>,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val spatial: Boolean,
) {
    val scale: Float get() = scaleAnim.value

    fun trigger() {
        if (!spatial) return
        scope.launch {
            scaleAnim.snapTo(PIVOT_TAP_BOUNCE_SCALE)
            scaleAnim.animateTo(
                1f,
                spring(
                    dampingRatio = FolioPressDefaults.SPRING_DAMPING,
                    stiffness = FolioPressDefaults.SPRING_STIFFNESS,
                ),
            )
        }
    }
}

@Composable
internal fun rememberFolioTapBounce(): FolioTapBounceState {
    val motion = LocalFolioBrowseMotion.current
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }
    return remember(scaleAnim, scope, motion.spatial) {
        FolioTapBounceState(scaleAnim, scope, motion.spatial)
    }
}

@Composable
internal fun rememberFolioScrollToTopState(
    onRegisterScrollToTop: (suspend () -> Unit) -> Unit,
): androidx.compose.foundation.lazy.LazyListState {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    DisposableEffect(listState, onRegisterScrollToTop) {
        onRegisterScrollToTop { listState.folioScrollToTop() }
        onDispose {}
    }
    return listState
}

/**
 * Places the active title at most one third across [pivotWidthPx] on pages with a left neighbor.
 */
internal fun computePivotPlacementForPage(
    pageIndex: Int,
    tabWidthsPx: List<Float>,
    pxPx: Float,
    gapPx: Float,
    pivotWidthPx: Float,
): FolioPivotPlacement {
    if (tabWidthsPx.isEmpty()) return FolioPivotPlacement(pxPx, isPeekingLeft = false)
    val clamped = pageIndex.coerceIn(0, tabWidthsPx.lastIndex)
    val tabStarts = FloatArray(tabWidthsPx.size)
    var cursor = 0f
    for (i in tabWidthsPx.indices) {
        tabStarts[i] = cursor
        cursor += tabWidthsPx[i] + if (i < tabWidthsPx.lastIndex) gapPx else 0f
    }
    val offsetLeft = tabStarts[clamped]

    val translationX = if (clamped == 0) {
        pxPx
    } else {
        val prevWidth = tabWidthsPx[clamped - 1]
        val full = pxPx + prevWidth + gapPx
        val limit = pivotWidthPx / 3f
        minOf(full, limit) - offsetLeft
    }

    val isPeekingLeft = tabWidthsPx.indices.any { i ->
        val left = translationX + tabStarts[i]
        val right = left + tabWidthsPx[i]
        left < -0.5f && right > 0.5f
    }
    return FolioPivotPlacement(
        translationX = translationX,
        isPeekingLeft = isPeekingLeft,
    )
}

internal fun tabAlphaForPage(
    tabIndex: Int,
    pageIndex: Int,
    isFullyVisible: Boolean = true,
): Float = when {
    tabIndex == pageIndex -> 1f
    isFullyVisible -> 0.32f
    else -> 0.24f
}

/**
 * Computes the condensed horizontal layout used while scrubbing across all tabs in [pivotWidthPx]
 * within `[pxPx .. pivotWidthPx - prPx]`.
 */
internal fun computeScrubPivotLayout(
    tabWidthsPx: List<Float>,
    pxPx: Float,
    prPx: Float,
    gapPx: Float,
    pivotWidthPx: Float,
    minGapPx: Float = gapPx * 0.45f,
): FolioPivotScrubLayout {
    if (tabWidthsPx.isEmpty()) {
        return FolioPivotScrubLayout(fitScale = 1f, tabStartXs = emptyList(), tabCentersPx = emptyList())
    }
    val availableWidth = (pivotWidthPx - pxPx - prPx).coerceAtLeast(1f)
    val gapCount = (tabWidthsPx.size - 1).coerceAtLeast(0)
    val sumWidths = tabWidthsPx.sum().coerceAtLeast(1f)
    val normalTotal = sumWidths + gapPx * gapCount
    val fitScale: Float
    val effectiveGap: Float
    if (normalTotal <= availableWidth) {
        fitScale = 1f
        effectiveGap = gapPx
    } else {
        effectiveGap = minGapPx.coerceAtMost(gapPx)
        fitScale = ((availableWidth - effectiveGap * gapCount) / sumWidths).coerceIn(0.25f, 1f)
    }

    val starts = ArrayList<Float>(tabWidthsPx.size)
    val centers = ArrayList<Float>(tabWidthsPx.size)
    var x = pxPx
    for (width in tabWidthsPx) {
        val scaledWidth = width * fitScale
        starts.add(x)
        centers.add(x + scaledWidth * 0.5f)
        x += scaledWidth + effectiveGap
    }
    return FolioPivotScrubLayout(
        fitScale = fitScale,
        tabStartXs = starts,
        tabCentersPx = centers,
    )
}

internal fun nearestScrubTabIndex(pointerX: Float, tabCentersPx: List<Float>): Int {
    if (tabCentersPx.isEmpty()) return 0
    var bestIndex = 0
    var bestDist = abs(pointerX - tabCentersPx[0])
    for (i in 1 until tabCentersPx.size) {
        val dist = abs(pointerX - tabCentersPx[i])
        if (dist < bestDist) {
            bestDist = dist
            bestIndex = i
        }
    }
    return bestIndex
}

/** Metro pivot title strip supporting multi-title trailing alignment, hold-to-scrub switching, and tap-to-scroll-top. */
@Composable
internal fun FolioPivotBar(
    titles: List<String>,
    pageProgress: Float,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onScrollCurrentPageToTop: () -> Unit = {},
    onRightEdgePxChanged: (Int) -> Unit = {},
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val motion = LocalFolioBrowseMotion.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val maxIndex = (titles.size - 1).coerceAtLeast(0)
    val progress = pageProgress.coerceIn(0f, maxIndex.toFloat())
    val lower = floor(progress).toInt().coerceIn(0, maxIndex)
    val upper = ceil(progress).toInt().coerceIn(0, maxIndex)
    val fraction = progress - lower

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubHighlightedPage by remember { mutableStateOf<Int?>(null) }
    val scrubCentersRef = remember { mutableListOf<Float>() }
    var barHeightPx by remember { mutableIntStateOf(0) }

    val latestSelectedPage by rememberUpdatedState(selectedPage)
    val latestOnSelectPage by rememberUpdatedState(onSelectPage)
    val latestOnScrollToTop by rememberUpdatedState(onScrollCurrentPageToTop)

    val scrubProgress by animateFloatAsState(
        targetValue = if (isScrubbing) 1f else 0f,
        animationSpec = if (motion.spatial) {
            spring(
                dampingRatio = FolioPressDefaults.SPRING_DAMPING,
                stiffness = FolioPressDefaults.SPRING_STIFFNESS,
            )
        } else {
            snap()
        },
        label = "pivot-scrub-progress",
    )

    val tapBounce = rememberFolioTapBounce()

    val peekStrength = remember { floatArrayOf(0f) }
    val cancelMarginPx = with(density) { 48.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tierSpec.pvh)
            .pointerInput(titles.size, cancelMarginPx) {
                if (titles.isEmpty()) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val downPos = down.position
                    var lastPos = downPos
                    var abortedBeforeHold = false

                    val timedOut = withTimeoutOrNull(PIVOT_SCRUB_HOLD_MS) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                abortedBeforeHold = true
                                return@withTimeoutOrNull true
                            }
                            lastPos = change.position
                            if ((lastPos - downPos).getDistance() > touchSlop) {
                                abortedBeforeHold = true
                                return@withTimeoutOrNull true
                            }
                        }
                    }

                    if (abortedBeforeHold || timedOut != null) return@awaitEachGesture

                    isScrubbing = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var cancelled = lastPos.y < -cancelMarginPx || lastPos.y > barHeightPx + cancelMarginPx
                    scrubHighlightedPage = if (cancelled) null else nearestScrubTabIndex(lastPos.x, scrubCentersRef)

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null) {
                            isScrubbing = false
                            scrubHighlightedPage = null
                            break
                        }
                        change.consume()
                        val pos = change.position
                        cancelled = pos.y < -cancelMarginPx || pos.y > barHeightPx + cancelMarginPx
                        scrubHighlightedPage = if (cancelled) null else nearestScrubTabIndex(pos.x, scrubCentersRef)

                        if (!change.pressed) {
                            val target = scrubHighlightedPage
                            isScrubbing = false
                            scrubHighlightedPage = null
                            if (!cancelled && target != null && target != latestSelectedPage) {
                                latestOnSelectPage(target)
                            }
                            break
                        }
                    }
                }
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                val barScale = if (motion.spatial) {
                    1f + (PIVOT_SCRUB_BAR_SCALE - 1f) * scrubProgress
                } else {
                    1f
                }
                scaleX = barScale
                scaleY = barScale
            }
            .clipToBounds()
            .drawWithContent {
                drawContent()
                val maskAlpha = peekStrength[0] * (1f - scrubProgress)
                if (maskAlpha > 0.01f && size.width > 0f) {
                    val fadePx = 18.dp.toPx()
                    val stop = (fadePx / size.width).coerceIn(0.01f, 0.25f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 1f - maskAlpha),
                            stop to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        val activeScrubTarget = scrubHighlightedPage ?: selectedPage

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                titles.forEachIndexed { index, title ->
                    val isCurrent = index == selectedPage
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                if (isCurrent && !isScrubbing) {
                                    val s = tapBounce.scale
                                    scaleX = s
                                    scaleY = s
                                }
                            }
                            .folioBrowsePressable(
                                enabled = !isScrubbing,
                                role = Role.Tab,
                                pressedScale = if (isCurrent) PIVOT_TAP_BOUNCE_SCALE else 0.97f,
                                onClick = {
                                    if (index == latestSelectedPage) {
                                        tapBounce.trigger()
                                        latestOnScrollToTop()
                                    } else {
                                        latestOnSelectPage(index)
                                    }
                                },
                            )
                            .semantics { selected = index == selectedPage }
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            text = title,
                            color = palette.fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Light,
                            fontSize = tierSpec.pvf,
                            lineHeight = tierSpec.pvf * 1.12f,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            barHeightPx = constraints.maxHeight
            val unbounded = Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
            val placeables = measurables.map { it.measure(unbounded) }
            val widths = placeables.map { it.width.toFloat() }
            val pxPx = tierSpec.px.toPx()
            val prPx = tierSpec.pr.toPx()
            val gapPx = tierSpec.pvg.toPx()
            val pivotWidthPx = constraints.maxWidth.toFloat()

            val lowPlacement = computePivotPlacementForPage(lower, widths, pxPx, gapPx, pivotWidthPx)
            val highPlacement = computePivotPlacementForPage(upper, widths, pxPx, gapPx, pivotWidthPx)
            val normalStartX = lowPlacement.translationX +
                (highPlacement.translationX - lowPlacement.translationX) * fraction
            peekStrength[0] = (if (lowPlacement.isPeekingLeft) 1f - fraction else 0f) +
                (if (highPlacement.isPeekingLeft) fraction else 0f)

            val normalXs = FloatArray(widths.size)
            val resolvedTabAlphas = FloatArray(widths.size)
            var nx = normalStartX
            for (i in widths.indices) {
                normalXs[i] = nx
                val offset = nx - normalStartX
                val lowLeft = lowPlacement.translationX + offset
                val highLeft = highPlacement.translationX + offset
                val lowFull = lowLeft >= -0.5f && lowLeft + widths[i] <= pivotWidthPx + 0.5f
                val highFull = highLeft >= -0.5f && highLeft + widths[i] <= pivotWidthPx + 0.5f
                val lowA = tabAlphaForPage(i, lower, isFullyVisible = lowFull)
                val highA = tabAlphaForPage(i, upper, isFullyVisible = highFull)
                val normalA = lowA + (highA - lowA) * fraction
                val scrubA = if (i == activeScrubTarget && (!isScrubbing || scrubHighlightedPage != null)) 1f else 0.32f
                resolvedTabAlphas[i] = normalA + (scrubA - normalA) * scrubProgress
                nx += widths[i] + gapPx
            }

            val scrubLayout = computeScrubPivotLayout(widths, pxPx, prPx, gapPx, pivotWidthPx)
            scrubCentersRef.clear()
            scrubCentersRef.addAll(scrubLayout.tabCentersPx)

            val fitScaleNow = 1f + (scrubLayout.fitScale - 1f) * scrubProgress
            val totalSpanPx = if (widths.isEmpty()) 0f else widths.sum() + gapPx * (widths.size - 1)
            onRightEdgePxChanged((normalStartX + totalSpanPx).roundToInt().coerceAtLeast(0))

            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val targetX = scrubLayout.tabStartXs.getOrElse(index) { normalXs[index] }
                    val placedX = normalXs[index] + (targetX - normalXs[index]) * scrubProgress
                    val layerAlpha = resolvedTabAlphas[index]
                    placeable.placeRelativeWithLayer(
                        x = placedX.roundToInt(),
                        y = 0,
                    ) {
                        alpha = layerAlpha
                        if (fitScaleNow != 1f) {
                            scaleX = fitScaleNow
                            scaleY = fitScaleNow
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    }
                }
            }
        }
    }
}
