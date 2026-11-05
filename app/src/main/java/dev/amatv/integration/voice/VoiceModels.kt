package dev.amatv.integration.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** How a downloadable model is run. */
enum class VoiceModelKind {
    /** Streaming Zipformer CTC: text appears while speaking. */
    StreamingCtc,
    /** Streaming Zipformer transducer: text while speaking, and hotwords (names from the library). */
    StreamingTransducer,
    /** SenseVoice: recognizes after the speaker pauses; most accurate, Chinese/English/Cantonese. */
    OfflineSenseVoice,
    /** Paraformer: recognizes after the speaker pauses. */
    OfflineParaformer,
}

/**
 * A sherpa-onnx model the user can download. Files come from the model's Hugging Face repository
 * (mirror first, for networks in mainland China); nothing ships in the APK.
 */
data class VoiceModel(
    val id: String,
    val name: String,
    val summary: String,
    val sizeMb: Int,
    val kind: VoiceModelKind,
    val repo: String,
    /** Role -> file in the repository: "model", "tokens", "encoder", "decoder", "joiner". */
    val files: Map<String, String>,
) {
    val supportsHotwords: Boolean get() = kind == VoiceModelKind.StreamingTransducer
}

object VoiceModelCatalog {
    val all = listOf(
        VoiceModel(
            id = "zh-ctc-small-2025-04",
            name = "极速（中文，流式）",
            summary = "26 MB，边说边出字，人名较弱",
            sizeMb = 26,
            kind = VoiceModelKind.StreamingCtc,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
            files = mapOf("model" to "model.int8.onnx", "tokens" to "tokens.txt"),
        ),
        VoiceModel(
            id = "zh-transducer-2025-06",
            name = "均衡（中文，流式，热词）",
            summary = "167 MB，边说边出字；资料库和最近播放里的名字作为热词",
            sizeMb = 167,
            kind = VoiceModelKind.StreamingTransducer,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            files = mapOf("encoder" to "encoder.int8.onnx", "decoder" to "decoder.onnx",
                "joiner" to "joiner.int8.onnx", "tokens" to "tokens.txt"),
        ),
        VoiceModel(
            id = "sensevoice-2025-09",
            name = "精准（SenseVoice，中英粤日韩）",
            summary = "237 MB，说完再识别，准确率最高",
            sizeMb = 237,
            kind = VoiceModelKind.OfflineSenseVoice,
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            files = mapOf("model" to "model.int8.onnx", "tokens" to "tokens.txt"),
        ),
        VoiceModel(
            id = "paraformer-zh-small",
            name = "Paraformer 小型（中文）",
            summary = "82 MB，说完再识别",
            sizeMb = 82,
            kind = VoiceModelKind.OfflineParaformer,
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
            files = mapOf("model" to "model.int8.onnx", "tokens" to "tokens.txt"),
        ),
    )

    fun byId(id: String): VoiceModel? = all.firstOrNull { it.id == id }
}

/** Downloaded models under the app's own files; nothing outside the app. */
class VoiceModelStore(context: Context) {
    private val root = File(context.filesDir, "voice-models")

    fun dir(model: VoiceModel) = File(root, model.id)
    fun file(model: VoiceModel, role: String) = File(dir(model), model.files.getValue(role))

    fun isInstalled(model: VoiceModel): Boolean = model.files.values.all { File(dir(model), it).let { f -> f.isFile && f.length() > 0 } }

    fun delete(model: VoiceModel) {
        dir(model).deleteRecursively()
    }

    /** Downloads every file of [model]; [progress] gets 0..1. Partial files never count as installed. */
    suspend fun download(model: VoiceModel, progress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = dir(model).apply { mkdirs() }
            val names = model.files.values.toList()
            val sizes = names.map { name -> runCatching { contentLength(model, name) }.getOrDefault(0L) }
            val total = sizes.sum().coerceAtLeast(1L)
            var before = 0L
            names.forEachIndexed { index, name ->
                val out = File(target, name)
                if (!(out.isFile && out.length() > 0 && (sizes[index] == 0L || out.length() == sizes[index]))) {
                    fetch(model, name, File(target, "$name.part")) { done -> progress(((before + done).toFloat() / total).coerceIn(0f, 1f)) }
                    check(File(target, "$name.part").renameTo(out)) { "Could not save $name" }
                }
                before += sizes[index].takeIf { it > 0 } ?: out.length()
                progress((before.toFloat() / total).coerceIn(0f, 1f))
            }
        }.onFailure { Log.w(TAG, "Download of ${model.id} failed: ${it.javaClass.simpleName}") }
    }

    private fun urls(model: VoiceModel, name: String) =
        HOSTS.map { "$it/${model.repo}/resolve/main/$name" }

    private suspend fun contentLength(model: VoiceModel, name: String): Long {
        for (url in urls(model, name)) {
            currentCoroutineContext().ensureActive()
            val length = runCatching {
                open(url, method = "HEAD").run { try { contentLengthLong } finally { disconnect() } }
            }.getOrDefault(-1L)
            if (length > 0) return length
        }
        return 0L
    }

    private suspend fun fetch(model: VoiceModel, name: String, part: File, progress: (Long) -> Unit) {
        var last: Throwable? = null
        for (url in urls(model, name)) {
            try {
                val connection = open(url, method = "GET")
                try {
                    check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                progress(done)
                            }
                        }
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                last = error
                part.delete()
            }
        }
        throw last ?: IllegalStateException("No download source")
    }

    /** Follows the Hugging Face redirects to its file storage. */
    private fun open(url: String, method: String): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AMATV-Flyme")
            }
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = URL(current, location)
            } else return connection
        }
        throw IllegalStateException("Too many redirects")
    }

    private companion object {
        const val TAG = "AMATV-Voice"
        val HOSTS = listOf("https://hf-mirror.com", "https://huggingface.co")
    }
}
