package dev.amatv.integration

import org.json.JSONObject
import java.time.Instant
import java.util.Base64

/** Local expiry/configuration check; Apple verifies the ES256 signature on every request. */
internal object DeveloperTokenPolicy {
    fun expiresAt(token: String): Long = runCatching {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any(String::isBlank)) return 0L
        val header = JSONObject(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
        if (header.optString("alg") != "ES256") return 0L
        JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).optLong("exp", 0L)
    }.getOrDefault(0L)

    fun isUsable(token: String, nowSeconds: Long = Instant.now().epochSecond): Boolean =
        expiresAt(token) > nowSeconds + 300L

    fun isConfigured(bundledToken: String, endpoint: String): Boolean =
        bundledToken.isNotBlank() || endpoint.isNotBlank()
}
