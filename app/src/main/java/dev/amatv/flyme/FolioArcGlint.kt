package dev.amatv.flyme

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal const val FOLIO_COVER_LIGHT_LUMINANCE_THRESHOLD: Float = 0.58f

@Immutable
internal data class FolioArcGlintSpec(
    val bandWidthRatio: Float,
    val arcRadiusRatio: Float,
    val darkCoreAlpha: Float,
    val lightCoreAlpha: Float,
    val edgeTintFactor: Float,
    val dispersionFringe: Boolean,
    val sweepMs: Int,
    val pauseMs: Int,
    val sweepEasing: Easing,
) {
    companion object {
        val Loading = FolioArcGlintSpec(
            bandWidthRatio = 0.36f,
            arcRadiusRatio = 1.70f,
            darkCoreAlpha = 0.42f,
            lightCoreAlpha = 0.56f,
            edgeTintFactor = 0.52f,
            dispersionFringe = false,
            sweepMs = FolioLoadingShimmerTiming.SWEEP_DURATION_MS,
            pauseMs = FolioLoadingShimmerTiming.SWEEP_PAUSE_MS,
            sweepEasing = CubicBezierEasing(0.32f, 0.0f, 0.22f, 1.0f),
        )

        val TrackSwitch = FolioArcGlintSpec(
            bandWidthRatio = 0.22f,
            arcRadiusRatio = 1.55f,
            darkCoreAlpha = 0.32f,
            lightCoreAlpha = 0.44f,
            edgeTintFactor = 0.55f,
            dispersionFringe = true,
            sweepMs = FolioGatherMotion.TRACK_GLASS_SWEEP_MS,
            pauseMs = 0,
            sweepEasing = FolioGatherMotion.ReleaseEasing,
        )
    }
}

internal object FolioLoadingShimmerTiming {
    const val START_DELAY_MS: Long = 160L
    const val MIN_DISPLAY_MS: Long = 480L
    const val FADE_OUT_MS: Long = 240L
    const val FADE_IN_MS: Int = 100
    const val SWEEP_DURATION_MS: Int = 1300
    const val SWEEP_PAUSE_MS: Int = 240
    const val LOW_PERF_BREATH_ALPHA: Float = 0.20f
    const val LOW_PERF_BREATH_MIN_FACTOR: Float = 0.30f
    const val LOW_PERF_BREATH_HALF_MS: Int = 800
}

internal enum class FolioLoadingShimmerPhase {
    Idle,
    PendingDelay,
    Active,
    FadingOut,
}

/**
 * Pure anti-flash and track-switch handover state machine for cover loading shimmer.
 *
 * Guarantees:
 * - While [switchActive] is true, loading shimmer stays [FolioLoadingShimmerPhase.Idle] (alpha 0)
 *   so the track-switch glass sweep and the loading shimmer band are never visible simultaneously.
 * - When [switchActive] ends while [isTrackLoading] is still true, transitions directly into
 *   [FolioLoadingShimmerPhase.Active] (skipping the 160ms start delay) for a seamless handover.
 * - Otherwise waits [FolioLoadingShimmerTiming.START_DELAY_MS] (160ms), keeps shown at least
 *   [FolioLoadingShimmerTiming.MIN_DISPLAY_MS] (480ms), and fades out over
 *   [FolioLoadingShimmerTiming.FADE_OUT_MS] (240ms).
 */
internal class FolioLoadingShimmerStateMachine {
    var phase: FolioLoadingShimmerPhase = FolioLoadingShimmerPhase.Idle
        private set

    private var pendingSinceMs: Long? = null
    private var shownAtMs: Long? = null
    private var fadeStartedAtMs: Long? = null
    private var wasSwitchActive: Boolean = false
    private var lastLoading: Boolean = false

