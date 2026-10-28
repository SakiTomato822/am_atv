package dev.amatv.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarFontsTest {
    @Test fun customFontRendersMixedLyricsAndChangesLatinMetrics() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val custom = Paint().apply { typeface = CarFonts.typeface(resources); textSize = 48f }
        val system = Paint().apply { typeface = Typeface.DEFAULT; textSize = 48f }
        listOf("歌", "词", "體", "夜", "A", "8").forEach { assertTrue("Missing glyph $it", custom.hasGlyph(it)) }
        assertTrue(kotlin.math.abs(custom.measureText("Music 0123456789") - system.measureText("Music 0123456789")) > 1f)
        assertSame(CarFonts.family(resources), CarFonts.family(resources))
    }
}
