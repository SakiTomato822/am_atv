package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.model.fallbackTrackPalette
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class PlaybackRepeatMode(val sdkValue: Int) {
    Off(0),
    One(1),
    All(2),
    ;

    companion object {
        fun fromSdk(value: Int): PlaybackRepeatMode = entries.firstOrNull {
            it.sdkValue == value
        } ?: Off
    }
}

data class PlaybackSnapshot(
    val currentTrack: Track?,
    /** True only when the backing player, rather than a requested-track fallback, reports this item. */
    val hasPlayerCurrentTrack: Boolean = false,
    val queue: List<Track>,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val repeatMode: PlaybackRepeatMode,
    val shuffleEnabled: Boolean,
    val autoplayEnabled: Boolean,
    val canSetRepeat: Boolean,
    val canSetShuffle: Boolean,
    val canSetAutoplay: Boolean,
    /** Null when the backing player does not expose subscription capability. */
    val subscriptionActive: Boolean? = null,
    /** Null when the backing player does not expose its DRM capability. */
    val drmPlaybackSupported: Boolean? = null,
    val errorMessage: String? = null,
    /** A non-fatal explanation to show once, e.g. a station playing previews only. */
    val notice: String? = null,
    /** Set once MusicKit continued into the app's queued next song without a new prepare. */
    val gaplessAdvanceCatalogId: String? = null,
    val capturedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    val isEnded: Boolean = false,
    val requestGeneration: Long = 0L,
    val isPrepared: Boolean = true,
    val failureKind: PlaybackFailureKind? = null,
    /** True for an unseekable live station stream whose item position is not a song duration. */
    val isLiveStream: Boolean = false,
    /** Seek capability; older controllers without this method fall back to !isLiveStream. */
    val canSeek: Boolean = true,
)

/**
 * Thin clean-room adapter around Apple-provided MusicKit AARs.
 * Reflection isolates version-specific controller methods. Developers supply
 * the licensed SDK in app/libs; no Apple binary is redistributed here.
 */