    fun update(nowMs: Long, isTrackLoading: Boolean, switchActive: Boolean): FolioLoadingShimmerPhase {
        lastLoading = isTrackLoading

        if (switchActive) {
            wasSwitchActive = true
            phase = FolioLoadingShimmerPhase.Idle
            pendingSinceMs = null
            shownAtMs = null
            fadeStartedAtMs = null
            return phase
        }

        val handedOverFromSwitch = wasSwitchActive
        wasSwitchActive = false

        if (handedOverFromSwitch) {
            if (isTrackLoading) {
                phase = FolioLoadingShimmerPhase.Active
                pendingSinceMs = null
                shownAtMs = nowMs
                fadeStartedAtMs = null
            } else {
                phase = FolioLoadingShimmerPhase.Idle
                pendingSinceMs = null
                shownAtMs = null
                fadeStartedAtMs = null
            }
            return phase
        }

        when (phase) {
            FolioLoadingShimmerPhase.Idle -> {
                if (isTrackLoading) {
                    phase = FolioLoadingShimmerPhase.PendingDelay
                    pendingSinceMs = nowMs
                    shownAtMs = null
                    fadeStartedAtMs = null
                }
            }
            FolioLoadingShimmerPhase.PendingDelay -> {
                if (!isTrackLoading) {
                    phase = FolioLoadingShimmerPhase.Idle
                    pendingSinceMs = null
                } else {
                    val since = pendingSinceMs ?: nowMs
                    if (nowMs - since >= FolioLoadingShimmerTiming.START_DELAY_MS) {
                        phase = FolioLoadingShimmerPhase.Active
                        pendingSinceMs = null
                        shownAtMs = nowMs
                        fadeStartedAtMs = null
                    }
                }
            }
            FolioLoadingShimmerPhase.Active -> {
                if (!isTrackLoading) {
                    val shownAt = shownAtMs ?: nowMs
                    if (nowMs - shownAt >= FolioLoadingShimmerTiming.MIN_DISPLAY_MS) {
                        phase = FolioLoadingShimmerPhase.FadingOut
                        fadeStartedAtMs = nowMs
                    }
                }
            }
            FolioLoadingShimmerPhase.FadingOut -> {
                if (isTrackLoading) {
                    phase = FolioLoadingShimmerPhase.Active
                    shownAtMs = nowMs
                    fadeStartedAtMs = null
                } else {
                    val fadeStart = fadeStartedAtMs ?: nowMs
                    if (nowMs - fadeStart >= FolioLoadingShimmerTiming.FADE_OUT_MS) {
                        phase = FolioLoadingShimmerPhase.Idle
                        shownAtMs = null
                        fadeStartedAtMs = null
                    }
                }
            }
        }
        return phase
    }

    fun nextEventDelayMs(nowMs: Long): Long? = when (phase) {
        FolioLoadingShimmerPhase.Idle -> null
        FolioLoadingShimmerPhase.PendingDelay -> {
            val since = pendingSinceMs ?: return null
            (FolioLoadingShimmerTiming.START_DELAY_MS - (nowMs - since)).coerceAtLeast(0L)
        }
        FolioLoadingShimmerPhase.Active -> {
            if (lastLoading) null
            else {
                val shownAt = shownAtMs ?: return 0L
                (FolioLoadingShimmerTiming.MIN_DISPLAY_MS - (nowMs - shownAt)).coerceAtLeast(0L)
            }
        }
        FolioLoadingShimmerPhase.FadingOut -> {
            val fadeStart = fadeStartedAtMs ?: return 0L
            (FolioLoadingShimmerTiming.FADE_OUT_MS - (nowMs - fadeStart)).coerceAtLeast(0L)
        }
    }

    fun alphaAt(nowMs: Long): Float = when (phase) {
        FolioLoadingShimmerPhase.Idle,
        FolioLoadingShimmerPhase.PendingDelay -> 0f
        FolioLoadingShimmerPhase.Active -> 1f
        FolioLoadingShimmerPhase.FadingOut -> {
            val fadeStart = fadeStartedAtMs ?: nowMs
            val fraction = ((nowMs - fadeStart).toFloat() / FolioLoadingShimmerTiming.FADE_OUT_MS.toFloat())
                .coerceIn(0f, 1f)
            (1f - MotionTokens.KaraokeGradientExitEasing.transform(fraction)).coerceIn(0f, 1f)
        }
    }
}

/**
 * Stable holder for the cover loading shimmer. Exposed as [State] objects read only in draw phase
 * so looping shimmer ticks never trigger recomposition.
 */
@Stable
internal class FolioLoadingShimmerMotion(
    val progress: State<Float> = mutableFloatStateOf(0f),
    val visibility: State<Float> = mutableFloatStateOf(0f),
    val lowPerfBreath: State<Float> = mutableFloatStateOf(1f),
)

internal val LocalFolioLoadingShimmer = staticCompositionLocalOf { FolioLoadingShimmerMotion() }

private val AlwaysFalseState: State<Boolean> = mutableStateOf(false)

