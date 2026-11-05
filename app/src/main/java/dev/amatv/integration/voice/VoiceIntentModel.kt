package dev.amatv.integration.voice

import dev.amatv.integration.VoiceCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional: a chat model reads what was said, plus the labels on screen, and answers with the
 * steps to take, for sentences the fixed rules do not understand ("退10s", "把刚才那首再放一遍"...).
 * Uses the user's own OpenAI-compatible endpoint (SiliconFlow's Qwen by default).
 */
class VoiceIntentModel(private val settings: () -> Triple<String, String, String>) { // base URL, key, model
    fun configured() = settings().let { it.first.isNotBlank() && it.second.isNotBlank() && it.third.isNotBlank() }

    /** Steps for [spoken], or null when the model is not set up or its answer is unusable. */
    suspend fun understand(spoken: String, screenLabels: List<String>): List<VoiceCommand>? = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext null
        val (base, key, model) = settings()
        runCatching {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0)
                // No response_format: not every provider accepts it; the prompt asks for JSON and
                // parseIntentJson tolerates code fences.
                .put("max_tokens", 300)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", JSONObject()
                        .put("said", spoken)
                        .put("on_screen", JSONArray(screenLabels.distinct().take(80)))
                        .toString())))
            val connection = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                parseIntentJson(content)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct"

        private val SYSTEM_PROMPT = """
你是车载音乐 App 的语音指令解析器。用户说的话来自语音识别，可能有同音错字。
把它转成 JSON：{"steps":[...]}，按顺序执行。每个 step 是下面之一：
{"action":"pause"} {"action":"resume"} {"action":"next"} {"action":"previous"}
{"action":"seek","seconds":整数，向前为正、后退为负，"一点"约10}
{"action":"lyrics"}  打开播放页/歌词
{"action":"go","page":"home|browse|library|search"}  主页/最新发行/资料库/搜索
{"action":"artist"} 当前歌曲的歌手页   {"action":"album"} 当前歌曲的完整专辑
{"action":"favorite"} 收藏当前歌曲   {"action":"shuffle"} 随机播放
{"action":"scroll","direction":"up|down|left|right","section":"某一排的标题，可省略"}
{"action":"personal_station"} 我的个人电台
{"action":"pick","number":N}  当前列表的第 N 个
{"action":"open","label":"屏幕上的文字"}  点击屏幕上的控件；label 尽量取 on_screen 里最接近的那一项
{"action":"play","query":"歌名/歌手/专辑/歌单/电台名"}  搜索并播放
on_screen 是当前屏幕上能看到的文字。用户说的名字和 on_screen 某项读音相近时，用 open 并写 on_screen 里的原文。
只输出 JSON，不要解释。听不懂就输出 {"steps":[]}。
""".trimIndent()
    }
}

/** The model's JSON answer as steps; null if it is not the expected shape. */
fun parseIntentJson(content: String): List<VoiceCommand>? {
    val text = content.replace(Regex("```(json)?"), "")
    val json = runCatching {
        JSONObject(if (text.contains('{')) text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1) else text)
    }.getOrNull() ?: return null
    val steps = json.optJSONArray("steps") ?: return null
    return (0 until steps.length()).mapNotNull { index ->
        val step = steps.optJSONObject(index) ?: return@mapNotNull null
        when (step.optString("action")) {
            "pause" -> VoiceCommand.Pause
            "resume" -> VoiceCommand.Resume
            "next" -> VoiceCommand.Next
            "previous" -> VoiceCommand.Previous
            "seek" -> step.optInt("seconds", 0).takeIf { it != 0 }?.let { VoiceCommand.Seek(it.coerceIn(-600, 600)) }
            "lyrics" -> VoiceCommand.NowPlaying
            "go" -> when (step.optString("page")) {
                "home" -> VoiceCommand.Go(VoiceCommand.Page.Home)
                "browse" -> VoiceCommand.Go(VoiceCommand.Page.Browse)
                "library" -> VoiceCommand.Go(VoiceCommand.Page.Library)
                "search" -> VoiceCommand.Go(VoiceCommand.Page.Search)
                else -> null
            }
            "artist" -> VoiceCommand.CurrentArtist
            "album" -> VoiceCommand.CurrentAlbum
            "favorite" -> VoiceCommand.Favorite
            "shuffle" -> VoiceCommand.Shuffle
            "scroll" -> {
                val direction = when (step.optString("direction")) {
                    "up" -> VoiceCommand.Direction.Up
                    "left" -> VoiceCommand.Direction.Left
                    "right" -> VoiceCommand.Direction.Right
                    "down" -> VoiceCommand.Direction.Down
                    else -> null
                }
                direction?.let { VoiceCommand.Scroll(it, step.optString("section").takeIf { s -> s.isNotBlank() }) }
            }
            "personal_station" -> VoiceCommand.PersonalStation
            "pick" -> step.optInt("number", 0).takeIf { it > 0 }?.let { VoiceCommand.Pick(it) }
            "open" -> step.optString("label").takeIf { it.isNotBlank() }?.let { VoiceCommand.Open(it) }
            "play" -> step.optString("query").takeIf { it.isNotBlank() }?.let { VoiceCommand.Play(it) }
            else -> null
        }
    }
}