class MusicKitBridge(
    private val context: Context,
    catalogPreviewUrl: suspend (String) -> String? = { null },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var authenticationManager: Any? = null
    @Volatile private var controller: Any? = null
    @Volatile private var controllerListener: Any? = null
    private var playbackThread: HandlerThread? = null
    private var playbackHandler: Handler? = null
    private var playbackListener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile
    private var requestedTrack: Track? = null
    @Volatile
    private var latestSnapshot: PlaybackSnapshot? = null
    @Volatile
    private var snapshotScheduled = false
    @Volatile private var pendingPlaybackError: String? = null
    @Volatile private var pendingFailureKind: PlaybackFailureKind? = null
    @Volatile private var desiredPlaying = false
    @Volatile private var activeGeneration = 0L
    @Volatile private var issuedPrepareGeneration = 0L
    private val playbackIntent = NativePlaybackIntent()
    private var lastLoggedPlaybackState: Int? = null
    private var lastLoggedTrackId: String? = null
    private var lastStartupState: Triple<Boolean, Boolean, Boolean>? = null
    private var playbackDeveloperToken: String? = null
    private var playbackUserToken: String? = null
    private val prepareGeneration = AtomicLong(0L)
    @Volatile private var preparing = false
    @Volatile private var endedCatalogId: String? = null
    @Volatile private var preparedNativeQueueId: Long? = null
    /** Gapless: the app's next song, appended after the current native item while it plays. */
    @Volatile private var lookahead: Track? = null
    @Volatile private var gaplessAdvanceCatalogId: String? = null
    @Volatile private var queuedLookaheadCatalogId: String? = null
    private var nativeSongQueue = NativeSongQueue()
    private var requestedNativeIndex: Int? = null
    /** Set once addQueueItems touched the current controller's queue. */
    @Volatile private var nativeQueueEdited = false
    private val diagnostics = PlaybackDiagnostics.get(context)
    private data class TransitionPlayback(
        val generation: Long, val catalogId: String, val timing: GaplessTransition, val startPositionMs: Long = 0L,
    )
    @Volatile private var lookaheadTrace: TransitionPlayback? = null
    @Volatile private var playingTrace: TransitionPlayback? = null
    private var confirmedLookahead: NativeSongQueue.Append? = null
    /** Diagnostics: a song change still waiting for its first audible position. */
    private class PendingSound(
        val catalogId: String?, val title: String, val startedMs: Long,
        val gapless: Boolean, val afterEnd: Boolean, val queueCommand: String? = null,
    )
    /** Fade at gapless song changes for the current queue; the app sets it per kind of queue. */
    @Volatile var fadeEnabled = false
    private var fade = GaplessFade()
    private var sessionLevel = SessionLevel()
    private val loudnessPrefs = context.getSharedPreferences(FLYME_PLAYBACK_PREFS, Context.MODE_PRIVATE)
    private val loudness by lazy {
        LoudnessRepository(LoudnessCache(File(context.noBackupFilesDir, "sound-check-v1.json")), catalogPreviewUrl)
    }
    private var trackLoudness: NativeTrackLoudness? = null
    private var appliedLoudnessDb = 0f
    private var lastFadeDb = 0f
    private val tailLoudness = TailLoudness()
    /** The song whose loudness [tailLoudness] holds, and whether its ending is fading by itself. */
    private var levelCatalogId: String? = null
    private var tailDecision: Boolean? = null
    private var previousTailQuiet = false
    private var fadeTicks = 0
    /** The song a gapless change moved into, faded in over its first seconds. */
    @Volatile private var fadeInCatalogId: String? = null
    @Volatile private var pollAfterCommandUntilMs = 0L

    private fun shouldPollPlayback(): Boolean {
        if (controller == null || pendingPlaybackError != null) return false
        if (preparing || SystemClock.elapsedRealtime() < pollAfterCommandUntilMs) return true
        val snapshot = latestSnapshot
        return desiredPlaying && !(snapshot?.requestGeneration == prepareGeneration.get() && snapshot.isEnded)
    }

    private fun restartPlaybackPolls(afterCommand: Boolean = false) {
        // A paused seek can settle asynchronously without a playback-state callback.
        if (afterCommand) pollAfterCommandUntilMs = SystemClock.elapsedRealtime() + 1_000L
        playbackHandler?.let { handler ->
            handler.removeCallbacks(fadeTick)
            handler.post(fadeTick)
        }
        mainHandler.removeCallbacks(snapshotPoll)
        if (playbackListener != null) mainHandler.post(snapshotPoll)
    }

    private fun stopPlaybackPolls() {
        pollAfterCommandUntilMs = 0L
        playbackHandler?.removeCallbacks(fadeTick)
        mainHandler.removeCallbacks(snapshotPoll)
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            val handler = playbackHandler ?: return
            if (Looper.myLooper() !== handler.looper || !shouldPollPlayback()) return
            tickFade()
            if (handler === playbackHandler && shouldPollPlayback()) {
                handler.removeCallbacks(this)
                handler.postDelayed(this, FADE_TICK_MS)
            }
        }
    }
    @Volatile private var pendingSound: PendingSound? = null
    @Volatile private var lastEndedAtMs = 0L
    private var reportedEndGeneration = 0L
    private val snapshotPoll = object : Runnable {
        override fun run() {
            if (playbackListener == null || !shouldPollPlayback()) return
            dispatchSnapshot()
            mainHandler.removeCallbacks(this)
            mainHandler.postDelayed(this, 250L)
        }
    }

    val isInstalled: Boolean
        get() = classExists(AUTH_FACTORY) && classExists(PLAYER_FACTORY)

    fun authenticationIntent(developerToken: String): Intent {
        check(isInstalled) { AppText.text(R.string.services_sdk_unavailable) }
        val factory = Class.forName(AUTH_FACTORY)
        val manager = factory.getMethod(
            "createAuthenticationManager",
            Context::class.java,
        ).invoke(null, context)
        authenticationManager = manager
        val builder = manager.javaClass.getMethod(
            "createIntentBuilder",
            String::class.java,
        ).invoke(manager, developerToken)
        return builder.javaClass.getMethod("build").invoke(builder) as Intent
    }

    fun userTokenFromResult(data: Intent): String {
        val manager = authenticationManager ?: run {
            val factory = Class.forName(AUTH_FACTORY)
            factory.getMethod("createAuthenticationManager", Context::class.java)
                .invoke(null, context)
                .also { authenticationManager = it }
        }
        val tokenResult = manager.javaClass.getMethod("handleTokenResult", Intent::class.java)
            .invoke(manager, data)
        val isError = tokenResult.javaClass.getMethod("isError").invoke(tokenResult) as Boolean
        check(!isError) {
            val error = tokenResult.javaClass.getMethod("getError").invoke(tokenResult)
            AppText.text(R.string.services_auth_failed, error?.toString() ?: "")
        }
        return tokenResult.javaClass.getMethod("getMusicUserToken")
            .invoke(tokenResult) as String
    }

    @Synchronized
    fun prepareAndPlay(track: Track, developerToken: String, userToken: String): Long =
        preparePlayback(track, developerToken, userToken, 0L, true)

    @Synchronized
    fun preparePlayback(
        track: Track,
        developerToken: String,
        userToken: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        transition: GaplessTransition? = null,
    ): Long {
        // A native queue we appended to (gapless) makes the next fresh prepare fail with
        // TYPE_PLAYBACK_QUEUE; start that request on a new controller instead.
        val rebuilding = controller != null && (playbackDeveloperToken != developerToken || playbackUserToken != userToken ||
                nativeQueueEdited)
        if (rebuilding) {
            transition?.mark(GaplessStage.Rebuild, SystemClock.elapsedRealtime())
            if (nativeQueueEdited) Log.i(TAG, "Rebuilding controller after a gapless queue edit")
            release()
        }
        nativeQueueEdited = false
        val generation = prepareGeneration.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        pendingSound = if (playWhenReady && track.catalogKind == CatalogKind.Song) {
            PendingSound(track.catalogId, track.title, now, gapless = false,
                afterEnd = lastEndedAtMs != 0L && now - lastEndedAtMs < AFTER_END_WINDOW_MS)
        } else null
        ensurePlaybackRuntime()
        requestedTrack = track
        preparing = true
        endedCatalogId = null
        preparedNativeQueueId = null
        lookahead = null
        lookaheadTrace = null
        queuedLookaheadCatalogId = null
        gaplessAdvanceCatalogId = null
        pendingPlaybackError = null
        pendingFailureKind = null
        desiredPlaying = playWhenReady
        playbackDeveloperToken = developerToken
        playbackUserToken = userToken
        val currentController = controller ?: createController(developerToken, userToken)
            .also { controller = it }
        if (rebuilding) transition?.mark(GaplessStage.Rebuilt, SystemClock.elapsedRealtime())
        fadeInCatalogId = null
        val storeId = checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
        val requestedPosition = if (track.catalogKind == CatalogKind.Station) 0L else positionMs
        playingTrace = transition?.let { TransitionPlayback(generation, storeId, it, requestedPosition) }
        restartPlaybackPolls()
        postPlaybackAction("prepare") {
            if (generation != prepareGeneration.get()) return@postPlaybackAction
            activeGeneration = generation
            resetTrackGain()
            prefetchLoudness(track)
            nativeSongQueue = NativeSongQueue()
            confirmedLookahead = null
            requestedNativeIndex = null
            queuedLookaheadCatalogId = null
            lastStartupState = null
            installRequestListener(currentController, generation)
            playbackIntent.begin(track.catalogId, requestedPosition, desiredPlaying, SystemClock.elapsedRealtime())
            val builderClass = Class.forName(QUEUE_BUILDER)
            val provider = createCatalogPlaybackQueueProvider(track, builderClass)
            val prepare = currentController.javaClass.methods.first {
                it.name == "prepare" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            // Never emit the beginning of a restored song. Readiness and any
            // resume seek are confirmed before the intent machine issues play().
            issuedPrepareGeneration = generation
            Log.i(TAG, "Prepare generation=$generation catalog=$storeId kind=${track.catalogKind} " +
                "source=${track.resourceType}:${track.resourceId} positionMs=$positionMs playWhenReady=$desiredPlaying")
            transition?.mark(GaplessStage.Prepare, SystemClock.elapsedRealtime())
            prepare.invoke(currentController, provider, false)
        }
        return generation
    }

    fun play() {
        desiredPlaying = true
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            if (generation != prepareGeneration.get()) return@post
            playbackIntent.requestPlaying(true)
            restartPlaybackPolls(afterCommand = true)
            dispatchSnapshot()
        }
    }
    fun pause() {
        desiredPlaying = false
        if (!preparing) stopPlaybackPolls()
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            if (generation != prepareGeneration.get()) return@post
            playbackIntent.requestPlaying(false)
            controller?.let { runCatching { it.callVoid("pause") } }
            dispatchSnapshot()
        }
    }
    fun seekTo(positionMs: Long) {
        val value = checkNotNull(controller) { AppText.text(R.string.services_playback_not_prepared) }
        postPlaybackAction("seekToPosition") {
            if (value.callOrNull("isLiveStream") == true || value.callOrNull("canSeek") == false) return@postPlaybackAction
            playbackIntent.requestSeek()
            endedCatalogId = null
            value.javaClass.getMethod("seekToPosition", Long::class.javaPrimitiveType).invoke(value, positionMs)
            restartPlaybackPolls(afterCommand = true)
        }
    }
    /**
     * Queue (or clear) the song the app will play next, so MusicKit can preload it and move on
     * without a new prepare. The app queue stays authoritative; only this one item is mirrored.
     */
    fun setLookahead(next: Track?, transition: GaplessTransition? = null) {
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            synchronized(this) {
                if (generation != prepareGeneration.get()) return@post
                lookahead = next?.takeIf { it.catalogId != null && it.catalogKind == CatalogKind.Song }
                lookaheadTrace = lookahead?.catalogId?.let { catalogId ->
                    transition?.let { TransitionPlayback(generation, catalogId, it) }
                }
                lookahead?.let(::prefetchLoudness)
                syncLookahead()
            }
        }
    }

    /**
     * Playback thread: leaves exactly the wanted song after the current native item. In 1.1.1
     * getQueueItems() comes back empty, so the item count and the song we queued are tracked here.
     */
    @Synchronized
    private fun syncLookahead() {
        val currentController = controller ?: return
        if (preparing || endedCatalogId != null || nativeSongQueue.pendingAppend != null ||
            requestedTrack?.catalogKind != CatalogKind.Song
        ) return
        val index = (currentController.callOrNull("getPlaybackQueueIndex") as? Int)?.takeIf { it >= 0 } ?: return
        if (nativeSongQueue.activeIndex != index) {
            // The app still names the previous entry until the next snapshot adopts the handoff.
            dispatchSnapshot()
            return
        }
        val count = currentController.callOrNull("getPlaybackQueueItemCount") as? Int ?: return
        val following = count - index - 1
        if (following < 0) return
        val wanted = lookahead
        if (following == 1 && confirmedLookahead?.let {
                it.index == index + 1 && it.matchesPlan(wanted?.catalogId, lookaheadTrace?.timing?.entryId)
            } == true
        ) {
            queuedLookaheadCatalogId = wanted?.catalogId
            return
        }
        queuedLookaheadCatalogId = null
        confirmedLookahead = null
        if (following > 0) {
            @Suppress("UNCHECKED_CAST")
            val items = (currentController.callOrNull("getQueueItems") as? List<Any?>).orEmpty().filterNotNull()
            val remove = currentController.javaClass.getMethod("removeQueueItemWithId", Long::class.javaPrimitiveType)
            items.drop(index + 1).forEach { item ->
                (item.callOrNull("getPlaybackQueueId") as? Long)?.let { runCatching { remove.invoke(currentController, it) } }
            }
            if (items.isNotEmpty()) nativeSongQueue.forgetFrom(index + 1)
            val left = (currentController.callOrNull("getPlaybackQueueItemCount") as? Int ?: count) - index - 1
            if (left > 0) {
                // A stale song we cannot address stays queued; report the real end so the app
                // prepares its own next song instead of trusting the native continuation.
                Log.i(TAG, "Gapless lookahead dropped: $left native item(s) after current")
                diagnostics.record(AppText.text(R.string.diagnostics_lookahead_dropped, left))
                return
            }
            if (currentController.callOrNull("getPlaybackQueueIndex") != index) return
            nativeSongQueue.forgetFrom(index + 1)
        }
        if (wanted == null || currentController.callOrNull("canAppendToPlaybackQueue") != true) return
        val append = nativeSongQueue.beginAppend(
            prepareGeneration.get(), index, checkNotNull(wanted.catalogId), lookaheadTrace?.timing,
        ) ?: return
        runCatching {
            val provider = createCatalogPlaybackQueueProvider(wanted, Class.forName(QUEUE_BUILDER))
            val addQueueItems = currentController.javaClass.methods.first {
                it.name == "addQueueItems" && it.parameterTypes.size == 2
            }
            append.timing?.mark(GaplessStage.Append, SystemClock.elapsedRealtime())
            addQueueItems.invoke(currentController, provider, INSERTION_TYPE_AT_END)
            nativeQueueEdited = true
            Log.i(TAG, "Gapless lookahead queued catalog=${wanted.catalogId}")
        }.onFailure {
            nativeSongQueue.clearAppend()
            logLookahead(append, "rejected", index, count, "append-failed")
            Log.w(TAG, "Gapless lookahead failed: ${it.javaClass.simpleName}")
            diagnostics.record(AppText.text(R.string.diagnostics_lookahead_failed, wanted.title, it.javaClass.simpleName))
        }
    }

    private fun logLookahead(
        append: NativeSongQueue.Append, result: String, index: Int?, count: Int?, reason: String,
        callbackArgs: Array<out Any?>? = null,
    ) {
        val callback = callbackArgs?.let { " callbackArgs=${it.contentToString()}" }.orEmpty()
        diagnostics.recordGapless(
            "lookahead=$result generation=${append.generation} entry=${append.timing?.entryId} " +
                "catalog=${append.catalogId} expectedIndex=${append.index} index=$index count=$count reason=$reason$callback",
        )
    }

    private fun confirmLookahead(generation: Long, index: Int, count: Int, catalogId: String?): Boolean {
        val append = nativeSongQueue.confirmAppend(generation, index, count, catalogId) ?: return false
        confirmedLookahead = append
        queuedLookaheadCatalogId = append.catalogId
        logLookahead(append, "confirmed", index, count, "callback-and-queue")
        return true
    }

    fun skipToNext() = invokeController("skipToNextItem")

    private fun loudnessMode() = LoudnessMode.fromPreference(loudnessPrefs.getString(LOUDNESS_PREF, null))

    fun prefetchLoudness(track: Track) {
        if (track.catalogKind == CatalogKind.Song && loudnessMode() != LoudnessMode.Off) loudness.request(track)
    }

    private fun resetTrackGain() {
        trackLoudness = null
        appliedLoudnessDb = 0f
        lastFadeDb = 0f
        fade.reset()
    }

    @Synchronized
    private fun syncTrackLoudness(generation: Long, track: Track, slot: Int?) {
        if (generation != prepareGeneration.get() || generation != activeGeneration) return
        val requested = requestedTrack ?: return
        if (requested.catalogKind != CatalogKind.Song ||
            (requestedNativeIndex != null && requestedNativeIndex != slot) ||
            (preparing && requested.catalogId != track.catalogId) ||
            (endedCatalogId != null && endedCatalogId != track.catalogId && lookahead?.catalogId != track.catalogId)
        ) return
        val active = trackLoudness
        val mode = loudnessMode()
        val sameTrack = active?.generation == generation && active.trackId == track.id && active.slot == slot
        // A mode change in Settings takes effect on the song that is playing, ramping from its current gain.
        if (sameTrack && active?.mode == mode) return
        val carriedGain = active?.takeIf { sameTrack }?.currentGain()
        val cached = if (mode == LoudnessMode.Off) null else loudness.cached(track.catalogId)
        val pending = if (mode == LoudnessMode.Off || cached != null) null else loudness.request(track)
        val lookup = when {
            mode == LoudnessMode.Off -> LoudnessLookup(null, "missing", "disabled")
            cached != null -> LoudnessLookup(cached, "cache")
            pending != null -> LoudnessLookup(null, "missing", "lookup-pending")
            track.catalogId?.startsWith("i.") == true || track.resourceId?.startsWith("i.") == true ->
                LoudnessLookup(null, "missing", "library-upload")
            else -> LoudnessLookup(null, "missing", "no-catalog-id")
        }
        trackLoudness = NativeTrackLoudness(generation, track.id, slot, track.catalogId, mode, lookup, pending, carriedGain)
        if (carriedGain == null) lastFadeDb = 0f
        tickFade()
    }

    /** Playback thread: update the fade and any one-time normalization ramp in one effect. */
    @Synchronized
    private fun tickFade() {
        val current = controller ?: return
        val generation = prepareGeneration.get()
        if (activeGeneration != generation) return
        val session = MusicKitAudioSession.currentId()
        val playing = (current.callOrNull("getPlaybackState") as? Int) == PLAYBACK_STATE_PLAYING
        if (fadeEnabled && playing) {
            applyGaplessFade(current, session)
        } else {
            if (!fadeEnabled) {
                lastFadeDb = 0f
                sessionLevel.release()
            }
            applyTrackGain(session, lastFadeDb, playing)
        }
    }

    private fun applyTrackGain(session: Int, fadeDb: Float, playing: Boolean) {
        lastFadeDb = fadeDb
        val active = trackLoudness?.takeIf { it.generation == activeGeneration }
        appliedLoudnessDb = active?.apply(fade, session, fadeDb, playing, SystemClock.elapsedRealtime())
            ?: fade.apply(session, fadeDb, 0f, limit = false)
    }

    private fun applyGaplessFade(current: Any, session: Int) {
        val position = current.callOrNull("getCurrentPosition") as? Long
        val duration = (current.callOrNull("getDuration") as? Long)?.takeIf { it > 0L }
        if (position == null || duration == null) {
            applyTrackGain(session, lastFadeDb, playing = true)
            return
        }
        val currentId = current.callOrNull("getCurrentItem")?.callOrNull("getItem")
            ?.callOrNull("getSubscriptionStoreId") as? String
        val remaining = duration - position
        if (currentId != levelCatalogId) {
            // A new song: an ending that faded by itself is followed by a plain start too.
            previousTailQuiet = tailDecision == true
            levelCatalogId = currentId
            tailLoudness.clear()
            tailDecision = null
        }
        if (fadeTicks++ % LEVEL_EVERY_TICKS == 0 && remaining >= FadeCurve.OUT_MS) {
            val allowed = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            sessionLevel.read(session, allowed)?.let { tailLoudness.add(it, position, remaining) }
        }
        val outDb = if (queuedLookaheadCatalogId != null && remaining in 0L until FadeCurve.OUT_MS) {
            val quiet = tailDecision ?: tailLoudness.alreadyQuiet().also {
                tailDecision = it
                Log.i(TAG, if (it) "Fade: ending already quiet, left alone" else "Fade: fading out")
            }
            if (quiet) 0f else FadeCurve.outDb(remaining)
        } else 0f
        val fadingIn = fadeInCatalogId
        val inDb = if (fadingIn != null && currentId == fadingIn && position < FadeCurve.IN_MS && !previousTailQuiet) {
            FadeCurve.inDb(position)
        } else {
            if (fadingIn != null && currentId == fadingIn) fadeInCatalogId = null
            0f
        }
        applyTrackGain(session, minOf(outDb, inDb), playing = true)
        if (outDb < 0f) lookaheadTrace?.timing?.fade(outgoing = true, fade.attachment(session))
        if (inDb < 0f) playingTrace?.takeIf { it.catalogId == currentId }
            ?.timing?.fade(outgoing = false, fade.attachment(session))
    }

    /** Diagnostics: why a song that just ended did not continue into a queued next song. */
    private fun recordEndWithoutContinuation(title: String) {
        val wanted = lookahead
        diagnostics.record(when {
            wanted == null -> AppText.text(R.string.diagnostics_end_no_lookahead, title)
            queuedLookaheadCatalogId != wanted.catalogId -> AppText.text(R.string.diagnostics_end_not_queued, title, wanted.title)
            else -> AppText.text(R.string.diagnostics_end_not_continued, title, wanted.title)
        })
    }

    private fun recordGaplessEnd(generation: Long, catalogId: String?, atMs: Long, source: String) {
        if (requestedTrack?.catalogKind != CatalogKind.Song) return
        val wanted = lookahead
        val reason = when {
            wanted == null -> "no-lookahead"
            queuedLookaheadCatalogId != wanted.catalogId -> "lookahead-not-queued"
            else -> "native-did-not-continue"
        }
        diagnostics.lastGaplessEnd = GaplessEnd(generation, catalogId, atMs, source, reason)
    }

    @Synchronized
    private fun recordCompletedTransition(trace: TransitionPlayback, observedAt: Long) {
        if (playingTrace !== trace || trace.generation != prepareGeneration.get()) return
        playingTrace = null
        trace.timing.mark(GaplessStage.Advanced, observedAt)
        val session = MusicKitAudioSession.currentId()
        applyTrackGain(session, lastFadeDb, playing = true)
        val fadeStatus = if (fadeEnabled) fade.attachment(session) else "disabled"
        val loudnessDb = appliedLoudnessDb
        mainHandler.post {
            diagnostics.recordGapless(
                trace.timing.finish(observedAt, trace.catalogId, diagnosticAudioRoute(), session, fadeStatus, loudnessDb),
            )
        }
    }

    private fun diagnosticAudioRoute(): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val audio = context.getSystemService(AudioManager::class.java)
            val types = audio.getAudioDevicesForAttributes(attributes).map { it.type }.distinct()
            return "media-policy:${types.joinToString(",").ifEmpty { "unknown" }}"
        }
        val router = context.getSystemService(MediaRouter::class.java)
        val route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        return "media-router:${route.deviceType}:${route.playbackType}"
    }

    /** Only a manual move within the app queue may reuse the controller's current songs. */
    suspend fun playQueuedSong(
        track: Track, transition: GaplessTransition?, confirmedLookaheadOnly: Boolean,
    ): Long? {
        val handler = playbackHandler ?: return null
        val generation = prepareGeneration.get()
        return suspendCancellableCoroutine { result ->
            val posted = handler.post {
                if (!result.isActive) return@post
                result.resumeWith(runCatching {
                    playQueuedSongOnHandler(track, generation, transition, confirmedLookaheadOnly)
                })
            }
            if (!posted) result.resume(null)
        }
    }

    @Synchronized
    private fun playQueuedSongOnHandler(
        track: Track, generation: Long, transition: GaplessTransition?, confirmedLookaheadOnly: Boolean,
    ): Long? {
        val current = controller ?: return null
        if (generation != prepareGeneration.get() || generation != activeGeneration ||
            requestedTrack?.catalogKind != CatalogKind.Song || endedCatalogId != null ||
            pendingPlaybackError != null || nativeSongQueue.pendingAppend != null ||
            (preparing && requestedNativeIndex == null)
        ) return null
        val catalogId = checkNotNull(track.catalogId)
        val index = (current.callOrNull("getPlaybackQueueIndex") as? Int)?.takeIf { it >= 0 } ?: return null
        val count = current.callOrNull("getPlaybackQueueItemCount") as? Int ?: return null
        val nativeTrack = current.callOrNull("getCurrentItem")?.let(::trackFromQueueItem) ?: return null
        nativeTrack.catalogId?.let { nativeSongQueue.record(index, it, nativeTrack.playbackQueueId) }
        val atRequestedItem = requestedNativeIndex == null || requestedNativeIndex == index
        val canSkipNext = atRequestedItem && current.callOrNull("canSkipToNextItem") == true
        val command = if (confirmedLookaheadOnly) {
            val append = confirmedLookahead?.takeIf {
                it.generation == generation && it.index == index + 1 && it.catalogId == catalogId &&
                    queuedLookaheadCatalogId == catalogId && !preparing
            } ?: return null
            if (!canSkipNext || append.index >= count || !nativeSongQueue.matches(append.index, catalogId)) return null
            transition?.let { timing ->
                timing.start(GaplessPath.ManualNative, SystemClock.elapsedRealtime())
                timing.timeOf(GaplessStage.Requested)?.let { timing.mark(GaplessStage.Resolved, it) }
                for (stage in listOf(GaplessStage.Append, GaplessStage.Added)) {
                    append.timing?.timeOf(stage)?.let { timing.mark(stage, it) }
                }
            }
            NativeSongQueue.Command.Next(append.index)
        } else {
            nativeSongQueue.command(
                catalogId, index, count,
                canSeek = atRequestedItem && current.callOrNull("canSeek") == true,
                canSkipNext = canSkipNext,
                canSkipById = current.callOrNull("canSkipToQueueItem") == true,
            ) ?: return null
        }

        val newGeneration = prepareGeneration.incrementAndGet()
        requestedTrack = track
        requestedNativeIndex = command.index
        preparing = true
        endedCatalogId = null
        preparedNativeQueueId = null
        reportedEndGeneration = 0L
        lookahead = null
        lookaheadTrace = null
        queuedLookaheadCatalogId = null
        confirmedLookahead = null
        gaplessAdvanceCatalogId = null
        fadeInCatalogId = null
        resetTrackGain()
        prefetchLoudness(track)
        val now = SystemClock.elapsedRealtime()
        playingTrace = transition?.let { TransitionPlayback(newGeneration, catalogId, it) }
        playbackIntent.begin(catalogId, 0L, desiredPlaying, now)
        activeGeneration = newGeneration
        issuedPrepareGeneration = newGeneration
        installRequestListener(current, newGeneration)
        val method = when (command) {
            is NativeSongQueue.Command.Restart -> "seekToPosition"
            is NativeSongQueue.Command.Next -> "skipToNextItem"
            is NativeSongQueue.Command.ById -> "skipToQueueItemWithId"
        }
        pendingSound = PendingSound(catalogId, track.title, now, gapless = false, afterEnd = false, queueCommand = method)
        diagnostics.logQueueMove(newGeneration, catalogId, method, index, command.index)
        when (command) {
            is NativeSongQueue.Command.Restart -> current.javaClass.getMethod(method, Long::class.javaPrimitiveType)
                .invoke(current, 0L)
            is NativeSongQueue.Command.Next -> current.callVoid(method)
            is NativeSongQueue.Command.ById -> current.javaClass.getMethod(method, Long::class.javaPrimitiveType)
                .invoke(current, command.queueId)
        }
        restartPlaybackPolls(afterCommand = true)
        dispatchSnapshot()
        return newGeneration
    }
    fun skipToPrevious() = invokeController("skipToPreviousItem")
    fun skipToQueueItem(queueId: Long) = invokeController("skipToQueueItemWithId", queueId)
    fun setRepeatMode(mode: PlaybackRepeatMode) =
        invokeController("setRepeatMode", mode.sdkValue)
    fun setShuffleEnabled(enabled: Boolean) =
        invokeController("setShuffleMode", if (enabled) 1 else 0)

    /**
     * Continuous playback arrived in the controller bundled with Apple Music
     * after the public 1.1.1 AAR. Keep the capability runtime-driven: a newer
     * licensed SDK can expose it without changing this app, while 1.1.1 never
     * presents a control that cannot affect playback.
     */
    fun setAutoplayEnabled(enabled: Boolean) =
        invokeController("setContinuousPlaybackState", enabled)

    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        playbackListener = listener
        mainHandler.removeCallbacks(snapshotPoll)
        if (listener != null && shouldPollPlayback()) mainHandler.post(snapshotPoll)
        val currentController = controller
        if (listener != null && currentController != null && controllerListener == null) {
            val generation = prepareGeneration.get()
            playbackHandler?.post {
                if (currentController === controller && generation == prepareGeneration.get() &&
                    activeGeneration == generation
                ) {
                    installRequestListener(currentController, generation)
                    dispatchSnapshot()
                }
            }
        }
    }

    /** Includes SDK-restored queues; querying never prepares or constructs a renderer. */
    fun audioSessionId(): Int = if (controller != null) MusicKitAudioSession.currentId() else 0

    fun playbackSnapshot(): PlaybackSnapshot? = latestSnapshot

    @Synchronized
    fun release() {
        prepareGeneration.incrementAndGet()
        stopPlaybackPolls()
        snapshotScheduled = false
        playingTrace = null
        lookaheadTrace = null
        nativeSongQueue.clearAppend()
        confirmedLookahead = null
        val currentController = controller
        val listener = controllerListener
        val oldHandler = playbackHandler
        val oldThread = playbackThread
        val oldFade = fade
        val oldLevel = sessionLevel
        fade = GaplessFade()
        sessionLevel = SessionLevel()
        val cleanup = {
            if (currentController != null && listener != null) {
                runCatching {
                    currentController.javaClass.methods.first {
                        it.name == "removeListener" && it.parameterTypes.size == 1
                    }.invoke(currentController, listener)
                }
            }
            runCatching { currentController?.callVoid("release") }
            oldFade.release()
            oldLevel.release()
            oldThread?.quitSafely()
            Unit
        }
        if (oldHandler == null || !oldHandler.post(cleanup)) cleanup()
        controllerListener = null
        controller = null
        latestSnapshot = null
        pendingPlaybackError = null
        pendingFailureKind = null
        activeGeneration = 0L
        issuedPrepareGeneration = 0L
        desiredPlaying = false
        preparing = false
        endedCatalogId = null
        preparedNativeQueueId = null
        playbackDeveloperToken = null
        playbackUserToken = null
        playbackHandler = null
        playbackThread = null
    }

    private fun createController(developerToken: String, userToken: String): Any {
        check(isInstalled) { AppText.text(R.string.services_sdk_unavailable) }
        val providerClass = Class.forName(TOKEN_PROVIDER)
        val tokenProvider = MusicKitTokenProvider(developerToken, userToken)
        val factory = Class.forName(PLAYER_FACTORY)
        val createdController = runCatching {
            // Apple's bundled SDK test app creates its controller on a
            // dedicated HandlerThread. Keep callbacks and the legacy native
            // player off Compose's main thread as the reference app does. Default priority:
            // prepare, skip and the queue edits wait on this thread, and a background-class
            // thread loses the CPU to the UI on slower car head units.
            val worker = HandlerThread(
                "AMATV:MusicKit",
                Process.THREAD_PRIORITY_DEFAULT,
            ).also {
                it.start()
                playbackThread = it
            }
            val handler = Handler(worker.looper).also { playbackHandler = it }
            factory.getMethod(
                "createLocalController",
                Context::class.java,
                Handler::class.java,
                providerClass,
            ).invoke(null, context, handler, tokenProvider).also {
                Log.i(TAG, "Created controller with the explicit-Handler overload.")
            }
        }.recoverCatching { handlerOverloadError ->
            playbackThread?.quitSafely()
            playbackThread = null
            playbackHandler = null
            Log.w(
                TAG,
                "Explicit-Handler controller overload failed; using main thread.",
                handlerOverloadError,
            )
            factory.getMethod(
                "createLocalController",
                Context::class.java,
                providerClass,
            ).invoke(null, context, tokenProvider).also {
                playbackHandler = mainHandler
                Log.i(TAG, "Created controller with the main-thread callback overload.")
            }
        }.getOrElse { error ->
            playbackThread?.quitSafely()
            playbackThread = null
            playbackHandler = null
            throw error
        }
        return checkNotNull(createdController) {
            AppText.text(R.string.services_controller_unavailable)
        }
    }

    /** Install on the controller's handler before prepare, retaining a request-specific epoch. */
    private fun installRequestListener(currentController: Any, generation: Long) {
        if (generation != prepareGeneration.get() || currentController !== controller) return
        controllerListener?.let { old ->
            currentController.javaClass.methods.first {
                it.name == "removeListener" && it.parameterTypes.size == 1
            }.invoke(currentController, old)
        }
        controllerListener = null
        if (generation != prepareGeneration.get()) return
        val listenerClass = Class.forName(CONTROLLER_LISTENER)
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "AMATVPlaybackListener($generation)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> synchronized(this@MusicKitBridge) {
                    // The epoch belongs to this registration, not the mutable controller.
                    // Synchronize validation+writes with preparePlayback/release so an old
                    // callback cannot write an error after a new request has cleared it.
                    if (generation != prepareGeneration.get() || activeGeneration != generation ||
                        issuedPrepareGeneration != generation || currentController !== controller
                    ) return@synchronized null
                    when (method.name) {
                        "onPlaybackQueueItemsAdded" -> {
                            // The SDK reports insertion, container and item types. Confirm the count from the queue.
                            nativeSongQueue.pendingAppend?.let { append ->
                                val callbackArgs = checkNotNull(args)
                                if (append.acknowledge(generation, callbackArgs[1] as Int, callbackArgs[2] as Int)) {
                                    append.timing?.mark(GaplessStage.Added, SystemClock.elapsedRealtime())
                                } else {
                                    val index = currentController.callOrNull("getPlaybackQueueIndex") as? Int
                                    val count = currentController.callOrNull("getPlaybackQueueItemCount") as? Int
                                    logLookahead(append, "rejected", index, count, "unexpected-callback", callbackArgs)
                                }
                            }
                            Log.d(TAG, method.name)
                            dispatchSnapshot()
                        }
                        "onCurrentItemChanged" -> {
                            val catalogId = args?.getOrNull(2)?.callOrNull("getItem")
                                ?.callOrNull("getSubscriptionStoreId") as? String
                            val now = SystemClock.elapsedRealtime()
                            playingTrace?.takeIf { it.generation == generation && it.catalogId == catalogId }
                                ?.timing?.mark(GaplessStage.ItemChanged, now)
                            lookaheadTrace?.takeIf { it.generation == generation && it.catalogId == catalogId }
                                ?.timing?.mark(GaplessStage.ItemChanged, now)
                            playbackHandler?.post {
                                if (generation == prepareGeneration.get() && currentController === controller) {
                                    currentController.callOrNull("getCurrentItem")?.let(::trackFromQueueItem)?.let { track ->
                                        val slot = currentController.callOrNull("getPlaybackQueueIndex") as? Int
                                        syncTrackLoudness(generation, track, slot)
                                    }
                                }
                            }
                            Log.d(TAG, method.name)
                            dispatchSnapshot()
                        }
                        "onBufferingStateChanged" -> {
                            val buffering = args?.getOrNull(1) as? Boolean
                            if (buffering != null) {
                                val timing = lookaheadTrace?.takeIf {
                                    it.generation == generation && (it.timing.has(GaplessStage.Ended) ||
                                        it.timing.has(GaplessStage.ItemChanged))
                                } ?: playingTrace?.takeIf { it.generation == generation }
                                timing?.timing?.buffering(buffering, SystemClock.elapsedRealtime())
                            }
                            Log.d(TAG, method.name)
                            dispatchSnapshot()
                        }
                        "onPlaybackError" -> {
                            val nativeCurrent = currentController.callOrNull("getCurrentItem")?.let(::trackFromQueueItem)
                            val stationContainerMatches = isCurrentStationContainer(currentController, requestedTrack)
                            if (isPlaybackErrorForActiveRequest(
                                    callbackGeneration = generation,
                                    requestGeneration = prepareGeneration.get(),
                                    activeGeneration = activeGeneration,
                                    issuedPrepareGeneration = issuedPrepareGeneration,
                                    isCurrentController = currentController === controller,
                                    requestPreparing = preparing,
                                    nativeCatalogId = nativeCurrent?.catalogId,
                                    requestedCatalogId = requestedTrack?.catalogId,
                                    stationContainerMatches = stationContainerMatches,
                                )
                            ) {
                                val error = args?.lastOrNull()
                                val firstFailure = pendingPlaybackError == null
                                pendingPlaybackError = describePlaybackError(error)
                                pendingFailureKind = classifyNativeFailure(error)
                                if (firstFailure) {
                                    Log.w(TAG, "Native playback failure generation=$generation " +
                                        "${nativePlaybackErrorDiagnostics(error)} kind=$pendingFailureKind")
                                }
                                dispatchSnapshot()
                            }
                        }
                        "onItemEnded" -> {
                            val endedTrack = args?.getOrNull(1)?.let(::trackFromQueueItem)
                            val endPosition = args?.getOrNull(2) as? Long ?: 0L
                            // With the next song queued natively, MusicKit continues by itself;
                            // reporting the end would make the app prepare that song again.
                            val index = currentController.callOrNull("getPlaybackQueueIndex") as? Int
                            val count = currentController.callOrNull("getPlaybackQueueItemCount") as? Int
                            if (index != null && count != null) {
                                val currentId = currentController.callOrNull("getCurrentItem")?.let(::trackFromQueueItem)?.catalogId
                                confirmLookahead(generation, index, count, currentId)
                            }
                            val ready = confirmedLookahead
                            val completed = isCompletedPlaybackItem(
                                eventGeneration = generation,
                                requestGeneration = prepareGeneration.get(),
                                prepared = !preparing,
                                requestedCatalogId = requestedTrack?.catalogId,
                                endedCatalogId = endedTrack?.catalogId,
                                expectedNativeQueueId = preparedNativeQueueId,
                                endedNativeQueueId = endedTrack?.playbackQueueId,
                                endPositionMs = endPosition,
                                durationMs = endedTrack?.durationMs ?: 0L,
                            )
                            val continuesNatively = ready != null && ready.matchesPlan(
                                lookahead?.catalogId, lookaheadTrace?.timing?.entryId,
                            ) && count == ready.index + 1 && (index == ready.index - 1 || index == ready.index) &&
                                completed
                            lastEndedAtMs = SystemClock.elapsedRealtime()
                            playingTrace?.takeIf {
                                it.generation == generation && it.timing.matchesPreviousEnd(generation, endedTrack?.catalogId)
                            }?.timing?.ended(GaplessEnd(generation, endedTrack?.catalogId, lastEndedAtMs, method.name))
                            if (continuesNatively) {
                                lookaheadTrace?.takeIf { it.generation == generation }?.timing?.ended(
                                    GaplessEnd(generation, endedTrack?.catalogId, lastEndedAtMs, method.name),
                                )
                                Log.i(TAG, "Gapless item ended; continuing into catalog=${lookahead?.catalogId}")
                                lookahead?.let {
                                    pendingSound = PendingSound(it.catalogId, it.title, lastEndedAtMs, gapless = true, afterEnd = true)
                                    fadeInCatalogId = it.catalogId
                                }
                            } else if (completed && reportedEndGeneration != generation) {
                                nativeSongQueue.pendingAppend?.let {
                                    logLookahead(it, "rejected", index, count, "unconfirmed-at-end")
                                }
                                endedCatalogId = endedTrack?.catalogId
                                reportedEndGeneration = generation
                                recordGaplessEnd(generation, endedTrack?.catalogId, lastEndedAtMs, method.name)
                                recordEndWithoutContinuation(endedTrack?.title ?: requestedTrack?.title.orEmpty())
                            }
                            dispatchSnapshot()
                        }
                        else -> if (method.name.startsWith("on")) {
                            Log.d(TAG, method.name)
                            dispatchSnapshot()
                        }
                    }
                    null
                }
            }
        }
        currentController.javaClass.methods.first {
            it.name == "addListener" && it.parameterTypes.size == 1
        }.invoke(currentController, listener)
        synchronized(this) {
            if (generation == prepareGeneration.get() && currentController === controller) {
                controllerListener = listener
            } else {
                runCatching {
                    currentController.javaClass.methods.first {
                        it.name == "removeListener" && it.parameterTypes.size == 1
                    }.invoke(currentController, listener)
                }
            }
        }
    }

    private fun dispatchSnapshot() {
        if (playbackListener == null || snapshotScheduled) return
        snapshotScheduled = true
        val generation = prepareGeneration.get()
        val readAndDeliver: () -> Unit = readAndDeliver@{
            snapshotScheduled = false
            val snapshot = synchronized(this) {
                if (generation != prepareGeneration.get() || generation != activeGeneration) return@readAndDeliver
                controller?.let { readSnapshot(it, generation) }
            } ?: return@readAndDeliver
            val resumed = snapshot.isPlaying && latestSnapshot?.isPlaying != true
            if (latestSnapshot?.isPlaying == true && !snapshot.isPlaying && !snapshot.isBuffering && desiredPlaying) {
                // The app did not ask for this pause: the SDK stopped by itself (audio focus, route, error).
                Log.i("AMATV-Pause", "source=sdk-without-app-command positionMs=${snapshot.positionMs} " +
                    "durationMs=${snapshot.durationMs} track=${snapshot.currentTrack?.catalogId}")
            }
            latestSnapshot = snapshot
            if (resumed) restartPlaybackPolls()
            mainHandler.post {
                if (generation == prepareGeneration.get()) playbackListener?.invoke(snapshot)
            }
            Unit
        }
        val handler = playbackHandler
        if (handler == null) mainHandler.postDelayed(readAndDeliver, SNAPSHOT_DELAY_MS)
        else handler.postDelayed(readAndDeliver, SNAPSHOT_DELAY_MS)
    }

    private fun readSnapshot(currentController: Any, generation: Long): PlaybackSnapshot {
        val currentQueueItem = currentController.callOrNull("getCurrentItem")
        val sdkTrack = currentQueueItem?.let(::trackFromQueueItem) ?: requestedTrack
        val index = currentController.callOrNull("getPlaybackQueueIndex") as? Int
        val count = currentController.callOrNull("getPlaybackQueueItemCount") as? Int
        val nativeCatalogId = sdkTrack?.catalogId
        val justConfirmed = index != null && count != null &&
            confirmLookahead(generation, index, count, nativeCatalogId)
        if (requestedTrack?.catalogKind == CatalogKind.Song && currentQueueItem != null &&
            index != null && index >= 0 && nativeCatalogId != null
        ) {
            nativeSongQueue.record(index, nativeCatalogId, sdkTrack.playbackQueueId)
        }
        val queued = lookahead
        val confirmed = confirmedLookahead
        if (!preparing && endedCatalogId == null && queued != null && currentQueueItem != null &&
            confirmed != null && index == confirmed.index && count == confirmed.index + 1 &&
            confirmed.matchesPlan(queued.catalogId, lookaheadTrace?.timing?.entryId) &&
            nativeCatalogId == queued.catalogId && requestedTrack?.catalogId != queued.catalogId
        ) {
            // MusicKit continued into the queued song: adopt it as the request, no prepare.
            Log.i(TAG, "Gapless advance generation=$generation catalog=${queued.catalogId}")
            val now = SystemClock.elapsedRealtime()
            playingTrace = lookaheadTrace?.takeIf { it.generation == generation && it.catalogId == nativeCatalogId }
                ?.also { trace ->
                    val inferred = trace.timing.timeOf(GaplessStage.ItemChanged) ?: now
                    trace.timing.start(GaplessPath.Native, now)
                    if (!trace.timing.has(GaplessStage.Ended)) {
                        trace.timing.ended(GaplessEnd(generation, requestedTrack?.catalogId, inferred, "inferred-item-change"))
                    }
                }
            lookaheadTrace = null
            requestedTrack = queued
            lookahead = null
            queuedLookaheadCatalogId = null
            confirmedLookahead = null
            endedCatalogId = null
            preparedNativeQueueId = sdkTrack?.playbackQueueId
            nativeSongQueue.activeIndex = index
            gaplessAdvanceCatalogId = queued.catalogId
            reportedEndGeneration = 0L
            playbackIntent.adoptContinuation(queued.catalogId, SystemClock.elapsedRealtime())
        }
        val liveStream = currentController.callOrNull("isLiveStream") as? Boolean ?: false
        val canSeek = currentController.callOrNull("canSeek") as? Boolean ?: !liveStream
        val currentStationContainer = isCurrentStationContainer(currentController, requestedTrack, liveStream)
        // MusicKit may enter an old, unaddressable lookahead after a queue edit. Publish the
        // validated end for the requested song so the app can choose the actual next entry.
        val unrequestedAdvance = !preparing && currentQueueItem != null &&
            requestedTrack?.catalogKind == CatalogKind.Song && nativeSongQueue.hasAdvanced(index)
        if (unrequestedAdvance) endedCatalogId = requestedTrack?.catalogId
        val unrequestedAfterEnd = !preparing && requestedTrack?.catalogKind == CatalogKind.Song &&
            endedCatalogId != null && (unrequestedAdvance || nativeCatalogId != endedCatalogId)
        val currentTrack = if (unrequestedAfterEnd) requestedTrack else sdkTrack
        val matchesRequestedSlot = requestedNativeIndex == null || requestedNativeIndex == index
        val observedCatalogId = when {
            !matchesRequestedSlot -> null
            currentStationContainer -> requestedTrack?.catalogId
            else -> nativeCatalogId
        }
        @Suppress("UNCHECKED_CAST")
        val queueItems = currentController.callOrNull("getQueueItems") as? List<Any?>
        val queue = queueItems.orEmpty().mapNotNull { it?.let(::trackFromQueueItem) }
            .ifEmpty { currentTrack?.let(::listOf).orEmpty() }
        val position = (currentController.callOrNull("getCurrentPosition") as? Long)
            ?.takeUnless { unrequestedAfterEnd }?.coerceAtLeast(0L) ?: 0L
        val controllerDuration = (currentController.callOrNull("getDuration") as? Long)
            ?.takeIf { !unrequestedAfterEnd && it > 0L }
        val playbackState = currentController.callOrNull("getPlaybackState") as? Int ?: 0
        val playbackRate = currentController.callOrNull("getPlaybackRate") as? Float ?: 0f
        val buffering = currentController.callOrNull("isBuffering") as? Boolean ?: false
        val trace = playingTrace?.takeIf { it.generation == generation }
        val observedAt = SystemClock.elapsedRealtime()
        if (currentQueueItem != null && !unrequestedAfterEnd && currentTrack != null) {
            syncTrackLoudness(generation, currentTrack, index)
        }
        if (trace != null && currentQueueItem != null && trace.catalogId == observedCatalogId) {
            trace.timing.buffering(buffering, observedAt)
        }
        val nativeEnded = !preparing && endedCatalogId != null && endedCatalogId == currentTrack?.catalogId
        if (unrequestedAfterEnd && playbackState == PLAYBACK_STATE_PLAYING) currentController.callVoid("pause")
        val decision = playbackIntent.observe(NativePlaybackProbe(
            catalogId = observedCatalogId,
            hasCurrentItem = currentQueueItem != null,
            positionMs = position,
            durationMs = if (liveStream) 0L else controllerDuration ?: currentTrack?.durationMs ?: 0L,
            playing = playbackState == PLAYBACK_STATE_PLAYING && playbackRate > 0f,
            buffering = buffering,
            canSeek = canSeek,
            isLiveStream = liveStream,
            paused = playbackState == 2,
            ended = nativeEnded,
            nowMs = SystemClock.elapsedRealtime(),
        ))
        if (BuildConfig.FLYME_UI_PREVIEW && requestedTrack?.catalogKind == CatalogKind.Song) {
            val startupState = Triple(currentQueueItem != null, buffering, decision.prepared)
            if (startupState != lastStartupState) {
                lastStartupState = startupState
                Log.d(TAG, "Startup generation=$generation hasItem=${startupState.first} " +
                    "buffering=$buffering ready=${decision.prepared}")
            }
        }
        if (pendingPlaybackError == null) {
            for (command in decision.commands) {
                runCatching {
                    when (command) {
                        NativePlaybackCommand.Play -> {
                            if (BuildConfig.FLYME_UI_PREVIEW) Log.d(TAG, "Play command generation=$generation")
                            trace?.timing?.mark(GaplessStage.Play, SystemClock.elapsedRealtime())
                            currentController.callVoid("play")
                        }
                        NativePlaybackCommand.Pause -> currentController.callVoid("pause")
                        is NativePlaybackCommand.Seek -> currentController.javaClass.getMethod(
                            "seekToPosition", Long::class.javaPrimitiveType,
                        ).invoke(currentController, command.positionMs)
                    }
                }.onFailure {
                    pendingPlaybackError = describePlaybackError(it)
                    pendingFailureKind = PlaybackFailureKind.Unexpected
                }
            }
            if (decision.failure != null) {
                // Log once when the native queue/readiness watchdog fails. The
                // UI message alone cannot distinguish an unadopted queue from
                // buffering, a mismatched item, or a failed position restore.
                Log.w(TAG, "Native readiness failure generation=$generation requested=${requestedTrack?.catalogId} " +
                    "native=${currentTrack?.catalogId} hasItem=${currentQueueItem != null} " +
                    "state=$playbackState rate=$playbackRate buffering=$buffering " +
                    "positionMs=$position queue=${queue.take(5).map { it.catalogId }} reason=${decision.failure}")
                pendingFailureKind = decision.failure
                pendingPlaybackError = when (decision.failure) {
                    PlaybackFailureKind.PositionRestore -> AppText.text(R.string.services_playback_restore_failed)
                    else -> AppText.text(R.string.services_song_connection_failed)
                }
            }
        }
        // The SDK sometimes never reports the end; the intent machine then decides it ended.
        if (decision.ended && reportedEndGeneration != generation) {
            reportedEndGeneration = generation
            nativeSongQueue.pendingAppend?.let { logLookahead(it, "rejected", index, count, "unconfirmed-at-end") }
            lastEndedAtMs = SystemClock.elapsedRealtime()
            recordGaplessEnd(
                generation, requestedTrack?.catalogId, playbackIntent.inferredEndAtMs ?: lastEndedAtMs, "inferred-completion",
            )
            diagnostics.record(AppText.text(R.string.diagnostics_end_inferred))
            recordEndWithoutContinuation(requestedTrack?.title.orEmpty())
        }
        if (trace != null && currentQueueItem != null && observedCatalogId == trace.catalogId &&
            decision.playing && position > trace.startPositionMs && pendingPlaybackError == null
        ) {
            recordCompletedTransition(trace, observedAt)
        }
        pendingSound?.let { pending ->
            if (decision.prepared && playbackState == PLAYBACK_STATE_PLAYING && !buffering && position > 0L &&
                currentTrack?.catalogId == pending.catalogId
            ) {
                pendingSound = null
                val waitedMs = SystemClock.elapsedRealtime() - pending.startedMs
                if (pending.queueCommand != null) {
                    diagnostics.logQueueSound(generation, pending.catalogId, pending.queueCommand, waitedMs)
                    if (pending.queueCommand == "skipToNextItem") {
                        diagnostics.record(AppText.text(R.string.diagnostics_skip_lookahead, pending.title, waitedMs))
                    }
                } else diagnostics.record(when {
                    pending.gapless -> AppText.text(R.string.diagnostics_gapless, pending.title, waitedMs)
                    pending.afterEnd -> AppText.text(R.string.diagnostics_prepare_after_end, pending.title, waitedMs / 1000.0)
                    else -> AppText.text(R.string.diagnostics_prepare, pending.title, waitedMs / 1000.0)
                })
            }
        }
        val becamePrepared = preparing && decision.prepared
        preparing = !decision.prepared
        if (decision.prepared) requestedNativeIndex = null
        if (decision.prepared && !unrequestedAfterEnd && currentQueueItem != null &&
            currentTrack?.catalogId == requestedTrack?.catalogId
        ) {
            preparedNativeQueueId = currentTrack?.playbackQueueId
            nativeSongQueue.activeIndex = index
        }
        // A lookahead requested while the song was still loading is queued once it is ready.
        if ((becamePrepared && lookahead != null) ||
            (justConfirmed && confirmed != null && index == confirmed.index - 1 && endedCatalogId == null)
        ) syncLookahead()
        val repeatMode = PlaybackRepeatMode.fromSdk(
            currentController.callOrNull("getRepeatMode") as? Int ?: 0,
        )
        val shuffleEnabled =
            (currentController.callOrNull("getShuffleMode") as? Int ?: 0) == 1
        val continuousState = currentController.callOrNull("getContinuousPlaybackState") as? Int
        val canSetAutoplay = currentController.hasNoArgMethod("canSetContinuousPlaybackMode") &&
            (currentController.callOrNull("canSetContinuousPlaybackMode") as? Boolean ?: false)
        if (lastLoggedPlaybackState != playbackState || lastLoggedTrackId != currentTrack?.id) {
            Log.i(
                TAG,
                "Snapshot state=$playbackState rate=$playbackRate " +
                    "buffering=$buffering positionMs=$position track=${currentTrack?.id}",
            )
            lastLoggedPlaybackState = playbackState
            if (lastLoggedTrackId != currentTrack?.id) {
                logAvailableTimedTextTracks(currentController)
            }
            lastLoggedTrackId = currentTrack?.id
        }
        return PlaybackSnapshot(
            currentTrack = currentTrack,
            hasPlayerCurrentTrack = currentQueueItem != null && matchesRequestedSlot && !unrequestedAfterEnd,
            queue = queue,
            positionMs = decision.positionMs,
            durationMs = if (liveStream) 0L else controllerDuration ?: currentTrack?.durationMs ?: 1L,
            isPlaying = decision.playing && pendingPlaybackError == null,
            isBuffering = buffering,
            canSkipNext = currentController.callOrNull("canSkipToNextItem") as? Boolean ?: false,
            canSkipPrevious = currentController.callOrNull("canSkipToPreviousItem") as? Boolean ?: false,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            autoplayEnabled = continuousState == CONTINUOUS_PLAYBACK_ON,
            canSetRepeat = currentController.callOrNull("canSetRepeatMode") as? Boolean ?: false,
            canSetShuffle = currentController.callOrNull("canSetShuffleMode") as? Boolean ?: false,
            canSetAutoplay = canSetAutoplay,
            errorMessage = pendingPlaybackError,
            isEnded = decision.ended,
            requestGeneration = generation,
            isPrepared = decision.prepared,
            failureKind = pendingFailureKind,
            isLiveStream = liveStream,
            canSeek = canSeek,
            gaplessAdvanceCatalogId = gaplessAdvanceCatalogId,
        )
    }

    private fun isCurrentStationContainer(
        currentController: Any,
        station: Track?,
        liveStream: Boolean = currentController.callOrNull("isLiveStream") as? Boolean ?: false,
    ): Boolean {
        if (station?.catalogKind != CatalogKind.Station) return false
        val stationId = station.catalogId ?: return false
        val containerType = (currentController.callOrNull("getCurrentContainerType") as? Number)?.toInt()
            ?: return false
        val containerTypeClass = Class.forName(MEDIA_CONTAINER_TYPE, false, currentController.javaClass.classLoader)
        val stationType = radioStationContainerType(containerTypeClass)
        val containerId = currentController.callOrNull("getCurrentContainerStoreId") as? String
        return containerType == stationType && (containerId == stationId || liveStream)
    }

    private fun trackFromQueueItem(queueItem: Any): Track? {
        val item = queueItem.callOrNull("getItem") ?: return null
        val storeId = item.callOrNull("getSubscriptionStoreId") as? String
        val title = (item.callOrNull("getTitle") as? String).orEmpty().ifBlank { "Apple Music" }
        val artist = (item.callOrNull("getArtistName") as? String).orEmpty()
            .ifBlank { "Apple Music" }
        val album = (item.callOrNull("getAlbumTitle") as? String).orEmpty().ifBlank { AppText.text(R.string.services_kind_song) }
        val artwork = item.javaClass.methods.firstOrNull {
            it.name == "getArtworkUrl" && it.parameterTypes.size == 2
        }?.invoke(item, 600, 600) as? String
        val duration = (item.callOrNull("getDuration") as? Long)?.takeIf { it > 0L }
            ?: requestedTrack?.durationMs ?: 1L
        val fallback = requestedTrack?.takeIf { requested ->
            requested.catalogId == storeId || requested.title == title
        }
        val identity = storeId?.takeIf(String::isNotBlank)
            ?: "queue:${queueItem.callOrNull("getPlaybackQueueId") ?: title.hashCode()}"
        return Track(
            id = "apple:$identity",
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            palette = fallback?.palette ?: fallbackTrackPalette(identity),
            lyrics = fallback?.lyrics.orEmpty(),
            catalogId = storeId,
            catalogKind = CatalogKind.Song,
            resourceId = fallback?.resourceId,
            resourceType = fallback?.resourceType,
            artworkUrl = artwork?.takeIf { it.startsWith("https://") } ?: fallback?.artworkUrl,
            artworkColors = fallback?.takeIf { storeId == null || it.catalogId == storeId }?.artworkColors
                ?: lookahead?.takeIf { storeId != null && it.catalogId == storeId }?.artworkColors,
            playbackQueueId = queueItem.callOrNull("getPlaybackQueueId") as? Long,
            isExplicit = nativeSongIsExplicit(storeId, requestedTrack, lookahead),
            previewUrl = fallback?.takeIf { it.catalogId == storeId }?.previewUrl
                ?: lookahead?.takeIf { it.catalogId == storeId }?.previewUrl,
        )
    }

    private fun Any.callOrNull(name: String): Any? = runCatching {
        javaClass.methods.first { it.name == name && it.parameterTypes.isEmpty() }.invoke(this)
    }.getOrNull()

    private fun Any.hasNoArgMethod(name: String): Boolean = javaClass.methods.any {
        it.name == name && it.parameterTypes.isEmpty()
    }

    /**
     * MusicKit's hosted Javadoc documents timed-text selection, but the
     * currently downloadable 1.1.1 playback AAR omits those public methods.
     * The bundled player still keeps a track-info set internally. Reading its
     * metadata lets us inspect actual timed-text tracks without requesting any private service API.
     */
    private fun logAvailableTimedTextTracks(currentController: Any) {
        runCatching {
            val coreField = currentController.javaClass.getDeclaredField("b").apply {
                isAccessible = true
            }
            val core = coreField.get(currentController) ?: return@runCatching
            val tracksField = core.javaClass.getDeclaredField("t").apply {
                isAccessible = true
            }
            val tracks = tracksField.get(core) as? Set<*> ?: emptySet<Any>()
            val summary = tracks.joinToString(prefix = "[", postfix = "]") { track ->
                if (track == null) return@joinToString "null"
                val values = listOf("a", "b", "c").map { name ->
                    runCatching {
                        track.javaClass.getDeclaredMethod(name).invoke(track)
                    }.getOrNull()
                }
                "type=${values[0]}, id=${values[1]}, language=${values[2]}"
            }
            Log.i(TAG, "MusicKit available media tracks: $summary")
        }.onFailure {
            Log.d(TAG, "MusicKit track metadata is unavailable in this SDK build.")
        }
    }

    private fun invokeController(name: String, vararg args: Any) {
        val value = checkNotNull(controller) { AppText.text(R.string.services_playback_not_prepared) }
        postPlaybackAction(name) {
            val method = value.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: error(AppText.text(R.string.services_controller_command_unavailable, name))
            Log.i(TAG, "Controller command: $name")
            method.invoke(value, *args)
            if (name.startsWith("skipTo")) restartPlaybackPolls(afterCommand = true)
        }
    }

    private fun postPlaybackAction(name: String, action: () -> Unit) {
        val handler = checkNotNull(playbackHandler) {
            AppText.text(R.string.services_playback_thread_unavailable)
        }
        val generation = prepareGeneration.get()
        handler.post {
            if (generation != prepareGeneration.get()) return@post
            try {
                action()
                dispatchSnapshot()
            } catch (error: Throwable) {
                if (generation != prepareGeneration.get()) return@post
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                pendingPlaybackError = describePlaybackError(cause)
                pendingFailureKind = if (requestedTrack?.catalogKind == CatalogKind.MusicVideo) {
                    PlaybackFailureKind.TrackUnavailable
                } else classifyNativeFailure(cause)
                Log.e(TAG, "MusicKit $name failed (${cause.javaClass.simpleName})")
                dispatchSnapshot()
            }
        }
    }

    private fun Any.callVoid(name: String) {
        javaClass.methods.first {
            it.name == name && it.parameterTypes.isEmpty()
        }.invoke(this)
    }

    private fun ensurePlaybackRuntime() {
        if (runtimeLoaded) return
        synchronized(MusicKitBridge::class.java) {
            if (runtimeLoaded) return
            // Apple's official sample requires this initialization before the
            // first MediaPlayerController is created.
            System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0")
            System.setProperty("org.bytedeco.javacpp.maxbytes", "0")
            System.loadLibrary("c++_shared")
            System.loadLibrary("appleMusicSDK")
            runtimeLoaded = true
        }
    }

    private fun classifyNativeFailure(error: Any?): PlaybackFailureKind {
        fun number(name: String): Int? = runCatching {
            error?.javaClass?.getMethod(name)?.invoke(error) as? Int
        }.getOrNull()
        return classifyPlaybackFailure(number("getType"), number("getErrorCode"),
            number("getComponentErrorCode"), error as? Throwable)
    }

    private fun describePlaybackError(error: Any?): String = when (classifyNativeFailure(error)) {
        PlaybackFailureKind.TrackUnavailable -> AppText.text(R.string.services_track_unavailable)
        PlaybackFailureKind.Authorization -> AppText.text(R.string.services_playback_auth_required)
        PlaybackFailureKind.Network -> AppText.text(
            if (nativeNetworkCause(error as? Throwable) == NativeNetworkCause.TlsHandshake) {
                R.string.services_connection_verification_failed
            } else R.string.services_apple_connection_failed,
        )
        PlaybackFailureKind.PositionRestore -> AppText.text(R.string.services_playback_restore_failed)
        PlaybackFailureKind.Unexpected -> if (isContentRestrictedFailure(
                runCatching { error?.javaClass?.getMethod("getErrorCode")?.invoke(error) as? Int }.getOrNull())
        ) AppText.text(R.string.services_playback_content_restricted)
        else AppText.text(R.string.services_playback_failed)
    }

    private fun classExists(name: String): Boolean = runCatching {
        Class.forName(name)
    }.isSuccess

    private companion object Constants {
        const val AUTH_FACTORY = "com.apple.android.sdk.authentication.AuthenticationFactory"
        const val TOKEN_PROVIDER = "com.apple.android.sdk.authentication.TokenProvider"
        const val PLAYER_FACTORY =
            "com.apple.android.music.playback.controller.MediaPlayerControllerFactory"
        const val CONTROLLER_LISTENER =
            "com.apple.android.music.playback.controller.MediaPlayerController\$Listener"
        const val QUEUE_BUILDER =
            "com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider\$Builder"
        const val MEDIA_CONTAINER_TYPE = "com.apple.android.music.playback.model.MediaContainerType"
        const val PLAYBACK_STATE_PLAYING = 1
        /** A prepare this soon after a song ended is the app moving on, not a user choice. */
        const val AFTER_END_WINDOW_MS = 15_000L
        const val FADE_TICK_MS = 50L
        const val LEVEL_EVERY_TICKS = 5
        const val CONTINUOUS_PLAYBACK_ON = 2
        const val SNAPSHOT_DELAY_MS = 80L
        const val TAG = "AMATV-MusicKit"
        @Volatile
        var runtimeLoaded = false
    }

}

