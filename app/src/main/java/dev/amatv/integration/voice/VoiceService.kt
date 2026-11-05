package dev.amatv.integration.voice

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** Which recognizer the mic button uses, and the user's own cloud credentials. */
data class VoiceEngineSettings(
    /** "model:<id>", "tencent" or "openai". */
    val engine: String = "model:" + VoiceModelCatalog.all.first().id,
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE,
    val openAiKey: String = "",
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
) {
    companion object {
        /** SiliconFlow serves SenseVoice through the OpenAI transcription API, reachable in mainland China. */
        const val DEFAULT_OPENAI_BASE = "https://api.siliconflow.cn/v1"
        const val DEFAULT_OPENAI_MODEL = "FunAudioLLM/SenseVoiceSmall"
    }
}

/**
 * The voice recognizer behind the mic button. Settings and keys stay in this app's private
 * storage; keys are never logged or exported.
 */
class VoiceService(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("am_atv_voice", Context.MODE_PRIVATE)
    val models = VoiceModelStore(context.applicationContext)
    private val mutable = MutableStateFlow(VoiceEngineSettings(
        engine = preferences.getString("engine", null) ?: VoiceEngineSettings().engine,
        tencentSecretId = preferences.getString("tencent_id", "").orEmpty(),
        tencentSecretKey = preferences.getString("tencent_key", "").orEmpty(),
        openAiBaseUrl = preferences.getString("openai_base", null) ?: VoiceEngineSettings.DEFAULT_OPENAI_BASE,
        openAiKey = preferences.getString("openai_key", "").orEmpty(),
        openAiModel = preferences.getString("openai_model", null) ?: VoiceEngineSettings.DEFAULT_OPENAI_MODEL,
    ))
    val settings: StateFlow<VoiceEngineSettings> = mutable.asStateFlow()

    fun update(change: (VoiceEngineSettings) -> VoiceEngineSettings) {
        val next = change(mutable.value)
        if (next.engine != mutable.value.engine) releaseEngine()
        mutable.value = next
        preferences.edit()
            .putString("engine", next.engine)
            .putString("tencent_id", next.tencentSecretId)
            .putString("tencent_key", next.tencentSecretKey)
            .putString("openai_base", next.openAiBaseUrl)
            .putString("openai_key", next.openAiKey)
            .putString("openai_model", next.openAiModel)
            .apply()
    }

    private val tencent = TencentSentenceEngine { settings.value.tencentSecretId.trim() to settings.value.tencentSecretKey.trim() }
    private val openAi = OpenAiTranscriptionEngine {
        Triple(settings.value.openAiBaseUrl.trim(), settings.value.openAiKey.trim(), settings.value.openAiModel.trim())
    }
    private var local: Pair<String, SpeechEngine>? = null

    private fun engine(): SpeechEngine? = synchronized(this) {
        val id = settings.value.engine
        when {
            id == "tencent" -> tencent
            id == "openai" -> openAi
            id.startsWith("model:") -> {
                val model = VoiceModelCatalog.byId(id.removePrefix("model:")) ?: return null
                local?.takeIf { it.first == model.id }?.second ?: run {
                    local?.second?.release()
                    val created = when (model.kind) {
                        VoiceModelKind.StreamingCtc, VoiceModelKind.StreamingTransducer -> SherpaStreamingEngine(model, models)
                        VoiceModelKind.OfflineSenseVoice, VoiceModelKind.OfflineParaformer -> SherpaOfflineEngine(model, models)
                    }
                    local = model.id to created
                    created
                }
            }
            else -> null
        }
    }

    /** Whether the chosen engine can run now: its model downloaded, or its keys entered. */
    fun ready(): Boolean {
        val s = settings.value
        return when {
            s.engine == "tencent" -> s.tencentSecretId.isNotBlank() && s.tencentSecretKey.isNotBlank()
            s.engine == "openai" -> s.openAiKey.isNotBlank() && s.openAiBaseUrl.isNotBlank()
            s.engine.startsWith("model:") -> VoiceModelCatalog.byId(s.engine.removePrefix("model:"))?.let(models::isInstalled) == true
            else -> false
        }
    }

    /** Where the floating mic sits, as fractions of the screen; bottom right at first. */
    fun micPosition(): Pair<Float, Float> =
        preferences.getFloat("mic_x", 0.95f) to preferences.getFloat("mic_y", 0.84f)

    fun saveMicPosition(x: Float, y: Float) {
        preferences.edit().putFloat("mic_x", x).putFloat("mic_y", y).apply()
    }

    /** Loads the chosen local model now (off the main thread), so the mic opens at once. */
    fun preload() {
        if (settings.value.engine.startsWith("model:")) engine()?.preload()
    }

    fun listen(hotwords: List<String>, control: ListenControl = ListenControl()): Flow<VoiceEvent> =
        engine()?.listen(hotwords, control) ?: flowOf(VoiceEvent.Failed(VoiceFailure.NotConfigured))

    private fun releaseEngine() = synchronized(this) {
        local?.second?.release()
        local = null
    }

    fun release() = releaseEngine()

    companion object {
        @Volatile private var instance: VoiceService? = null
        fun get(context: Context): VoiceService = instance ?: synchronized(this) {
            instance ?: VoiceService(context).also { instance = it }
        }
    }
}
