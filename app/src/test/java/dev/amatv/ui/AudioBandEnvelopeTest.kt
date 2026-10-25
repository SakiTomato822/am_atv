package dev.amatv.ui

import dev.amatv.integration.AudioSpectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBandEnvelopeTest {
    @Test fun measuredBandsStayIndependentAndGrowWithoutInventingOtherEnergy() {
        val envelope = AudioBandEnvelope()
        repeat(20) {
            envelope.advance(AudioSpectrum(bass = .64f, mid = .16f, treble = 0f, available = true), true, 16f)
        }
        assertTrue(envelope.bass in .1f.. .3f)
        assertTrue(envelope.mid > 0f && envelope.mid < envelope.bass / 4f)
        assertEquals(0f, envelope.treble, 0f)
    }

    @Test fun sustainedLoudAudioKeepsHeadroomInsteadOfPinningAllThreeBars() {
        val envelope = AudioBandEnvelope()
        repeat(180) {
            envelope.advance(AudioSpectrum(1f, .9f, .8f, available = true), true, 16f)
            assertTrue(envelope.bass < .5f)
            assertTrue(envelope.mid < .5f)
            assertTrue(envelope.treble < .5f)
        }
        assertTrue(envelope.bass > .3f)
        assertTrue(envelope.bass > envelope.mid && envelope.mid > envelope.treble)
    }

    @Test fun bassHitRisesAboveItsRecentLevelThenReturnsWithoutMovingOtherBands() {
        val envelope = AudioBandEnvelope()
        val ordinary = AudioSpectrum(.6f, .4f, .3f, available = true)
        repeat(120) { envelope.advance(ordinary, true, 16f) }
        val baseline = envelope.bass
        val middle = envelope.mid
        val high = envelope.treble
        repeat(5) { envelope.advance(ordinary.copy(bass = .95f), true, 16f) }
        val peak = envelope.bass
        assertTrue(peak > baseline + .35f)
        assertTrue(peak < .95f)
        assertEquals(middle, envelope.mid, .002f)
        assertEquals(high, envelope.treble, .002f)
        repeat(25) { envelope.advance(ordinary, true, 16f) }
        assertTrue(envelope.bass < peak / 2f)
        assertEquals(baseline, envelope.bass, .03f)
    }

    @Test fun dynamicsStayConsistentAcrossThirtyAndSixtyHzRendering() {
        fun run(frameMs: Float): List<Float> {
            val envelope = AudioBandEnvelope()
            fun hold(level: Float, durationMs: Int) {
                repeat((durationMs / frameMs).toInt()) {
                    envelope.advance(AudioSpectrum(bass = level, available = true), true, frameMs)
                }
            }
            hold(.5f, 2_000)
            hold(.9f, 200)
            val peak = envelope.bass
            hold(.5f, 400)
            return listOf(peak, envelope.bass)
        }
        val slower = run(1000f / 30f)
        val faster = run(1000f / 60f)
        assertEquals(slower[0], faster[0], .04f)
        assertEquals(slower[1], faster[1], .04f)
    }

    @Test fun missingOrSilentCaptureDoesNotAnimateFlatBars() {
        val envelope = AudioBandEnvelope()
        assertFalse(envelope.advance(AudioSpectrum(bass = 1f, available = false), true, 16f))
        assertFalse(envelope.advance(AudioSpectrum(bass = 1f, available = true, captured = false), true, 16f))
        assertFalse(envelope.advance(AudioSpectrum(available = true), true, 16f))
        assertTrue(envelope.isFlat)
    }

    @Test fun explicitPauseSettlesAllBandsAndStopsEvenIfTheLastFftWasLoud() {
        val envelope = AudioBandEnvelope()
        val loud = AudioSpectrum(1f, 1f, 1f, available = true)
        repeat(20) { envelope.advance(loud, true, 16f) }
        assertTrue(envelope.bass > .35f)
        repeat(12) { envelope.advance(loud, false, 16f) }
        assertTrue(envelope.isFlat)
        assertFalse(envelope.advance(loud, false, 16f))
    }

    @Test fun resumedFramesAndMalformedSamplesCannotJumpOrPoisonTheEnvelope() {
        val envelope = AudioBandEnvelope()
        envelope.advance(AudioSpectrum(1f, Float.NaN, -1f, available = true), true, 10_000f)
        assertTrue(envelope.bass in 0f.. .4f)
        assertEquals(0f, envelope.mid, 0f)
        assertEquals(0f, envelope.treble, 0f)
        val before = envelope.bass
        assertFalse(envelope.advance(AudioSpectrum(), true, Float.NaN))
        assertEquals(before, envelope.bass, 0f)
        assertTrue(envelope.reset())
        assertTrue(envelope.isFlat)
        assertFalse(envelope.reset())
    }
}