/** Build the catalog queue provider using the SDK's declared item/container types. */
internal fun createCatalogPlaybackQueueProvider(track: Track, builderClass: Class<*>): Any {
    val storeId = checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
    val builder = builderClass.getConstructor().newInstance()
    val typeMethod = builderClass.getMethod(
        if (track.catalogKind == CatalogKind.Song) "items" else "containers",
        Int::class.javaPrimitiveType,
        Array<String>::class.java,
    )
    when (track.catalogKind) {
        CatalogKind.Song -> typeMethod.invoke(builder, 1, arrayOf(storeId))
        CatalogKind.Station -> {
            val mediaContainerType = Class.forName(
                "com.apple.android.music.playback.model.MediaContainerType",
                false,
                builderClass.classLoader,
            )
            typeMethod.invoke(builder, radioStationContainerType(mediaContainerType), arrayOf(storeId))
        }
        CatalogKind.Album -> typeMethod.invoke(builder, 1, arrayOf(storeId))
        CatalogKind.Playlist -> typeMethod.invoke(builder, 2, arrayOf(storeId))
        CatalogKind.Artist -> error(AppText.text(R.string.services_collection_required))
        CatalogKind.MusicVideo -> error(AppText.text(R.string.services_video_unsupported))
    }
    return checkNotNull(builderClass.getMethod("build").invoke(builder)) {
        "MusicKit catalog queue builder returned null"
    }
}

