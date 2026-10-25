package dev.amatv.ui

import dev.amatv.integration.AudioSpectrum
import kotlin.math.exp

/** Frame-rate-independent smoothing for measured audio, separate from the ambient orbit clock. */
internal class BackdropMotion {
    var elapsedNanos: Long = 0L
        private set
    var bass: Float = 0f
        private set
    var bassImpact: Float = 0f
        private set
    var mid: Float = 0f
        private set
    var treble: Float = 0f
        private set
    private var bassFloor = 0f
    private var bassImpulse = 0f
    private var hasBassBaseline = false

    fun advance(deltaNanos: Long, spectrum: AudioSpectrum) {
        // Neither a stale frame callback nor a resumed lifecycle should jump the color field.
        val delta = deltaNanos.coerceIn(0L, 64_000_000L)
        if (delta == 0L) return
        elapsedNanos += delta
        val seconds = delta / 1_000_000_000f
        val bassInput = target(spectrum.bass, spectrum.available)
        // The logarithmic FFT level often sits around 0.6–0.8 throughout a song. Scaling
        // that almost-constant level only makes a permanently wider blob. Detect rises
        // above the recent bass floor instead; no clock, lyrics or synthetic beats.
        val validCapture = spectrum.captured && spectrum.bass.isFinite()
        val impactTarget = if (!validCapture) {
            // Reconnecting a capture in the middle of a sustained note is not a kick.
            hasBassBaseline = false
            bassFloor = 0f
            0f
        } else if (!hasBassBaseline) {
            bassFloor = bassInput
            hasBassBaseline = true
            0f
        } else {
            bassFloor += (bassInput - bassFloor) * (1f - exp(-seconds / 0.65f))
            val rise = (bassInput - bassFloor - 0.025f).coerceAtLeast(0f)
            if (spectrum.available && bassInput >= 0.14f) rise / (0.06f + rise) else 0f
        }
        // Two short envelopes keep the silhouette continuous at a kick's attack, while
        // letting it return between kicks. This is separate from sustained bass pressure.
        bassImpulse = smooth(bassImpulse, impactTarget, seconds, 0.025f, 0.10f)
        bassImpact = smooth(bassImpact, bassImpulse, seconds, 0.045f, 0.17f)
        // A fluid silhouette needs a soft attack/longer release, rather than strobing on FFT bins.
        bass = smooth(bass, bassInput, seconds, 0.09f, 0.24f)
        mid = smooth(mid, target(spectrum.mid, spectrum.available), seconds, 0.07f, 0.20f)
        // Treble is an accent: soften fast hats/sibilants without delaying the bass impulse.
        treble = smooth(treble, target(spectrum.treble, spectrum.available), seconds, 0.11f, 0.25f)
    }

    private fun target(value: Float, available: Boolean): Float =
        if (available && value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun smooth(current: Float, target: Float, seconds: Float, attack: Float, release: Float): Float {
        val timeConstant = if (target > current) attack else release
        val next = target + (current - target) * exp(-seconds / timeConstant)
        return if (next < 0.001f && target == 0f) 0f else next.coerceIn(0f, 1f)
    }
}
