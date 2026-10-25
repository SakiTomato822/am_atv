package dev.amatv.ui

import dev.amatv.integration.AudioSpectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropMotionTest {
    @Test
    fun `short treble spikes stay gentle while bass impacts keep their own attack`() {
        val motion = BackdropMotion()
        repeat(250) { motion.advance(16_000_000, AudioSpectrum(bass = .65f, available = true)) }
        repeat(4) { motion.advance(16_000_000, AudioSpectrum(bass = .90f, treble = 1f, available = true)) }
        assertTrue(motion.treble in .30f.. .48f)
        assertTrue(motion.bassImpact > .30f)
        val high = motion.treble
        motion.advance(16_000_000, AudioSpectrum(bass = .65f, available = true))
        assertTrue(motion.treble < high && motion.treble > high * .9f)
    }

    @Test
    fun `no capture never invents audio energy while ambient time advances`() {
        val motion = BackdropMotion()
        repeat(120) { motion.advance(16_000_000L, AudioSpectrum(bass = 1f, mid = 1f, treble = 1f)) }
        assertEquals(0f, motion.bass, 0f)
        assertEquals(0f, motion.mid, 0f)
        assertEquals(0f, motion.treble, 0f)
        assertEquals(1_920_000_000L, motion.elapsedNanos)
    }

    @Test
    fun `measured bands are independent and smoothing is frame rate independent`() {
        val slow = BackdropMotion()
        val fast = BackdropMotion()
        val bassOnly = AudioSpectrum(bass = 1f, available = true)
        repeat(10) { slow.advance(32_000_000L, bassOnly) }
        repeat(20) { fast.advance(16_000_000L, bassOnly) }
        assertEquals(slow.bass, fast.bass, 0.00001f)
        assertTrue(fast.bass > 0.95f)
        assertEquals(0f, fast.mid, 0f)
        assertEquals(0f, fast.treble, 0f)
    }

    @Test
    fun `available but silent capture stays silent over arbitrary ambient motion`() {
        val motion = BackdropMotion()
        repeat(300) { motion.advance(32_000_000L, AudioSpectrum(available = true)) }
        assertEquals(0f, motion.bass, 0f)
        assertEquals(0f, motion.mid, 0f)
        assertEquals(0f, motion.treble, 0f)
    }

    @Test
    fun `all bands retain the same attack and release at different display frame rates`() {
        val slow = BackdropMotion()
        val fast = BackdropMotion()
        val measured = AudioSpectrum(0.15f, 0.4f, 0.8f, true)
        repeat(10) { slow.advance(32_000_000L, measured) }
        repeat(20) { fast.advance(16_000_000L, measured) }
        assertEquals(slow.bass, fast.bass, 0.00001f)
        assertEquals(slow.mid, fast.mid, 0.00001f)
        assertEquals(slow.treble, fast.treble, 0.00001f)
        repeat(10) { slow.advance(32_000_000L, AudioSpectrum(available = true)) }
        repeat(20) { fast.advance(16_000_000L, AudioSpectrum(available = true)) }
        assertEquals(slow.bass, fast.bass, 0.00001f)
        assertEquals(slow.mid, fast.mid, 0.00001f)
        assertEquals(slow.treble, fast.treble, 0.00001f)
    }

    @Test
    fun `capture loss releases measured energy to zero rather than retaining a pulse`() {
        val motion = BackdropMotion()
        repeat(20) { motion.advance(16_000_000L, AudioSpectrum(1f, 1f, 1f, true)) }
        val before = motion.bass
        motion.advance(16_000_000L, AudioSpectrum())
        assertTrue(motion.bass < before)
        assertTrue(motion.bass > before * 0.85f)
        repeat(150) { motion.advance(16_000_000L, AudioSpectrum()) }
        assertEquals(0f, motion.bass, 0f)
        assertEquals(0f, motion.mid, 0f)
        assertEquals(0f, motion.treble, 0f)
    }

    @Test
    fun `invalid capture and stale clock callbacks cannot corrupt drawing coordinates`() {
        val motion = BackdropMotion()
        motion.advance(-1L, AudioSpectrum(1f, 1f, 1f, true))
        assertEquals(0L, motion.elapsedNanos)
        motion.advance(5_000_000_000L, AudioSpectrum(Float.NaN, Float.POSITIVE_INFINITY, -2f, true))
        assertEquals(64_000_000L, motion.elapsedNanos)
        assertEquals(0f, motion.bass, 0f)
        assertEquals(0f, motion.mid, 0f)
        assertEquals(0f, motion.treble, 0f)
    }
}
