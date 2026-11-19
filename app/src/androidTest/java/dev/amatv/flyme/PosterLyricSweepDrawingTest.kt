package dev.amatv.flyme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PosterLyricSweepDrawingTest {
    @Test
    fun wrappedSweepUsesGradientOpacityAfterAnUnlitFrame() {
        val line = LyricLine(listOf(
            LyricWord("HHHHHH", 1_000, 2_000),
            LyricWord("HHHHHH", 2_000, 3_000),
        ))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = Density(1f)
        val direction = LayoutDirection.Ltr
        val layout = TextMeasurer(createFontFamilyResolver(context), density, direction).measure(
            text = posterLeadText(line),
            style = TextStyle(fontSize = 64.sp, fontFamily = FontFamily.Monospace),
            constraints = Constraints(maxWidth = 320),
        )
        assertEquals(2, layout.lineCount)
        val sweep = PosterLyricSweep(layout, line)
        val bitmap = Bitmap.createBitmap(layout.size.width, layout.size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val scope = CanvasDrawScope()
        fun draw(positionMs: Long, ink: Color) {
            bitmap.eraseColor(0)
            scope.draw(density, direction, canvas, Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                sweep.draw(this, positionMs, ink)
            }
        }
        fun glyphAlpha(row: Int, offset: Int): Int {
            val bounds = layout.getBoundingBox(layout.getLineStart(row) + offset)
            return (bounds.left.roundToInt() until bounds.right.roundToInt()).maxOf { x ->
                (layout.getLineTop(row).roundToInt() until layout.getLineBottom(row).roundToInt()).maxOf { y ->
                    bitmap.getPixel(x, y) ushr 24
                }
            }
        }

        try {
            for (ink in listOf(Color.White, Color.Black)) {
                draw(0, ink)
                assertEquals(102, glyphAlpha(row = 0, offset = 0))

                draw(1_500, ink)
                assertEquals(255, glyphAlpha(row = 0, offset = 0))
                assertTrue(glyphAlpha(row = 0, offset = 5) in 102..128)
                assertEquals(102, glyphAlpha(row = 1, offset = 0))

                draw(2_500, ink)
                assertEquals(255, glyphAlpha(row = 0, offset = 0))
                assertEquals(255, glyphAlpha(row = 1, offset = 0))
                assertTrue(glyphAlpha(row = 1, offset = 5) in 102..128)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
