package dev.amatv.flyme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun WelcomeRibbonLayer(
    target: WelcomeAuraTarget,
    motionEnabled: Boolean,
    open: Boolean,
    dragOffsetPx: Float,
    gatherScale: Float,
    ridgeAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val light = LocalFolioPalette.current.isLight
    // Keep the white welcome typography legible in both app themes.
    val base = if (light) Color(0xFF353648) else Color(0xFF12132D)
    val tint = if (light) Color(0xFFC2C5DB) else Color(0xFFA7A9E2)
    val primary = lerp(target.primaryColor, tint, if (light) 0.64f else 0.35f)
    val secondary = lerp(target.secondaryColor, tint, if (light) 0.64f else 0.35f)

    if (motionEnabled) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val colorA by animateColorAsState(primary, tween(1_400), label = "ribbon-primary")
        val colorB by animateColorAsState(secondary, tween(1_400), label = "ribbon-secondary")
        val opacity by animateFloatAsState(target.alpha, tween(1_100), label = "ribbon-opacity")
        key(lifecycle) {
            AndroidView(
                factory = { FolioRibbonBackdropView(it, lifecycle) },
                modifier = modifier.fillMaxSize(),
                onRelease = FolioRibbonBackdropView::release,
                update = { view ->
                    view.update(
                        RibbonFrame(base, colorA, colorB, opacity, gatherScale, ridgeAlpha, dragOffsetPx),
                        open,
                    )
                },
            )
        }
    } else {
        StaticRibbonBackdrop(
            RibbonFrame(base, primary, secondary, target.alpha, gatherScale, ridgeAlpha, dragOffsetPx),
            modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun StaticRibbonBackdrop(frame: RibbonFrame, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val home = frame.preset != RibbonPreset.Welcome
        val bandCount = if (home) 32 else 8
        val drawSize = if (home) {
            val scale = (480f / size.width).coerceAtMost(1f)
            Size(
                (size.width * scale).roundToInt().coerceAtLeast(1).toFloat(),
                (size.height * scale).roundToInt().coerceAtLeast(1).toFloat(),
            )
        } else size
        val bands = welcomeRibbons.flatMap { ribbon ->
            val color = lerp(frame.primary, frame.secondary, ribbon.colorMix)
            // Nested translucent paths approximate the shader's distance falloff in a cached still.
            (0 until bandCount).map { band ->
                val feather = ribbon.feather * frame.preset.featherScale
                val radius = 1f + 0.4f * feather - (band + 0.5f) / bandCount * 1.4f * feather
                val path = Path().apply {
                    for (i in 0..64) {
                        val x = i / 64f
                        val y = ribbon.center(x) - ribbon.width * radius
                        if (i == 0) moveTo(0f, y * drawSize.height) else lineTo(x * drawSize.width, y * drawSize.height)
                    }
                    for (i in 64 downTo 0) {
                        val x = i / 64f
                        val y = ribbon.center(x) + ribbon.width * radius
                        lineTo(x * drawSize.width, y * drawSize.height)
                    }
                    close()
                }
                val stops = (0..8).map { i ->
                    val x = i / 8f
                    val light = ribbon.lightAt(x)
                    val strength = ribbon.opacity * light * frame.bandStrength
                    val outer = strength * smoothstep(band.toFloat() / bandCount)
                    val inner = strength * smoothstep((band + 1f) / bandCount)
                    val alpha = (inner - outer) / (1f - outer)
                    val bandColor = if (!home) {
                        color.copy(alpha = alpha)
                    } else {
                        val shade = 0.76f + 0.24f * light
                        Color(color.red * shade, color.green * shade, color.blue * shade, alpha)
                    }
                    x to bandColor
                }.toTypedArray()
                path to Brush.horizontalGradient(*stops, endX = drawSize.width)
            }
        }
        val drawBands: DrawScope.() -> Unit = {
            drawRect(frame.base.copy(alpha = frame.preset.baseAlpha))
            for ((path, brush) in bands) drawPath(path, brush)
        }
        if (home) {
            // Rasterize the finer feathering once. The low-performance path draws just this image.
            val image = ImageBitmap(drawSize.width.toInt(), drawSize.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, Canvas(image), drawSize, block = drawBands)
            onDrawBehind {
                drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        } else {
            onDrawBehind(drawBands)
        }
    })
}

internal data class RibbonFrame(
    val base: Color,
    val primary: Color,
    val secondary: Color,
    val opacity: Float,
    val gatherScale: Float,
    val ridgeAlpha: Float,
    val dragOffsetPx: Float,
    val preset: RibbonPreset = RibbonPreset.Welcome,
) {
    val bandStrength: Float
        get() = (0.61f + 0.39f * opacity) * preset.brightness
}

internal enum class RibbonPreset(
    val brightness: Float,
    val crestStrength: Float,
    val featherScale: Float,
    val periodScale: Float,
    val baseAlpha: Float,
) {
    Welcome(1f, 1f, 1f, 1f, 0.86f),
    Home(0.85f, 0.80f, 1.45f, 1.5f, 1f),
    HomeLight(0.66f, 0.35f, 1.45f, 1.5f, 1f),
}

private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

internal data class WelcomeRibbon(
    val height: Float,
    val phase: Float,
    val slope: Float,
    val width: Float,
    val feather: Float,
    val opacity: Float,
    val period: Float,
    val colorMix: Float,
) {
    fun center(x: Float): Float = height + slope * (x - 0.5f) +
        0.098f * sin(5.4f * x + phase) + 0.031f * sin(10f * x - phase) + 0.024f * sin(phase)

    fun lightAt(x: Float): Float {
        val side = ((abs(x - 0.5f) - 0.10f) / 0.35f).coerceIn(0f, 1f)
        return (0.64f + 0.36f * cos(5.3f * x + phase)) * (0.46f + 0.54f * smoothstep(side))
    }
}

// Back to front, with distinct motion periods and progressively narrower edges.
internal val welcomeRibbons = listOf(
    WelcomeRibbon(0.20f, 0.35f, -0.16f, 0.117f, 0.94f, 0.43f, 20f, 0.88f),
    WelcomeRibbon(0.81f, 3.30f, -0.16f, 0.124f, 0.88f, 0.51f, 18f, 0.20f),
    WelcomeRibbon(0.27f, 2.10f, 0.12f, 0.072f, 0.74f, 0.68f, 16f, 0.30f),
    WelcomeRibbon(0.76f, -0.55f, 0.17f, 0.083f, 0.64f, 0.77f, 14f, 0.79f),
)
