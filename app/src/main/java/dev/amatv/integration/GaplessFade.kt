package dev.amatv.integration

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.util.Log

/**
 * Turns the native player's output down and up again around a gapless song change. MusicKit has
 * no volume or crossfade API, and plays one song at a time, so this is a fade-out followed by a
 * fade-in, applied together with Sound Check at the input of MusicKit's DynamicsProcessing effect.
 */
internal class GaplessFade {
    private var effect: DynamicsProcessing? = null
    private var sessionId = 0
    private var attached = false
    private var appliedDb = 0f
    private var limiterEnabled = false

    fun attachment(session: Int): String = when {
        session <= 0 -> "session-unavailable"
        sessionId == session && attached -> "attached"
        else -> "not-attached"
    }

    /** Returns the normalization component successfully applied to the current audio session. */
    fun apply(session: Int, fadeDb: Float, loudnessDb: Float, limit: Boolean): Float {
        if (session <= 0) return 0f
        val target = combinedPlaybackGain(loudnessDb, fadeDb)
        if (sessionId != session) {
            release()
            if (target == 0f && !limit) return 0f
            sessionId = session
            effect = runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                    false, 0, false, 0, false, 0, true,
                ).build()
                val processor = DynamicsProcessing(0, session, config)
                try {
                    processor.setLimiterAllChannelsTo(limiter(limit))
                    check(processor.setEnabled(true) == AudioEffect.SUCCESS)
                    processor
                } catch (e: Exception) {
                    processor.release()
                    throw e
                }
            }.onFailure { Log.w(TAG, "DynamicsProcessing unavailable on session $session: ${it.javaClass.simpleName}") }
                .getOrNull()
            attached = effect != null
            limiterEnabled = limit
        }
        val processor = effect ?: return 0f
        return runCatching {
            if (limit && !limiterEnabled) {
                processor.setLimiterAllChannelsTo(limiter(true))
                limiterEnabled = true
            }
            if (target != appliedDb) {
                processor.setInputGainAllChannelsTo(target)
                appliedDb = target
            }
            if (!limit && limiterEnabled) {
                processor.setLimiterAllChannelsTo(limiter(false))
                limiterEnabled = false
            }
            loudnessDb
        }.getOrDefault(0f)
    }

    fun reset() {
        apply(sessionId, fadeDb = 0f, loudnessDb = 0f, limit = false)
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
        attached = false
        appliedDb = 0f
        limiterEnabled = false
    }

    private companion object {
        const val TAG = "AMATV-Fade"
        const val CHANNELS = 2

        fun limiter(enabled: Boolean) = DynamicsProcessing.Limiter(
            true, enabled, 0, 1f, 60f, 100f, LOUDNESS_CEILING_DB, 0f,
        )
    }
}

/**
 * The shape of the fade, in decibels rather than amplitude so it follows how loud it sounds. The
 * out-fade starts imperceptibly and only dips at the very end, like a song that ends on its own;
 * it stops at [DEPTH_DB] below instead of silence. The next song starts at that level and comes
 * up quickly, then settles.
 */
internal object FadeCurve {
    const val OUT_MS = 2_000L
    const val IN_MS = 800L
    const val DEPTH_DB = 9f

    fun outDb(remainingMs: Long): Float {
        if (remainingMs >= OUT_MS) return 0f
        val t = 1f - remainingMs.coerceAtLeast(0L).toFloat() / OUT_MS
        return -DEPTH_DB * t * t
    }

    fun inDb(positionMs: Long): Float {
        if (positionMs >= IN_MS) return 0f
        val left = 1f - positionMs.coerceAtLeast(0L).toFloat() / IN_MS
        return -DEPTH_DB * left * left
    }
}

/**
 * Decides whether a song's ending is already quiet. Compares the level just before the fade window
 * with the song's own body; songs that fade out by themselves (or end on a soft chord) are left
 * alone, so the app never fades on top of a fade. Without measurements it answers false.
 */
internal class TailLoudness {
    private var bodySum = 0.0
    private var bodyCount = 0
    private var tailSum = 0.0
    private var tailCount = 0

    fun clear() {
        bodySum = 0.0; bodyCount = 0; tailSum = 0.0; tailCount = 0
    }

    /** [rmsDb] of the song at [positionMs] with [remainingMs] to go; silence-floor values are ignored. */
    fun add(rmsDb: Float, positionMs: Long, remainingMs: Long) {
        if (rmsDb <= FLOOR_DB) return
        when {
            remainingMs in FadeCurve.OUT_MS..TAIL_FROM_MS -> { tailSum += rmsDb; tailCount++ }
            positionMs >= BODY_AFTER_MS && remainingMs > TAIL_FROM_MS -> { bodySum += rmsDb; bodyCount++ }
        }
    }

    fun alreadyQuiet(): Boolean {
        if (bodyCount < MIN_BODY || tailCount < MIN_TAIL) return false
        val body = bodySum / bodyCount
        val tail = tailSum / tailCount
        return tail < body - QUIETER_BY_DB || tail < QUIET_DB
    }

    companion object {
        const val TAIL_FROM_MS = 5_000L
        const val BODY_AFTER_MS = 3_000L
        const val FLOOR_DB = -70f
        const val QUIETER_BY_DB = 6f
        const val QUIET_DB = -40f
        const val MIN_BODY = 8
        const val MIN_TAIL = 4
    }
}

/**
 * Reads the RMS level of MusicKit's session (not the device mix, not the microphone). Needs the
 * record-audio permission; protected routes may only give silence, which [TailLoudness] ignores.
 */
internal class SessionLevel {
    private var visualizer: Visualizer? = null
    private var sessionId = 0
    private var failedSession = 0
    private val measurement = Visualizer.MeasurementPeakRms()

    /** RMS in dB, or null when the level cannot be read. */
    fun read(session: Int, allowed: Boolean): Float? {
        if (!allowed || session <= 0) {
            release()
            return null
        }
        if (visualizer == null || sessionId != session) {
            if (failedSession == session) return null
            release()
            visualizer = runCatching {
                Visualizer(session).apply {
                    check(setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS)
                    check(setEnabled(true) == Visualizer.SUCCESS)
                }
            }.onFailure {
                failedSession = session
                Log.w(TAG, "Level unavailable on session $session: ${it.javaClass.simpleName}")
            }.getOrNull() ?: return null
            sessionId = session
        }
        val ok = runCatching { visualizer?.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS }
            .getOrDefault(false)
        return if (ok) measurement.mRms / 100f else null
    }

    fun release() {
        runCatching { visualizer?.release() }
        visualizer = null
        sessionId = 0
    }

    private companion object {
        const val TAG = "AMATV-Fade"
    }
}
