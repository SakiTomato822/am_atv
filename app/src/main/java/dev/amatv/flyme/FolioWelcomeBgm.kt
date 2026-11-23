package dev.amatv.flyme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.amatv.R
import dev.amatv.core.WelcomePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BGM_NORMAL_VOLUME = 0.6f
private const val BGM_DUCK_VOLUME = 0.15f
private const val BGM_FADE_IN_MS = 1_500L
private const val BGM_EXTERNAL_FADE_MS = 1_000L
private const val BGM_QUICK_FADE_MS = 350L
private const val BGM_EXIT_FADE_MS = 450L

@Stable
internal class FolioWelcomeBgmController(
    context: Context,
    private val prefs: WelcomePreferences,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var fadeJob: Job? = null
    private var currentVolume: Float = 0f
    private var pausedForExternalFlow: Boolean = false
    private var released: Boolean = false

    var isMutedUi by mutableStateOf(prefs.bgmMuted || audioManager.isMusicActive)
        private set

    var isActivelyPlaying by mutableStateOf(false)
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isActivelyPlaying) animateVolume(BGM_DUCK_VOLUME, 250L)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                fadeJob?.cancel()
                player?.takeIf { it.isPlaying }?.pause()
                isActivelyPlaying = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                fadeJob?.cancel()
                player?.takeIf { it.isPlaying }?.pause()
                isActivelyPlaying = false
                isMutedUi = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!released && !prefs.bgmMuted && !pausedForExternalFlow) {
                    ensurePlayer()?.let { media ->
                        if (!media.isPlaying) media.start()
                        isActivelyPlaying = true
                        isMutedUi = false
                        animateVolume(BGM_NORMAL_VOLUME, 500L)
                    }
                }
            }
        }
    }

    fun startIfAllowed(isAppPlaying: Boolean) {
        if (released) return
        val canPlay = shouldPlayWelcomeBgm(
            isMusicActive = audioManager.isMusicActive,
            isAppPlaying = isAppPlaying,
            userMuted = prefs.bgmMuted,
        )
        isMutedUi = !canPlay
        if (!canPlay) return
        if (!requestAudioFocus()) {
            isMutedUi = true
            return
        }
        val media = ensurePlayer() ?: return
        currentVolume = 0f
        media.setVolume(0f, 0f)
        if (!media.isPlaying) media.start()
        isActivelyPlaying = true
        animateVolume(BGM_NORMAL_VOLUME, BGM_FADE_IN_MS)
    }

    fun toggleMute(isAppPlaying: Boolean) {
        val nextMuted = !isMutedUi
        prefs.bgmMuted = nextMuted
        isMutedUi = nextMuted
        if (nextMuted) {
            fadeJob?.cancel()
            fadeJob = scope.launch {
                rampVolume(0f, BGM_QUICK_FADE_MS)
                player?.takeIf { it.isPlaying }?.pause()
                isActivelyPlaying = false
                abandonAudioFocus()
            }
        } else {
            pausedForExternalFlow = false
            if (isAppPlaying || audioManager.isMusicActive || !requestAudioFocus()) {
                isMutedUi = true
                return
            }
            val media = ensurePlayer() ?: return
            if (!media.isPlaying) {
                currentVolume = 0f
                media.setVolume(0f, 0f)
                media.start()
            }
            isActivelyPlaying = true
            animateVolume(BGM_NORMAL_VOLUME, BGM_FADE_IN_MS)
        }
    }

    fun fadeOutForExternalFlow() {
        pausedForExternalFlow = true
        if (!isActivelyPlaying) return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            rampVolume(0f, BGM_EXTERNAL_FADE_MS)
            player?.takeIf { it.isPlaying }?.pause()
            isActivelyPlaying = false
        }
    }

    fun resumeAfterExternalFlow(isAppPlaying: Boolean) {
        if (!pausedForExternalFlow || released) return
        pausedForExternalFlow = false
        if (prefs.bgmMuted || isAppPlaying || audioManager.isMusicActive) {
            isMutedUi = true
            return
        }
        if (!requestAudioFocus()) {
            isMutedUi = true
            return
        }
        val media = ensurePlayer() ?: return
        if (!media.isPlaying) media.start()
        isActivelyPlaying = true
        isMutedUi = false
        animateVolume(BGM_NORMAL_VOLUME, BGM_FADE_IN_MS)
    }

    fun fadeOutAndRelease(onReleased: () -> Unit = {}) {
        if (released) {
            onReleased()
            return
        }
        fadeJob?.cancel()
        fadeJob = scope.launch {
            try {
                if (isActivelyPlaying) rampVolume(0f, BGM_EXIT_FADE_MS)
            } finally {
                releaseImmediate()
                onReleased()
            }
        }
    }

    fun releaseImmediate() {
        if (released) return
        released = true
        fadeJob?.cancel()
        fadeJob = null
        isActivelyPlaying = false
        player?.run {
            runCatching { if (isPlaying) stop() }
            runCatching { release() }
        }
        player = null
        abandonAudioFocus()
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        return MediaPlayer.create(appContext, R.raw.folio_welcome_bgm)?.apply {
            isLooping = true
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setVolume(0f, 0f)
            player = this
        }
    }

    private fun requestAudioFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun animateVolume(target: Float, durationMs: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            rampVolume(target, durationMs)
        }
    }

    private suspend fun rampVolume(target: Float, durationMs: Long) {
        val media = player ?: return
        val start = currentVolume
        val steps = (durationMs / 25L).toInt().coerceAtLeast(1)
        val stepMs = durationMs / steps
        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            val v = start + (target - start) * fraction
            currentVolume = v
            runCatching { media.setVolume(v, v) }
            if (i < steps) {
                try {
                    delay(stepMs)
                } catch (c: CancellationException) {
                    throw c
                }
            }
        }
        currentVolume = target
        runCatching { media.setVolume(target, target) }
    }
}

@Composable
internal fun rememberFolioWelcomeBgmController(
    prefs: WelcomePreferences,
): FolioWelcomeBgmController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, prefs, scope) {
        FolioWelcomeBgmController(context, prefs, scope)
    }
    DisposableEffect(controller) {
        onDispose { controller.releaseImmediate() }
    }
    return controller
}
