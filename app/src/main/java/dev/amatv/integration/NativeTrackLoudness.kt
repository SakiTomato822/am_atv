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
    private var rampFromGain = 0f
    private var lastGain = 0f
    private var effectiveLookup = lookup
    private var album: AlbumLoudness.Summary? = null
    private var loggedAlbum: AlbumLoudness.Summary? = null
    private var loggedLookup: LoudnessLookup? = null
    private var loggedGain: Float? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun gainAt(playing: Boolean, nowMs: Long): Float {
        pending?.takeIf { it.isCompleted }?.let { request ->
            lookup = runCatching { request.getCompleted() }
                .getOrElse { LoudnessLookup(null, "missing", "lookup-cancelled") }
            pending = null
        }
        album = AlbumLoudness.forTrack(catalogId).takeUnless { mode == LoudnessMode.Off }
        val target = album?.tag?.let { LoudnessLookup(it, "album") }
            ?: lookup.copy(reason = lookup.reason ?: "album-tags-pending".takeIf { album != null })
        if (started && target.tag != effectiveLookup.tag) {
            rampStartMs = nowMs
            rampFromGain = lastGain
        }
        started = started || playing
        effectiveLookup = target
        val progress = rampStartMs?.let { ((nowMs - it) / RAMP_MS.toFloat()).coerceIn(0f, 1f) } ?: 1f
        if (progress == 1f) rampStartMs = null
        val targetGain = target.tag?.gainFor(mode) ?: 0f
        val rampGain = if (progress == 1f) targetGain else rampFromGain + (targetGain - rampFromGain) * progress
        lastGain = minOf(rampGain, target.tag?.maxBoostDb ?: 0f)
        return lastGain
    }

    fun apply(effect: GaplessFade, session: Int, fadeDb: Float, playing: Boolean, nowMs: Long): Float {
        val gain = gainAt(playing, nowMs)
        val lookup = effectiveLookup
        val applied = effect.apply(session, fadeDb, gain, limit = lookup.tag != null)
        if (lookup != loggedLookup || album != loggedAlbum || (rampStartMs == null && applied != loggedGain)) {
            val reason = lookup.reason ?: when {
                applied == gain -> "-"
                effect.attachment(session) == "attached" -> "apply-failed"
                else -> effect.attachment(session)
            }
            Log.i(
                LOUDNESS_LOG,
                "catalogId=${catalogId ?: "-"} tagDb=${lookup.tag?.gainDb ?: "-"} " +
                    "peakDbFS=${lookup.tag?.peakDb ?: "-"} mode=$mode appliedDb=$applied " +
                    "source=${lookup.source} reason=$reason scope=${if (album != null) "album" else "track"} " +
                    "albumTracksKnown=${album?.tracksKnown ?: 0}",
            )
            loggedLookup = lookup
            loggedAlbum = album
            loggedGain = applied
        }
        return applied
    }

    private companion object {
        const val RAMP_MS = 1_000L
    }
}
