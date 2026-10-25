package dev.amatv.benchmark

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dev.amatv.ui.rendering.TextureAlphaMask

/** Verifies Android's actual gradient rasterization, independently of the JVM ramp tests. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class KaraokeGradientRenderingTest {
    @Test fun textureMaskMatchesFourStopFeatherPixels() {
        var maximumError = 0
        val texture = TextureAlphaMask()
        for (width in listOf(1, 17, 128, 511)) {
            for (feather in listOf(0f, 0.01f, 0.2f, 0.5f)) {
                for (progress in listOf(-0.2f, 0f, 0.001f, 0.02f, 0.25f, 0.5f, 0.75f, 0.98f, 0.999f, 1f, 1.2f)) {
                    for (rtl in listOf(false, true)) {
                        for ((lit, unlit) in listOf(255 to 0, 180 to 45, 76 to 51)) {
                            val start = if (rtl) progress.coerceIn(0f, 1f)
                                else (progress - feather).coerceIn(0f, progress.coerceIn(0f, 1f))
                            val end = if (rtl) (progress + feather).coerceIn(start, 1f)
                                else progress.coerceIn(0f, 1f)
                            val left = Color.argb(if (rtl) unlit else lit, 255, 255, 255)
                            val right = Color.argb(if (rtl) lit else unlit, 255, 255, 255)
                            val before = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
                            val after = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
                            try {
                                for (x in 0 until width) {
                                    val glyph = Color.argb(32 + (x * 37 % 224), 255, 255, 255)
                                    before.setPixel(x, 0, glyph)
                                    after.setPixel(x, 0, glyph)
                                }
                                val paint = Paint().apply { blendMode = BlendMode.DST_IN }
                                paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                                    intArrayOf(left, left, right, right), floatArrayOf(0f, start, end, 1f), Shader.TileMode.CLAMP)
                                Canvas(before).drawRect(0f, 0f, width.toFloat(), 1f, paint)
                                val canvas = Canvas(after)
                                texture.draw(canvas, 0f, 0f, width.toFloat(), 1f,
                                    start * width, end * width, Color.alpha(left) / 255f, Color.alpha(right) / 255f)
                                for (x in 0 until width) {
                                    val error = kotlin.math.abs(Color.alpha(before.getPixel(x, 0)) - Color.alpha(after.getPixel(x, 0)))
                                    maximumError = maxOf(maximumError, error)
                                    assertTrue("Mask alpha mismatch width=$width progress=$progress feather=$feather rtl=$rtl x=$x error=$error before=${Color.alpha(before.getPixel(x, 0))} after=${Color.alpha(after.getPixel(x, 0))}", error <= 1)
                                }
                            } finally {
                                before.recycle()
                                after.recycle()
                            }
                        }
                    }
                }
            }
        }
        android.util.Log.i("AMATV-mask", "Android software raster comparison maxAlphaError=$maximumError")
    }

    @Test fun textureViewportEdgesMatchTheOriginalVerticalMask() {
        val topMask = TextureAlphaMask()
        val bottomMask = TextureAlphaMask()
        for (height in listOf(1, 17, 540, 1080)) for (bottomStart in listOf(.76f, .81f, .86f)) {
            val before = Bitmap.createBitmap(1, height, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(1, height, Bitmap.Config.ARGB_8888)
            try {
                before.eraseColor(Color.WHITE)
                after.eraseColor(Color.WHITE)
                val paint = Paint().apply {
                    blendMode = BlendMode.DST_IN
                    shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                        intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                        floatArrayOf(0f, .09f, bottomStart, 1f), Shader.TileMode.CLAMP)
                }
                Canvas(before).drawRect(0f, 0f, 1f, height.toFloat(), paint)
                val canvas = Canvas(after)
                topMask.draw(canvas, 0f, 0f, 1f, height.toFloat(), 0f, height * .09f, 0f, 1f, vertical = true)
                bottomMask.draw(canvas, 0f, 0f, 1f, height.toFloat(), height * bottomStart, height.toFloat(), 1f, 0f, vertical = true)
                for (y in 0 until height) {
                    val error = kotlin.math.abs(Color.alpha(before.getPixel(0, y)) - Color.alpha(after.getPixel(0, y)))
                    assertTrue("Viewport edge alpha mismatch height=$height y=$y bottom=$bottomStart error=$error", error <= 1)
                }
            } finally { before.recycle(); after.recycle() }
        }
    }
}
