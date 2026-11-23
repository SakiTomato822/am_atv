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

private enum class BgmPlayerState {
    Idle,
    Prepared,
    Started,
    Paused,
    Released,
}

@Stable
internal class FolioWelcomeBgmController(
    context: Context,
    private val prefs: WelcomePreferences,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var playerState: BgmPlayerState = BgmPlayerState.Idle
    private var focusRequest: AudioFocusRequest? = null
    private var fadeJob: Job? = null
    private var currentVolume: Float = 0f
    private var pausedForExternalFlow: Boolean = false

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
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausePlayback()
                isMutedUi = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playerState != BgmPlayerState.Released && !prefs.bgmMuted && !pausedForExternalFlow) {
                    startPreparedOrPaused(BGM_NORMAL_VOLUME, 500L)
                }
            }
        }
    }

    fun startIfAllowed(isAppPlaying: Boolean) {
        if (playerState == BgmPlayerState.Released) return
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
        currentVolume = 0f
        startPreparedOrPaused(BGM_NORMAL_VOLUME, BGM_FADE_IN_MS, resetToZero = true)
    }

    fun toggleMute(isAppPlaying: Boolean) {
        val nextMuted = !isMutedUi
        prefs.bgmMuted = nextMuted
        isMutedUi = nextMuted
        if (nextMuted) {
            fadeJob?.cancel()
            fadeJob = scope.launch {
                rampVolume(0f, BGM_QUICK_FADE_MS)
                pausePlaybackInternal()
                abandonAudioFocus()
            }
        } else {
            pausedForExternalFlow = false
            if (isAppPlaying || audioManager.isMusicActive || !requestAudioFocus()) {
                isMutedUi = true
                return
            }
            startPreparedOrPaused(
                targetVolume = BGM_NORMAL_VOLUME,
                fadeDurationMs = BGM_FADE_IN_MS,
                resetToZero = playerState != BgmPlayerState.Started,
            )
        }
    }

    fun fadeOutForExternalFlow() {
        pausedForExternalFlow = true
        if (!isActivelyPlaying) return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            rampVolume(0f, BGM_EXTERNAL_FADE_MS)
            pausePlaybackInternal()
        }
    }

    fun resumeAfterExternalFlow(isAppPlaying: Boolean) {
        if (!pausedForExternalFlow || playerState == BgmPlayerState.Released) return
        pausedForExternalFlow = false
        if (prefs.bgmMuted || isAppPlaying || audioManager.isMusicActive || !requestAudioFocus()) {
            isMutedUi = true
            return
        }
        startPreparedOrPaused(BGM_NORMAL_VOLUME, BGM_FADE_IN_MS)
    }

    fun fadeOutAndRelease(onReleased: () -> Unit = {}) {
        if (playerState == BgmPlayerState.Released) {
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
        if (playerState == BgmPlayerState.Released) return
        fadeJob?.cancel()
        fadeJob = null
        isActivelyPlaying = false
        val media = player
        if (media != null) {
            if (playerState == BgmPlayerState.Started || playerState == BgmPlayerState.Paused) {
                media.stop()
            }
            if (playerState != BgmPlayerState.Idle) {
                media.release()
            }
        }
        player = null
        playerState = BgmPlayerState.Released
        abandonAudioFocus()
    }

    private fun startPreparedOrPaused(
        targetVolume: Float,
        fadeDurationMs: Long,
        resetToZero: Boolean = false,
    ) {
        val media = ensurePreparedPlayer() ?: return
        if (resetToZero) {
            currentVolume = 0f
            applyVolume(0f)
        }
        if (playerState == BgmPlayerState.Prepared || playerState == BgmPlayerState.Paused) {
            media.start()
            playerState = BgmPlayerState.Started
        }
        isActivelyPlaying = playerState == BgmPlayerState.Started
        isMutedUi = false
        animateVolume(targetVolume, fadeDurationMs)
    }

    private fun pausePlayback() {
        fadeJob?.cancel()
        pausePlaybackInternal()
    }

    private fun pausePlaybackInternal() {
        val media = player
        if (media != null && playerState == BgmPlayerState.Started) {
            media.pause()
            playerState = BgmPlayerState.Paused
        }
        isActivelyPlaying = false
    }

    private fun ensurePreparedPlayer(): MediaPlayer? {
        if (playerState == BgmPlayerState.Released) return null
        player?.let { return it }
        val created = MediaPlayer.create(appContext, R.raw.folio_welcome_bgm) ?: return null
        created.isLooping = true
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        created.setVolume(0f, 0f)
        player = created
        playerState = BgmPlayerState.Prepared
        return created
    }

    private fun applyVolume(volume: Float) {
        val media = player ?: return
        if (playerState == BgmPlayerState.Prepared ||
            playerState == BgmPlayerState.Started ||
            playerState == BgmPlayerState.Paused
        ) {
            media.setVolume(volume, volume)
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
        if (player == null || playerState == BgmPlayerState.Idle || playerState == BgmPlayerState.Released) return
        val start = currentVolume
        val steps = (durationMs / 25L).toInt().coerceAtLeast(1)
        val stepMs = durationMs / steps
        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            val v = start + (target - start) * fraction
            currentVolume = v
            applyVolume(v)
            if (i < steps) delay(stepMs)
        }
        currentVolume = target
        applyVolume(target)
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
