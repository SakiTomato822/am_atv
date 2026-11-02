package dev.amatv.integration

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.json.JSONObject

/**
 * Optional second engine for Apple Music station queues.
 *
 * The pinned MusicKit Playback 1.1.1 AAR cannot build a station queue (see
 * docs/flyme-radio-playback-status.md). A variant may supply an engine that
 * plays stations through MusicKit JS instead; songs always stay native.
 */
interface StationPlaybackEngine {
    /** Returns this request's generation; snapshots carry it as requestGeneration. */
    fun prepare(station: Track, developerToken: String, userToken: String, playWhenReady: Boolean): Long
    fun play()
    fun pause()
    fun skipToNext()
    /** Stops audio and drops the queue; the engine may keep its browser warm. */
    fun stop()
    fun release()
    fun playbackSnapshot(): PlaybackSnapshot?
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?)

    companion object {
        /** Engines live in variant source sets; main code only knows the class name. */
        fun create(context: Context): StationPlaybackEngine? {
            val name = BuildConfig.STATION_ENGINE_CLASS.takeIf(String::isNotBlank) ?: return null
            return runCatching {
                Class.forName(name).getConstructor(Context::class.java)
                    .newInstance(context.applicationContext) as StationPlaybackEngine
            }.onFailure { Log.w("AMATV-Station", "Station engine unavailable: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
    }
}

/** Generations far above MusicKitBridge's counter so the two engines never collide. */
internal const val WEB_STATION_GENERATION_BASE = 1L shl 40

/**
 * Maps one sanitized MusicKit JS state message to the app's snapshot model.
 * [station] is the requested station; songs inside it inherit its palette.
 */
internal fun webStationSnapshot(
    state: JSONObject,
    station: Track,
    generation: Long,
    prepared: Boolean,
    failure: PlaybackFailureKind?,
    failureMessage: (PlaybackFailureKind) -> String,
    nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    previewNotice: () -> String = { AppText.text(R.string.services_station_preview_only) },
): PlaybackSnapshot {
    val item = state.optJSONObject("item")
    val live = state.optBoolean("live") || item?.optBoolean("live") == true
    val current = item?.let { webStationItemTrack(it, station, live) }
    val stateName = state.optString("state")
    val buffering = stateName in setOf("loading", "waiting", "stalled", "seeking")
    val duration = if (live) 0L else current?.durationMs ?: 0L
    return PlaybackSnapshot(
        currentTrack = current ?: station,
        hasPlayerCurrentTrack = current != null,
        queue = listOfNotNull(current ?: station),
        positionMs = if (live) 0L else state.optLong("positionMs").coerceIn(0L, duration.coerceAtLeast(0L)),
        durationMs = duration,
        isPlaying = state.optBoolean("playing"),
        isBuffering = buffering,
        // Station queues advance server-side; a live stream has no next item.
        canSkipNext = prepared && !live && state.optBoolean("canSkipNext", true),
        canSkipPrevious = false,
        repeatMode = PlaybackRepeatMode.Off,
        shuffleEnabled = false,
        autoplayEnabled = false,
        canSetRepeat = false,
        canSetShuffle = false,
        canSetAutoplay = false,
        drmPlaybackSupported = state.optBoolean("drm", true),
        errorMessage = failure?.let(failureMessage),
        notice = if (failure == null && state.optBoolean("preview")) previewNotice() else null,
        capturedAtElapsedRealtimeMs = nowElapsedMs,
        isEnded = stateName == "completed" || stateName == "ended",
        requestGeneration = generation,
        isPrepared = prepared,
        failureKind = failure,
        isLiveStream = live,
        canSeek = false,
    )
}

private fun webStationItemTrack(item: JSONObject, station: Track, live: Boolean): Track? {
    val type = item.optString("type")
    val title = item.optString("title").ifBlank { return null }
    val song = type == "song" && !live
    val catalogId = item.optString("catalogId").takeIf(String::isNotBlank)
        ?: if (song) null else station.catalogId
    val identity = catalogId ?: item.optString("id").ifBlank { title.hashCode().toString() }
    val artwork = item.optString("artworkUrl").takeIf { it.startsWith("https://") }
    return Track(
        id = "apple:$identity",
        title = title,
        artist = item.optString("artist").ifBlank { station.artist },
        album = item.optString("album").ifBlank { station.title },
        durationMs = item.optLong("durationMs").takeIf { it > 0L && song } ?: 1L,
        palette = station.palette,
        lyrics = emptyList(),
        catalogId = catalogId,
        catalogKind = if (song) CatalogKind.Song else CatalogKind.Station,
        artworkUrl = artwork ?: station.artworkUrl.takeUnless { song },
        hasLyricsAvailable = song && item.optBoolean("hasLyrics"),
    )
}

/** MusicKit JS MKError reasons, collapsed to the app's failure kinds. */
internal fun webStationFailureKind(reason: String): PlaybackFailureKind = when (reason) {
    "AUTHORIZATION_ERROR", "ACCESS_DENIED", "SUBSCRIPTION_ERROR", "TOKEN_EXPIRED",
    "UNAUTHORIZED", "USER_INTERACTION_REQUIRED", "auth" -> PlaybackFailureKind.Authorization
    "NETWORK_ERROR", "BUFFER_STALLED_ERROR", "SERVER_ERROR", "TIMEOUT", "network" -> PlaybackFailureKind.Network
    "CONTENT_UNAVAILABLE", "CONTENT_RESTRICTED", "CONTENT_UNSUPPORTED", "GEO_BLOCK", "NOT_FOUND",
    "AGE_GATE", "unavailable" -> PlaybackFailureKind.TrackUnavailable
    else -> PlaybackFailureKind.Unexpected
}