/**
 * The 1.1.1 playback AAR omits this public-field constant, but Apple's Android SDK
 * documentation defines MediaContainerType.RADIO_STATION as 3. Use the field when
 * a newer AAR provides it and retain the documented value for the bundled artifact.
 */
internal fun radioStationContainerType(mediaContainerTypeClass: Class<*>): Int =
    mediaContainerTypeClass.fields.firstOrNull { it.name == "RADIO_STATION" }
        ?.getInt(null)
        ?: MUSIC_KIT_RADIO_STATION_CONTAINER_TYPE

internal const val MUSIC_KIT_RADIO_STATION_CONTAINER_TYPE = 3

internal fun nativeSongIsExplicit(catalogId: String?, requested: Track?, lookahead: Track?): Boolean {
    if (catalogId.isNullOrBlank()) return false
    return requested?.takeIf { it.catalogKind == CatalogKind.Song && it.catalogId == catalogId }?.isExplicit
        ?: (lookahead?.takeIf { it.catalogKind == CatalogKind.Song && it.catalogId == catalogId }?.isExplicit == true)
}

/** Queue errors belong to an in-flight request even when the controller still exposes the prior item. */
internal fun isPlaybackErrorForActiveRequest(
    callbackGeneration: Long,
    requestGeneration: Long,
    activeGeneration: Long,
    issuedPrepareGeneration: Long,
    isCurrentController: Boolean,
    requestPreparing: Boolean,
    nativeCatalogId: String?,
    requestedCatalogId: String?,
    stationContainerMatches: Boolean,
): Boolean {
    if (callbackGeneration != requestGeneration || callbackGeneration != activeGeneration ||
        callbackGeneration != issuedPrepareGeneration || !isCurrentController
    ) return false
    return requestPreparing || nativeCatalogId == null || nativeCatalogId == requestedCatalogId ||
        stationContainerMatches
}

