package dev.amatv.flyme

import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.amatv.core.SettingsStore
import dev.amatv.core.assessPlaybackFrames
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val SAMPLE_DURATION_NANOS = 10_000_000_000L

@Composable
internal fun FolioPlaybackFrameMonitor(settingsStore: SettingsStore) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(view, lifecycle, settingsStore) {
        var completed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (completed) return@repeatOnLifecycle
            val refreshRateHz = view.display?.refreshRate ?: return@repeatOnLifecycle
            val frameDurations = mutableListOf<Long>()
            try {
                collectPlaybackFrames(frameDurations)
                completed = true
            } finally {
                val stats = assessPlaybackFrames(frameDurations, refreshRateHz)
                val switched = completed && stats.shouldEnableLowPerformance &&
                    settingsStore.enableLowPerformanceForPlaybackJank()
                Log.i(
                    "AMATV-Perf",
                    String.format(
                        Locale.ROOT,
                        "frames=%d slow=%.1f%% refreshRate=%.2fHz switched=%b completed=%b",
                        stats.frameCount, stats.slowFrameRatio * 100, refreshRateHz, switched, completed,
                    ),
                )
            }
        }
    }
}

private suspend fun collectPlaybackFrames(durations: MutableList<Long>): Unit =
    suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            private var startNanos = 0L
            private var previousNanos = 0L

            override fun doFrame(frameTimeNanos: Long) {
                if (previousNanos == 0L) {
                    startNanos = frameTimeNanos
                } else {
                    durations.add(frameTimeNanos - previousNanos)
                }
                previousNanos = frameTimeNanos
                if (frameTimeNanos - startNanos >= SAMPLE_DURATION_NANOS) {
                    continuation.resume(Unit)
                } else {
                    choreographer.postFrameCallback(this)
                }
            }
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
