package dev.amatv.integration

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.i18n.AppText
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays Apple Music stations with MusicKit JS in a headless GeckoView session.
 *
 * MusicKit Playback 1.1.1 has no station queue provider, while MusicKit JS on
 * Gecko plays personal, song and live stations with Widevine. The page gets
 * the app's existing account token, so no second sign-in is needed. All Gecko
 * calls run on the main thread; public methods may be called from any thread.
 */
class GeckoStationEngine(context: Context) : StationPlaybackEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generations = AtomicLong(WEB_STATION_GENERATION_BASE)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    @Volatile private var listener: ((PlaybackSnapshot) -> Unit)? = null
    @Volatile private var latest: PlaybackSnapshot? = null

    private data class Request(val station: Track, val generation: Long, val play: Boolean)
    private data class Tokens(val developer: String, val user: String)

    // Main-thread state.
    private var bootJob: Job? = null
    private var tokens: Tokens? = null
    private var session: GeckoSession? = null
    private var server: AuthLoopbackServer? = null
    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var pageReady = false
    private var pending: Request? = null
    private var active: Request? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var lastTransition = ""
    /** Latest play/pause intent; a tap can arrive while the page is still booting. */
    private var desiredPlaying = false
    /** The request whose broken media pipeline was already rebuilt once. */
    private var mediaRestartGeneration = 0L

    override fun prepare(station: Track, developerToken: String, userToken: String, playWhenReady: Boolean): Long {
        val request = Request(station, generations.incrementAndGet(), playWhenReady)
        main.post { start(request, Tokens(developerToken, userToken)) }
        return request.generation
    }

    override fun play() {
        main.post {
            val current = active ?: return@post
            desiredPlaying = true
            if (!pageReady) return@post
            if (requestFocus()) send(JSONObject().put("cmd", "play"))
            else fail(current, PlaybackFailureKind.Unexpected)
        }
    }

    override fun pause() {
        main.post {
            resumeOnFocusGain = false
            desiredPlaying = false
            if (active != null && pageReady) send(JSONObject().put("cmd", "pause"))
        }
    }

    override fun skipToNext() {
        main.post {
            if (active != null) send(JSONObject().put("cmd", "next"))
        }
    }

    override fun stop() {
        main.post {
            pending = null
            active = null
            latest = null
            send(JSONObject().put("cmd", "stop"))
            abandonFocus()
        }
    }

    override fun release() {
        main.post {
            pending = null
            active = null
            latest = null
            abandonFocus()
            closeBrowser()
        }
    }

    override fun playbackSnapshot(): PlaybackSnapshot? = latest

    override fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        this.listener = listener
    }

    private fun start(request: Request, requestTokens: Tokens) {
        if (request.generation != generations.get()) return
        active = request
        desiredPlaying = request.play
        emit(request, JSONObject().put("state", "loading"), prepared = false)
        val booted = session != null || bootJob?.isActive == true
        if (tokens != requestTokens || !booted) {
            // A different account (or first use) gets a fresh page and StoreKit.
            closeBrowser()
            tokens = requestTokens
            pending = request
            boot(requestTokens)
        } else if (pageReady) {
            sendPrepare(request)
        } else {
            pending = request
        }
    }

    private fun boot(bootTokens: Tokens) {
        bootJob = scope.launch {
            var created: AuthLoopbackServer? = null
            try {
                val runtime = GeckoAuthRuntime.get(appContext)
                val bridge = runtime.webExtensionController
                    .ensureBuiltIn("resource://android/assets/gecko-radio/", EXTENSION_ID).stationAwait()
                extension = bridge
                val loopback = withContext(Dispatchers.IO) {
                    AuthLoopbackServer { origin -> page(origin, bootTokens) }.also { created = it }
                }
                server = loopback
                val browser = createSession()
                session = browser
                browser.webExtensionController.setMessageDelegate(bridge, messageDelegate, NATIVE_APP)
                browser.open(runtime)
                browser.setActive(true)
                browser.loadUri(loopback.origin.url)
                Log.i(TAG, "Station browser booting")
            } catch (cancelled: CancellationException) {
                created?.close()
                throw cancelled
            } catch (error: Throwable) {
                created?.close()
                Log.w(TAG, "Station browser failed to start: ${error.javaClass.simpleName}")
                closeBrowser()
                (pending ?: active)?.let { fail(it, PlaybackFailureKind.Unexpected) }
            }
        }
    }

    private fun page(origin: GeckoAuthOrigin, bootTokens: Tokens): String {
        val config = JSONObject()
            .put("nonce", origin.nonce)
            .put("developerToken", bootTokens.developer)
            .put("userToken", bootTokens.user)
            .put("appName", AppText.text(R.string.app_name))
            .put("appBuild", BuildConfig.VERSION_NAME)
            .toString().replace("<", "\\u003c")
        fun asset(name: String) = appContext.assets.open("gecko-radio/$name").bufferedReader().use { it.readText() }
        return asset("page.html").replace("__CONFIG__", config).replace("__SCRIPT__", asset("player.js"))
    }

    private fun createSession(): GeckoSession = GeckoSession(GeckoSessionSettings.Builder()
        .usePrivateMode(false).contextId(CONTEXT_ID)
        .allowJavascript(true).useTrackingProtection(false)
        // Matches the desktop Gecko configuration verified with MusicKit JS.
        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build()).apply {
        permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                val port = server?.origin?.port
                val local = runCatching {
                    val uri = URI(perm.uri)
                    uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port
                }.getOrDefault(false)
                val approved = session === this@GeckoStationEngine.session && local && perm.permission in setOf(
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                )
                return GeckoResult.fromValue(if (approved)
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                if (server?.origin?.ownsUrl(request.uri) == true || request.uri == "about:blank") GeckoResult.allow()
                else GeckoResult.deny()

            // A headless player never opens windows (e.g. MusicKit's sign-in popup).
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
        }
        contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) = browserStopped(session)
            override fun onKill(session: GeckoSession) = browserStopped(session)
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val origin = server?.origin
            val sender = port.sender
            if (origin == null || sender.webExtension.id != EXTENSION_ID ||
                sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                sender.session !== session || !sender.isTopLevel || !origin.ownsUrl(sender.url)
            ) {
                port.disconnect()
                return
            }
            this@GeckoStationEngine.port?.disconnect()
            this@GeckoStationEngine.port = port
            port.setDelegate(portDelegate)
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            if (port !== this@GeckoStationEngine.port) return
            val data = message as? JSONObject ?: return
            if (data.optString("nonce") != server?.origin?.nonce) return
            onPageMessage(data)
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (port === this@GeckoStationEngine.port) this@GeckoStationEngine.port = null
        }
    }

    private fun onPageMessage(data: JSONObject) {
        when (data.optString("kind")) {
            "ready" -> {
                pageReady = true
                val authorized = data.optBoolean("authorized")
                Log.i(TAG, "Station player ready authorized=$authorized nativeHlsHidden=${data.optBoolean("nativeHls")} " +
                    "widevine=${data.optBoolean("drm", true)} previewOnly=${data.optBoolean("previewOnly")}")
                val request = pending ?: return
                pending = null
                if (authorized) sendPrepare(request) else fail(request, PlaybackFailureKind.Authorization)
            }
            "trace" -> data.optString("stage").takeIf { it.matches(SAFE_REASON) }?.let { Log.i(TAG, "Station $it") }
            "state" -> {
                val request = active?.takeIf { it.generation == data.optLong("generation") } ?: return
                val snapshot = emit(request, data, data.optBoolean("prepared"))
                if (snapshot.isPlaying) requestFocus()
                val transition = "${data.optString("state")} prepared=${snapshot.isPrepared} " +
                    "playing=${snapshot.isPlaying} live=${snapshot.isLiveStream} item=${snapshot.hasPlayerCurrentTrack} " +
                    "preview=${data.optBoolean("preview")} widevine=${data.optBoolean("drm", true)}"
                if (transition != lastTransition) Log.i(TAG, "Station state $transition")
                lastTransition = transition
            }
            "error" -> {
                val request = active?.takeIf { it.generation == data.optLong("generation") } ?: return
                val reason = data.optString("reason")
                Log.w(TAG, "Station playback failed reason=${reason.takeIf { it.matches(SAFE_REASON) } ?: "Error"}")
                // After a fatal decode error every later item fails in the same page.
                // A fresh page (new media pipeline) recovers it; do that once per request.
                val restartTokens = tokens
                if (reason == "MEDIA_PLAYBACK" && restartTokens != null && mediaRestartGeneration != request.generation) {
                    mediaRestartGeneration = request.generation
                    Log.i(TAG, "Station media pipeline restarting")
                    closeBrowser()
                    start(request.copy(play = desiredPlaying), restartTokens)
                    return
                }
                fail(request, webStationFailureKind(reason))
            }
            "fatal" -> {
                val reason = data.optString("reason")
                Log.w(TAG, "Station player unavailable reason=${reason.takeIf { it.matches(SAFE_REASON) } ?: "Error"}")
                val request = pending ?: active
                closeBrowser()
                request?.let { fail(it, webStationFailureKind(reason)) }
            }
        }
    }

    private fun sendPrepare(request: Request) {
        val play = desiredPlaying
        if (play && !requestFocus()) {
            fail(request, PlaybackFailureKind.Unexpected)
            return
        }
        val stationId = request.station.catalogId ?: run {
            fail(request, PlaybackFailureKind.TrackUnavailable)
            return
        }
        Log.i(TAG, "Station prepare sent play=$play portOpen=${port != null}")
        send(JSONObject().put("cmd", "prepare").put("generation", request.generation)
            .put("stationId", stationId).put("play", play))
    }

    private fun send(command: JSONObject) {
        port?.postMessage(command)
    }

    private fun emit(request: Request, state: JSONObject, prepared: Boolean): PlaybackSnapshot =
        webStationSnapshot(state, request.station, request.generation, prepared, null, ::failureMessage)
            .also(::publish)

    private fun fail(request: Request, kind: PlaybackFailureKind) {
        if (active?.generation != request.generation && pending?.generation != request.generation) return
        publish(webStationSnapshot(JSONObject(), request.station, request.generation, prepared = false,
            failure = kind, failureMessage = ::failureMessage))
        if (pending?.generation == request.generation) pending = null
        abandonFocus()
    }

    private fun publish(snapshot: PlaybackSnapshot) {
        latest = snapshot
        listener?.invoke(snapshot)
    }

    private fun failureMessage(kind: PlaybackFailureKind): String = when (kind) {
        PlaybackFailureKind.TrackUnavailable -> AppText.text(R.string.services_track_unavailable)
        PlaybackFailureKind.Authorization -> AppText.text(R.string.services_playback_auth_required)
        PlaybackFailureKind.Network -> AppText.text(R.string.services_apple_connection_failed)
        PlaybackFailureKind.PositionRestore, PlaybackFailureKind.Unexpected ->
            AppText.text(R.string.services_playback_failed)
    }

    private fun browserStopped(stopped: GeckoSession) {
        if (stopped !== session) return
        Log.w(TAG, "Station browser content process stopped")
        val request = active
        closeBrowser()
        request?.let { fail(it, PlaybackFailureKind.Unexpected) }
    }

    private fun closeBrowser() {
        bootJob?.cancel()
        bootJob = null
        port?.disconnect()
        port = null
        session?.let { browser ->
            extension?.let { runCatching { browser.webExtensionController.setMessageDelegate(it, null, NATIVE_APP) } }
            runCatching { browser.close() }
        }
        session = null
        server?.close()
        server = null
        pageReady = false
        tokens = null
    }

    // GeckoView leaves Android audio focus to the embedder.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                focusRequest = null
                send(JSONObject().put("cmd", "pause"))
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = latest?.isPlaying == true
                send(JSONObject().put("cmd", "pause"))
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                send(JSONObject().put("cmd", "play"))
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        resumeOnFocusGain = false
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "AMATV-Station"
        const val EXTENSION_ID = "amatv-radio@dev.amatv"
        const val NATIVE_APP = "amatv_radio"
        const val CONTEXT_ID = "amatv-station-player"
        val SAFE_REASON = Regex("[A-Za-z_]{2,40}")
    }
}

private suspend fun <T : Any> GeckoResult<T>.stationAwait(): T = suspendCancellableCoroutine { continuation ->
    accept({ value -> if (continuation.isActive) {
        if (value != null) continuation.resume(value)
        else continuation.resumeWithException(IllegalStateException("No browser result"))
    } }, { error -> if (continuation.isActive) continuation.resumeWithException(
        error ?: IllegalStateException("Browser initialization failed")) })
}
