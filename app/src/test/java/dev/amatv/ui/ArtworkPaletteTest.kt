package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkPaletteTest {
    @Test fun findsDistinctAccentsBeyondTheOldFiveSamplePoints() {
        val colors = intArrayOf(0xFFDB1724.toInt(), 0xFF26A746.toInt(), 0xFF164BE2.toInt(), 0xFFFFBF16.toInt(), 0xFFAC32D0.toInt())
        val pixels = IntArray(2_304) { colors[0] }
        for (group in 1..4) repeat(120) { pixels[group * 120 + it] = colors[group] }
        val result = ArtworkPalette.extract(pixels)
        assertEquals(5, result.distinct().size)
        for (i in result.indices) for (j in 0 until i) assertTrue(ArtworkPalette.distance(result[i], result[j]) > 0.13)
    }

    @Test fun similarDominantShadesDoNotCrowdOutRealAlternativeColors() {
        val pixels = IntArray(2_000) { when (it % 4) {
            0 -> 0xFFBC172A.toInt()
            1 -> 0xFFC31C2F.toInt()
            2 -> 0xFFC02030.toInt()
            else -> 0xFFB91A2B.toInt()
        } }
        for (i in 0 until 120) pixels[i] = 0xFF2859D9.toInt()
        for (i in 120 until 240) pixels[i] = 0xFF35BC64.toInt()
        val result = ArtworkPalette.extract(pixels, 3)
        assertEquals(3, result.distinct().size)
        assertTrue(ArtworkPalette.distance(result[0], result[1]) > 0.15)
        assertTrue(ArtworkPalette.distance(result[0], result[2]) > 0.15)
    }

    @Test fun neutralAndMonochromeCoversNeverInventUnrelatedHues() {
        val gray = ArtworkPalette.extract(IntArray(500) { 0xFF777777.toInt() })
        assertEquals(5, gray.size)
        assertEquals(1, gray.distinct().size)
        for (c in gray) {
            assertEquals(c shr 16 and 255, c shr 8 and 255)
            assertEquals(c shr 8 and 255, c and 255)
        }
    }

    @Test fun isolatedCompressionPixelsAndTransparencyAreNotAccentCandidates() {
        val pixels = IntArray(2_000) { 0xFF345678.toInt() }
        pixels[0] = 0xFFFF0000.toInt()
        pixels[1] = 0x0000FF00
        assertEquals(1, ArtworkPalette.extract(pixels).distinct().size)
        assertTrue(ArtworkPalette.extract(IntArray(20)).isEmpty())
    }

    @Test fun candidateShortageFallsBackWithoutDroppingTheFieldCount() {
        val result = ArtworkPalette.extract(IntArray(500) { if (it % 2 == 0) 0xFF193A51.toInt() else 0xFF21435B.toInt() })
        assertEquals(5, result.size)
        assertTrue(result.distinct().size <= 2)
    }
}