@Composable
internal fun rememberFolioLoadingShimmerMotion(
    isTrackLoading: Boolean,
    switchSweepActive: State<Boolean> = AlwaysFalseState,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
): FolioLoadingShimmerMotion {
    val latestLoading = rememberUpdatedState(isTrackLoading)
    val progress = remember { Animatable(0f) }
    val visibility = remember { Animatable(0f) }
    val lowPerfBreath = remember { Animatable(1f) }
    val motion = remember {
        FolioLoadingShimmerMotion(
            progress = progress.asState(),
            visibility = visibility.asState(),
            lowPerfBreath = lowPerfBreath.asState(),
        )
    }

    LaunchedEffect(switchSweepActive, lowPerformance, reducedMotion) {
        val machine = FolioLoadingShimmerStateMachine()
        coroutineScope {
            var loopJob: Job? = null

            fun ensureLoopRunning() {
                if (loopJob?.isActive == true) return
                loopJob = launch {
                    if (lowPerformance || reducedMotion) {
                        if (reducedMotion) {
                            lowPerfBreath.snapTo(1f)
                        } else {
                            while (true) {
                                lowPerfBreath.animateTo(
                                    targetValue = FolioLoadingShimmerTiming.LOW_PERF_BREATH_MIN_FACTOR,
                                    animationSpec = tween(
                                        durationMillis = FolioLoadingShimmerTiming.LOW_PERF_BREATH_HALF_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                                lowPerfBreath.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = FolioLoadingShimmerTiming.LOW_PERF_BREATH_HALF_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }
                        }
                    } else {
                        while (true) {
                            progress.snapTo(0f)
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = FolioArcGlintSpec.Loading.sweepMs,
                                    easing = FolioArcGlintSpec.Loading.sweepEasing,
                                ),
                            )
                            delay(FolioArcGlintSpec.Loading.pauseMs.toLong())
                        }
                    }
                }
            }

            fun stopLoop() {
                loopJob?.cancel()
                loopJob = null
            }

            snapshotFlow { latestLoading.value to switchSweepActive.value }
                .collectLatest { (loading, switchActive) ->
                    while (true) {
                        val now = SystemClock.uptimeMillis()
                        val phase = machine.update(now, loading, switchActive)
                        when (phase) {
                            FolioLoadingShimmerPhase.Idle -> {
                                stopLoop()
                                visibility.snapTo(0f)
                                progress.snapTo(0f)
                                lowPerfBreath.snapTo(1f)
                                break
                            }
                            FolioLoadingShimmerPhase.PendingDelay -> {
                                stopLoop()
                                visibility.snapTo(0f)
                                val wait = machine.nextEventDelayMs(now) ?: break
                                delay(wait)
                            }
                            FolioLoadingShimmerPhase.Active -> {
                                ensureLoopRunning()
                                if (visibility.value < 0.99f) {
                                    visibility.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = if (reducedMotion) 0 else FolioLoadingShimmerTiming.FADE_IN_MS,
                                        ),
                                    )
                                }
                                val wait = machine.nextEventDelayMs(SystemClock.uptimeMillis()) ?: break
                                delay(wait)
                            }
                            FolioLoadingShimmerPhase.FadingOut -> {
                                visibility.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else FolioLoadingShimmerTiming.FADE_OUT_MS.toInt(),
                                        easing = MotionTokens.KaraokeGradientExitEasing,
                                    ),
                                )
                                machine.update(
                                    SystemClock.uptimeMillis() + FolioLoadingShimmerTiming.FADE_OUT_MS,
                                    isTrackLoading = false,
                                    switchActive = false,
                                )
                                stopLoop()
                                progress.snapTo(0f)
                                lowPerfBreath.snapTo(1f)
                                break
                            }
                        }
                    }
                }
        }
    }

    return motion
}

internal fun folioCoverLuminance(
    artworkPalette: List<Color>,
    fallback: Float = 0.25f,
): Float {
    if (artworkPalette.isEmpty()) return fallback
    val weights = floatArrayOf(0.55f, 0.30f, 0.15f)
    var weightSum = 0f
    var lumSum = 0f
    val count = minOf(artworkPalette.size, weights.size)
    for (i in 0 until count) {
        val w = weights[i]
        lumSum += artworkPalette[i].luminance() * w
        weightSum += w
    }
    return (lumSum / weightSum).coerceIn(0f, 1f)
}

/**
 * Shared API-30 arc glint overlay backed by a single cached large-radius [RadialGradient]
 * translated per frame via [Shader.setLocalMatrix] (zero per-frame Shader allocations, zero
 * recomposition, zero saveLayer).
 */
