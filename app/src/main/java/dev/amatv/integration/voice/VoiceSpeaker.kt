package dev.amatv.integration.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** How much of voice's feedback is read aloud. */
enum class VoiceSpeak { All, ChoicesOnly, Off }

/**
 * Reads voice prompts with the system's text-to-speech ("找到四个，请说第几个"). Music ducks while
 * it talks, and [say] returns only when it has finished, so the microphone that opens next never
 * hears the prompt. No engine, no Chinese voice, or anything failing: nothing is said and the
 * bubble's text is the whole answer, as before.
 */
class VoiceSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var engine: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val ids = AtomicInteger()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private fun start(): TextToSpeech? = engine ?: synchronized(this) {
        engine ?: runCatching {
            TextToSpeech(appContext) { status ->
                val tts = engine
                val usable = status == TextToSpeech.SUCCESS && tts != null && runCatching {
                    tts.setAudioAttributes(attributes)
                    val chinese = tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
                    if (chinese) tts.language = Locale.SIMPLIFIED_CHINESE
                    chinese
                }.getOrDefault(false)
                if (!usable) Log.i(TAG, "Text-to-speech unavailable (status=$status); prompts stay on screen only")
                ready.complete(usable)
            }.also { tts ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.complete(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.complete(Unit) }
                    override fun onError(utteranceId: String?, errorCode: Int) { pending.remove(utteranceId)?.complete(Unit) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
                })
                engine = tts
            }
        }.onFailure { Log.w(TAG, "Text-to-speech failed to start", it); ready.complete(false) }.getOrNull()
    }

    /** Starts the engine early (it takes a moment), so the first prompt is not late. */
    fun warmUp() { start() }

    /** Says [text] and returns when it is done (at most [limitMs]); false if nothing was said. */
    suspend fun say(text: String, volume: Float = 0.8f, rate: Float = 1.1f, limitMs: Long = 12_000L): Boolean {
        if (text.isBlank()) return false
        val tts = start() ?: return false
        if (withTimeoutOrNull(3_000L) { ready.await() } != true) return false
        val id = "amatv-${ids.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        // No audio focus request: the app ducks its own music (setVoiceDuck), and MusicKit pauses
        // on any transient focus loss, which held songs back until the prompt was over.
        return try {
            runCatching { tts.setSpeechRate(rate.coerceIn(0.5f, 2f)) }
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.1f, 1f))
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                false
            } else {
                withTimeoutOrNull(limitMs) { done.await() }
                true
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Whether a prompt is being read right now. */
    fun speaking(): Boolean = pending.isNotEmpty()

    /** Stops talking at once (the user tapped the mic or dismissed the bubble). */
    fun stop() {
        runCatching { engine?.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
    }

    private companion object {
        const val TAG = "AMATV-Voice"
    }
}
