package dev.amatv.integration

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Shared by the system WebView and compatibility login surfaces. */
internal class AuthDiagnosticsOverlay private constructor(private val parent: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private val buffer = AuthDiagnosticsBuffer()
    private val lock = Any()
    private val startedAt = SystemClock.elapsedRealtime()
    private var updateScheduled = false
    @Volatile private var disposed = false
    @Volatile private var visible = true

    private val text = TextView(parent.context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f
        setTextColor(Color.rgb(230, 236, 232))
        setPadding(dp(6), dp(5), dp(6), dp(5))
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val panel = PassThroughScrollView(parent.context).apply {
        setBackgroundColor(Color.argb(185, 0, 0, 0))
        elevation = dp(12).toFloat()
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }
    private val scrollToLatest = Runnable {
        if (!disposed) panel.scrollTo(0, text.height)
    }
    private val updateBounds = Runnable {
        if (disposed) return@Runnable
        val metrics = parent.resources.displayMetrics
        val availableWidth = parent.width.takeIf { it > 0 } ?: metrics.widthPixels
        val availableHeight = parent.height.takeIf { it > 0 } ?: metrics.heightPixels
        panel.layoutParams = FrameLayout.LayoutParams(
            minOf(dp(300), (availableWidth * 0.27f).roundToInt()).coerceAtLeast(1),
            (availableHeight * 0.30f).roundToInt().coerceAtLeast(1),
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            updateBounds.run()
        }
    }
    private val flush = Runnable {
        synchronized(lock) { updateScheduled = false }
        if (disposed || !visible) return@Runnable
        text.text = buffer.snapshot()
        panel.removeCallbacks(scrollToLatest)
        panel.post(scrollToLatest)
    }

    init {
        parent.addView(panel)
        parent.addOnLayoutChangeListener(layoutListener)
        updateBounds.run()
    }

    fun add(level: String, message: String, source: String? = null, line: Int? = null) {
        if (disposed) return
        val entry = buildString {
            append(SystemClock.elapsedRealtime() - startedAt).append("ms [")
            append(level.uppercase(Locale.ROOT).take(12)).append("] ").append(message)
            if (!source.isNullOrBlank()) {
                append("\n  ").append(source.substringBefore('?').substringBefore('#'))
                if (line != null && line > 0) append(':').append(line)
            }
        }
        buffer.append(entry)
        scheduleUpdate()
    }

    fun event(message: String) = add("INFO", message)

    fun setVisible(show: Boolean) {
        visible = show
        onMain {
            if (!disposed) {
                panel.visibility = if (show) View.VISIBLE else View.GONE
                if (show) scheduleUpdate()
            }
        }
    }

    fun toggleVisibility(): Boolean {
        val next = !visible
        setVisible(next)
        return next
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            updateScheduled = false
            handler.removeCallbacks(flush)
        }
        onMain {
            panel.removeCallbacks(scrollToLatest)
            parent.removeOnLayoutChangeListener(layoutListener)
            parent.removeView(panel)
        }
    }

    private fun scheduleUpdate() {
        synchronized(lock) {
            if (disposed || updateScheduled || !visible) return
            updateScheduled = true
            handler.postDelayed(flush, 125L)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun dp(value: Int): Int = (value * parent.resources.displayMetrics.density).roundToInt()

    private class PassThroughScrollView(context: Context) : ScrollView(context) {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
        override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = false
        override fun onInterceptTouchEvent(event: MotionEvent?): Boolean = false
    }

    companion object {
        fun attach(parent: FrameLayout): AuthDiagnosticsOverlay = AuthDiagnosticsOverlay(parent)
    }
}

/** Never render credentials or URL query/fragment payloads from an authorization page. */
internal object AuthDiagnosticsSanitizer {
    private const val REDACTED = "[redacted]"
    private val url = Regex("""(?i)\b(?:https?|wss?|ftp|file)://[^\s<>"']+""")
    private val relativeUrl = Regex("""(?<![\w:])/(?:[^\s<>"']*)[?#][^\s<>"']*""")
    private val userInfo = Regex("""(://)[^/@\s]+@""")
    private val dataUrl = Regex("""(?i)\bdata:[^\s<>"']+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){1,2}\b""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val basic = Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]+""")
    private val privateHeader = Regex("""(?im)\b(?:authorization|set-cookie|cookie|scnt|x-apple-id-session-id)\s*:\s*[^\r\n]*""")
    private val privateValue = Regex(
        """(?i)(["']?(?:password|passwd|passcode|secret|client[_ -]?secret|credentials?|authorization|cookies?|scnt|email|username|account[_ -]?name|apple[_ -]?id|session[_ -]?id|verification[_ -]?code|security[_ -]?code|otp|pin|(?:access|refresh|id|user|music[_ -]?user|media[_ -]?user|developer|oauth|auth|csrf|session)[_ -]?token|token)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*(?:"|$)|'(?:\\.|[^'\\])*(?:'|$)|[^\s,;&}\]]+)""",
    )
    private val email = Regex("""(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)+\b""")
    private val opaqueCredential = Regex("""[A-Za-z0-9_+/=-]{80,}""")
    private val controls = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")

    fun sanitize(value: String, maxChars: Int = 700): String {
        require(maxChars > 0)
        var safe = controls.replace(value.take(12_000), "")
        safe = url.replace(safe) { match ->
            userInfo.replace(match.value.substringBefore('?').substringBefore('#')) { "${it.groupValues[1]}$REDACTED@" }
        }
        safe = relativeUrl.replace(safe) { it.value.substringBefore('?').substringBefore('#') }
        safe = dataUrl.replace(safe, "data:$REDACTED")
        safe = jwt.replace(safe, REDACTED)
        safe = bearer.replace(safe, "Bearer $REDACTED")
        safe = basic.replace(safe, "Basic $REDACTED")
        safe = privateValue.replace(safe) { it.groupValues[1] + REDACTED }
        safe = privateHeader.replace(safe) { it.value.substringBefore(':') + ": " + REDACTED }
        safe = email.replace(safe, REDACTED)
        safe = opaqueCredential.replace(safe, REDACTED)
        return if (safe.length <= maxChars) safe else safe.take((maxChars - 1).coerceAtLeast(0)) + "…"
    }
}

/** Bounded text only; this buffer never writes login diagnostics to disk. */
internal class AuthDiagnosticsBuffer(
    private val maxMessages: Int = 24,
    private val maxChars: Int = 5_000,
) {
    private val messages = ArrayDeque<String>()
    private var characters = 0

    init {
        require(maxMessages > 0 && maxChars > 0)
    }

    @Synchronized fun append(message: String) {
        val safe = AuthDiagnosticsSanitizer.sanitize(message, minOf(700, maxChars))
        messages.addLast(safe)
        characters += safe.length + 1
        while (messages.size > maxMessages || characters - 1 > maxChars) {
            characters -= messages.removeFirst().length + 1
        }
    }

    @Synchronized fun snapshot(): String = messages.joinToString("\n")

    @Synchronized fun size(): Int = messages.size
}
