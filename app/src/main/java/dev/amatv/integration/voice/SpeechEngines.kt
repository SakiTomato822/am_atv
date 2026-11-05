package dev.amatv.integration.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

sealed interface VoiceEvent {
    /** The microphone is open. */
    data object Listening : VoiceEvent
    /** What has been heard so far (streaming engines only); it may still change. */
    data class Partial(val text: String) : VoiceEvent
    /** The speaker paused; the engine is turning the recording into text. */
    data object Recognizing : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val reason: VoiceFailure, val detail: String? = null) : VoiceEvent
}

enum class VoiceFailure { ModelMissing, MicUnavailable, NoSpeech, NotConfigured, Network }

/**
 * How one listen ends. Normally at a pause in speech; with [holdToTalk] (the mic held down like a
 * walkie-talkie) only when the user lets go, which sets [finish].
 */
class ListenControl(val holdToTalk: Boolean = false) {
    @Volatile var finish: Boolean = false
}

/** The microphone level for the screen glow, 0 (silence) .. 1 (loud speech), smoothed. */
object VoiceLevel {
    @Volatile var value: Float = 0f
        private set

    internal fun update(rms: Double) {
        // -50 dBFS .. -10 dBFS mapped to 0..1; rises fast, falls slowly.
        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-5))
        val target = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        value = if (target > value) value + (target - value) * 0.6f else value + (target - value) * 0.15f
    }

    internal fun reset() {
        value = 0f
    }
}

/**
 * Hotwords as the cjkchar models can take them: simplified Han characters only. sherpa reads
 * ":" in a line as "a score follows" and aborts the whole app on a name like
 * 「復黑王：永遠是你的」 (std::stof, uncaught); "#", "/" and brackets are syntax too. Traditional
 * characters, Latin and punctuation are not in these models' token tables anyway.
 */
internal fun hotwordLines(names: List<String>, simplify: (String) -> String = dev.amatv.integration.ChineseText::simplified): List<String> =
    names.asSequence()
        .map { name -> simplify(name).filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
        .filter { it.length in 2..16 }
        .distinct()
        .take(200)
        .toList()

// A plain loop: the audio thread calls this for every buffer, and sumOf boxed each sample.
private fun rmsOf(samples: ShortArray, count: Int): Double {
    var sum = 0.0
    for (i in 0 until count) {
        val s = samples[i] / 32768.0
        sum += s * s
    }
    return sqrt(sum / count.coerceAtLeast(1))
}

/** One way of turning speech into text. [hotwords]: names worth favouring (library, recent plays). */
interface SpeechEngine {
    fun listen(hotwords: List<String>, control: ListenControl = ListenControl()): Flow<VoiceEvent>
    /** Loads the model ahead of the first tap (slow for the large ones); call off the main thread. */
    fun preload() = Unit
    fun release() = Unit
}

internal const val SAMPLE_RATE = 16_000
private const val CHUNK = 1_600 // 100 ms
private const val TAG = "AMATV-Voice"

/** An open 16 kHz mono microphone, or the failure that kept it closed. */
@SuppressLint("MissingPermission") // The caller asks for RECORD_AUDIO first.
private suspend fun FlowCollector<VoiceEvent>.openMic(): AudioRecord? {
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val audio = runCatching {
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 4))
    }.getOrNull()
    if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
        audio?.release()
        emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
        return null
    }
    audio.startRecording()
    if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        audio.release()
        emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
        return null
    }
    emit(VoiceEvent.Listening)
    return audio
}

/**
 * Records one utterance for engines that recognize after the speaker stops: starts counting once
 * the level rises clearly above the room's noise, ends after [SILENCE_MS] of quiet. Null when
 * nothing was said or the microphone failed (a [VoiceEvent.Failed] has been emitted).
 */