/** Log exception structure only: vendor messages and causes can contain request details or credentials. */
internal fun nativePlaybackErrorDiagnostics(error: Any?): String {
    fun errorNumber(name: String): Int? = runCatching {
        (error?.javaClass?.getMethod(name)?.invoke(error) as? Number)?.toInt()
    }.getOrNull()

    fun errorType(value: Int?): String = when (value) {
        0 -> "TYPE_UNKNOWN"
        1 -> "TYPE_IO"
        2 -> "TYPE_DRM"
        3 -> "TYPE_PLAYBACK_QUEUE"
        4 -> "TYPE_PLAYBACK_LEASE"
        5 -> "TYPE_CHROMECAST_ERROR"
        6 -> "TYPE_EXPLICIT_VERIFY_AGE"
        null -> "unavailable"
        else -> value.toString()
    }

    fun code(value: Int?): String = when (value) {
        Int.MIN_VALUE -> "ERROR_UNKNOWN"
        null -> "unavailable"
        else -> value.toString()
    }

    val causes = generateSequence(error as? Throwable) { it.cause }.take(5).toList()
    val causeTypes = causes
        .map { it.javaClass.name }
        .joinToString(">", prefix = "[", postfix = "]")
    val messageHints = causes.mapNotNull(::safeNativePlaybackMessageHint)
        .distinct()
        .joinToString(",", prefix = "[", postfix = "]")

    return "class=${error?.javaClass?.name ?: "unavailable"} " +
        "type=${errorType(errorNumber("getType"))} " +
        "code=${code(errorNumber("getErrorCode"))} " +
        "component=${code(errorNumber("getComponentErrorCode"))} " +
        "causeTypes=$causeTypes messageHints=$messageHints networkCause=${nativeNetworkCause(error as? Throwable) ?: "-"}"
}

