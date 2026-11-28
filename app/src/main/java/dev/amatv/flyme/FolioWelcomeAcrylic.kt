package dev.amatv.flyme

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.amatv.R
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun WelcomeAcrylic(
    capture: WelcomeFrostCapture,
    onFinished: (skipped: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val timeline = remember(resources) {
        val bytes = resources.openRawResource(R.raw.folio_handwriting).use { it.readBytes() }
        WelcomeInkTimeline(parseWelcomeInk(bytes))
    }
    val frost = capture.bitmap
    val elapsed = remember { mutableFloatStateOf(0f) }
    val skipped = remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(frost, timeline, skipped.value) {
        if (skipped.value) {
            finish(true)
            return@LaunchedEffect
        }
        if (frost == null) return@LaunchedEffect
        val start = withFrameNanos { it }
        do {
            val time = withFrameNanos { (it - start) / 1_000_000f }
            elapsed.floatValue = time
        } while (elapsed.floatValue < timeline.durationMs)
        finish(false)
    }
    val tint = if (LocalFolioPalette.current.isLight) {
        Color.White.copy(alpha = 0.35f)
    } else {
        WelcomeWallBackground.copy(alpha = 0.45f)
    }
    val grain = remember { welcomeFrostGrain() }
    Box(
        modifier.fillMaxSize()
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = stringResource(R.string.welcome_skip),
                role = Role.Button,
                onClick = { skipped.value = true },
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val ink = WelcomeInkMask(timeline.strokes, size, 1.35.dp.toPx())
                val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
                onDrawBehind {
                    val frame = timeline.frameAt(elapsed.floatValue, skip = skipped.value)
                    if (!frame.finished) {
                        if (frost == null) {
                            drawRect(WelcomeWallBackground)
                        } else {
                            drawImage(frost, dstSize = destination, filterQuality = FilterQuality.Low)
                        }
                        drawRect(tint)
                        drawRect(grain)
                        if (frost != null) with(ink) { carve(frame) }
                    }
                }
            },
    )
}

private fun welcomeFrostGrain(): ShaderBrush {
    val random = Random(39)
    val pixels = IntArray(96 * 96) {
        val alpha = random.nextInt(12) shl 24
        alpha or if (random.nextBoolean()) 0xffffff else 0
    }
    val image = Bitmap.createBitmap(pixels, 96, 96, Bitmap.Config.ARGB_8888).asImageBitmap()
    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}

private class WelcomeInkMask(strokes: List<List<WelcomeInkPoint>>, size: Size, private val feather: Float) {
    private val writingWidth = size.width * 0.56f
    private val origin = Offset((size.width - writingWidth) / 2f, (size.height - writingWidth * 0.194f) / 2f)
    private val baseRadius = writingWidth * 0.008f
    private val points = strokes.map { stroke -> stroke.map(::brushPoint) }
    private val outlines = points.map { stroke ->
        Outline().apply {
            stamp(stroke.first())
            stroke.zipWithNext().forEach { (from, to) -> extend(from, to) }
        }
    }
    private val centerlines = Path().apply {
        points.forEach { stroke ->
            moveTo(stroke.first().position.x, stroke.first().position.y)
            stroke.drop(1).forEach { lineTo(it.position.x, it.position.y) }
        }
    }
    private val expansionRadius: Float
    private val completed = Outline()
    private val prefix = Outline()
    private val tipSegment = Outline()
    private var completedCount = 0
    private var prefixIndex = -1

    init {
        val centers = points.flatten()
        val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        expansionRadius = corners.maxOf { corner -> centers.minOf { (corner - it.position).getDistance() } }
    }

    fun DrawScope.carve(frame: WelcomeInkFrame) {
        while (completedCount < frame.completedStrokes) {
            completed.append(outlines[completedCount++])
            prefix.reset()
            prefixIndex = -1
        }
        val tip = frame.tip
        if (tip != null) {
            val stroke = points[frame.completedStrokes]
            if (prefixIndex == -1) {
                prefix.stamp(stroke.first())
                prefixIndex = 0
            }
            while (prefixIndex < tip.lastPointIndex) {
                prefix.extend(stroke[prefixIndex], stroke[prefixIndex + 1])
                prefixIndex++
            }
            tipSegment.reset()
            tipSegment.extend(stroke[prefixIndex], brushPoint(tip.point))
        }

        val growth = expansionRadius * frame.expansion
        if (growth > 0f) {
            drawGrowth(growth + feather, 0.36f)
            if (growth >= baseRadius * 1.25f) {
                drawGrowth(growth, 1f)
                return
            }
        }
        drawPath(completed.softEdge, Color.Black, alpha = 0.36f, blendMode = BlendMode.DstOut)
        if (tip != null) {
            drawPath(prefix.softEdge, Color.Black, alpha = 0.36f, blendMode = BlendMode.DstOut)
            drawPath(tipSegment.softEdge, Color.Black, alpha = 0.36f, blendMode = BlendMode.DstOut)
        }
        if (growth > 0f) drawGrowth(growth, 1f)
        drawPath(completed.cut, Color.Black, blendMode = BlendMode.DstOut)
        if (tip != null) {
            drawPath(prefix.cut, Color.Black, blendMode = BlendMode.DstOut)
            drawPath(tipSegment.cut, Color.Black, blendMode = BlendMode.DstOut)
        }
    }

    private fun DrawScope.drawGrowth(radius: Float, alpha: Float) {
        drawPath(
            centerlines,
            Color.Black,
            alpha = alpha,
            style = Stroke(width = radius * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.DstOut,
        )
    }

    private fun brushPoint(point: WelcomeInkPoint) = BrushPoint(
        origin + Offset(point.x * writingWidth, point.y * writingWidth),
        baseRadius * point.widthScale,
    )

    private data class BrushPoint(val position: Offset, val radius: Float)

    private inner class Outline {
        val cut = Path()
        val softEdge = Path()

        fun append(outline: Outline) {
            cut.addPath(outline.cut)
            softEdge.addPath(outline.softEdge)
        }

        fun reset() {
            cut.rewind()
            softEdge.rewind()
        }

        fun stamp(point: BrushPoint) {
            cut.addOval(Rect(point.position, point.radius), Path.Direction.Clockwise)
            softEdge.addOval(Rect(point.position, point.radius + feather), Path.Direction.Clockwise)
        }

        fun extend(from: BrushPoint, to: BrushPoint) {
            val delta = to.position - from.position
            val distance = delta.getDistance()
            if (distance == 0f) return
            val normal = Offset(-delta.y, delta.x) / distance
            cut.addStrip(from, to, normal, 0f)
            softEdge.addStrip(from, to, normal, feather)
            stamp(to)
        }

        // Keep the strips and round joins wound in the same direction so overlapping parts fill.
        private fun Path.addStrip(from: BrushPoint, to: BrushPoint, normal: Offset, extra: Float) {
            val start = normal * (from.radius + extra)
            val end = normal * (to.radius + extra)
            val a = from.position + start
            val b = from.position - start
            val c = to.position - end
            val d = to.position + end
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            lineTo(d.x, d.y)
            close()
        }
    }
}
