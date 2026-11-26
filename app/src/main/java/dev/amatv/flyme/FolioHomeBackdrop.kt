package dev.amatv.flyme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.artwork.rememberArtworkPalette
import dev.amatv.core.HomeBackgroundStyle
import dev.amatv.integration.AudioSpectrum
import dev.amatv.model.Track

internal enum class HomeBackdropMode { Ambient, StaticLava, StaticRibbon, Animated }

internal fun homeBackdropMode(
    style: HomeBackgroundStyle,
    hasTrack: Boolean,
    useGl: Boolean,
): HomeBackdropMode = when {
    !hasTrack -> HomeBackdropMode.Ambient
    useGl -> HomeBackdropMode.Animated
    style == HomeBackgroundStyle.Lava -> HomeBackdropMode.StaticLava
    else -> HomeBackdropMode.StaticRibbon
}

@Composable
internal fun FolioHomeBackdrop(
    track: Track?,
    palette: FolioPalette,
    style: HomeBackgroundStyle,
    useGl: Boolean,
    playerOpen: Boolean,
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    modifier: Modifier = Modifier,
) {
    val mode = homeBackdropMode(style, track != null, useGl)
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
    val backdropColors = remember(colors, lava, style, palette.isLight) {
        if (style == HomeBackgroundStyle.Ribbon) {
            LavaPalette.from(colors.map { it.toArgb() }.toIntArray(), palette.isLight, homeRibbon = true)
        } else lava
    }
    val ambient = palette.copy(
        glowTopRight = Color(lava[3], lava[4], lava[5], alpha = 0.80f),
        glowBottomLeft = Color(lava[6], lava[7], lava[8], alpha = 0.80f),
    )

    when (mode) {
        HomeBackdropMode.Ambient -> Box(modifier.folioAmbientBackground(palette))
        HomeBackdropMode.StaticLava -> Box(modifier.folioAmbientBackground(ambient))
        HomeBackdropMode.StaticRibbon -> StaticRibbonBackdrop(homeRibbonFrame(backdropColors, palette.isLight), modifier)
        HomeBackdropMode.Animated -> {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            Box(modifier.folioAmbientBackground(ambient)) {
                key(lifecycle) {
                    AndroidView(
                        factory = { FolioLavaBackdropView(it, lifecycle) },
                        update = { it.update(backdropColors, style, palette.isLight, playerOpen, isPlaying, audioSpectrum) },
                        onRelease = FolioLavaBackdropView::release,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

internal fun homeRibbonFrame(colors: FloatArray, isLight: Boolean): RibbonFrame = RibbonFrame(
    base = Color(colors[0], colors[1], colors[2]),
    primary = Color(colors[3], colors[4], colors[5]),
    secondary = Color(colors[6], colors[7], colors[8]),
    opacity = 1f,
    gatherScale = 1f,
    ridgeAlpha = 0f,
    dragOffsetPx = 0f,
    preset = if (isLight) RibbonPreset.HomeLight else RibbonPreset.Home,
)
