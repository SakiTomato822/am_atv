package dev.amatv.integration

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.amatv.BuildConfig
import dev.amatv.R
import org.json.JSONObject

/**
 * TV-friendly MusicKit Web authorization surface.
 *
 * Preserves `window.opener` across Apple's `window.open` authorization popup
 * while also capturing same-origin callback redirects on `https://am-atv.local/`.
 */
class MusicKitWebAuthActivity : Activity() {
    private lateinit var rootContainer: LinearLayout
    private lateinit var browserContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var popupWebView: WebView? = null
    private val authHandler = Handler(Looper.getMainLooper())
    private var popupLoadGeneration = 0
    private var lastPopupResourceFailure: String? = null
    private var popupPageReady = false
    private var popupHasShownInteractiveContent = false
    private var mainWebViewDestroyed = false
    private var rendererFailureReported = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextCompat.getContextForLanguage(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val developerToken = intent.getStringExtra(EXTRA_DEVELOPER_TOKEN)
        if (developerToken.isNullOrBlank()) {
            finishWithError(getString(R.string.services_auth_token_missing))
            return
        }
        Log.i(TAG, "Using ${currentWebViewLabel()}")

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = getString(R.string.services_auth_loading)
            val spacing = (12 * resources.displayMetrics.density).toInt()
            setPadding(spacing * 2, spacing, spacing * 2, spacing)
            setBackgroundColor(Color.rgb(20, 20, 24))
        }
        val createdView = runCatching {
            createConfiguredWebView(isPopup = false).apply {
                addJavascriptInterface(AuthBridge(), JS_BRIDGE)
            }
        }.getOrElse { error ->
            finishWithError(getString(R.string.services_auth_page_failed, error.message ?: getString(R.string.services_unknown_error)))
            return
        }
        webView = createdView

        browserContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 20, 24))
            // Native status owns a separate row; it can never cover Apple's form
            // or consent buttons, regardless of how long the status text becomes.
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(browserContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        }
        setContentView(rootContainer)
        webView.loadDataWithBaseURL(
            AUTH_ORIGIN,
            authorizationPage(developerToken),
            "text/html",
            "UTF-8",
            null,
        )
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val popup = popupWebView
        if (popup != null) {
            closePopupWebView(popup)
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        authHandler.removeCallbacksAndMessages(null)
        popupWebView?.let(::closePopupWebView)
        if (::webView.isInitialized && !mainWebViewDestroyed) {
            webView.removeJavascriptInterface(JS_BRIDGE)
            webView.stopLoading()
            webView.destroy()
            mainWebViewDestroyed = true
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(isPopup: Boolean): WebView {
        val created = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 8, 10))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    popupWebView?.let(::closePopupWebView)
                    val child = createConfiguredWebView(isPopup = true)
                    popupWebView = child
                    popupHasShownInteractiveContent = false
                    lastPopupResourceFailure = null
                    browserContainer.addView(
                        child,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    child.requestFocus()
                    hideStatus()
                    transport.webView = child
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    if (window != null) {
                        closePopupWebView(window)
                    }
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    // Apple may include account/authorization data in console text.
                    // Record where the error occurred without retaining its payload.
                    if (consoleMessage?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        Log.w(TAG, "Authorization script error at ${resourceLabel(consoleMessage.sourceId())}:${consoleMessage.lineNumber()}")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = captureAuthorizationCallback(request?.url)

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (captureAuthorizationCallback(url?.let(Uri::parse))) return
                    if (isPopup && isAppleAuthorizationUrl(url)) {
                        beginPopupLoad(view, url)
                    }
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isPopup && popupWebView == null) {
                        hideStatus()
                        view?.requestFocus()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    val failingUrl = request?.url?.toString().orEmpty()
                    if (isPopup && request?.isForMainFrame != true && isRelevantAuthResource(failingUrl)) {
                        lastPopupResourceFailure = "WebView ${error?.errorCode}"
                        Log.w(TAG, "Apple auth resource failed: ${resourceLabel(failingUrl)} (${lastPopupResourceFailure})")
                    }
                    if (request?.isForMainFrame == true) {
                        if (failingUrl.startsWith(AUTH_ORIGIN)) return
                        showStatus(getString(R.string.services_auth_page_failed,
                            error?.description ?: getString(R.string.services_unknown_error)))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val failingUrl = request?.url?.toString().orEmpty()
                    if (isPopup && isRelevantAuthResource(failingUrl)) {
                        lastPopupResourceFailure = "HTTP ${errorResponse?.statusCode ?: 0}"
                        Log.w(TAG, "Apple auth HTTP error: ${resourceLabel(failingUrl)} (${lastPopupResourceFailure})")
                    }
                    if (request?.isForMainFrame == true) {
                        showStatus(getString(
                            R.string.services_auth_page_failed,
                            lastPopupResourceFailure ?: getString(R.string.services_unknown_error),
                        ))
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    val affected = view ?: return true
                    if (::browserContainer.isInitialized) browserContainer.removeView(affected)
                    if (popupWebView === affected) popupWebView = null
                    if (::webView.isInitialized && webView === affected) mainWebViewDestroyed = true
                    runCatching { affected.destroy() }
                    if (!rendererFailureReported) {
                        rendererFailureReported = true
                        val reason = if (detail?.didCrash() == true) "crashed" else "was reclaimed"
                        Log.e(TAG, "WebView renderer $reason; provider=${currentWebViewLabel()}")
                        finishWithError(getString(
                            R.string.services_auth_renderer_stopped,
                            currentWebViewLabel(),
                        ))
                    }
                    return true
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(created, true)
        }
        return created
    }

    private fun closePopupWebView(popup: WebView) {
        popupLoadGeneration++
        if (popupWebView === popup) {
            popupWebView = null
        }
        runCatching {
            browserContainer.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        if (::webView.isInitialized && !mainWebViewDestroyed && !isFinishing) {
            hideStatus()
            webView.requestFocus()
        }
    }

    private fun beginPopupLoad(view: WebView?, url: String?) {
        val popup = view ?: return
        if (popupWebView !== popup) return
        popupLoadGeneration++
        val generation = popupLoadGeneration
        popupPageReady = false
        hideStatus()
        Log.i(TAG, "Apple authorization navigation: ${Uri.parse(url).host}")
        authHandler.postDelayed(
            { probePopupReadiness(popup, generation) },
            POPUP_INITIAL_PROBE_DELAY_MS,
        )
    }

    private fun probePopupReadiness(popup: WebView, generation: Int) {
        if (popupWebView !== popup || generation != popupLoadGeneration || popupPageReady || isFinishing) return
        popup.evaluateJavascript(POPUP_READINESS_PROBE) { rawResult ->
            if (popupWebView !== popup || generation != popupLoadGeneration || isFinishing) {
                return@evaluateJavascript
            }
            if (rawResult == "\"interactive\"") {
                // A hydrated form stays ready until a real main-frame navigation.
                // Stop readiness polling before the user starts typing. Input
                // and authorization have no app-imposed deadline.
                popupPageReady = true
                popupHasShownInteractiveContent = true
                popupLoadGeneration++
                popup.settings.cacheMode = WebSettings.LOAD_DEFAULT
                hideStatus()
                popup.requestFocus()
                Log.i(TAG, "Apple authorization form ready")
            } else if (rawResult == "\"embedded\"") {
                // Same-origin JS cannot inspect cross-origin Apple ID frames. Do
                // not call that success or reload a potentially working form.
                hideStatus()
            }
            if (generation == popupLoadGeneration) {
                authHandler.postDelayed(
                    { probePopupReadiness(popup, generation) },
                    POPUP_PROBE_INTERVAL_MS,
                )
            }
        }
    }

    private fun isAppleAuthorizationUrl(url: String?): Boolean = runCatching {
        isAppleAuthorizationHost(Uri.parse(url).host)
    }.getOrDefault(false)

    private fun isRelevantAuthResource(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        isAppleAuthResourceHost(host) && (
            path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".html") ||
                path.contains("auth") || path.contains("signin")
            )
    }.getOrDefault(false)

    private fun currentWebViewLabel(): String {
        val provider = WebView.getCurrentWebViewPackage() ?: return "WebView unavailable"
        return "${provider.packageName} ${provider.versionName.orEmpty()}".trim()
    }

    private fun resourceLabel(url: String?): String = runCatching {
        val uri = Uri.parse(url.orEmpty())
        "${uri.host.orEmpty()}${uri.path.orEmpty()}"
    }.getOrDefault("unknown")

    private fun authorizationPage(developerToken: String): String {
        val tokenLiteral = JSONObject.quote(developerToken)
        val buildLiteral = JSONObject.quote(BuildConfig.VERSION_NAME)
        val labels = JSONObject()
            .put("continue", getString(R.string.services_auth_continue))
            .put("componentFailed", getString(R.string.services_auth_component_failed))
            .put("waiting", getString(R.string.services_auth_waiting))
            .put("invalidAuthorization", getString(R.string.services_auth_invalid_user_token))
            .put("retry", getString(R.string.services_auth_retry))
        return """
            <!doctype html>
            <html lang="${resources.configuration.locales[0].toLanguageTag()}">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                  * { box-sizing: border-box; }
                  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                    background: radial-gradient(circle at 50% 0%, #40152d, #09090b 60%);
                    color: #fff; font: 24px -apple-system, BlinkMacSystemFont, sans-serif; }
                  main { width: 84vw; max-width: 760px; text-align: center; }
                  h1 { font-size: 46px; margin: 0 0 18px; }
                  p { color: rgba(255,255,255,.68); line-height: 1.5; }
                  button { margin-top: 28px; min-width: 360px; padding: 20px 34px; border: 0;
                    border-radius: 38px; background: #fff; color: #111; font-size: 24px;
                    font-weight: 700; outline: 0; cursor: pointer; }
                  button:focus { transform: scale(1.04); box-shadow: 0 0 0 5px #ff375f; }
                  button:disabled { opacity: .45; }
                  #error { color: #ff9bab; min-height: 36px; margin-top: 18px; font-size: 18px; }
                </style>
              </head>
              <body>
                <main>
                  <h1>${TextUtils.htmlEncode(getString(R.string.services_auth_title))}</h1>
                  <button id="signin" disabled onclick="authorizeMusic()">${TextUtils.htmlEncode(getString(R.string.services_auth_preparing))}</button>
                  <p id="error"></p>
                </main>
                <script>
                  // This synthetic origin only holds our MusicKit bootstrap
                  // state. Never let its old token bypass a fresh local sign-in.
                  try {
                    window.localStorage.clear();
                    window.sessionStorage.clear();
                  } catch (storageUnavailable) { }
                  var button = document.getElementById('signin');
                  var error = document.getElementById('error');
                  var labels = $labels;
                  function readableError(value) {
                    return value && value.message ? value.message : String(value);
                  }
                  function showAuthorizeError(value, reload) {
                    error.textContent = readableError(value);
                    button.disabled = false;
                    button.textContent = labels.retry;
                    button.onclick = reload ? function() { window.location.reload(); } : authorizeMusic;
                    button.focus();
                  }
                  document.addEventListener('musickitloaded', function() {
                    try {
                      var configuration = MusicKit.configure({
                        developerToken: $tokenLiteral,
                        app: { name: 'AM for TV', build: $buildLiteral }
                      });
                      Promise.resolve(configuration).then(function() {
                        button.disabled = false;
                        button.textContent = labels.continue;
                        button.onclick = authorizeMusic;
                        button.focus();
                      }, function(value) {
                        showAuthorizeError(value, true);
                      });
                    } catch (e) {
                      showAuthorizeError(e, true);
                    }
                  });
                  function scriptFailed() {
                    var message = labels.componentFailed;
                    error.textContent = message;
                    button.disabled = false;
                    button.textContent = labels.retry;
                    button.onclick = function() { window.location.reload(); };
                    button.focus();
                  }
                  function authorizeMusic() {
                    button.disabled = true;
                    button.textContent = labels.waiting;
                    error.textContent = '';
                    try {
                      var instance = MusicKit.getInstance();
                      Promise.resolve(instance.authorize()).then(function(token) {
                        var resolved = (typeof token === 'string' && token.length > 20)
                          ? token
                          : (typeof instance.musicUserToken === 'string' ? instance.musicUserToken : '');
                        if (resolved && resolved.length > 20) {
                          $JS_BRIDGE.authorized(resolved);
                        } else {
                          showAuthorizeError(new Error(labels.invalidAuthorization));
                        }
                      }, showAuthorizeError);
                    } catch (e) {
                      showAuthorizeError(e);
                    }
                  }
                </script>
                <script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js"
                  onerror="scriptFailed()"></script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun showStatus(message: String, onRetry: (() -> Unit)? = null) = runOnUiThread {
        status.text = message
        status.visibility = View.VISIBLE
        status.isClickable = onRetry != null
        status.isFocusable = onRetry != null
        status.setOnClickListener(if (onRetry == null) null else View.OnClickListener { onRetry() })
        if (onRetry != null && !popupHasShownInteractiveContent) status.requestFocus()
    }

    private fun hideStatus() = runOnUiThread {
        status.setOnClickListener(null)
        status.isClickable = false
        status.isFocusable = false
        status.visibility = View.GONE
    }

    private fun captureAuthorizationCallback(uri: Uri?): Boolean {
        if (uri?.scheme != "https" || uri.host != "am-atv.local") return false
        val fragment = uri.encodedFragment?.let(Uri::decode).orEmpty()
        if (fragment.isBlank()) return false
        val callbackValue = fragment
            .substringAfter("music-user-token=", fragment)
            .substringAfter("musicUserToken=", fragment)
            .substringBefore('&')
            .trim()
        val token = extractMusicUserToken(callbackValue)
        if (token.length <= 20 || token.any(Char::isWhitespace)) return false
        runOnUiThread {
            popupWebView?.let(::closePopupWebView)
            webView.stopLoading()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_USER_TOKEN, token))
            finish()
        }
        return true
    }

    private fun extractMusicUserToken(callbackValue: String): String = runCatching {
        val decoded = String(Base64.decode(callbackValue, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(decoded).optString("musicUserToken")
            .takeIf { it.length > 20 && it.none(Char::isWhitespace) }
            ?: callbackValue
    }.getOrDefault(callbackValue)

    private fun finishWithError(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    private inner class AuthBridge {
        @JavascriptInterface
        fun authorized(userToken: String) {
            if (userToken.length <= 20 || userToken.any(Char::isWhitespace)) {
                failed(getString(R.string.services_auth_invalid_token_format))
                return
            }
            runOnUiThread {
                popupWebView?.let(::closePopupWebView)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_USER_TOKEN, userToken))
                finish()
            }
        }

        @JavascriptInterface
        fun failed(message: String) {
            showStatus(getString(R.string.services_auth_incomplete, message))
        }
    }

    companion object {
        const val EXTRA_DEVELOPER_TOKEN = "dev.amatv.extra.DEVELOPER_TOKEN"
        const val EXTRA_USER_TOKEN = "dev.amatv.extra.MUSIC_USER_TOKEN"
        const val EXTRA_ERROR = "dev.amatv.extra.AUTH_ERROR"
        private const val JS_BRIDGE = "AndroidMusicAuth"
        private const val AUTH_ORIGIN = "https://am-atv.local/"
        private const val TAG = "MusicKitWebAuth"
        private const val POPUP_INITIAL_PROBE_DELAY_MS = 1_200L
        private const val POPUP_PROBE_INTERVAL_MS = 500L
        private const val POPUP_READINESS_PROBE = """
            (function() {
              if (document.readyState === 'loading' || !document.body) return 'incomplete';
              var opaqueFrame = false;
              function visible(node) {
                var rect = node.getBoundingClientRect();
                var style = node.ownerDocument.defaultView.getComputedStyle(node);
                return rect.width > 16 && rect.height > 16 && style.display !== 'none' &&
                  style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
              }
              function inspect(doc) {
                var nodes = doc.querySelectorAll(
                  'input:not([type="hidden"]), [role="textbox"], [contenteditable="true"], ' +
                  'button, [role="button"]'
                );
                for (var i = 0; i < nodes.length; i++) {
                  var node = nodes[i];
                  var chrome = node.closest('header, footer, nav, [role="banner"], ' +
                    '[role="contentinfo"], [role="navigation"]');
                  if (!chrome && !node.disabled && node.getAttribute('aria-disabled') !== 'true' &&
                      visible(node)) return true;
                }
                var frames = doc.querySelectorAll('iframe');
                for (var j = 0; j < frames.length; j++) {
                  if (!visible(frames[j])) continue;
                  try {
                    var child = frames[j].contentDocument;
                    if (child && child.body) {
                      if (inspect(child)) return true;
                    } else {
                      opaqueFrame = true;
                    }
                  } catch (e) { opaqueFrame = true; }
                }
                return false;
              }
              if (inspect(document)) return 'interactive';
              return opaqueFrame ? 'embedded' : 'incomplete';
            })()
        """
    }
}

internal fun isAppleAuthorizationHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase().orEmpty()
    return normalized == "apple.com" || normalized.endsWith(".apple.com") ||
        normalized == "icloud.com" || normalized.endsWith(".icloud.com")
}

private fun isAppleAuthResourceHost(host: String): Boolean =
    isAppleAuthorizationHost(host) || host == "cdn-apple.com" || host.endsWith(".cdn-apple.com")
