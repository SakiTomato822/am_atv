package dev.amatv.integration

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

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
    val capturedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    val isEnded: Boolean = false,
    val requestGeneration: Long = 0L,
    val isPrepared: Boolean = true,
    val failureKind: PlaybackFailureKind? = null,
)

/**
 * Thin clean-room adapter around Apple-provided MusicKit AARs.
 * Reflection isolates version-specific controller methods. Developers supply
 * the licensed SDK in app/libs; no Apple binary is redistributed here.
 */
class MusicKitBridge(private val context: Context) {
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
    private var playbackDeveloperToken: String? = null
    private var playbackUserToken: String? = null
    private val prepareGeneration = AtomicLong(0L)
    @Volatile private var preparing = false
    @Volatile private var endedCatalogId: String? = null
    @Volatile private var preparedNativeQueueId: Long? = null
    private val snapshotPoll = object : Runnable {
        override fun run() {
            if (playbackListener == null) return
            if (controller != null) dispatchSnapshot()
            mainHandler.postDelayed(this, 250L)
        }
    }

    val isInstalled: Boolean
        get() = classExists(AUTH_FACTORY) && classExists(PLAYER_FACTORY)

    fun authenticationIntent(developerToken: String): Intent {
        check(isInstalled) { "Apple MusicKit Android AARs are not installed." }
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
            "Apple Music authentication failed: $error"
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
    ): Long {
        if (controller != null && (playbackDeveloperToken != developerToken || playbackUserToken != userToken)) {
            release()
        }
        val generation = prepareGeneration.incrementAndGet()
        ensurePlaybackRuntime()
        requestedTrack = track
        preparing = true
        endedCatalogId = null
        preparedNativeQueueId = null
        pendingPlaybackError = null
        pendingFailureKind = null
        desiredPlaying = playWhenReady
        playbackDeveloperToken = developerToken
        playbackUserToken = userToken
        val currentController = controller ?: createController(developerToken, userToken)
            .also { controller = it }
        val storeId = checkNotNull(track.catalogId) { "Track has no Apple catalog ID" }
        if (playbackListener != null) {
            mainHandler.removeCallbacks(snapshotPoll)
            mainHandler.post(snapshotPoll)
        }
        postPlaybackAction("prepare") {
            if (generation != prepareGeneration.get()) return@postPlaybackAction
            activeGeneration = generation
            installRequestListener(currentController, generation)
            playbackIntent.begin(track.catalogId, positionMs, desiredPlaying, SystemClock.elapsedRealtime())
            val builderClass = Class.forName(QUEUE_BUILDER)
            val builder = builderClass.getConstructor().newInstance()
            when (track.catalogKind) {
                CatalogKind.Song -> builderClass.getMethod(
                    "items",
                    Int::class.javaPrimitiveType,
                    Array<String>::class.java,
                ).invoke(builder, 1, arrayOf(storeId))

                CatalogKind.MusicVideo -> error(
                    "The installed MusicKit Android SDK does not support music-video playback.",
                )

                CatalogKind.Album,
                CatalogKind.Playlist,
                -> {
                    val containerType = when (track.catalogKind) {
                        CatalogKind.Album -> 1
                        CatalogKind.Playlist -> 2
                        CatalogKind.Song,
                        CatalogKind.MusicVideo,
                        CatalogKind.Station,
                        -> error("Item kinds do not use a container queue")
                    }
                    builderClass.getMethod(
                        "containers",
                        Int::class.javaPrimitiveType,
                        Array<String>::class.java,
                    ).invoke(builder, containerType, arrayOf(storeId))
                }

                CatalogKind.Station -> error(
                    "The installed MusicKit Android SDK does not support station playback.",
                )
            }
            val provider = builderClass.getMethod("build").invoke(builder)
            val prepare = currentController.javaClass.methods.first {
                it.name == "prepare" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            // Never emit the beginning of a restored song. Readiness and any
            // resume seek are confirmed before the intent machine issues play().
            issuedPrepareGeneration = generation
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
            dispatchSnapshot()
        }
    }
    fun pause() {
        desiredPlaying = false
        val generation = prepareGeneration.get()
        playbackHandler?.post {
            if (generation != prepareGeneration.get()) return@post
            playbackIntent.requestPlaying(false)
            controller?.let { runCatching { it.callVoid("pause") } }
            dispatchSnapshot()
        }
    }
    fun seekTo(positionMs: Long) {
        val value = checkNotNull(controller) { "Playback has not been prepared." }
        postPlaybackAction("seekToPosition") {
            playbackIntent.requestSeek()
            endedCatalogId = null
            value.javaClass.getMethod("seekToPosition", Long::class.javaPrimitiveType).invoke(value, positionMs)
        }
    }
    fun skipToNext() = invokeController("skipToNextItem")
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
        if (listener != null) mainHandler.post(snapshotPoll)
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

    fun playbackSnapshot(): PlaybackSnapshot? = latestSnapshot

    @Synchronized
    fun release() {
        prepareGeneration.incrementAndGet()
        mainHandler.removeCallbacks(snapshotPoll)
        snapshotScheduled = false
        val currentController = controller
        val listener = controllerListener
        val oldHandler = playbackHandler
        val oldThread = playbackThread
        val cleanup = {
            if (currentController != null && listener != null) {
                runCatching {
                    currentController.javaClass.methods.first {
                        it.name == "removeListener" && it.parameterTypes.size == 1
                    }.invoke(currentController, listener)
                }
            }
            runCatching { currentController?.callVoid("release") }
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
        check(isInstalled) { "Apple MusicKit Android AARs are not installed." }
        val providerClass = Class.forName(TOKEN_PROVIDER)
        val tokenProvider = MusicKitTokenProvider(developerToken, userToken)
        val factory = Class.forName(PLAYER_FACTORY)
        val createdController = runCatching {
            // Apple's bundled SDK test app creates its controller on a
            // dedicated HandlerThread. Keep callbacks and the legacy native
            // player off Compose's main thread as the reference app does.
            val worker = HandlerThread(
                "AMATV:MusicKit",
                Process.THREAD_PRIORITY_BACKGROUND,
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
            "MusicKit did not create a playback controller."
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
                        "onPlaybackError" -> {
                            val nativeCurrent = currentController.callOrNull("getCurrentItem")?.let(::trackFromQueueItem)
                            if (nativeCurrent == null || nativeCurrent.catalogId == requestedTrack?.catalogId) {
                                val error = args?.lastOrNull()
                                pendingPlaybackError = describePlaybackError(error)
                                pendingFailureKind = classifyNativeFailure(error)
                                dispatchSnapshot()
                            }
                        }
                        "onItemEnded" -> {
                            val endedTrack = args?.getOrNull(1)?.let(::trackFromQueueItem)
                            val endPosition = args?.getOrNull(2) as? Long ?: 0L
                            if (isCompletedPlaybackItem(
                                eventGeneration = generation,
                                requestGeneration = prepareGeneration.get(),
                                prepared = !preparing,
                                requestedCatalogId = requestedTrack?.catalogId,
                                endedCatalogId = endedTrack?.catalogId,
                                expectedNativeQueueId = preparedNativeQueueId,
                                endedNativeQueueId = endedTrack?.playbackQueueId,
                                endPositionMs = endPosition,
                                durationMs = endedTrack?.durationMs ?: 0L,
                            )) endedCatalogId = endedTrack?.catalogId
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
            if (generation != prepareGeneration.get() || generation != activeGeneration) return@readAndDeliver
            val snapshot = controller?.let { readSnapshot(it, generation) } ?: return@readAndDeliver
            latestSnapshot = snapshot
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
        val currentTrack = currentQueueItem?.let(::trackFromQueueItem) ?: requestedTrack
        @Suppress("UNCHECKED_CAST")
        val queueItems = currentController.callOrNull("getQueueItems") as? List<Any?>
        val queue = queueItems.orEmpty().mapNotNull { it?.let(::trackFromQueueItem) }
            .ifEmpty { currentTrack?.let(::listOf).orEmpty() }
        val position = (currentController.callOrNull("getCurrentPosition") as? Long)
            ?.coerceAtLeast(0L) ?: 0L
        val controllerDuration = (currentController.callOrNull("getDuration") as? Long)
            ?.takeIf { it > 0L }
        val playbackState = currentController.callOrNull("getPlaybackState") as? Int ?: 0
        val playbackRate = currentController.callOrNull("getPlaybackRate") as? Float ?: 0f
        val buffering = currentController.callOrNull("isBuffering") as? Boolean ?: false
        val nativeEnded = !preparing && endedCatalogId != null && endedCatalogId == currentTrack?.catalogId
        val decision = playbackIntent.observe(NativePlaybackProbe(
            catalogId = currentTrack?.catalogId,
            hasCurrentItem = currentQueueItem != null,
            positionMs = position,
            durationMs = controllerDuration ?: currentTrack?.durationMs ?: 0L,
            playing = playbackState == PLAYBACK_STATE_PLAYING && playbackRate > 0f,
            buffering = buffering,
            canSeek = currentController.callOrNull("canSeek") as? Boolean ?: false,
            paused = playbackState == 2,
            ended = nativeEnded,
            nowMs = SystemClock.elapsedRealtime(),
        ))
        if (pendingPlaybackError == null) {
            for (command in decision.commands) {
                runCatching {
                    when (command) {
                        NativePlaybackCommand.Play -> currentController.callVoid("play")
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
                pendingFailureKind = decision.failure
                pendingPlaybackError = when (decision.failure) {
                    PlaybackFailureKind.PositionRestore -> "無法恢復這首歌的播放進度，請重試。"
                    else -> "歌曲連線暫時無法完成，請重試。"
                }
            }
        }
        preparing = !decision.prepared
        if (decision.prepared && currentQueueItem != null && currentTrack?.catalogId == requestedTrack?.catalogId) {
            preparedNativeQueueId = currentTrack?.playbackQueueId
        }
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
            hasPlayerCurrentTrack = currentQueueItem != null,
            queue = queue,
            positionMs = decision.positionMs,
            durationMs = controllerDuration ?: currentTrack?.durationMs ?: 1L,
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
        )
    }

    private fun trackFromQueueItem(queueItem: Any): Track? {
        val item = queueItem.callOrNull("getItem") ?: return null
        val storeId = item.callOrNull("getSubscriptionStoreId") as? String
        val title = (item.callOrNull("getTitle") as? String).orEmpty().ifBlank { "Apple Music" }
        val artist = (item.callOrNull("getArtistName") as? String).orEmpty()
            .ifBlank { "Apple Music" }
        val album = (item.callOrNull("getAlbumTitle") as? String).orEmpty().ifBlank { "Song" }
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
            palette = fallback?.palette ?: playbackPalette(identity),
            lyrics = fallback?.lyrics.orEmpty(),
            catalogId = storeId,
            catalogKind = CatalogKind.Song,
            artworkUrl = artwork?.takeIf { it.startsWith("https://") } ?: fallback?.artworkUrl,
            hasLyricsAvailable = item.callOrNull("hasLyricsAvailable") as? Boolean
                ?: fallback?.hasLyricsAvailable
                ?: false,
            playbackQueueId = queueItem.callOrNull("getPlaybackQueueId") as? Long,
        )
    }

    private fun playbackPalette(seed: String): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF361044), Color(0xFF9D205D), Color(0xFFE06F66)),
            listOf(Color(0xFF102B46), Color(0xFF176E80), Color(0xFFC99A58)),
            listOf(Color(0xFF24203E), Color(0xFF56529C), Color(0xFFE2746B)),
            listOf(Color(0xFF12382F), Color(0xFF348563), Color(0xFFD0C348)),
        )
        return palettes[(seed.hashCode() and Int.MAX_VALUE) % palettes.size]
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
     * metadata lets us distinguish a real text track from a catalog-only
     * `hasLyricsAvailable` flag without requesting any private service API.
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
        val value = checkNotNull(controller) { "Playback has not been prepared." }
        postPlaybackAction(name) {
            val method = value.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: error("MusicKit controller does not expose $name.")
            Log.i(TAG, "Controller command: $name")
            method.invoke(value, *args)
        }
    }

    private fun postPlaybackAction(name: String, action: () -> Unit) {
        val handler = checkNotNull(playbackHandler) {
            "MusicKit playback thread is not running."
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
                pendingFailureKind = if (requestedTrack?.catalogKind in setOf(CatalogKind.MusicVideo, CatalogKind.Station)) {
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
        PlaybackFailureKind.TrackUnavailable -> "這首歌曲目前無法播放。"
        PlaybackFailureKind.Authorization -> "Apple Music 授權需要更新，請重新登入。"
        PlaybackFailureKind.Network -> "暫時無法連線至 Apple Music，請重試。"
        PlaybackFailureKind.PositionRestore -> "無法恢復播放進度，請重試。"
        PlaybackFailureKind.Unexpected -> "無法完成播放，請重試。"
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
        const val PLAYBACK_STATE_PLAYING = 1
        const val CONTINUOUS_PLAYBACK_ON = 2
        const val SNAPSHOT_DELAY_MS = 80L
        const val TAG = "AMATV-MusicKit"
        @Volatile
        var runtimeLoaded = false
    }

}
