package dev.amatv.integration

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10

/**
 * Turns the native player's output down and up again around a gapless song change. MusicKit has
 * no volume or crossfade API, and plays one song at a time, so this is a fade-out followed by a
 * fade-in (no overlap), applied as DynamicsProcessing input gain on MusicKit's audio session.
 * Only the gain stage is in use: no EQ, compressor or limiter touches the sound.
 */
internal class GaplessFade {
    private var effect: DynamicsProcessing? = null
    private var sessionId = 0
    private var appliedDb = 0f

    /** [gain] is linear amplitude, 0..1. A session of 0 (unknown) leaves the sound alone. */
    fun apply(session: Int, gain: Float) {
        if (session <= 0) return
        val db = if (gain >= 0.999f) 0f else 20f * log10(gain.coerceAtLeast(MIN_GAIN))
        if (effect == null || sessionId != session) {
            if (db == 0f) return
            release()
            effect = runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                    false, 0, false, 0, false, 0, false,
                ).build()
                DynamicsProcessing(0, session, config).apply { enabled = true }
            }.onFailure { Log.w(TAG, "Fade unavailable on session $session: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return
            sessionId = session
            appliedDb = 0f
        }
        if (abs(db - appliedDb) < 0.1f) return
        runCatching { effect?.setInputGainAllChannelsTo(db) }.onSuccess { appliedDb = db }
    }

    fun reset() {
        if (appliedDb != 0f) runCatching { effect?.setInputGainAllChannelsTo(0f) }
        appliedDb = 0f
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
        appliedDb = 0f
    }

    private companion object {
        const val TAG = "AMATV-Fade"
        const val CHANNELS = 2
        const val MIN_GAIN = 0.01f
    }
}
