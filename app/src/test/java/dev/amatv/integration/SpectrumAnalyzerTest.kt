package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class SpectrumAnalyzerTest {
    private fun tone(hz: Int): ByteArray = ByteArray(1024).apply {
        val bin = (hz * 1024f / 48_000f).toInt().coerceIn(1, 511)
        this[bin * 2] = 100
        this[bin * 2 + 1] = (-80).toByte()
    }
    @Test fun resolvesRealBassMidAndTrebleAtMillihertzSampleRate() {
        val bass = SpectrumAnalyzer().analyze(tone(120), 48_000_000, 100)
        val mid = SpectrumAnalyzer().analyze(tone(1_000), 48_000_000, 100)
        val treble = SpectrumAnalyzer().analyze(tone(6_000), 48_000_000, 100)
        assertTrue(bass.available && mid.available && treble.available)
        assertTrue(bass.bass > 0f && bass.mid == 0f && bass.treble == 0f)
        assertTrue(mid.mid > 0f && mid.bass == 0f && mid.treble == 0f)
        assertTrue(treble.treble > 0f && treble.bass == 0f && treble.mid == 0f)
    }
    @Test fun ordinaryAndStrongBassHitsRemainDistinctInsteadOfClippingTogether() {
        fun bassHit(amplitude: Int) = ByteArray(1024).apply {
            for (bin in 1..5) this[bin * 2] = amplitude.toByte()
        }
        val ordinary = SpectrumAnalyzer().analyze(bassHit(40), 48_000_000, 250)
        val strong = SpectrumAnalyzer().analyze(bassHit(120), 48_000_000, 250)
        assertTrue(ordinary.available && strong.available)
        assertTrue(strong.bass < 1f)
        assertTrue(strong.bass - ordinary.bass > 0.15f)
        assertEquals(0f, strong.mid, 0f)
        assertEquals(0f, strong.treble, 0f)
    }
    @Test fun bassCanReleaseBetweenHitsWhileOtherFrequenciesContinue() {
        val analyzer = SpectrumAnalyzer()
        val hit = analyzer.analyze(tone(120), 48_000_000, 250)
        val betweenHits = analyzer.analyze(tone(6_000), 48_000_000, 150)
        assertTrue(betweenHits.available)
        assertTrue(betweenHits.bass < hit.bass * 0.4f)
        assertTrue(betweenHits.treble > 0f)
    }
    @Test fun dcNyquistAndSilenceDoNotInventFrequencyEnergy() {
        val bytes = ByteArray(1024).apply { this[0] = 127; this[1] = 127 }
        assertEquals(AudioSpectrum(captured = true), SpectrumAnalyzer().analyze(bytes, 48_000_000, 33))
        assertEquals(AudioSpectrum(captured = true), SpectrumAnalyzer().analyze(ByteArray(1024), 48_000_000, 33))
    }
    @Test fun releaseDecaysWithoutReportingNewSignal() {
        val analyzer = SpectrumAnalyzer()
        val attack = analyzer.analyze(tone(120), 48_000_000, 100)
        val release = analyzer.analyze(ByteArray(1024), 48_000_000, 100)
        assertFalse(release.available)
        assertTrue(release.bass in 0f..<attack.bass)
    }

    @Test fun normalizedNoiseCannotBecomeMusicWhenAbsoluteOutputIsSilent() {
        val analyzer = SpectrumAnalyzer()
        // A normalized FFT may be large even when the actual session is at -90 dBFS.
        repeat(60) {
            val sample = analyzer.analyze(tone(120), 48_000_000, 33, rmsMilliBels = -9_000)
            assertFalse(sample.available)
            assertTrue(sample.captured)
            assertEquals(0f, sample.bass, 0f)
        }
        assertTrue(analyzer.analyze(tone(120), 48_000_000, 33, -2_000).available)
    }

    @Test fun absoluteLevelGateHasHysteresisAroundQuietPassages() {
        val analyzer = SpectrumAnalyzer()
        assertFalse(analyzer.analyze(tone(120), 48_000_000, 33, -6_000).available)
        assertTrue(analyzer.analyze(tone(120), 48_000_000, 33, -5_500).available)
        assertTrue(analyzer.analyze(tone(120), 48_000_000, 33, -6_000).available)
        assertFalse(analyzer.analyze(tone(120), 48_000_000, 33, -6_600).available)
        assertFalse(analyzer.analyze(tone(120), 48_000_000, 33, -6_000).available)
    }

    @Test fun fftQuantizationNoiseIsSilentWithoutHardwareLevelMeasurement() {
        val noise = ByteArray(1024) { index -> if (index % 2 == 0) 1 else -1 }
        val sample = SpectrumAnalyzer().analyze(noise, 48_000_000, 100)
        assertEquals(AudioSpectrum(captured = true), sample)
    }
}
