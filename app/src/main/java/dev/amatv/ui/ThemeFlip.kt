package dev.amatv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Light/dark changes as a desk-calendar page of frosted glass: the sheet comes over the top edge,
 * falls faster and faster, lands with a small bounce and settles, filling with the new page color
 * on the way; then the UI underneath switches and the glass dissolves. Android 11 has no live
 * blur, so the glass shows a blurred, downscaled snapshot of the screen taken as the flip starts.
 */
@Composable
internal fun FlymeThemeFlip(targetLight: Boolean, content: @Composable (light: Boolean) -> Unit) {
    var shownLight by remember { mutableStateOf(targetLight) }
    var sheetLight by remember { mutableStateOf(targetLight) }
    var flipping by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val angle = remember { Animatable(0f) }
    val tint = remember { Animatable(0f) }
    val glass = remember { Animatable(0f) }
    val sheen = remember { Animatable(0f) }
    val view = LocalView.current

    LaunchedEffect(targetLight) {
        if (targetLight == shownLight) {
            flipping = false
            return@LaunchedEffect
        }
        try {
            sheetLight = targetLight
            snapshot = captureBlurred(view)
            angle.snapTo(FALL_FROM_DEGREES)
            tint.snapTo(0.06f)
            glass.snapTo(0.4f)
            sheen.snapTo(0.2f)
            flipping = true
            coroutineScope {
                launch {
                    angle.animateTo(0f, keyframes {
                        durationMillis = LAND_MS
                        FALL_FROM_DEGREES at 0 using CubicBezierEasing(0.5f, 0f, 0.9f, 0.4f)
                        0f at 626 using CubicBezierEasing(0.2f, 0.6f, 0.4f, 1f)
                        BOUNCE_DEGREES at 778 using CubicBezierEasing(0.5f, 0f, 0.8f, 0.6f)
                        0f at 907 using CubicBezierEasing(0.2f, 0.6f, 0.4f, 1f)
                        2f at 994 using CubicBezierEasing(0.5f, 0f, 0.8f, 0.6f)
                    })
                }
                launch {
                    tint.animateTo(0.96f, keyframes {
                        durationMillis = LAND_MS
                        0.06f at 0
                        0.3f at 300
                        0.88f at 630
                    })
                }
                launch { glass.animateTo(1f, tween(630)) }
                launch {
                    sheen.animateTo(0f, keyframes {
                        durationMillis = LAND_MS
                        0.2f at 0
                        0.9f at 630
                        0.25f at 900
                    })
                }
            }
            // The glass lies flat: switch the page underneath, then let the glass dissolve.
            shownLight = targetLight
            coroutineScope {
                launch { tint.animateTo(0f, tween(DISSOLVE_MS)) }
                launch { glass.animateTo(0f, tween(DISSOLVE_MS)) }
            }
        } finally {
            shownLight = targetLight
            flipping = false
            snapshot = null
        }
    }

    // Dark status and navigation bar icons on the light UI.
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = shownLight
            isAppearanceLightNavigationBars = shownLight
        }
    }

    Box(Modifier.fillMaxSize()) {
        content(shownLight)
        if (flipping) {
            val page = (if (sheetLight) LightFlymePalette else DarkFlymePalette).page
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    rotationX = -angle.value
                    cameraDistance = 10f * density
                },
            ) {
                snapshot?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = glass.value })
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = tint.value }.background(page))
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = sheen.value }.background(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.45f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f),
                )))
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = glass.value }
                    .border(1.dp, Color.White.copy(alpha = 0.35f)))
            }
        }
    }
}

/** A small, blurred copy of the window; null when the system cannot copy it. */
private suspend fun captureBlurred(view: android.view.View): ImageBitmap? {
    val window = (view.context as? Activity)?.window ?: return null
    if (view.width <= 0 || view.height <= 0) return null
    val bitmap = Bitmap.createBitmap((view.width / SNAPSHOT_SCALE).coerceAtLeast(1),
        (view.height / SNAPSHOT_SCALE).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val copied = suspendCancellableCoroutine { continuation ->
        runCatching {
            PixelCopy.request(window, bitmap, { result -> continuation.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()))
        }.onFailure { continuation.resume(false) }
    }
    if (!copied) return null
    return withContext(Dispatchers.Default) { boxBlur(bitmap, radius = 3, passes = 3).asImageBitmap() }
}

/** Repeated box blur (about a Gaussian) on a small bitmap; cheap enough for one frame. */
private fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = source.width
    val h = source.height
    var pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
    var scratch = IntArray(w * h)
    repeat(passes) {
        blurLine(pixels, scratch, w, h, horizontal = true, radius)
        blurLine(scratch, pixels, w, h, horizontal = false, radius)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

private fun blurLine(input: IntArray, output: IntArray, w: Int, h: Int, horizontal: Boolean, radius: Int) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) input[line * w + c] else input[c * w + line]
        }
        var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) {
            val p = at(i); r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            val out = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) output[line * w + i] = out else output[i * w + line] = out
            val add = at(i + radius + 1); val remove = at(i - radius)
            r += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
            b += (add and 0xFF) - (remove and 0xFF)
        }
    }
}

private const val FALL_FROM_DEGREES = 100f
private const val BOUNCE_DEGREES = 9f
private const val LAND_MS = 1080
private const val DISSOLVE_MS = 420
private const val SNAPSHOT_SCALE = 8
