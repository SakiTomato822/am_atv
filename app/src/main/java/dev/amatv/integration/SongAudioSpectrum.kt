package dev.amatv.integration

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.amatv.BuildConfig
import kotlin.math.roundToInt

/** Captures only this player's declared session. Never captures the device mix or microphone. */
class SongAudioSpectrum(private val context: Context) {
    private val thread = HandlerThread("AMATV-song-spectrum").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var sessionId = 0
    @Volatile private var active = false
    @Volatile private var released = false
    @Volatile private var frame = AudioSpectrum()
    @Volatile private var capturedAtMs = 0L
    private var capture: Visualizer? = null
    private var attachedSession = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) != context.packageName) return
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (id <= 0) return
            if (intent.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                if (id == sessionId) { sessionId = 0; refreshCapture() }
            } else {
                sessionId = id
                Log.i(TAG, "Player declared audio session $id")
                refreshCapture()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun sample(): AudioSpectrum = if (active && SystemClock.elapsedRealtime() - capturedAtMs < 250L) {
        frame
    } else AudioSpectrum()

    fun setActive(enabled: Boolean) {
        active = enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!active) frame = AudioSpectrum()
        refreshCapture()
    }

    private fun refreshCapture() {
        if (released) return
        handler.post {
            if (released) return@post
            val desiredSession = sessionId.takeIf { active } ?: 0
            if (desiredSession == attachedSession) return@post
            capture?.release()
            capture = null
            attachedSession = 0
            frame = AudioSpectrum()
            if (desiredSession <= 0) return@post
            var created: Visualizer? = null
            try {
                val analyzer = SpectrumAnalyzer()
                var lastMs = SystemClock.elapsedRealtime()
                var loggedSignal = false
                var diagnosticAtMs = lastMs
                var diagnosticWindows = 0
                val minimumBands = FloatArray(3) { 1f }
                val maximumBands = FloatArray(3)
                created = Visualizer(desiredSession)
                val visualizer = created
                val range = Visualizer.getCaptureSizeRange()
                visualizer.captureSize = 1_024.coerceIn(range[0], range[1])
                val measurement = Visualizer.MeasurementPeakRms()
                val measuresLevel = runCatching {
                    visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS
                }.getOrDefault(false)
                visualizer.scalingMode = if (measuresLevel) Visualizer.SCALING_MODE_NORMALIZED
                    else Visualizer.SCALING_MODE_AS_PLAYED
                check(visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (!active || v !== capture || fft == null) return
                        val now = SystemClock.elapsedRealtime()
                        val hasLevel = measuresLevel && runCatching {
                            visualizer.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS
                        }.getOrDefault(false)
                        frame = if (measuresLevel && !hasLevel) AudioSpectrum() else {
                            analyzer.analyze(fft, rate, now - lastMs, if (hasLevel) measurement.mRms else null)
                        }
                        capturedAtMs = now
                        lastMs = now
                        // Bounded developer diagnostics distinguish a live FFT from a useful
                        // visual range. Never record audio samples or run this in release.
                        if (BuildConfig.DEBUG && diagnosticWindows < 5) {
                            minimumBands[0] = minOf(minimumBands[0], frame.bass)
                            minimumBands[1] = minOf(minimumBands[1], frame.mid)
                            minimumBands[2] = minOf(minimumBands[2], frame.treble)
                            maximumBands[0] = maxOf(maximumBands[0], frame.bass)
                            maximumBands[1] = maxOf(maximumBands[1], frame.mid)
                            maximumBands[2] = maxOf(maximumBands[2], frame.treble)
                            if (now - diagnosticAtMs >= 2_000L) {
                                val ranges = (0..2).joinToString(" ") { band ->
                                    "${(minimumBands[band] * 100).roundToInt()}..${(maximumBands[band] * 100).roundToInt()}"
                                }
                                Log.d(TAG, "Song band ranges % (bass mid treble): $ranges")
                                minimumBands.fill(1f)
                                maximumBands.fill(0f)
                                diagnosticAtMs = now
                                diagnosticWindows++
                            }
                        }
                        if (frame.available && !loggedSignal) {
                            loggedSignal = true
                            Log.i(TAG, "Nonzero song FFT session=$desiredSession rate=${rate / 1_000}Hz")
                        }
                    }
                }, minOf(30_000, Visualizer.getMaxCaptureRate()), false, true) == Visualizer.SUCCESS)
                capture = visualizer
                attachedSession = desiredSession
                visualizer.enabled = true
            } catch (error: RuntimeException) {
                created?.release()
                capture = null
                attachedSession = 0
                // Encoded passthrough or protected routes may not expose PCM.
                // Preserve playback and fall back to ambient motion, never fake bands.
                Log.w(TAG, "Song spectrum unavailable for session $desiredSession", error)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        active = false
        frame = AudioSpectrum()
        context.unregisterReceiver(receiver)
        handler.post { capture?.release(); capture = null }
        thread.quitSafely()
    }

    private companion object { const val TAG = "AMATV-Spectrum" }
}
