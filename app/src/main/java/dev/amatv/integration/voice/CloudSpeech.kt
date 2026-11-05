package dev.amatv.integration.voice

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent Cloud "一句话识别" (SentenceRecognition, 2019-06-14), signed with TC3-HMAC-SHA256.
 * Hotwords go in as a temporary hotword list. The keys are the user's own.
 */
class TencentSentenceEngine(private val keys: () -> Pair<String, String>) : CloudSpeechEngine() {
    override fun configured() = keys().let { it.first.isNotBlank() && it.second.isNotBlank() }

    override fun transcribe(pcm: ShortArray, hotwords: List<String>): String {
        val (secretId, secretKey) = keys()
        val bytes = ByteArray(pcm.size * 2).also { out ->
            pcm.forEachIndexed { i, s -> out[i * 2] = s.toByte(); out[i * 2 + 1] = (s.toInt() shr 8).toByte() }
        }
        val body = JSONObject()
            .put("EngSerViceType", "16k_zh")
            .put("SourceType", 1)
            .put("VoiceFormat", "pcm")
            .put("Data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("DataLen", bytes.size)
            .apply {
                // "词|权重" pairs; Tencent keeps names short, so only plausible ones.
                val list = hotwords.filter { it.length in 2..10 }.distinct().take(128)
                if (list.isNotEmpty()) put("HotwordList", list.joinToString(",") { "${it.replace(",", " ").replace("|", " ")}|10" })
            }
            .toString()
        val timestamp = System.currentTimeMillis() / 1000
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp * 1000))
        val host = "asr.tencentcloudapi.com"
        val canonical = "POST\n/\n\ncontent-type:application/json; charset=utf-8\nhost:$host\n\ncontent-type;host\n${sha256Hex(body)}"
        val scope = "$date/asr/tc3_request"
        val toSign = "TC3-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonical)}"
        val signing = hmac(hmac(hmac(("TC3$secretKey").toByteArray(), date), "asr"), "tc3_request")
        val signature = hmac(signing, toSign).joinToString("") { "%02x".format(it) }
        val authorization = "TC3-HMAC-SHA256 Credential=$secretId/$scope, SignedHeaders=content-type;host, Signature=$signature"
        val response = post("https://$host/", body.toByteArray(), mapOf(
            "Authorization" to authorization,
            "Content-Type" to "application/json; charset=utf-8",
            "Host" to host,
            "X-TC-Action" to "SentenceRecognition",
            "X-TC-Version" to "2019-06-14",
            "X-TC-Timestamp" to timestamp.toString(),
        ))
        val result = JSONObject(response).getJSONObject("Response")
        result.optJSONObject("Error")?.let { error(it.optString("Code") + ": " + it.optString("Message")) }
        return result.optString("Result")
    }

    private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())
}

/**
 * Any OpenAI-compatible /audio/transcriptions endpoint: SiliconFlow (SenseVoice, in mainland
 * China), OpenAI, Groq and others. Hotwords go in the prompt where the service supports one.
 */
class OpenAiTranscriptionEngine(
    private val settings: () -> Triple<String, String, String>, // base URL, key, model
) : CloudSpeechEngine() {
    override fun configured() = settings().let { it.first.isNotBlank() && it.second.isNotBlank() && it.third.isNotBlank() }

    override fun transcribe(pcm: ShortArray, hotwords: List<String>): String {
        val (base, key, model) = settings()
        val boundary = "amatv-" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model)
        field("response_format", "json")
        val prompt = hotwords.distinct().take(60).joinToString("，")
        if (prompt.isNotEmpty()) field("prompt", prompt)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(pcm.toWav())
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val response = post(base.trimEnd('/') + "/audio/transcriptions", body.toByteArray(), mapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "multipart/form-data; boundary=$boundary",
        ))
        return JSONObject(response).optString("text")
    }
}

private fun post(url: String, body: ByteArray, headers: Map<String, String>): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 10_000
        readTimeout = 20_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) { "HTTP $code ${text.take(160)}" }
        return text
    } finally {
        connection.disconnect()
    }
}
