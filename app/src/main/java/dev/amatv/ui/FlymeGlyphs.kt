package dev.amatv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Solid transport glyphs with softened corners, drawn in the weight Apple Music uses.
 * Material's rounded set reads thin and Android-like at car sizes.
 */
internal object FlymeGlyphs {
    val Play: ImageVector by lazy {
        glyph("FlymePlay") {
            moveTo(8.3f, 4.2f)
            curveTo(7.3f, 3.6f, 6.2f, 4.3f, 6.2f, 5.4f)
            lineTo(6.2f, 18.6f)
            curveTo(6.2f, 19.7f, 7.3f, 20.4f, 8.3f, 19.8f)
            lineTo(19.2f, 13.2f)
            curveTo(20.1f, 12.6f, 20.1f, 11.4f, 19.2f, 10.8f)
            close()
        }
    }

    val Pause: ImageVector by lazy {
        glyph("FlymePause") {
            roundedBar(5.8f)
            roundedBar(13.6f)
        }
    }

    val Forward: ImageVector by lazy { glyph("FlymeForward") { twin(mirror = false) } }
    val Backward: ImageVector by lazy { glyph("FlymeBackward") { twin(mirror = true) } }

    private fun glyph(name: String, body: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White), pathBuilder = body).build()

    private fun PathBuilder.roundedBar(left: Float) {
        val right = left + 4.6f
        moveTo(left + 1.4f, 4.4f)
        lineTo(right - 1.4f, 4.4f)
        quadTo(right, 4.4f, right, 5.8f)
        lineTo(right, 18.2f)
        quadTo(right, 19.6f, right - 1.4f, 19.6f)
        lineTo(left + 1.4f, 19.6f)
        quadTo(left, 19.6f, left, 18.2f)
        lineTo(left, 5.8f)
        quadTo(left, 4.4f, left + 1.4f, 4.4f)
        close()
    }

    /** Two touching right-pointing triangles (fast forward); mirrored for rewind. */
    private fun PathBuilder.twin(mirror: Boolean) {
        fun x(value: Float) = if (mirror) 24f - value else value
        for (a in listOf(1.6f, 11.6f)) {
            moveTo(x(a), 6.7f)
            curveTo(x(a), 5.7f, x(a + 1.0f), 5.2f, x(a + 1.8f), 5.7f)
            lineTo(x(a + 9.8f), 11.1f)
            curveTo(x(a + 10.5f), 11.6f, x(a + 10.5f), 12.4f, x(a + 9.8f), 12.9f)
            lineTo(x(a + 1.8f), 18.3f)
            curveTo(x(a + 1.0f), 18.8f, x(a), 18.3f, x(a), 17.3f)
            close()
        }
    }
}
