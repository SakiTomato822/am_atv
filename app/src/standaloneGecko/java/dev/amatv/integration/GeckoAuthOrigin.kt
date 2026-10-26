package dev.amatv.integration

import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** An unguessable, one-attempt bootstrap with a real, non-opaque loopback origin. */
internal data class GeckoAuthOrigin(val port: Int, val nonce: String) {
    val path: String = "/auth/$nonce/"
    val authority: String = "127.0.0.1:$port"
    val url: String = "http://$authority$path"

    fun ownsUrl(value: String, allowFragment: Boolean = false): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port &&
            uri.rawUserInfo == null && uri.rawPath == path && uri.rawQuery == null &&
            (allowFragment || uri.rawFragment == null)
    }.getOrDefault(false)
}

internal fun isGeckoAppleAuthUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme == "https" && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) && listOf(
            "apple.com", "apple.com.cn", "icloud.com", "icloud.com.cn", "cdn-apple.com",
        ).any { host == it || host.endsWith(".$it") }
}.getOrDefault(false)

internal fun validGeckoMusicUserToken(value: String): Boolean =
    value.length in 21..16_384 && value.none(Char::isWhitespace)

internal fun geckoCallbackToken(value: String): String? = runCatching {
    val fragment = URI(value).fragment ?: return null
    val candidate = fragment.split('&').firstOrNull {
        it.startsWith("music-user-token=") || it.startsWith("musicUserToken=")
    }?.substringAfter('=') ?: fragment
    val decodedToken = runCatching {
        val bytes = runCatching { Base64.getDecoder().decode(candidate) }
            .getOrElse { Base64.getUrlDecoder().decode(candidate) }
        JSONObject(String(bytes, Charsets.UTF_8)).optString("musicUserToken")
            .takeIf(::validGeckoMusicUserToken)
    }.getOrNull()
    decodedToken ?: candidate.takeIf(::validGeckoMusicUserToken)
}.getOrNull()
