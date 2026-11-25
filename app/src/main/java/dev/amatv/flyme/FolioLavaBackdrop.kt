package dev.amatv.flyme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.amatv.artwork.rememberArtworkPalette
import dev.amatv.integration.AudioSpectrum
import dev.amatv.model.Track

@Composable
internal fun FolioLavaBackdrop(
    track: Track?,
    palette: FolioPalette,
    useGl: Boolean,
    playerOpen: Boolean,
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    modifier: Modifier = Modifier,
) {
    if (track == null) {
        Box(modifier.folioAmbientBackground(palette))
        return
    }

    val colors = key(track.artworkUrl, track.palette) {
        rememberArtworkPalette(track.artworkUrl, track.palette)
    }
    val lava = remember(colors, palette.isLight) {
        LavaPalette.from(colors.map { it.toArgb() }.toIntArray(), palette.isLight)
    }
    val ambient = palette.copy(
        glowTopRight = Color(lava[3], lava[4], lava[5], alpha = 0.80f),
        glowBottomLeft = Color(lava[6], lava[7], lava[8], alpha = 0.80f),
    )
    Box(modifier.folioAmbientBackground(ambient)) {
        if (useGl) {
            val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            val visible = isLavaBackdropVisible(lifecycleState, playerOpen)
            val running = isLavaBackdropRunning(lifecycleState, playerOpen, isPlaying)
            AndroidView(
                factory = { FolioLavaBackdropView(it) },
                update = { it.update(lava, visible, running, audioSpectrum) },
                onRelease = { it.release() },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun isLavaBackdropVisible(lifecycleState: Lifecycle.State, playerOpen: Boolean): Boolean =
    lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !playerOpen

internal fun isLavaBackdropRunning(
    lifecycleState: Lifecycle.State,
    playerOpen: Boolean,
    isPlaying: Boolean,
): Boolean = isPlaying && isLavaBackdropVisible(lifecycleState, playerOpen)
