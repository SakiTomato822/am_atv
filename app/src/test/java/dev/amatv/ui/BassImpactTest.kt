package dev.amatv.ui

import dev.amatv.integration.AudioSpectrum
import org.junit.Assert.*
import org.junit.Test

class BassImpactTest {
    private fun feed(motion: BackdropMotion, bass: Float, durationMs: Int, stepMs: Int = 16) {
        repeat(durationMs / stepMs) {
            motion.advance(stepMs * 1_000_000L, AudioSpectrum(bass = bass, available = true))
        }
    }

    @Test fun sustainedBassDoesNotInventRepeatedBeats() {
        val motion = BackdropMotion()
        feed(motion, .75f, 5_000)
        assertTrue(motion.bass > .74f)
        assertEquals(0f, motion.bassImpact, .001f)
        feed(motion, .75f, 2_000)
        assertEquals(0f, motion.bassImpact, .001f)
    }

    @Test fun kicksRemainDistinctAboveAContinuousLoudBassBed() {
        val motion = BackdropMotion()
        feed(motion, .65f, 4_000)
        repeat(5) {
            feed(motion, .88f, 96)
            val hit = motion.bassImpact
            assertTrue("The kick must not disappear into the sustained level", hit > .30f)
            feed(motion, .63f, 320)
            assertTrue("The next kick needs a visible return", motion.bassImpact < hit * .55f)
        }
    }

    @Test fun strongerBassAttacksKeepTheirExtraStrengthWithoutHardClipping() {
        fun hit(level: Float): Float {
            val motion = BackdropMotion()
            feed(motion, .60f, 4_000)
            feed(motion, level, 96)
            return motion.bassImpact
        }
        val ordinary = hit(.70f)
        val strong = hit(.92f)
        assertTrue(ordinary > .15f)
        assertTrue(strong > ordinary + .10f)
        assertTrue(strong < 1f)
    }

    @Test fun silenceCaptureLossAndHighFrequenciesCannotGenerateBassImpacts() {
        val motion = BackdropMotion()
        repeat(200) { motion.advance(16_000_000, AudioSpectrum(mid = 1f, treble = 1f, available = true)) }
        assertEquals(0f, motion.bassImpact, 0f)
        repeat(200) { motion.advance(16_000_000, AudioSpectrum(bass = 1f, available = false)) }
        assertEquals(0f, motion.bassImpact, 0f)
        // A captured silent frame establishes an actual zero baseline before the kick.
        motion.advance(16_000_000, AudioSpectrum(captured = true))
        feed(motion, .9f, 160)
        val hit = motion.bassImpact
        motion.advance(16_000_000, AudioSpectrum())
        assertTrue(motion.bassImpact > 0f)
        assertTrue(motion.bassImpact >= hit * .8f)
        repeat(200) { motion.advance(16_000_000, AudioSpectrum()) }
        assertEquals(0f, motion.bassImpact, .001f)
    }

    @Test fun differentFrameRatesPreserveTheKickAndReturnEnvelope() {
        val slow = BackdropMotion()
        val fast = BackdropMotion()
        feed(slow, .65f, 4_000, 32)
        feed(fast, .65f, 4_000, 16)
        repeat(5) {
            feed(slow, .87f, 96, 32)
            feed(fast, .87f, 96, 16)
            assertEquals(fast.bassImpact, slow.bassImpact, .045f)
            feed(slow, .64f, 224, 32)
            feed(fast, .64f, 224, 16)
            assertEquals(fast.bassImpact, slow.bassImpact, .045f)
        }
    }

    @Test fun invalidSamplesAndStaleCallbacksCannotCorruptTheImpactState() {
        val motion = BackdropMotion()
        repeat(100) { motion.advance(16_000_000, AudioSpectrum(bass = Float.NaN, available = true)) }
        assertEquals(0f, motion.bassImpact, 0f)
        feed(motion, .9f, 96)
        val before = motion.bassImpact
        motion.advance(-1, AudioSpectrum(bass = 1f, available = true))
        assertEquals(before, motion.bassImpact, 0f)
        repeat(100) { motion.advance(16_000_000, AudioSpectrum(bass = Float.POSITIVE_INFINITY, available = true)) }
        assertTrue(motion.bassImpact.isFinite() && motion.bassImpact in 0f..1f)
    }

    @Test fun reconnectingMidNoteDoesNotInventAKick() {
        val motion = BackdropMotion()
        feed(motion, .82f, 500)
        assertEquals(0f, motion.bassImpact, 0f)
        repeat(100) { motion.advance(16_000_000, AudioSpectrum()) }
        feed(motion, .82f, 500)
        assertEquals(0f, motion.bassImpact, 0f)
        feed(motion, .62f, 2_000)
        feed(motion, .90f, 96)
        assertTrue(motion.bassImpact > .30f)
    }

    @Test fun stableBassWithSmallFftJitterDoesNotPumpTheField() {
        val motion = BackdropMotion()
        feed(motion, .65f, 2_000)
        repeat(300) { index ->
            val level = if (index % 2 == 0) .64f else .66f
            motion.advance(16_000_000, AudioSpectrum(bass = level, available = true))
            assertEquals(0f, motion.bassImpact, 0f)
        }
    }

    @Test fun aRealKickAfterCapturedDigitalSilenceRemainsVisible() {
        val motion = BackdropMotion()
        repeat(100) { motion.advance(16_000_000, AudioSpectrum(captured = true)) }
        feed(motion, .90f, 96)
        assertTrue(motion.bassImpact > .30f)
    }
}
