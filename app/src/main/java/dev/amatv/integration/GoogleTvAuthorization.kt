package dev.amatv.integration

import dev.amatv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

data class GoogleTvPairing(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val expiresAtMs: Long,
    val pollIntervalSeconds: Int,
)

/** Official limited-input OAuth flow; tokens are encrypted by SecureTokenStore. */
class GoogleTvAuthorization(private val store: SecureTokenStore) {
    private val io = Dispatchers.IO.limitedParallelism(2)
    private val refreshMutex = Mutex()
    val configured get() = BuildConfig.YTM_OAUTH_CLIENT_ID.isNotBlank() &&
        BuildConfig.YTM_OAUTH_CLIENT_SECRET.isNotBlank()
    val signedIn get() = store.readYtmSession() != null

    suspend fun startPairing(): GoogleTvPairing {
        check(configured) { "請設定 YTM_OAUTH_CLIENT_ID 與 YTM_OAUTH_CLIENT_SECRET。" }
        val response = post(
            "https://oauth2.googleapis.com/device/code",
            mapOf(
                "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                "scope" to "https://www.googleapis.com/auth/youtube.readonly",
            ),
        )
        check(response.status in 200..299) { "Google 配對啟動失敗 (HTTP ${response.status})。" }
        val json = response.body
        return GoogleTvPairing(
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url")
                .ifBlank { json.getString("verification_uri") },
            deviceCode = json.getString("device_code"),
            expiresAtMs = System.currentTimeMillis() + json.getLong("expires_in") * 1_000L,
            pollIntervalSeconds = json.optInt("interval", 5).coerceAtLeast(5),
        )
    }

    suspend fun awaitPairing(pairing: GoogleTvPairing) {
        var interval = pairing.pollIntervalSeconds
        while (System.currentTimeMillis() < pairing.expiresAtMs) {
            delay(interval * 1_000L)
            val response = post(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                    "client_secret" to BuildConfig.YTM_OAUTH_CLIENT_SECRET,
                    "device_code" to pairing.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            val body = response.body
            if (response.status in 200..299) {
                val refresh = body.optString("refresh_token")
                check(refresh.isNotBlank()) { "Google did not return a refresh token." }
                saveTokens(body, refresh)
                return
            }
            when (body.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> error("Google 帳號授權已拒絕。")
                "expired_token" -> error("Google 配對碼已過期。")
                else -> error("Google 配對失敗 (HTTP ${response.status})。")
            }
        }
        error("Google 配對碼已過期。")
    }

    suspend fun accessToken(): String? = refreshMutex.withLock {
        val saved = store.readYtmSession()?.let(::JSONObject) ?: return@withLock null
        if (saved.optLong("expires_at_ms") > System.currentTimeMillis() + 60_000L) {
            return@withLock saved.optString("access_token").takeIf(String::isNotBlank)
        }
        if (!configured) return@withLock null
        val refresh = saved.optString("refresh_token").takeIf(String::isNotBlank)
            ?: return@withLock null
        val response = post(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "client_id" to BuildConfig.YTM_OAUTH_CLIENT_ID,
                "client_secret" to BuildConfig.YTM_OAUTH_CLIENT_SECRET,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            ),
        )
        if (response.status !in 200..299) {
            if (response.body.optString("error") == "invalid_grant") store.clearYtmSession()
            return@withLock null
        }
        saveTokens(response.body, refresh)
        response.body.optString("access_token").takeIf(String::isNotBlank)
    }

    fun signOut() = store.clearYtmSession()

    private fun saveTokens(body: JSONObject, refresh: String) {
        store.writeYtmSession(JSONObject()
            .put("access_token", body.getString("access_token"))
            .put("refresh_token", refresh)
            .put("expires_at_ms", System.currentTimeMillis() +
                body.optLong("expires_in", 3_600L) * 1_000L)
            .toString())
    }

    private data class Response(val status: Int, val body: JSONObject)

    private suspend fun post(url: String, form: Map<String, String>): Response = withContext(io) {
        coroutineContext.ensureActive()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val encoded = form.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}" +
                    "=${URLEncoder.encode(value, "UTF-8")}" 
            }
            connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(status, JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }
}