internal suspend fun FlowCollector<VoiceEvent>.recordUtterance(control: ListenControl): ShortArray? {
    val audio = openMic() ?: return null
    try {
        val buffer = ShortArray(CHUNK)
        val speech = ByteArrayOutputStream()
        val started = SystemClock.elapsedRealtime()
        var noise = 0.0
        var noiseChunks = 0
        var speaking = false
        var quietMs = 0L
        var zeroReads = 0
        val pre = ArrayDeque<ShortArray>() // 300 ms before speech starts, so first syllables survive
        // Held down: everything counts as speech until the user lets go.
        if (control.holdToTalk) speaking = true
        while (currentCoroutineContext().isActive) {
            if (control.finish) break
            val read = audio.read(buffer, 0, buffer.size)
            if (read < 0) { emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable)); return null }
            if (read == 0) {
                if (++zeroReads > 30) { emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable)); return null }
                continue
            }
            val chunk = buffer.copyOf(read)
            val rms = rmsOf(chunk, read)
            VoiceLevel.update(rms)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (control.holdToTalk) {
                speech.writeShorts(chunk)
                if (elapsed > HOLD_MAX_MS) break
                continue
            }
            if (!speaking) {
                // The quietest of the first chunks, not their mean: people start talking at once,
                // and speech in the "noise" sample raised the bar until quieter words counted as
                // silence and the sentence was cut in half.
                if (noiseChunks < 3) { noise = if (noiseChunks == 0) rms else minOf(noise, rms); noiseChunks++ }
                val floor = noise.coerceAtLeast(0.004)
                pre.addLast(chunk)
                if (pre.size > 3) pre.removeFirst()
                if (noiseChunks >= 3 && rms > floor * 3) {
                    speaking = true
                    pre.forEach { speech.writeShorts(it) }
                    pre.clear()
                } else if (elapsed > NO_SPEECH_MS) {
                    emit(VoiceEvent.Failed(VoiceFailure.NoSpeech)); return null
                }
            } else {
                speech.writeShorts(chunk)
                val floor = noise.coerceAtLeast(0.004)
                quietMs = if (rms < floor * 2) quietMs + read * 1000L / SAMPLE_RATE else 0L
                if (quietMs >= SILENCE_MS || elapsed > MAX_MS) break
            }
        }
        if (!speaking || speech.size() < SAMPLE_RATE / 5) {
            if (control.holdToTalk) emit(VoiceEvent.Failed(VoiceFailure.NoSpeech))
            return null
        }
        val bytes = speech.toByteArray()
        return ShortArray(bytes.size / 2) { (bytes[it * 2].toInt() and 0xFF or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
    } finally {
        runCatching { audio.stop() }
        audio.release()
        VoiceLevel.reset()
    }
}

private fun ByteArrayOutputStream.writeShorts(samples: ShortArray) {
    for (s in samples) { write(s.toInt() and 0xFF); write((s.toInt() shr 8) and 0xFF) }
}

// A breath between words ("林忆莲的专辑…零") is not the end of the sentence.
private const val SILENCE_MS = 1_200L
private const val NO_SPEECH_MS = 5_000L
private const val MAX_MS = 10_000L
private const val HOLD_MAX_MS = 30_000L

internal fun ShortArray.toFloats() = FloatArray(size) { this[it] / 32768f }

/** 16-bit mono PCM as a WAV file, for the cloud APIs. */
internal fun ShortArray.toWav(): ByteArray {
    val data = size * 2
    val out = ByteArrayOutputStream(44 + data)
    fun int(v: Int) { repeat(4) { out.write((v shr (8 * it)) and 0xFF) } }
    fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    out.write("RIFF".toByteArray()); int(36 + data); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(SAMPLE_RATE); int(SAMPLE_RATE * 2); short(2); short(16)
    out.write("data".toByteArray()); int(data)
    for (s in this) short(s.toInt())
    return out.toByteArray()
}

/** Streaming sherpa-onnx model: text appears while speaking; ends on a pause. */
class SherpaStreamingEngine(private val model: VoiceModel, private val store: VoiceModelStore) : SpeechEngine {
    @Volatile private var recognizer: OnlineRecognizer? = null

