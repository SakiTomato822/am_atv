package dev.amatv.integration

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Clears this app's embedded browser session after an explicit local sign-out.
 * It does not change an external browser's cookies or revoke Apple authorization.
 */
class WebAuthenticationSession internal constructor(
    private val resetMarker: WebSessionResetMarker,
    private val clearWebData: suspend () -> Unit,
) {
    constructor(context: Context) : this(
        PreferencesWebSessionResetMarker(context.applicationContext.getSharedPreferences(
            "web_authentication_session", Context.MODE_PRIVATE,
        )),
        ::clearAppWebSession,
    )

    /** No WebView initialization is needed to sign out, including on provider-less boxes. */
    fun markLocalWebSessionForReset() = synchronized(markerLock) {
        val previous = resetMarker.revision
        resetMarker.revision = if (previous == Long.MAX_VALUE) 1L else previous + 1L
    }

    /** Must finish before a new authorization WebView is created or navigated. */
    suspend fun prepareForSignIn() = cleanupMutex.withLock {
        while (true) {
            val pendingRevision = synchronized(markerLock) { resetMarker.revision }
            if (pendingRevision == 0L) return@withLock

            // Failures (including a missing provider) and cancellation retain the
            // persisted marker. A later attempt must not silently reuse old cookies.
            clearWebData()
            currentCoroutineContext().ensureActive()
            val complete = synchronized(markerLock) {
                if (resetMarker.revision == pendingRevision) {
                    resetMarker.revision = 0L
                    true
                } else {
                    false
                }
            }
            if (complete) return@withLock
        }
    }

    private companion object {
        // Browser data is app-wide, so separate MusicServices instances must not
        // race cookie removal or acknowledge a newer sign-out with an older reset.
        val cleanupMutex = Mutex()
        val markerLock = Any()
    }
}

internal interface WebSessionResetMarker {
    var revision: Long
}

private class PreferencesWebSessionResetMarker(
    private val preferences: SharedPreferences,
) : WebSessionResetMarker {
    override var revision: Long
        get() = preferences.getLong("pending_reset_revision", 0L)
        set(value) {
            preferences.edit().putLong("pending_reset_revision", value).apply()
        }
}

private suspend fun clearAppWebSession() {
    val cookieManager = withContext(Dispatchers.Main.immediate) {
        val cookies = CookieManager.getInstance()
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine<Unit> { continuation ->
            // false means there were no cookies to remove, not that removal failed.
            cookies.removeAllCookies {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        cookies
    }
    // flush() can perform blocking disk IO; keep it away from the UI thread.
    withContext(Dispatchers.IO) { cookieManager.flush() }
}
