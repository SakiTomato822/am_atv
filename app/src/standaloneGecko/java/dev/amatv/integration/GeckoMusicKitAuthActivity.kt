package dev.amatv.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.amatv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.util.UUID
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled-browser sign-in only. MusicKit's native music playback is unchanged. */
class GeckoMusicKitAuthActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val contextId = "amatv-auth-${UUID.randomUUID()}"
    private val sessions = mutableListOf<GeckoSession>()
    private lateinit var browser: GeckoView
    private lateinit var status: TextView
    private lateinit var diagnostics: AuthDiagnosticsOverlay
    private var loopback: AuthLoopbackServer? = null
    private var extension: WebExtension? = null
    private var mainSession: GeckoSession? = null
    private var runtime: GeckoRuntime? = null
    private var completed = false
    private data class RemotePress(val session: GeckoSession, val keyCode: Int?, val metaState: Int)
    private val remotePresses = mutableMapOf<Int, RemotePress>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextCompat.getContextForLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val developerToken = intent.getStringExtra(MusicKitWebAuthActivity.EXTRA_DEVELOPER_TOKEN)
        if (developerToken.isNullOrBlank()) {
            finishWithError(getString(R.string.services_auth_token_missing))
            return
        }
        val outer = FrameLayout(this)
        status = TextView(this).apply {
            if (dev.amatv.BuildConfig.FLYME_AUTO) typeface = dev.amatv.ui.CarFonts.typeface(resources)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 20, 24))
            textSize = 16f
            gravity = Gravity.CENTER
            val gap = (12 * resources.displayMetrics.density).toInt()
            setPadding(gap * 2, gap, gap * 2, gap)
            text = getString(R.string.services_auth_loading)
        }
        browser = GeckoView(this).apply {
            setBackgroundColor(Color.rgb(8, 8, 10))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        outer.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(outer)
        diagnostics = AuthDiagnosticsOverlay.attach(outer)
        diagnostics.event("Bundled GeckoView $GECKO_VERSION; private sign-in")
        scope.launch {
            var createdServer: AuthLoopbackServer? = null
            try {
                val engine = GeckoAuthRuntime.get(applicationContext)
                runtime = engine
                val bridge = engine.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/gecko-auth/", EXTENSION_ID,
                ).awaitResult()
                extension = engine.webExtensionController
                    .setAllowedInPrivateBrowsing(bridge, true).awaitResult()
                val server = withContext(Dispatchers.IO) {
                    AuthLoopbackServer { origin ->
                        musicKitAuthorizationPage(this@GeckoMusicKitAuthActivity, developerToken, bridgeShim(origin))
                    }.also { createdServer = it }
                }
                loopback = server
                val session = createSession()
                mainSession = session
                sessions += session
                session.open(engine)
                browser.setSession(session)
                session.loadUri(server.origin.url)
                browser.requestFocus()
                diagnostics.event("Local authentication bootstrap ready")
            } catch (cancelled: CancellationException) {
                createdServer?.close()
                throw cancelled
            } catch (error: Throwable) {
                createdServer?.close()
                diagnostics.add("ERROR", "Bundled browser initialization failed: ${error.javaClass.simpleName}")
                finishWithError(getString(R.string.services_auth_page_failed,
                    error.message ?: getString(R.string.services_unknown_error)))
            }
        }
    }

    private fun bridgeShim(origin: GeckoAuthOrigin): String = """
        window.$MUSIC_AUTH_JS_BRIDGE = {
          authorized: function(token) {
            window.postMessage({type:'amatv-auth-result', nonce:${JSONObject.quote(origin.nonce)}, token:token}, window.location.origin);
          }
        };
    """.trimIndent()

    private fun createSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .contextId(contextId)
            .allowJavascript(true)
            .useTrackingProtection(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build()
        return GeckoSession(settings).apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> {
                    val origin = loopback?.origin
                    if (origin != null && origin.ownsUrl(request.uri, allowFragment = true)) {
                        if (captureCallback(request.uri)) return GeckoResult.deny()
                        return if (session === mainSession) GeckoResult.allow() else GeckoResult.deny()
                    }
                    if (request.uri == "about:blank" || isGeckoAppleAuthUrl(request.uri)) return GeckoResult.allow()
                    diagnostics.add("WARN", "Blocked non-Apple authorization navigation", request.uri)
                    return GeckoResult.deny()
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    if (isFinishing || completed || !sessions.contains(session)) return null
                    if (uri != "about:blank" && !isGeckoAppleAuthUrl(uri)) return null
                    // Gecko opens this fresh session itself. Manually opening or
                    // loading it here would break the JS opener relationship.
                    val child = createSession()
                    sessions += child
                    browser.releaseSession()
                    browser.setSession(child)
                    browser.requestFocus()
                    hideStatus()
                    diagnostics.event("Opened Apple authorization window")
                    Log.i(TAG, "Opened Apple authorization window: ${runCatching { URI(uri).host }.getOrNull()}")
                    return GeckoResult.fromValue(child)
                }

                override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                    if (!sessions.contains(session) || isFinishing) return null
                    diagnostics.add("ERROR", "Page load failed (category=${error.category}, code=${error.code})", uri)
                    showError(getString(R.string.services_auth_page_failed, "GeckoView ${error.code}")) {
                        sessions.lastOrNull()?.reload()
                    }
                    return null
                }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCloseRequest(session: GeckoSession) { closeSession(session) }
                override fun onFocusRequest(session: GeckoSession) {
                    if (sessions.lastOrNull() === session) browser.requestFocus()
                }
                override fun onCrash(session: GeckoSession) = rendererStopped()
                override fun onKill(session: GeckoSession) = rendererStopped()
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (sessions.lastOrNull() === session) hideStatus()
                    diagnostics.add("INFO", "Loading authorization document", url)
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    diagnostics.event("Authorization document ${if (success) "loaded" else "failed"}")
                }
            }
            extension?.let { webExtensionController.setMessageDelegate(it, messageDelegate, NATIVE_APP) }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP || completed || isFinishing || sender.webExtension.id != EXTENSION_ID ||
                sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                !sessions.contains(sender.session)
            ) return null
            val data = message as? JSONObject ?: return null
            val origin = loopback?.origin ?: return null
            when (data.optString("kind")) {
                "bootstrap-ready" -> {
                    if (sender.session !== mainSession || !sender.isTopLevel || !origin.ownsUrl(sender.url)) return null
                    val secure = data.optBoolean("secureContext")
                    diagnostics.event("Native bridge ready; secure context=$secure")
                    Log.i(TAG, "Native bridge ready; secureContext=$secure; GeckoView=$GECKO_VERSION")
                }
                "authorized" -> {
                    if (sender.session !== mainSession || !sender.isTopLevel ||
                        !origin.ownsUrl(sender.url) || data.optString("nonce") != origin.nonce
                    ) return null
                    val token = data.optString("token")
                    if (validGeckoMusicUserToken(token)) finishWithToken(token)
                }
                "diagnostic" -> {
                    if (!origin.ownsUrl(sender.url) && !isGeckoAppleAuthUrl(sender.url)) return null
                    diagnostics.add(
                        data.optString("level", "INFO").take(12),
                        data.optString("message").take(700),
                        data.optString("source", sender.url).take(1_024),
                        data.optInt("line").takeIf { it > 0 },
                    )
                    // Only fixed lifecycle labels from our own bootstrap go to
                    // logcat. Arbitrary Apple/console payloads stay sanitized in UI.
                    if (sender.session === mainSession && origin.ownsUrl(sender.url) &&
                        data.optString("message") in setOf(
                            "MusicKit script loaded", "MusicKit ready", "MusicKit authorization requested", "MusicKit authorization completed",
                        )
                    ) Log.i(TAG, data.optString("message"))
                }
            }
            return GeckoResult.fromValue(JSONObject().put("ok", true) as Any)
        }
    }

    private fun captureCallback(url: String): Boolean {
        val origin = loopback?.origin ?: return false
        if (!origin.ownsUrl(url, allowFragment = true)) return false
        val token = geckoCallbackToken(url) ?: return false
        finishWithToken(token)
        return true
    }

    private fun finishWithToken(token: String) {
        if (completed || isFinishing) return
        completed = true
        diagnostics.event("MusicKit authorization completed")
        setResult(RESULT_OK, Intent().putExtra(MusicKitWebAuthActivity.EXTRA_USER_TOKEN, token))
        finish()
    }

    private fun finishWithError(message: String) {
        if (completed || isFinishing) return
        completed = true
        setResult(RESULT_CANCELED, Intent().putExtra(MusicKitWebAuthActivity.EXTRA_ERROR, message))
        finish()
    }

    private fun rendererStopped() {
        diagnostics.add("ERROR", "Bundled browser content process stopped")
        finishWithError(getString(R.string.services_auth_renderer_stopped, "GeckoView $GECKO_VERSION"))
    }

    private fun showError(message: String, retry: () -> Unit) {
        status.text = message
        status.visibility = View.VISIBLE
        status.isFocusable = true
        status.isClickable = true
        status.setOnClickListener { retry() }
        status.requestFocus()
    }

    private fun hideStatus() {
        val restoreBrowserFocus = status.hasFocus()
        status.visibility = View.GONE
        status.setOnClickListener(null)
        status.isFocusable = false
        status.isClickable = false
        if (restoreBrowserFocus) browser.requestFocus()
    }

    private fun closeSession(session: GeckoSession) {
        val index = sessions.indexOf(session)
        if (index < 0) return
        if (session === mainSession) {
            finish()
            return
        }
        // A nested window can retain its parent's opener until explicitly closed.
        browser.releaseSession()
        while (sessions.size > index) sessions.removeAt(sessions.lastIndex).close()
        sessions.lastOrNull()?.let { browser.setSession(it) }
        browser.requestFocus()
        hideStatus()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val current = sessions.lastOrNull()
        if (current != null && current !== mainSession) closeSession(current) else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && ::diagnostics.isInitialized) {
            diagnostics.toggleVisibility()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val held = remotePresses[event.keyCode]
        if (held != null && (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0)) {
            if (event.action == KeyEvent.ACTION_UP) remotePresses.remove(event.keyCode)
            // Opening a popup or keyboard mid-press must not release the old
            // activation key into a different window. Ignore repeated submits.
            if (held.keyCode == null || browser.session !== held.session || !browser.hasFocus() ||
                (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            ) return true
            return dispatchBrowserKey(event, held)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        remotePresses.remove(event.keyCode)
        if (::browser.isInitialized && browser.hasFocus()) {
            // Let the TV keyboard own all its navigation keys while visible.
            if (ViewCompat.getRootWindowInsets(browser)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                return super.dispatchKeyEvent(event)
            }
            val inputMethod = getSystemService(InputMethodManager::class.java)
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN &&
                inputMethod.isActive(browser) && inputMethod.isAcceptingText
            ) {
                browser.session?.let { remotePresses[event.keyCode] = RemotePress(it, null, event.metaState) }
                inputMethod.showSoftInput(browser, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
            val keyCode = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_TAB
                KeyEvent.KEYCODE_DPAD_CENTER -> KeyEvent.KEYCODE_ENTER
                else -> return super.dispatchKeyEvent(event)
            }
            // Gecko uses browser keyboard navigation rather than WebView's TV
            // focus search. Preserve both halves of a real key press so clicks
            // remain user gestures. Left/right continue to move the text caret.
            val meta = event.metaState or if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                KeyEvent.META_SHIFT_ON
            } else 0
            val session = browser.session ?: return super.dispatchKeyEvent(event)
            val press = RemotePress(session, keyCode, meta)
            remotePresses[event.keyCode] = press
            return dispatchBrowserKey(event, press)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchBrowserKey(event: KeyEvent, press: RemotePress): Boolean =
        super.dispatchKeyEvent(KeyEvent(
            event.downTime, event.eventTime, event.action, checkNotNull(press.keyCode), event.repeatCount,
            press.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        ))

    override fun onDestroy() {
        scope.cancel()
        if (::diagnostics.isInitialized) diagnostics.dispose()
        if (::browser.isInitialized && browser.session != null) browser.releaseSession()
        sessions.asReversed().forEach { session ->
            extension?.let { session.webExtensionController.setMessageDelegate(it, null, NATIVE_APP) }
            runCatching { session.close() }
        }
        sessions.clear()
        remotePresses.clear()
        mainSession = null
        loopback?.close()
        loopback = null
        runtime?.storageController?.clearDataForSessionContext(contextId)
        // Runtime is process-scoped; close private tabs, not the engine. The
        // isolated :gecko_auth process can be reclaimed without touching audio.
        super.onDestroy()
    }

    private companion object {
        const val EXTENSION_ID = "amatv-auth@dev.amatv"
        const val NATIVE_APP = "amatv_auth"
        const val GECKO_VERSION = "153.0.20260810162159"
        const val TAG = "MusicKitGeckoAuth"
    }
}

private object GeckoAuthRuntime {
    private var instance: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime = instance ?: GeckoRuntime.create(
        context.applicationContext,
        GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(false)
            .remoteDebuggingEnabled(false)
            .contentBlocking(ContentBlocking.Settings.Builder()
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .build())
            .build(),
    ).also { instance = it }
}

private suspend fun <T : Any> GeckoResult<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    accept(
        { value ->
            if (continuation.isActive) {
                if (value != null) continuation.resume(value)
                else continuation.resumeWithException(IllegalStateException("Bundled browser initialization returned no result"))
            }
        },
        { error ->
            if (continuation.isActive) continuation.resumeWithException(
                error ?: IllegalStateException("Bundled browser initialization failed"),
            )
        },
    )
}