/** Reduce exception text to fixed labels; never write vendor messages, URLs, or query data to logcat. */
private fun safeNativePlaybackMessageHint(error: Throwable): String? {
    val message = error.message?.lowercase(java.util.Locale.ROOT).orEmpty()
    val className = error.javaClass.name.lowercase(java.util.Locale.ROOT)
    val httpStatus = Regex(
        "\\b(?:http(?:/[0-9.]+)?|status(?:\\s+code)?|response(?:\\s+status)?(?:\\s+code)?)\\s*[:=]?\\s*([1-5][0-9]{2})\\b",
    ).find(message)?.groupValues?.getOrNull(1)
    if (httpStatus != null) return "http_status_$httpStatus"
    return when {
        "unknownhost" in className || "name or service not known" in message -> "dns_failure"
        "timeoutexception" in className || "timed out" in message -> "timeout"
        "ssl" in className || "certificate" in message || "tls" in message -> "tls_failure"
        "unauthorized" in message || "forbidden" in message -> "authorization_rejected"
        "subscription" in message || "not entitled" in message -> "subscription_required"
        "drm" in message || "license" in message -> "drm_failure"
        "not found" in message || "unavailable" in message -> "resource_unavailable"
        "connection reset" in message -> "connection_reset"
        else -> null
    }
}
