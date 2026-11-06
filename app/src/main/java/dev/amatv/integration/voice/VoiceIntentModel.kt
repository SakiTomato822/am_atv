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
    suspend fun understand(
        spoken: String,
        screenLabels: List<String>,
        candidates: List<String> = emptyList(),
        spokenPinyin: String? = null,
        context: Map<String, String> = emptyMap(),
    ): List<VoiceCommand>? = withContext(Dispatchers.IO) {
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
                        .apply { if (!spokenPinyin.isNullOrBlank()) put("said_pinyin", spokenPinyin) }
                        .put("on_screen", JSONArray(screenLabels.distinct().take(80)))
                        .put("known_names", JSONArray(candidates.distinct().take(120)))
                        .apply { context.forEach { (k, v) -> put(k, v) } }
                        .toString())))
            val connection = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    android.util.Log.w(TAG, "Model $model HTTP $code in ${android.os.SystemClock.elapsedRealtime() - started}ms: ${error.take(200)}")
                    error("HTTP $code")
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val steps = parseIntentJson(content)
                android.util.Log.i(TAG, "Model $model ${android.os.SystemClock.elapsedRealtime() - started}ms " +
                    "said=\"$spoken\" answer=${content.replace('\n', ' ').take(400)} steps=$steps")
                steps
            } finally {
                connection.disconnect()
            }
        }.onFailure { android.util.Log.w(TAG, "Model failed: ${it.javaClass.simpleName} ${it.message?.take(120)}") }
            .getOrNull()
    }

    /**
     * Preview-only evaluation: asks the model with the R4 action schema and [state], and returns its
     * raw JSON answer and latency. Nothing is executed; the caller only logs it.
     */
    suspend fun probe(spoken: String, state: String): Pair<String?, Long> = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext null to 0L
        val (base, key, model) = settings()
        val started = android.os.SystemClock.elapsedRealtime()
        val answer = runCatching {
            val body = JSONObject().put("model", model).put("temperature", 0).put("max_tokens", 200)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", PROBE_PROMPT))
                    .put(JSONObject().put("role", "user").put("content",
                        JSONObject().put("said", spoken).put("state", state).toString())))
            val connection = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            } finally {
                connection.disconnect()
            }
        }.onFailure { android.util.Log.w(TAG, "Probe failed: ${it.javaClass.simpleName} ${it.message?.take(80)}") }.getOrNull()
        answer to (android.os.SystemClock.elapsedRealtime() - started)
    }

    companion object {
        private const val TAG = "AMATV-Voice"
        const val DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct"

        private val PROBE_PROMPT = """
你是车载音乐 App 的语音意图解析器。输入 JSON：said 是语音识别文字（可能有错字），state 是当前状态。
只输出一个 JSON：{"steps":[...]}，按顺序执行。每个 step 必须是以下之一：
{"intent":"start_music","source":"queue|personal_station"}  泛泛地要听音乐。state 有队列用 queue，否则 personal_station
{"intent":"set_shuffle","enabled":true|false}
{"intent":"set_repeat","mode":"one|all|off"}
{"intent":"play_from_collection","collection":"favorites|current","ordinal":N}  某个列表里的第 N 首
{"intent":"pick","ordinal":N}  选当前候选里的第 N 个
{"intent":"candidate_page","delta":1|-1}
{"intent":"confirm"} {"intent":"deny"}  回答「你是说…吗」或否定刚播的歌
{"intent":"recommend","scene":"…","mood":"…","energy":"…"}  按场景/情绪/能量推荐，没有的字段留空
{"intent":"clarify"}  情绪与想听的音乐不明确时（难过、烦）问一次
{"intent":"play_named","title":"…","artist":"…","album":"…","kind":"song|album|artist|any"}  明确点名的歌/歌手/专辑
{"intent":"control","command":"pause|resume|next|previous"}
{"intent":"out_of_domain","reason":"other|volume"}  天气、导航、车控、电话等；音量相关用 volume
{"intent":"not_understood"}
只输出 JSON，不要解释。
""".trimIndent()

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
{"action":"search","query":"搜索词"}  打开搜索页并查询
{"action":"cancel"}  取消当前语音操作
{"action":"play","query":"歌名/歌手/专辑/歌单/电台名"}  搜索并播放；可补充 kind（song|album|artist|playlist|station）及 title、artist、album
on_screen 是当前屏幕上能看到的文字；known_names 是用户最近听过、当前列表里的歌名和歌手；said_pinyin 是识别文字的拼音。
识别常把名字写成同音错字：按 said_pinyin 的读音和这些名字比对。和 on_screen 某项读音相近时，用 open 并写 on_screen 里的原文；
和 known_names 某项读音相近时，用 play 并写 known_names 里的原文。都对不上时保留原话，不要编造名字。
page 是当前页面（Home 主页 / Browse 最新发行 / Library 资料库 / Search 搜索 / NowPlaying 播放页）；
now_playing 是正在播放的歌（"这首""这个歌手""这张专辑"指它）；previous_turn 是同一次对话里上一句和执行结果（"第二个呢""再往下"接着它）。
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
            "cancel" -> VoiceCommand.Cancel
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
            "search" -> step.optString("query").takeIf { it.isNotBlank() }?.let { VoiceCommand.Search(it) }
            "play" -> {
                val kind = VoiceCommand.Kind.entries.firstOrNull {
                    it.name.equals(step.optString("kind"), ignoreCase = true)
                } ?: VoiceCommand.Kind.Any
                val title = step.optString("title").trim().ifEmpty { null }
                val artist = step.optString("artist").trim().ifEmpty { null }
                val album = step.optString("album").trim().ifEmpty { null }
                val query = step.optString("query").trim().ifEmpty {
                    listOfNotNull(artist, if (kind == VoiceCommand.Kind.Album) album ?: title else title).joinToString(" ")
                }
                query.takeIf { it.isNotEmpty() }?.let { VoiceCommand.Play(it, kind, title, artist, album) }
            }
            else -> null
        }
    }
}
