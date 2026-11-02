package dev.amatv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.BuildConfig
import dev.amatv.integration.AudioSpectrum
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** A compact, bottom-anchored view of the playing song's measured low/mid/high bands. */
@Composable
internal fun AudioBandIndicator(
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Without a capture (no permission, a station, an unsupported head unit) pulse instead of lying flat. */
    decorativeFallback: Boolean = BuildConfig.FLYME_AUTO,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val envelope = remember { AudioBandEnvelope() }
    val redraw = remember { mutableIntStateOf(0) }
    val latestSpectrum by rememberUpdatedState(audioSpectrum)
    LaunchedEffect(isPlaying, enabled, started) {
        if (!enabled || !started) {
            if (envelope.reset()) redraw.intValue++
            return@LaunchedEffect
        }
        var previousFrame = 0L
        while (isPlaying || !envelope.isFlat) {
            withFrameNanos { now ->
                if (previousFrame != 0L && (now - previousFrame) < 30_000_000L) return@withFrameNanos
                val elapsedMs = if (previousFrame == 0L) 16.667f else (now - previousFrame) / 1_000_000f
                previousFrame = now
                val sample = if (isPlaying) latestSpectrum() else SilentAudioBands
                val changed = if (isPlaying && decorativeFallback && !sample.available) {
                    envelope.pulse(now / 1_000_000_000.0, elapsedMs)
                } else envelope.advance(sample, isPlaying, elapsedMs)
                if (changed) redraw.intValue++
            }
        }
    }

    Canvas(modifier.size(width = 13.dp, height = 12.dp)) {
        // The generation is observed only in drawing. Audio frames never
        // recompose or remeasure the neighbouring artist label or player card.
        redraw.intValue
        val width = size.width * (2.4f / 13f)
        val gap = size.width * (2.3f / 13f)
        val left = (size.width - width * 3f - gap * 2f) / 2f
        val restHeight = minOf(size.height, width * (1.4f / 2.4f))
        val tint = Color.White.copy(alpha = .76f)
        for (band in 0..2) {
            val amount = when (band) {
                0 -> envelope.bass
                1 -> envelope.mid
                else -> envelope.treble
            }
            val height = restHeight + (size.height - restHeight) * amount
            drawRoundRect(
                color = tint,
                topLeft = Offset(left + band * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2f, width / 2f),
            )
        }
    }
}

private val SilentAudioBands = AudioSpectrum()

/**
 * Allocation-free visual envelope. Measured silence never supplies synthetic movement;
 * only [pulse] does, and only when there is no measurement at all.
 */
internal class AudioBandEnvelope {
    private val averageEnergy = FloatArray(3)
    private val initialized = BooleanArray(3)
    var bass = 0f
        private set
    var mid = 0f
        private set
    var treble = 0f
        private set
    val isFlat: Boolean get() = bass == 0f && mid == 0f && treble == 0f

    fun reset(): Boolean {
        val changed = !isFlat
        averageEnergy.fill(0f)
        initialized.fill(false)
        bass = 0f
        mid = 0f
        treble = 0f
        return changed
    }

    /** A steady decorative beat: three bands drifting out of phase, not tied to the audio. */
    fun pulse(timeSeconds: Double, elapsedMs: Float): Boolean {
        val dt = elapsedMs.takeIf(Float::isFinite)?.coerceIn(0f, 50f) ?: 0f
        if (dt <= 0f) return false
        initialized.fill(false)
        averageEnergy.fill(0f)
        val ease = 1f - exp(-dt / 70f)
        fun wave(a: Double, b: Double, phase: Double): Float =
            (.45 + .3 * sin(timeSeconds * a + phase) + .2 * sin(timeSeconds * b + phase * 1.7)).toFloat().coerceIn(.08f, 1f)
        val low = bass + (wave(4.1, 9.7, 0.0) - bass) * ease
        val middle = mid + (wave(5.3, 12.1, 2.1) - mid) * ease
        val high = treble + (wave(6.7, 14.3, 4.2) - treble) * ease
        val changed = low != bass || middle != mid || high != treble
        bass = low
        mid = middle
        treble = high
        return changed
    }

    fun advance(sample: AudioSpectrum, playing: Boolean, elapsedMs: Float): Boolean {
        val dt = elapsedMs.takeIf(Float::isFinite)?.coerceIn(0f, 50f) ?: 0f
        if (dt <= 0f) return false
        val measured = playing && sample.available && sample.captured
        val attack = 1f - exp(-dt / 40f)
        val release = 1f - exp(-dt / 100f)
        val averageBlend = 1f - exp(-dt / 900f)
        fun next(band: Int, current: Float, raw: Float): Float {
            val target = if (measured && raw.isFinite() && raw > 0f) {
                // The source is already logarithmically compressed. Expand its
                // range rather than lifting ordinary levels toward full height.
                val level = raw.coerceAtMost(1f)
                val energy = level * level
                if (!initialized[band]) {
                    averageEnergy[band] = energy
                    initialized[band] = true
                } else {
                    averageEnergy[band] += (energy - averageEnergy[band]) * averageBlend
                }
                // Reserve most of the travel for measured changes in this band.
                // Sustained loud notes settle lower instead of pinning the bars;
                // the minimum excursion rejects tiny relative noise in quiet audio.
                val excursion = (energy - averageEnergy[band]).coerceAtLeast(0f)
                val transient = (excursion / maxOf(.12f, averageEnergy[band] * .65f)).coerceAtMost(1f)
                0.40f * energy + 0.55f * transient
            } else {
                initialized[band] = false
                averageEnergy[band] = 0f
                0f
            }
            val value = if (!playing) {
                // Explicit pause reaches its final dashes in at most 180 ms,
                // then the frame job ends instead of polling an idle player.
                (current - dt / 180f).coerceAtLeast(0f)
            } else {
                current + (target - current) * if (target > current) attack else release
            }
            return if (abs(target - value) < .001f) target else value
        }
        val low = next(0, bass, sample.bass)
        val middle = next(1, mid, sample.mid)
        val high = next(2, treble, sample.treble)
        val changed = low != bass || middle != mid || high != treble
        bass = low
        mid = middle
        treble = high
        return changed
    }
}
