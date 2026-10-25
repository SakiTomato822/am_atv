package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class LavaPaletteTest {
    @Test fun theDominantBlueFieldIsNotReplacedByASmallerMoreSaturatedWarmDetail() {
        val colors = LavaPalette.from(intArrayOf(0xFF294F6F.toInt(), 0xFFC45936.toInt(), 0xFFBBBBBB.toInt()))
        assertTrue(colors[5] > colors[3])
        assertTrue(colors[4] > colors[3])
        assertTrue(colors[8] < colors[6]) // The warm detail remains a separate accent.
    }

    @Test fun neutralArtworkDoesNotInventHuesAndAllOutputIsBounded() {
        val colors = LavaPalette.from(intArrayOf(0xFF333333.toInt(), 0xFFBBBBBB.toInt()))
        for (i in colors.indices step 3) {
            assertEquals(colors[i], colors[i + 1], .00001f)
            assertEquals(colors[i], colors[i + 2], .00001f)
        }
        assertTrue(colors.all { it.isFinite() && it in 0f..1f })
        assertTrue(LavaPalette.from(intArrayOf()).all(Float::isFinite))
    }

    @Test fun interruptedPaletteChangeStartsFromTheDisplayedColorWithoutAFlash() {
        val first = FloatArray(15) { .2f }
        val transition = LavaPaletteTransition(first)
        transition.retarget(FloatArray(15) { .7f }, 0)
        val before = transition.sample(900_000_000).copyOf()
        transition.retarget(FloatArray(15) { .1f }, 900_000_000)
        assertArrayEquals(before, transition.sample(900_000_000), 0f)
        assertTrue(transition.sample(910_000_000).all { it < before[0] && it > .1f })
        assertArrayEquals(FloatArray(15) { .1f }, transition.sample(2_700_000_000), .00001f)
        assertFalse(transition.isChanging(2_700_000_000))
    }
}