internal fun Modifier.folioArcGlint(
    progressState: State<Float>,
    visibilityState: State<Float>,
    shape: Shape,
    coverLuminance: Float,
    albumTint: Color?,
    lowPerformance: Boolean,
    spec: FolioArcGlintSpec,
    allowLowPerfBreathing: Boolean = false,
    lowPerfBreathState: State<Float>? = null,
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clipPath = outline.asPath()

    if (lowPerformance) {
        if (!allowLowPerfBreathing) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val overlayBase = if (coverLuminance > FOLIO_COVER_LIGHT_LUMINANCE_THRESHOLD) Color.Black else Color.White
        return@drawWithCache onDrawWithContent {
            drawContent()
            val vis = visibilityState.value
            if (vis <= 0.005f) return@onDrawWithContent
            val breath = lowPerfBreathState?.value ?: 1f
            val alpha = (FolioLoadingShimmerTiming.LOW_PERF_BREATH_ALPHA * breath * vis).coerceIn(0f, 1f)
            clipPath(clipPath) {
                drawRect(color = overlayBase.copy(alpha = alpha))
            }
        }
    }

    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    val radius = w * spec.arcRadiusRatio
    val bandHalf = w * (spec.bandWidthRatio * 0.5f)
    val outerR = radius + bandHalf
    val sStart = ((radius - bandHalf) / outerR).coerceIn(0f, 0.985f)
    val sMid = (radius / outerR).coerceIn(sStart + 0.005f, 0.992f)
    val isLightCover = coverLuminance > FOLIO_COVER_LIGHT_LUMINANCE_THRESHOLD

    val colors: IntArray
    val stops: FloatArray
    if (isLightCover) {
        val core = Color.White.copy(alpha = spec.lightCoreAlpha)
        val trailTint = (albumTint ?: Color.White).copy(alpha = spec.lightCoreAlpha * spec.edgeTintFactor)
        val leadingRim = Color.Black.copy(alpha = 0.14f)
        val sTrail = sStart + (sMid - sStart) * 0.38f
        val sCoreTrail = sStart + (sMid - sStart) * 0.76f
        val sCoreLead = sMid + (1f - sMid) * 0.16f
        val sLeadRim = sMid + (1f - sMid) * 0.64f
        colors = intArrayOf(
            Color.Transparent.toArgb(),
            trailTint.toArgb(),
            core.toArgb(),
            core.toArgb(),
            leadingRim.toArgb(),
            Color.Transparent.toArgb(),
        )
        stops = floatArrayOf(sStart, sTrail, sCoreTrail, sCoreLead, sLeadRim, 1f)
    } else {
        val core = Color.White.copy(alpha = spec.darkCoreAlpha)
        val baseTint = albumTint ?: Color.White
        val trailTint = if (spec.dispersionFringe) {
            lerp(baseTint, Color(0xFFBFE6FF), 0.35f).copy(alpha = spec.darkCoreAlpha * spec.edgeTintFactor)
        } else {
            baseTint.copy(alpha = spec.darkCoreAlpha * spec.edgeTintFactor)
        }
        val leadTint = if (spec.dispersionFringe) {
            lerp(baseTint, Color(0xFFFFE4F0), 0.30f).copy(alpha = spec.darkCoreAlpha * (spec.edgeTintFactor * 0.85f))
        } else {
            baseTint.copy(alpha = spec.darkCoreAlpha * spec.edgeTintFactor)
        }
        val sTrail = sStart + (sMid - sStart) * 0.38f
        val sCoreTrail = sStart + (sMid - sStart) * 0.78f
        val sCoreLead = sMid + (1f - sMid) * 0.22f
        val sLead = sMid + (1f - sMid) * 0.62f
        colors = intArrayOf(
            Color.Transparent.toArgb(),
            trailTint.toArgb(),
            core.toArgb(),
            core.toArgb(),
            leadTint.toArgb(),
            Color.Transparent.toArgb(),
        )
        stops = floatArrayOf(sStart, sTrail, sCoreTrail, sCoreLead, sLead, 1f)
    }

    val shader = RadialGradient(
        0f,
        h * 0.5f,
        outerR,
        colors,
        stops,
        Shader.TileMode.CLAMP,
    )
    val matrix = Matrix()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = shader
        if (!isLightCover) {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
    }
    val startCenterX = -outerR - w * 0.04f
    val endCenterX = w - (radius - bandHalf) + w * 0.04f

    onDrawWithContent {
        drawContent()
        val vis = visibilityState.value
        if (vis <= 0.005f) return@onDrawWithContent
        val p = progressState.value.coerceIn(0f, 1f)
        val cx = startCenterX + (endCenterX - startCenterX) * p
        matrix.setTranslate(cx, 0f)
        shader.setLocalMatrix(matrix)
        paint.alpha = (255f * vis).roundToInt().coerceIn(0, 255)
        clipPath(clipPath) {
            drawContext.canvas.nativeCanvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}

/**
 * Option 2B frosted-glass overlay for track switches:
 * - Pre-blurred 64px cover frost veil (falling back to a semi-transparent tint film before decode,
 *   never flashing black),
 * - Directional bevel highlight matching [FolioGlass.kt]'s `drawDirectionalGlassEdge`,
 * - Shared [FolioArcGlintSpec.TrackSwitch] arc glint sweep that "wipes the fog clear".
 *
 * Strictly a no-op when [lowPerformance] or [reducedMotion] is true.
 */
internal fun Modifier.folioCoverFrostedSwitchOverlay(
    motion: FolioTrackSwitchMotion,
    shape: Shape,
    isLight: Boolean,
    baseColor: Color,
    blurredCover: ImageBitmap?,
    coverLuminance: Float,
    albumTint: Color?,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
): Modifier {
    if (lowPerformance || reducedMotion) return this
    val frostAndBevel = this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clipPath = outline.asPath()
        val strokePx = 1.dp.toPx()
        val innerSize = Size(
            (size.width - strokePx).coerceAtLeast(1f),
            (size.height - strokePx).coerceAtLeast(1f),
        )
        val bevelOutline = shape.createOutline(innerSize, layoutDirection, this)
        val edgeSpec = resolveFolioGlassEdgeSpec(
            isLight = isLight,
            albumTint = albumTint,
            edgeBrightness = FolioGatherMotion.TRACK_EDGE_GATHER_MULTIPLIER,
        )
        val sheenBrush = folioDirectionalSheenBrush(edgeSpec, size)
        val rimBrush = folioDirectionalRimBrush(edgeSpec, innerSize)
        val tintFilmBase = if (isLight) {
            lerp(baseColor, Color.White, 0.55f)
        } else {
            lerp(baseColor, Color.White, 0.20f)
        }
        val crop = blurredCover?.let { bmp ->
            computeCenteredCropRect(
                srcWidth = bmp.width.toFloat(),
                srcHeight = bmp.height.toFloat(),
                dstWidth = size.width,
                dstHeight = size.height,
            )
        }

        onDrawWithContent {
            drawContent()
            val frost = motion.frostAlpha.value
            val rim = motion.glassRimAlpha.value
            if (frost <= 0.005f && rim <= 0.005f) return@onDrawWithContent

            clipPath(clipPath) {
                if (frost > 0.005f) {
                    if (blurredCover != null && crop != null) {
                        drawImage(
                            image = blurredCover,
                            dstOffset = IntOffset(crop[0].roundToInt(), crop[1].roundToInt()),
                            dstSize = IntSize(crop[2].roundToInt(), crop[3].roundToInt()),
                            alpha = frost.coerceIn(0f, 1f),
                        )
                        val veilAlpha = (if (isLight) 0.16f else 0.12f) * frost
                        drawRect(color = tintFilmBase.copy(alpha = veilAlpha.coerceIn(0f, 1f)))
                    } else {
                        val fallbackAlpha = (if (isLight) 0.42f else 0.34f) * frost
                        drawRect(color = tintFilmBase.copy(alpha = fallbackAlpha.coerceIn(0f, 1f)))
                    }
                }

                if (rim > 0.005f) {
                    val r = rim.coerceIn(0f, 1f)
                    drawOutline(outline = outline, brush = sheenBrush, alpha = r)
                    val lineY = (size.height - strokePx * 0.5f).coerceAtLeast(0f)
                    drawLine(
                        color = edgeSpec.bottomLine.copy(alpha = edgeSpec.bottomLine.alpha * r),
                        start = Offset(0f, lineY),
                        end = Offset(size.width, lineY),
                        strokeWidth = strokePx,
                    )
                    inset(strokePx * 0.5f) {
                        drawOutline(
                            outline = bevelOutline,
                            brush = rimBrush,
                            alpha = r,
                            style = Stroke(width = strokePx),
                        )
                    }
                }
            }
        }
    }

    return frostAndBevel.folioArcGlint(
        progressState = motion.glassSweepProgress,
        visibilityState = motion.glassSweepAlpha,
        shape = shape,
        coverLuminance = coverLuminance,
        albumTint = albumTint,
        lowPerformance = false,
        spec = FolioArcGlintSpec.TrackSwitch,
        allowLowPerfBreathing = false,
    )
}
