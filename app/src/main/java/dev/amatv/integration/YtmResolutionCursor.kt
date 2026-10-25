package dev.amatv.integration

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * An app-owned cursor keeps unprocessed candidates before advancing YouTube's page.
 * It contains catalog metadata and the provider cursor, never an account credential.
 */
internal data class YtmResolutionCursor(
    val seedVideoId: String,
    val pending: List<YtmSong>,
    val providerContinuation: String?,
) {
    fun encode(): String? {
        if (pending.isEmpty()) return providerContinuation
        require(pending.size <= MAX_PENDING)
        val songs = JSONArray()
        pending.forEach { song ->
            songs.put(JSONObject().put("id", song.videoId).put("title", song.title)
                .put("artist", song.artist).put("duration", song.durationMs))
        }
        val json = JSONObject().put("seed", seedVideoId).put("pending", songs)
            .put("next", providerContinuation)
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val PREFIX = "amatv-radio-v1:"
        private const val MAX_PENDING = 256
        private const val MAX_CURSOR_LENGTH = 300_000
        fun isLocal(value: String?): Boolean = value?.startsWith(PREFIX) == true

        fun decode(value: String?): YtmResolutionCursor? {
            if (!isLocal(value) || value == null || value.length > MAX_CURSOR_LENGTH) return null
            return runCatching {
                val json = JSONObject(String(Base64.getUrlDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8))
                val seed = json.getString("seed")
                require(seed.matches(Regex("[A-Za-z0-9_-]{11}")))
                val data = json.getJSONArray("pending")
                require(data.length() <= MAX_PENDING)
                val pending = (0 until data.length()).map { index ->
                    val song = data.getJSONObject(index)
                    val id = song.getString("id")
                    val title = song.getString("title")
                    val artist = song.getString("artist")
                    require(id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.length in 1..2_000 && artist.length in 1..2_000)
                    YtmSong(id, title, artist, if (song.isNull("duration")) null else song.getLong("duration"))
                }
                val next = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
                YtmResolutionCursor(seed, pending, next)
            }.getOrNull()
        }
    }
}
