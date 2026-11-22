package dev.amatv.integration

import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Owned by the playback handler and discarded on an item change. */
internal class NativeTrackLoudness(
    val generation: Long,
    val trackId: String,
    val slot: Int?,
    private val catalogId: String?,
    private val mode: LoudnessMode,
    private var lookup: LoudnessLookup,
    private var pending: Deferred<LoudnessLookup>?,
) {
    private var started = false
    private var rampStartMs: Long? = null
    private var loggedLookup: LoudnessLookup? = null
    private var loggedGain: Float? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun gainAt(playing: Boolean, nowMs: Long): Float {
        started = started || playing
        pending?.takeIf { it.isCompleted }?.let { request ->
            lookup = runCatching { request.getCompleted() }
                .getOrElse { LoudnessLookup(null, "missing", "lookup-cancelled") }
            pending = null
            if (started && lookup.tag != null) rampStartMs = nowMs
        }
        val progress = rampStartMs?.let { ((nowMs - it) / RAMP_MS.toFloat()).coerceIn(0f, 1f) } ?: 1f
        if (progress == 1f) rampStartMs = null
        return (lookup.tag?.gainFor(mode) ?: 0f) * progress
    }

    fun apply(effect: GaplessFade, session: Int, fadeDb: Float, playing: Boolean, nowMs: Long): Float {
        val gain = gainAt(playing, nowMs)
        val applied = effect.apply(session, fadeDb, gain, limit = lookup.tag != null)
        if (lookup != loggedLookup || (rampStartMs == null && applied != loggedGain)) {
            val reason = lookup.reason ?: when {
                applied == gain -> "-"
                effect.attachment(session) == "attached" -> "apply-failed"
                else -> effect.attachment(session)
            }
            val targetHeadroomUsedDb = lookup.tag?.targetHeadroomUsedDb(mode) ?: 0f
            Log.i(
                LOUDNESS_LOG,
                "catalogId=${catalogId ?: "-"} tagDb=${lookup.tag?.gainDb ?: "-"} " +
                    "peakDbFS=${lookup.tag?.peakDb ?: "-"} mode=$mode appliedDb=$applied " +
                    "source=${lookup.source} reason=$reason " +
                    "targetHeadroomUsed=${targetHeadroomUsedDb > 0f} targetHeadroomUsedDb=$targetHeadroomUsedDb",
            )
            loggedLookup = lookup
            loggedGain = applied
        }
        return applied
    }

    private companion object {
        const val RAMP_MS = 1_000L
    }
}
