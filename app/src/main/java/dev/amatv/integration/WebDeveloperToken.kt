package dev.amatv.integration

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

/** The public Apple Music web credential, scoped to Browse editorial requests. */
internal class WebDeveloperToken(private val preferences: SharedPreferences) {
    private val mutex = Mutex()

    suspend fun token(storefront: String): String = mutex.withLock {
        val now = Instant.now().epochSecond
        val cached = preferences.getString(KEY, null)
        if (cached != null) {
            val exp = DeveloperTokenPolicy.expiresAt(cached)
            if (isUsable(exp, now)) {
                Log.i(BROWSE_LOG_TAG, tokenLog(cached.length, exp, "cache"))
                return@withLock cached
            }
        }

        val bundle = checkNotNull(findBundle(storefront) { downloadWebTokenResource(it, "page") }) {
            "Apple Music web entry script missing"
        }
        val (_, javascript) = checkNotNull(downloadWebTokenResource(bundle, "bundle")) {
            "Apple Music web entry script unavailable"
        }
        val token = checkNotNull(extractToken(javascript, Instant.now().epochSecond)) {
            "Apple Music web developer token missing or expired"
        }
        preferences.edit().putString(KEY, token).apply()
        Log.i(BROWSE_LOG_TAG, tokenLog(token.length, DeveloperTokenPolicy.expiresAt(token), "network"))
        token
    }

    suspend fun invalidate(rejectedToken: String) = mutex.withLock {
        if (preferences.getString(KEY, null) == rejectedToken) preferences.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "developer-token"
        private const val EXPIRY_MARGIN_SECONDS = 86_400L
        private val ENTRY_SCRIPT = Regex(
            """<script\b[^>]*\s+src\s*=\s*["'](/assets/index[~-][A-Za-z0-9_-]+\.js)["']""",
            RegexOption.IGNORE_CASE,
        )
        // The JSON header can begin with either alg (eyJh) or typ (eyJ0).
        private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

        internal fun findBundle(storefront: String, loadPage: (URI) -> Pair<URI, String>?): URI? {
            val paths = listOf("/$storefront/new", "/us/new", "/").distinct()
            for (path in paths) {
                val (pageUri, html) = loadPage(URI("$APPLE_MUSIC_WEB_ORIGIN$path")) ?: continue
                val bundle = bundlePath(html) ?: continue
                return pageUri.resolve(bundle)
            }
            return null
        }

        internal fun bundlePath(html: String): String? = ENTRY_SCRIPT.find(html)?.groupValues?.get(1)

        internal fun extractToken(javascript: String, nowSeconds: Long): String? = JWT.findAll(javascript)
            .map(MatchResult::value)
            .firstOrNull { isUsable(DeveloperTokenPolicy.expiresAt(it), nowSeconds) }

        internal fun isUsable(exp: Long, nowSeconds: Long): Boolean = exp > nowSeconds + EXPIRY_MARGIN_SECONDS

        internal fun tokenLog(length: Int, exp: Long, source: String): String =
            "channel=web-token source=$source length=$length exp=$exp"
    }
}

internal const val APPLE_MUSIC_WEB_ORIGIN = "https://music.apple.com"

private const val MAX_WEB_TOKEN_REDIRECTS = 3
private val WEB_TOKEN_REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal fun downloadWebTokenResource(
    start: URI,
    step: String,
    request: (URI) -> WebBrowseResponse = { getWebBrowseResource(it) },
    log: (String) -> Unit = { Log.i(BROWSE_LOG_TAG, it) },
): Pair<URI, String>? {
    var uri = start
    for (redirects in 0..MAX_WEB_TOKEN_REDIRECTS) {
        val prefix = "channel=web-token step=$step host=${uri.host} path=${uri.rawPath.ifEmpty { "/" }} hasUserToken=false"
        val (status, body, location) = try {
            request(uri)
        } catch (error: IOException) {
            log("$prefix httpStatus=unavailable shelves=0 error=${error.javaClass.simpleName}")
            throw error
        }
        log("$prefix httpStatus=$status shelves=0")
        if (status in 200..299) return uri to body
        if (status !in WEB_TOKEN_REDIRECT_STATUSES || redirects == MAX_WEB_TOKEN_REDIRECTS || location == null) return null
        uri = uri.resolve(location)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null) return null
    }
    return null
}

internal data class WebBrowseResponse(val status: Int, val body: String, val location: String? = null)

internal fun getWebBrowseResource(uri: URI, headers: Map<String, String> = emptyMap()): WebBrowseResponse {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        headers.forEach(connection::setRequestProperty)
        val status = connection.responseCode
        val body = if (status in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
        return WebBrowseResponse(status, body, connection.getHeaderField("Location"))
    } finally {
        connection.disconnect()
    }
}
