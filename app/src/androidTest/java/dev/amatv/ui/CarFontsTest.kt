package dev.amatv.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarFontsTest {
    @Test fun composeResolvesRealBoldChineseGlyphs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = createFontFamilyResolver(context)
        val family = CarFonts.family(context.resources)
        fun resolve(weight: FontWeight) = resolver.resolve(
            family, weight, fontSynthesis = FontSynthesis.None,
        ).value as Typeface
        val normal = resolve(FontWeight.Normal)
        val bold = resolve(FontWeight.ExtraBold)
        assertEquals(400, normal.weight)
        assertEquals(800, bold.weight)
        fun ink(typeface: Typeface): Long {
            val bitmap = Bitmap.createBitmap(400, 110, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText("歌词字重", 5f, 85f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 80f
                color = android.graphics.Color.WHITE
            })
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            return pixels.sumOf { (it ushr 24).toLong() }
        }
        assertTrue("Chinese 800 must visibly contain more ink than 400", ink(bold) > ink(normal) * 1.15)
    }

    @Test fun customFontRendersMixedLyricsAndChangesLatinMetrics() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val custom = Paint().apply { typeface = CarFonts.typeface(resources); textSize = 48f }
        val system = Paint().apply { typeface = Typeface.DEFAULT; textSize = 48f }
        listOf("歌", "词", "體", "夜", "A", "8").forEach { assertTrue("Missing glyph $it", custom.hasGlyph(it)) }
        assertTrue(kotlin.math.abs(custom.measureText("Music 0123456789") - system.measureText("Music 0123456789")) > 1f)
        assertSame(CarFonts.family(resources), CarFonts.family(resources))
    }
}
