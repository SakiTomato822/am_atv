package dev.amatv.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws the current lyric sharply and softens inactive rows.
 *
 * [contentKey] must identify the row's text and all pixel-affecting style choices
 * (for example text, font size/weight, color bucket and line identity). The
 * modifier also keys the raster by measured pixel size, density, font scale and
 * layout direction. Keeping the key stable lets API 28-30 reuse a captured row
 * when its blur radius changes. It waits for the row to remain blur-eligible
 * through its outgoing animation before taking the static snapshot.
 *
 * API 31+ uses Compose's RenderEffect blur. API 28-30 captures the row once per
 * key, downsamples it to a bounded raster, then applies a CPU box blur only when
 * the blur-radius bucket changes. Layout measurement is unaffected.
 */
@Composable
internal fun CarLyricSoftFocus(
    radius: Dp,
    contentKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val safeRadius = radius.value
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, CAR_LYRIC_SOFT_FOCUS_MAX_DP)
        ?.dp ?: 0.dp

    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val focusedModifier = if (safeRadius.value > 0f) {
                modifier.blur(
                    radius = safeRadius,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                )
            } else {
                modifier
            }
            Box(modifier = focusedModifier, content = content)
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            CarLyricRasterSoftFocus(
                radius = safeRadius,
                contentKey = contentKey,
                modifier = modifier,
                content = content,
            )
        }

        else -> Box(modifier = modifier, content = content)
    }
}