    /** Built once; hotwords go with each stream, so changing them costs nothing. */
    private fun recognizer(): OnlineRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: run {
            val transducer = model.kind == VoiceModelKind.StreamingTransducer
            val modelConfig = if (transducer) OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = store.file(model, "encoder").path,
                    decoder = store.file(model, "decoder").path,
                    joiner = store.file(model, "joiner").path,
                ),
                tokens = store.file(model, "tokens").path, numThreads = 2, provider = "cpu",
                modelingUnit = "cjkchar",
            ) else OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = store.file(model, "model").path),
                tokens = store.file(model, "tokens").path, numThreads = 2, provider = "cpu",
            )
            OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 4.0f, 0f),
                    rule2 = EndpointRule(true, 0.9f, 0f),
                    rule3 = EndpointRule(false, 0f, 12f),
                ),
                enableEndpoint = true,
                decodingMethod = if (transducer) "modified_beam_search" else "greedy_search",
                hotwordsScore = 2.0f,
            )).also { recognizer = it; Log.i(TAG, "Loaded ${model.id}") }
        }
    }

    override fun preload() {
        if (store.isInstalled(model)) runCatching { recognizer() }.onFailure { Log.w(TAG, "Preload failed", it) }
    }

    override fun listen(hotwords: List<String>, control: ListenControl): Flow<VoiceEvent> = flow {
        if (!store.isInstalled(model)) { emit(VoiceEvent.Failed(VoiceFailure.ModelMissing)); return@flow }
        val engine = recognizer()
        val audio = openMic() ?: return@flow
        val words = if (model.supportsHotwords) hotwordLines(hotwords) else emptyList()
        val stream = if (words.isEmpty()) engine.createStream() else
            runCatching { engine.createStream(words.joinToString("/")) }.getOrElse { engine.createStream() }
        try {
            val buffer = ShortArray(CHUNK)
            val started = SystemClock.elapsedRealtime()
            var heard = ""
            var zeroReads = 0
            while (currentCoroutineContext().isActive) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) { emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable)); return@flow }
                if (read == 0) {
                    if (++zeroReads > 30) { emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable)); return@flow }
                    continue
                }
                VoiceLevel.update(rmsOf(buffer, read))
                stream.acceptWaveform(FloatArray(read) { buffer[it] / 32768f }, SAMPLE_RATE)
                while (engine.isReady(stream)) engine.decode(stream)
                val text = engine.getResult(stream).text.trim()
                if (text != heard) { heard = text; emit(VoiceEvent.Partial(text)) }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (control.finish) break
                if (control.holdToTalk) { if (elapsed > HOLD_MAX_MS) break } // only letting go ends it
                else if (engine.isEndpoint(stream) || elapsed > MAX_MS) break
            }
            stream.inputFinished()
            while (engine.isReady(stream)) engine.decode(stream)
            val final = engine.getResult(stream).text.trim()
            emit(if (final.isEmpty()) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(final))
        } finally {
            runCatching { audio.stop() }
            audio.release()
            stream.release()
            VoiceLevel.reset()
        }
    }.flowOn(Dispatchers.IO)

    override fun release() = synchronized(this) {
        recognizer?.release()
        recognizer = null
    }
}

/** SenseVoice / Paraformer: records the sentence, then recognizes it at once. */
class SherpaOfflineEngine(private val model: VoiceModel, private val store: VoiceModelStore) : SpeechEngine {
    @Volatile private var recognizer: OfflineRecognizer? = null

    private fun recognizer(): OfflineRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = if (model.kind == VoiceModelKind.OfflineSenseVoice) OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = store.file(model, "model").path, language = "auto",
                    useInverseTextNormalization = true),
                tokens = store.file(model, "tokens").path, numThreads = 2, provider = "cpu",
            ) else OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = store.file(model, "model").path),
                tokens = store.file(model, "tokens").path, numThreads = 2, provider = "cpu",
            ),
        )).also { recognizer = it; Log.i(TAG, "Loaded ${model.id}") }
    }

    override fun listen(hotwords: List<String>, control: ListenControl): Flow<VoiceEvent> = flow {
        if (!store.isInstalled(model)) { emit(VoiceEvent.Failed(VoiceFailure.ModelMissing)); return@flow }
        // Start listening at once; a model not loaded yet loads while the user speaks. The load
        // belongs to this listen: cancelling the recording cancels the wait for it.
        kotlinx.coroutines.coroutineScope {
            val loading = async(Dispatchers.Default) { recognizer() }
            val pcm = recordUtterance(control)
            if (pcm == null) {
                loading.cancel()
                return@coroutineScope
            }
            emit(VoiceEvent.Recognizing)
            val engine = loading.await()
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(pcm.toFloats(), SAMPLE_RATE)
                engine.decode(stream)
                val text = engine.getResult(stream).text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
                emit(if (text.isEmpty()) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(text))
            } finally {
                stream.release()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun preload() {
        if (store.isInstalled(model)) runCatching { recognizer() }.onFailure { Log.w(TAG, "Preload failed", it) }
    }

    override fun release() = synchronized(this) {
        recognizer?.release()
        recognizer = null
    }
}

/** A cloud API: record the sentence, send it, get the text. */
abstract class CloudSpeechEngine : SpeechEngine {
    protected abstract fun configured(): Boolean
    protected abstract fun transcribe(pcm: ShortArray, hotwords: List<String>): String

    override fun listen(hotwords: List<String>, control: ListenControl): Flow<VoiceEvent> = flow {
        if (!configured()) { emit(VoiceEvent.Failed(VoiceFailure.NotConfigured)); return@flow }
        val pcm = recordUtterance(control) ?: return@flow
        emit(VoiceEvent.Recognizing)
        val text = runCatching { transcribe(pcm, hotwords) }
            .onFailure { Log.w(TAG, "Cloud recognition failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}") }
        text.fold(
            onSuccess = { emit(if (it.isBlank()) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(it.trim())) },
            onFailure = { emit(VoiceEvent.Failed(VoiceFailure.Network, it.message?.take(120))) },
        )
    }.flowOn(Dispatchers.IO)
}
