package dev.amatv.flyme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val WelcomeWallBackground = Color(0xFF08070A)

@Composable
internal fun rememberWelcomeFrostCapture(hasWall: Boolean): WelcomeFrostCapture {
    val wallLayer = rememberGraphicsLayer()
    val frostLayer = rememberGraphicsLayer()
    val capture = remember(wallLayer, frostLayer) { WelcomeFrostCapture(wallLayer, frostLayer) }
    val wallAvailable by rememberUpdatedState(hasWall)
    LaunchedEffect(capture) {
        // A cold opening can start while Browse loads its first wall rows.
        withTimeoutOrNull(500L) { snapshotFlow { wallAvailable }.first { it } }
        capture.takeSnapshot()
    }
    return capture
}

internal class WelcomeFrostCapture(
    private val wallLayer: GraphicsLayer,
    private val frostLayer: GraphicsLayer,
) {
    private val recorded = CompletableDeferred<Unit>()
    private var requested by mutableStateOf(false)
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    val wallModifier = Modifier.drawWithContent {
        if (requested && !recorded.isCompleted) {
            val wallSize = size
            val smallSize = IntSize(
                (wallSize.width.toInt() / 16).coerceAtLeast(1),
                (wallSize.height.toInt() / 16).coerceAtLeast(1),
            )
            wallLayer.record { this@drawWithContent.drawContent() }
            frostLayer.record(size = smallSize) {
                // Scale the recorded wall before readback to avoid a full-screen bitmap.
                drawRect(WelcomeWallBackground)
                scale(smallSize.width / wallSize.width, smallSize.height / wallSize.height, Offset.Zero) {
                    drawLayer(wallLayer)
                }
            }
            drawLayer(wallLayer)
            recorded.complete(Unit)
        } else {
            drawContent()
        }
    }

    suspend fun takeSnapshot() {
        requested = true
        recorded.await()
        val snapshot = frostLayer.toImageBitmap()
        bitmap = withContext(Dispatchers.Default) { blurWelcomeFrost(snapshot) }
    }
}

private fun blurWelcomeFrost(image: ImageBitmap): ImageBitmap {
    val width = image.width
    val height = image.height
    val source = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(width * height)
    val blurred = IntArray(pixels.size)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    source.recycle()

    for (y in 0 until height) {
        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (dy in -2..2) {
                val row = (y + dy).coerceIn(0, height - 1) * width
                for (dx in -2..2) {
                    val pixel = pixels[row + (x + dx).coerceIn(0, width - 1)]
                    red += (pixel shr 16) and 0xff
                    green += (pixel shr 8) and 0xff
                    blue += pixel and 0xff
                }
            }
            blurred[y * width + x] =
                (0xff shl 24) or ((red / 25) shl 16) or ((green / 25) shl 8) or (blue / 25)
        }
    }
    return Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
