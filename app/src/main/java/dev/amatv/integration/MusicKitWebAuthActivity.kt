package dev.amatv.integration

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var popupWebView: WebView? = null

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

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            text = getString(R.string.services_auth_loading)
            setPadding(48, 24, 48, 24)
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

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
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
        popupWebView?.let(::closePopupWebView)
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE)
            webView.stopLoading()
            webView.destroy()
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
                    rootContainer.addView(
                        child,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    child.requestFocus()
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
                    android.util.Log.d("MusicKitWebAuth", "JS [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                    return super.onConsoleMessage(consoleMessage)
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
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isPopup) {
                        status.visibility = View.GONE
                    }
                    view?.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        val failingUrl = request.url?.toString().orEmpty()
                        if (failingUrl.startsWith(AUTH_ORIGIN)) return
                        showStatus(getString(R.string.services_auth_page_failed,
                            error?.description ?: getString(R.string.services_unknown_error)))
                    }
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
        if (popupWebView === popup) {
            popupWebView = null
        }
        runCatching {
            rootContainer.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        if (::webView.isInitialized) {
            webView.requestFocus()
        }
    }

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
                  main { width: min(760px, 84vw); text-align: center; }
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
                  const button = document.getElementById('signin');
                  const error = document.getElementById('error');
                  const labels = $labels;
                  let configured = false;
                  setTimeout(() => {
                    if (!configured) {
                      const msg = labels.componentFailed + ' (Timeout / WebView incompatible)';
                      error.textContent = msg;
                      button.disabled = false;
                      button.textContent = labels.retry;
                      button.onclick = () => window.location.reload();
                      button.focus();
                    }
                  }, 12000);
                  document.addEventListener('musickitloaded', async () => {
                    try {
                      await MusicKit.configure({
                        developerToken: $tokenLiteral,
                        app: { name: 'AM for TV', build: $buildLiteral }
                      });
                      configured = true;
                      button.disabled = false;
                      button.textContent = labels.continue;
                      button.onclick = authorizeMusic;
                      button.focus();
                    } catch (e) {
                      configured = true;
                      const message = e && e.message ? e.message : String(e);
                      error.textContent = message;
                      button.disabled = false;
                      button.textContent = labels.retry;
                      button.onclick = () => window.location.reload();
                      button.focus();
                    }
                  });
                  function scriptFailed() {
                    const message = labels.componentFailed;
                    error.textContent = message;
                    button.disabled = false;
                    button.textContent = labels.retry;
                    button.onclick = () => window.location.reload();
                    button.focus();
                  }
                  async function authorizeMusic() {
                    button.disabled = true;
                    button.textContent = labels.waiting;
                    error.textContent = '';
                    try {
                      const instance = MusicKit.getInstance();
                      const token = await instance.authorize();
                      const resolved = (typeof token === 'string' && token.length > 20)
                        ? token
                        : (typeof instance.musicUserToken === 'string' ? instance.musicUserToken : '');
                      if (resolved && resolved.length > 20) {
                        $JS_BRIDGE.authorized(resolved);
                      } else {
                        throw new Error(labels.invalidAuthorization);
                      }
                    } catch (e) {
                      const message = e && e.message ? e.message : String(e);
                      error.textContent = message;
                      button.disabled = false;
                      button.textContent = labels.retry;
                      button.focus();
                    }
                  }
                </script>
                <script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js"
                  onerror="scriptFailed()"></script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun showStatus(message: String) = runOnUiThread {
        status.text = message
        status.visibility = View.VISIBLE
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
    }
}