@Composable
private fun CarLyricRasterSoftFocus(
    radius: Dp,
    contentKey: Any,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val graphicsLayer = rememberGraphicsLayer()
    val cache = remember { CarLyricRasterCache() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val blurEligible = radius.value > 0f
    val eligibilityKey = remember(
        contentKey,
        density.density,
        density.fontScale,
        layoutDirection,
        blurEligible,
    ) { Any() }
    var captureReadyKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(eligibilityKey) {
        captureReadyKey = null
        if (blurEligible) {
            // Karaoke alpha/scale exit can run for 150ms delay + 800ms duration.
            // Keep drawing the live row until that transition has settled, then
            // capture it once so the cached bitmap cannot freeze a lit glyph.
            delay(CAR_LYRIC_CAPTURE_SETTLE_MILLIS)
            captureReadyKey = eligibilityKey
        }
    }
    val captureReady = blurEligible && captureReadyKey === eligibilityKey

    LaunchedEffect(cache, graphicsLayer) {
        launch {
            cache.captureRequests.receiveAsFlow().collectLatest { request ->
                try {
                    withFrameNanos { }
                    val image = graphicsLayer.toImageBitmap()
                    val pixels = withContext(Dispatchers.Default) {
                        IntArray(image.width * image.height).also { captured ->
                            image.readPixels(captured)
                            check(captured.any { pixel -> pixel ushr 24 != 0 }) {
                                "GraphicsLayer snapshot contained no visible pixels"
                            }
                        }
                    }
                    if (cache.isCurrentContent(request.key, request.generation)) {
                        cache.source.set(
                            CarLyricSourceRaster(
                                key = request.key,
                                pixels = pixels,
                                width = image.width,
                                height = image.height,
                                scaleX = request.geometry.scaleX,
                                scaleY = request.geometry.scaleY,
                                paddingPx = request.geometry.paddingPx,
                            ),
                        )
                        cache.invalidateDraw()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (cache.onCaptureFailure(request)) {
                        delay(CAR_LYRIC_CAPTURE_RETRY_DELAY_MILLIS)
                        if (cache.releaseCaptureForRetry(request)) cache.invalidateDraw()
                    }
                    // Bounded retry; after repeated failure keep the original row visible.
                }
            }
        }
        launch {
            cache.blurRequests.receiveAsFlow().collectLatest { request ->
                try {
                    val blurred = withContext(Dispatchers.Default) {
                        val pixels = boxBlurArgb8888(
                            source = request.source.pixels,
                            width = request.source.width,
                            height = request.source.height,
                            radius = request.radiusPx,
                        )
                        Bitmap.createBitmap(
                            request.source.width,
                            request.source.height,
                            Bitmap.Config.ARGB_8888,
                        ).apply {
                            setPixels(
                                pixels,
                                0,
                                request.source.width,
                                0,
                                0,
                                request.source.width,
                                request.source.height,
                            )
                        }.asImageBitmap()
                    }
                    if (cache.isCurrentBlur(request)) {
                        cache.rendered.set(
                            CarLyricRenderedRaster(
                                key = request.source.key,
                                radiusPx = request.radiusPx,
                                image = blurred,
                            ),
                        )
                        cache.invalidateDraw()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed rasterization must not hide the original lyrics.
                }
            }
        }
    }

    DisposableEffect(cache) {
        onDispose { cache.dispose() }
    }

    val focusModifier = modifier.drawWithContent {
        // Reading snapshot state here invalidates only this draw node when a
        // completed capture/blur arrives; no layout pass or per-frame readback.
        cache.drawRevision

        val widthPx = size.width.roundToInt().coerceAtLeast(0)
        val heightPx = size.height.roundToInt().coerceAtLeast(0)
        if (widthPx == 0 || heightPx == 0) {
            drawContent()
            return@drawWithContent
        }

        val key = CarLyricCaptureKey(
            contentKey = contentKey,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density.density,
            fontScale = density.fontScale,
            layoutDirection = layoutDirection,
        )
        val isBlurEligible = radius.value > 0f
        val generation = cache.activateContent(key, isBlurEligible)
        if (!isBlurEligible) {
            drawContent()
            return@drawWithContent
        }

        val source = cache.source.get()?.takeIf { it.key == key }
        val render = cache.rendered.get()?.takeIf { it.key == key }
        if (render != null && source != null) {
            val offsetX = -source.paddingPx / source.scaleX
            val offsetY = -source.paddingPx / source.scaleY
            val drawWidth = (source.width / source.scaleX).roundToInt()
            val drawHeight = (source.height / source.scaleY).roundToInt()
            drawImage(
                image = render.image,
                dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                dstSize = IntSize(drawWidth, drawHeight),
                filterQuality = FilterQuality.Medium,
            )
        } else {
            // Show the original immediately while the first raster is prepared.
            drawContent()
        }

        if (source == null) {
            if (captureReady) {
                val geometry = carLyricRasterGeometry(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    density = density.density,
                )
                if (cache.markCaptureRequested(key)) {
                    val targetSize = IntSize(geometry.widthPx, geometry.heightPx)
                    // Use the DrawScope extension, which retargets the outer
                    // ContentDrawScope canvas to this GraphicsLayer. Calling
                    // the explicit density/layoutDirection member overload
                    // leaves drawContent() bound to the onscreen canvas and
                    // can produce an empty snapshot on API 30.
                    graphicsLayer.record(size = targetSize) {
                        translate(geometry.paddingPx.toFloat(), geometry.paddingPx.toFloat()) {
                            scale(geometry.scaleX, geometry.scaleY, pivot = Offset.Zero) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
                    cache.captureRequests.trySend(
                        CarLyricCaptureRequest(
                            key = key,
                            generation = generation,
                            geometry = geometry,
                        ),
                    )
                }
            }
        } else {
            val radiusPx = carLyricRasterBlurRadius(
                radiusDp = radius.value,
                density = density.density,
                source = source,
            )
            val request = CarLyricBlurRequest(source, radiusPx)
            val renderedForRadius = render?.radiusPx == radiusPx
            if (cache.setBlurTarget(request) && !renderedForRadius) {
                cache.blurRequests.trySend(request)
            }
        }
    }

    Box(modifier = focusModifier, content = content)
}

private class CarLyricRasterCache {
    val captureRequests = Channel<CarLyricCaptureRequest>(Channel.CONFLATED)
    val blurRequests = Channel<CarLyricBlurRequest>(Channel.CONFLATED)
    val source = AtomicReference<CarLyricSourceRaster?>(null)
    val rendered = AtomicReference<CarLyricRenderedRaster?>(null)
    private val activeKey = AtomicReference<CarLyricCaptureKey?>(null)
    private val captureRequested = AtomicReference<CarLyricCaptureKey?>(null)
    private val blurRequested = AtomicReference<CarLyricBlurRequest?>(null)
    private val contentGeneration = AtomicLong(0)
    private val blurEligible = AtomicBoolean(false)
    private val captureFailures = AtomicInteger(0)

    var drawRevision by mutableIntStateOf(0)
        private set

    /** Called from drawing; only atomic cache bookkeeping is changed here. */
    fun activateContent(key: CarLyricCaptureKey, isBlurEligible: Boolean): Long {
        val keyChanged = activeKey.get() != key
        val becameIneligible = blurEligible.getAndSet(isBlurEligible) && !isBlurEligible
        if (keyChanged || becameIneligible) {
            contentGeneration.incrementAndGet()
            activeKey.set(key)
            captureRequested.set(null)
            blurRequested.set(null)
            source.set(null)
            rendered.set(null)
            captureFailures.set(0)
        }
        return contentGeneration.get()
    }

    fun onCaptureFailure(request: CarLyricCaptureRequest): Boolean {
        if (!isCurrentContent(request.key, request.generation)) return false
        return captureFailures.incrementAndGet() <= CAR_LYRIC_CAPTURE_MAX_RETRIES
    }

    fun releaseCaptureForRetry(request: CarLyricCaptureRequest): Boolean {
        if (!isCurrentContent(request.key, request.generation)) return false
        return captureRequested.compareAndSet(request.key, null)
    }

    fun markCaptureRequested(key: CarLyricCaptureKey): Boolean =
        captureRequested.compareAndSet(null, key)

    fun setBlurTarget(request: CarLyricBlurRequest): Boolean {
        val previous = blurRequested.getAndSet(request)
        if (previous == request) return false
        return true
    }

    fun isCurrentContent(key: CarLyricCaptureKey, generation: Long): Boolean =
        activeKey.get() == key && contentGeneration.get() == generation

    fun isCurrentBlur(request: CarLyricBlurRequest): Boolean =
        activeKey.get() == request.source.key && blurRequested.get() == request

    fun invalidateDraw() {
        drawRevision++
    }

    fun dispose() {
        contentGeneration.incrementAndGet()
        activeKey.set(null)
        captureRequested.set(null)
        blurRequested.set(null)
        blurEligible.set(false)
        captureFailures.set(0)
        source.set(null)
        rendered.set(null)
        captureRequests.close()
        blurRequests.close()
    }
}

private data class CarLyricCaptureKey(
    val contentKey: Any,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection,
)

internal data class CarLyricRasterGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val paddingPx: Int,
    val scaleX: Float,
    val scaleY: Float,
)

private data class CarLyricCaptureRequest(
    val key: CarLyricCaptureKey,
    val generation: Long,
    val geometry: CarLyricRasterGeometry,
)

private data class CarLyricSourceRaster(
    val key: CarLyricCaptureKey,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val scaleX: Float,
    val scaleY: Float,
    val paddingPx: Int,
)

private data class CarLyricRenderedRaster(
    val key: CarLyricCaptureKey,
    val radiusPx: Int,
    val image: ImageBitmap,
)

private data class CarLyricBlurRequest(
    val source: CarLyricSourceRaster,
    val radiusPx: Int,
)

/** Converts a text row to a raster no larger than the per-row pixel budget. */
internal fun carLyricRasterGeometry(
    widthPx: Int,
    heightPx: Int,
    density: Float,
): CarLyricRasterGeometry {
    require(widthPx > 0 && heightPx > 0)
    require(density.isFinite() && density > 0f)

    val supportAtFullScale = CAR_LYRIC_SOFT_FOCUS_MAX_DP * density
    var scale = min(
        1f,
        min(
            sqrt(
                CAR_LYRIC_RASTER_MAX_PIXELS.toFloat() /
                    ((widthPx + 2f * supportAtFullScale) *
                        (heightPx + 2f * supportAtFullScale)).coerceAtLeast(1f),
            ),
            CAR_LYRIC_RASTER_MAX_SIDE / max(widthPx, heightPx).toFloat(),
        ),
    )
    scale = scale.coerceIn(0.0001f, 1f)

    var contentWidth: Int
    var contentHeight: Int
    var padding: Int
    var targetWidth: Int
    var targetHeight: Int
    var attempts = 0
    while (true) {
        contentWidth = max(1, floor(widthPx * scale).toInt())
        contentHeight = max(1, floor(heightPx * scale).toInt())
        padding = ceil(supportAtFullScale * scale).toInt().coerceAtLeast(1)
        targetWidth = contentWidth + padding * 2
        targetHeight = contentHeight + padding * 2
        if (targetWidth.toLong() * targetHeight <= CAR_LYRIC_RASTER_MAX_PIXELS &&
            max(targetWidth, targetHeight) <= CAR_LYRIC_RASTER_MAX_SIDE
        ) {
            break
        }
        scale *= 0.9f
        attempts++
        check(attempts < 128) { "Could not fit lyric raster within its pixel budget" }
    }

    return CarLyricRasterGeometry(
        widthPx = targetWidth,
        heightPx = targetHeight,
        paddingPx = padding,
        scaleX = contentWidth.toFloat() / widthPx,
        scaleY = contentHeight.toFloat() / heightPx,
    )
}

/** One true two-axis box convolution, with out-of-bounds pixels treated as transparent. */
internal fun boxBlurArgb8888(
    source: IntArray,
    width: Int,
    height: Int,
    radius: Int,
): IntArray {
    require(width > 0 && height > 0)
    val requestedPixelCount = width.toLong() * height
    require(requestedPixelCount <= source.size)
    require(radius >= 0)
    if (radius == 0) return source.copyOf(width * height)

    val pixelCount = requestedPixelCount.toInt()
    val horizontal = IntArray(pixelCount)
    val output = IntArray(pixelCount)
    blurHorizontal(source, horizontal, width, height, radius)
    blurVertical(horizontal, output, width, height, radius)
    return output
}

private fun blurHorizontal(source: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (x in -radius..radius) {
            if (x in 0 until width) {
                val color = source[y * width + x]
                val alpha = color ushr 24
                a += alpha
                r += premultipliedChannel(color ushr 16 and 0xff, alpha)
                g += premultipliedChannel(color ushr 8 and 0xff, alpha)
                b += premultipliedChannel(color and 0xff, alpha)
            }
        }
        for (x in 0 until width) {
            output[y * width + x] = packAverage(a, r, g, b, window)
            val removeX = x - radius
            if (removeX in 0 until width) {
                val color = source[y * width + removeX]
                val alpha = color ushr 24
                a -= alpha
                r -= premultipliedChannel(color ushr 16 and 0xff, alpha)
                g -= premultipliedChannel(color ushr 8 and 0xff, alpha)
                b -= premultipliedChannel(color and 0xff, alpha)
            }
            val addX = x + radius + 1
            if (addX in 0 until width) {
                val color = source[y * width + addX]
                val alpha = color ushr 24
                a += alpha
                r += premultipliedChannel(color ushr 16 and 0xff, alpha)
                g += premultipliedChannel(color ushr 8 and 0xff, alpha)
                b += premultipliedChannel(color and 0xff, alpha)
            }
        }
    }
}

private fun blurVertical(source: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (y in -radius..radius) {
            if (y in 0 until height) {
                val color = source[y * width + x]
                val alpha = color ushr 24
                a += alpha
                r += premultipliedChannel(color ushr 16 and 0xff, alpha)
                g += premultipliedChannel(color ushr 8 and 0xff, alpha)
                b += premultipliedChannel(color and 0xff, alpha)
            }
        }
        for (y in 0 until height) {
            output[y * width + x] = packAverage(a, r, g, b, window)
            val removeY = y - radius
            if (removeY in 0 until height) {
                val color = source[removeY * width + x]
                val alpha = color ushr 24
                a -= alpha
                r -= premultipliedChannel(color ushr 16 and 0xff, alpha)
                g -= premultipliedChannel(color ushr 8 and 0xff, alpha)
                b -= premultipliedChannel(color and 0xff, alpha)
            }
            val addY = y + radius + 1
            if (addY in 0 until height) {
                val color = source[addY * width + x]
                val alpha = color ushr 24
                a += alpha
                r += premultipliedChannel(color ushr 16 and 0xff, alpha)
                g += premultipliedChannel(color ushr 8 and 0xff, alpha)
                b += premultipliedChannel(color and 0xff, alpha)
            }
        }
    }
}

private fun premultipliedChannel(channel: Int, alpha: Int): Int = (channel * alpha + 127) / 255

private fun packAverage(a: Int, r: Int, g: Int, b: Int, divisor: Int): Int {
    val averageAlpha = a / divisor
    if (averageAlpha == 0 || a == 0) return 0
    val straightRed = (r * 255 / a).coerceIn(0, 255)
    val straightGreen = (g * 255 / a).coerceIn(0, 255)
    val straightBlue = (b * 255 / a).coerceIn(0, 255)
    return (averageAlpha shl 24) or
        (straightRed shl 16) or
        (straightGreen shl 8) or
        straightBlue
}

private fun carLyricRasterBlurRadius(
    radiusDp: Float,
    density: Float,
    source: CarLyricSourceRaster,
): Int {
    val scale = (source.scaleX + source.scaleY) / 2f
    return ceil(radiusDp * density * scale)
        .toInt()
        .coerceIn(1, source.paddingPx)
}

/**
 * Converts distance from the current lyric line into a bounded blur radius.
 * Key this to active-line changes, not the continuously advancing playback time.
 */
internal fun carLyricSoftFocusRadiusDp(distanceInLines: Int): Float =
    (distanceInLines.coerceAtLeast(0) * CAR_LYRIC_SOFT_FOCUS_STEP_DP)
        .coerceAtMost(CAR_LYRIC_SOFT_FOCUS_MAX_DP)

/** Returns distance to the closest active line, including overlapping duet lines. */
internal fun carLyricDistanceFromActiveLines(lineIndex: Int, activeLineIndices: Set<Int>): Int =
    activeLineIndices.minOfOrNull { kotlin.math.abs(lineIndex - it) } ?: 0

internal const val CAR_LYRIC_SOFT_FOCUS_STEP_DP = 2.5f
internal const val CAR_LYRIC_SOFT_FOCUS_MAX_DP = 10f
internal const val CAR_LYRIC_RASTER_MAX_PIXELS = 65_536
internal const val CAR_LYRIC_RASTER_MAX_SIDE = 1_024
internal const val CAR_LYRIC_CAPTURE_SETTLE_MILLIS = 1_000L
internal const val CAR_LYRIC_CAPTURE_RETRY_DELAY_MILLIS = 100L
internal const val CAR_LYRIC_CAPTURE_MAX_RETRIES = 1

internal enum class CarLyricSoftFocusPath {
    None,
    RasterizedCpuBlur,
    GraphicsLayerBlur,
    Unsupported,
}

/** Kept pure so API-level behavior can be verified without a device renderer. */
internal fun carLyricSoftFocusPath(apiLevel: Int, radiusDp: Float): CarLyricSoftFocusPath = when {
    radiusDp <= 0f -> CarLyricSoftFocusPath.None
    apiLevel >= Build.VERSION_CODES.S -> CarLyricSoftFocusPath.GraphicsLayerBlur
    apiLevel >= Build.VERSION_CODES.P -> CarLyricSoftFocusPath.RasterizedCpuBlur
    else -> CarLyricSoftFocusPath.Unsupported
}
