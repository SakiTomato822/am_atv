package dev.amatv.integration

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

sealed interface VoiceEvent {
    /** The microphone is open. */
    data object Listening : VoiceEvent
    /** What has been heard so far; it may still change. */
    data class Partial(val text: String) : VoiceEvent
    /** The speaker paused: the final text. */
    data class Final(val text: String) : VoiceEvent
    data class Failed(val reason: VoiceFailure) : VoiceEvent
}

enum class VoiceFailure { ModelMissing, MicUnavailable, NoSpeech }

/**
 * On-device Chinese speech recognition (sherpa-onnx, streaming Zipformer CTC, int8): nothing is
 * sent anywhere. The model ships in the APK's assets; the recognizer loads on first use and stays.
 */
class OfflineVoiceRecognizer(private val context: Context) {
    @Volatile private var recognizer: OnlineRecognizer? = null

    fun isAvailable(): Boolean =
        runCatching { context.assets.list(MODEL_DIR)?.contains(MODEL_FILE) == true }.getOrDefault(false)

    private fun recognizer(): OnlineRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: OnlineRecognizer(
            context.assets,
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "$MODEL_DIR/$MODEL_FILE"),
                    tokens = "$MODEL_DIR/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    // Nothing said for 4 s; a pause of 0.9 s after speech; at most 12 s in all.
                    rule1 = EndpointRule(false, 4.0f, 0f),
                    rule2 = EndpointRule(true, 0.9f, 0f),
                    rule3 = EndpointRule(false, 0f, 12f),
                ),
                enableEndpoint = true,
            ),
        ).also {
            recognizer = it
            Log.i(TAG, "Recognizer ready")
        }
    }

    /** Warms the model up off the main thread, so the first command does not wait for it. */
    fun preload() {
        if (isAvailable()) runCatching { recognizer() }.onFailure { Log.w(TAG, "Preload failed", it) }
    }

    /** Opens the microphone until the speaker pauses; cancel the collector to stop early. */
    @SuppressLint("MissingPermission") // The caller asks for RECORD_AUDIO first.
    fun listen(): Flow<VoiceEvent> = flow {
        if (!isAvailable()) {
            emit(VoiceEvent.Failed(VoiceFailure.ModelMissing))
            return@flow
        }
        val engine = recognizer()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 4))
        }.getOrNull()
        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
            audio?.release()
            emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
            return@flow
        }
        val stream = engine.createStream()
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
                return@flow
            }
            emit(VoiceEvent.Listening)
            val buffer = ShortArray(CHUNK)
            val started = SystemClock.elapsedRealtime()
            var heard = ""
            var silentReads = 0
            while (currentCoroutineContext().isActive) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) {
                    emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
                    return@flow
                }
                if (read == 0) {
                    // Another app holds the microphone and we only get silence-free zero reads.
                    if (++silentReads > 30) {
                        emit(VoiceEvent.Failed(VoiceFailure.MicUnavailable))
                        return@flow
                    }
                    continue
                }
                stream.acceptWaveform(FloatArray(read) { buffer[it] / 32768f }, SAMPLE_RATE)
                while (engine.isReady(stream)) engine.decode(stream)
                val text = engine.getResult(stream).text.trim()
                if (text != heard) {
                    heard = text
                    emit(VoiceEvent.Partial(text))
                }
                if (engine.isEndpoint(stream) || SystemClock.elapsedRealtime() - started > MAX_LISTEN_MS) break
            }
            stream.inputFinished()
            while (engine.isReady(stream)) engine.decode(stream)
            val final = engine.getResult(stream).text.trim()
            Log.i(TAG, "Heard ${final.length} chars in ${SystemClock.elapsedRealtime() - started} ms")
            emit(if (final.isEmpty()) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(final))
        } finally {
            runCatching { audio.stop() }
            audio.release()
            stream.release()
        }
    }.flowOn(Dispatchers.IO)

    fun release() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }

    private companion object {
        const val TAG = "AMATV-Voice"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1_600 // 100 ms
        const val MAX_LISTEN_MS = 12_000L
        const val MODEL_DIR = "asr"
        const val MODEL_FILE = "model.int8.onnx"
    }
}
