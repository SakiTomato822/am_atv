package dev.amatv.integration

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import dev.amatv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shell-only preview experiment. No production player routing is changed. */
class GeckoRadioProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = mutableListOf<GeckoSession>()
    private var server: AuthLoopbackServer? = null
    private var probeExtension: WebExtension? = null
    private var messageDelegate: WebExtension.MessageDelegate? = null
    private var mainSession: GeckoSession? = null
    private lateinit var view: GeckoView
    private lateinit var status: TextView
    private var eventCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "activity_enter previewBuild=${BuildConfig.FLYME_UI_PREVIEW}")
        if (!BuildConfig.FLYME_UI_PREVIEW) { finish(); return }
        status = TextView(this).apply {
            text = "正在启动独立电台验证…"
            setPadding(20, 10, 20, 10)
        }
        event("status_created")
        event("geckoview_constructing")
        view = GeckoView(this)
        event("geckoview_constructed")
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        event("content_view_ready")
        scope.launch {
            try {
                event("runtime_starting")
                val runtime = GeckoAuthRuntime.get(applicationContext)
                event("runtime_ready")
                event("extension_starting")
                val extension = runtime.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/gecko-radio-probe/", EXTENSION_ID,
                ).probeAwait()
                event("extension_ready")
                val delegate = object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any,
                        sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val origin = server?.origin ?: return null
                        if (nativeApp != NATIVE_APP || sender.webExtension.id != EXTENSION_ID ||
                            sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                            sender.session !== mainSession || !sender.isTopLevel || !origin.ownsUrl(sender.url)
                        ) return null
                        val data = message as? JSONObject ?: return null
                        if (data.optString("nonce") != origin.nonce) return null
                        val stage = data.optString("stage")
                        if (stage !in STAGES) return null
                        val safe = JSONObject().put("stage", stage)
                        for (key in BOOLEAN_FIELDS) if (data.has(key)) safe.put(key, data.optBoolean(key))
                        for (key in NUMBER_FIELDS) if (data.has(key)) {
                            val n = data.optDouble(key)
                            if (n.isFinite()) safe.put(key, n)
                        }
                        val kind = data.optString("kind")
                        if (kind in setOf("personal", "live")) safe.put("kind", kind)
                        val error = data.optString("error")
                        if (error.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,60}"))) safe.put("error", error)
                        event(safe.toString())
                        return GeckoResult.fromValue(JSONObject().put("ok", true) as Any)
                    }
                }
                probeExtension = extension
                messageDelegate = delegate
                val userToken = SecureTokenStore(this@GeckoRadioProbeActivity).readUserToken()
                server = withContext(Dispatchers.IO) {
                    AuthLoopbackServer { origin ->
                        val config = JSONObject().put("nonce", origin.nonce)
                            .put("developerToken", BuildConfig.MUSICKIT_DEVELOPER_TOKEN)
                            .put("userToken", userToken.orEmpty()).toString().replace("<", "\\u003c")
                        assets.open("gecko-radio-probe/page.html").bufferedReader().use { it.readText() }
                            .replace("__CONFIG__", config)
                            .replace("__SCRIPT__", assets.open("gecko-radio-probe/probe.js")
                                .bufferedReader().use { it.readText() })
                    }
                }
                event("loopback_ready")
                val session = createSession()
                mainSession = session
                sessions += session
                session.webExtensionController.setMessageDelegate(extension, delegate, NATIVE_APP)
                event("bridge_ready")
                event("session_opening")
                session.open(runtime)
                event("session_opened")
                view.setSession(session)
                session.loadUri(checkNotNull(server).origin.url)
                event("navigation_requested storedUserTokenPresent=${!userToken.isNullOrBlank()}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                event("bootstrap_failed class=${e.javaClass.simpleName}")
            }
        }
    }

    private fun createSession(): GeckoSession = GeckoSession(GeckoSessionSettings.Builder()
        .usePrivateMode(false).contextId("amatv-radio-probe")
        .allowJavascript(true).useTrackingProtection(false)
        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build()).apply {
        permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                val origin = server?.origin
                val local = runCatching {
                    val u = URI(perm.uri)
                    u.scheme == "http" && u.host == "127.0.0.1" && u.port == origin?.port
                }.getOrDefault(false)
                val approved = sessions.contains(session) && local && perm.permission in setOf(
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                )
                event("permission type=${perm.permission} granted=$approved")
                return GeckoResult.fromValue(if (approved)
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                if (server?.origin?.ownsUrl(request.uri, allowFragment = true) == true ||
                    request.uri == "about:blank" || isGeckoAppleAuthUrl(request.uri)) GeckoResult.allow()
                else GeckoResult.deny()

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri != "about:blank" && !isGeckoAppleAuthUrl(uri)) return null
                val child = createSession()
                sessions += child
                view.releaseSession()
                view.setSession(child)
                event("authorization_window")
                return GeckoResult.fromValue(child)
            }
        }
        contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                if (session !== mainSession) {
                    sessions.remove(session)
                    view.releaseSession()
                    session.close()
                    mainSession?.let { view.setSession(it) }
                }
            }
        }
        progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { event("page_started") }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                event(if (success) "page_loaded" else "page_failed")
            }
        }
        mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, media: MediaSession) { event("gecko_media_activated") }
            override fun onPlay(session: GeckoSession, media: MediaSession) { event("gecko_media_play") }
            override fun onPause(session: GeckoSession, media: MediaSession) { event("gecko_media_pause") }
            override fun onStop(session: GeckoSession, media: MediaSession) { event("gecko_media_stop") }
            override fun onPositionState(session: GeckoSession, media: MediaSession,
                state: MediaSession.PositionState) {
                event("gecko_position position=${state.position} rate=${state.playbackRate}")
            }
        }
    }

    private fun event(value: String) {
        if (++eventCount > 350) return
        Log.i(TAG, value)
        status.text = value
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { view.releaseSession() }
        sessions.forEach { session ->
            probeExtension?.let { extension ->
                runCatching { session.webExtensionController.setMessageDelegate(extension, null, NATIVE_APP) }
            }
            runCatching { session.close() }
        }
        messageDelegate = null
        probeExtension = null
        server?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AMATV-RadioProbe"
        private const val EXTENSION_ID = "amatv-radio-probe@dev.amatv"
        private const val NATIVE_APP = "amatv_radio_probe"
        private val BOOLEAN_FIELDS = setOf("secure", "eme", "authorized", "userTokenPresent", "playing", "itemPresent")
        private val NUMBER_FIELDS = setOf("status", "count", "position", "state")
        private val STAGES = setOf("environment", "drm_unavailable", "drm_keys_created", "drm_failed",
            "script_failed", "script_loaded", "unhandled", "musickit_ready", "configure_failed",
            "playback_state", "item_changed", "media_error", "clock", "authorization_done",
            "authorization_failed", "web_authorization_needed", "station_request", "station_response",
            "station_missing", "queue_ready", "play_resolved", "station_failed", "stopped", "stop_failed")
    }
}

private suspend fun <T : Any> GeckoResult<T>.probeAwait(): T = suspendCancellableCoroutine { continuation ->
    accept({ value -> if (continuation.isActive) {
        if (value != null) continuation.resume(value)
        else continuation.resumeWithException(IllegalStateException("No browser result"))
    } }, { error -> if (continuation.isActive) continuation.resumeWithException(
        error ?: IllegalStateException("Browser initialization failed")) })
}
