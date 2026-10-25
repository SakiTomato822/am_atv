package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.integration.AudioSpectrum
import dev.amatv.model.Track

/** One persistent native GPU surface; artwork changes retarget its colors without resetting it. */
@Composable
fun PlayerBackdrop(
    track: Track,
    animate: Boolean,
    modifier: Modifier = Modifier,
    audioSpectrum: () -> AudioSpectrum = { AudioSpectrum() },
) {
    val colors = rememberArtworkPalette(track.artworkUrl, track.palette)
    val palette = remember(colors) { colors.map { it.toArgb() }.toIntArray() }
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
    AndroidView(
        factory = { LavaBackdropView(it) },
        update = { it.update(palette, animate && started, audioSpectrum) },
        onRelease = { it.release() },
        modifier = modifier.background(Color(0xFF171722)),
    )
}
