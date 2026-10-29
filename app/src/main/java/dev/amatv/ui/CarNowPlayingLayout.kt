package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Responsive mode selected from the actual Compose viewport after CarLayout scaling/insets. */
internal enum class CarNowPlayingLayoutMode {
    Wide,
    Compact,
    Stacked,
}

/** Layout measurements shared by the artwork, transport card, and lyric slots. */
internal data class CarNowPlayingGeometry(
    val mode: CarNowPlayingLayoutMode,
    val leftFraction: Float,
    val leftPaneWidth: Dp,
    val lyricsPaneWidth: Dp,
    val lyricsPaneHeight: Dp,
    val columnGap: Dp,
    val artworkMaxSize: Dp,
    val stackedArtworkHeight: Dp,
    val stackedHeaderHeight: Dp,
    val transportCardWidth: Dp,
    val transportCardHeight: Dp,
    val transportCardHorizontalInset: Dp,
    val transportCardBottomInset: Dp,
    val transportTouchTargetSize: Dp,
    val compactTransport: Boolean,
)

/**
 * Flyme Auto lyric-mode layout: full-height artwork at the left, a translucent transport card
 * over the artwork, and a full-height lyric viewport at the right. Narrow viewports stack a
 * small cover, the transport card, then lyrics so text and touch controls keep usable width.
 *
 * The caller owns playback, focus, lyric timing, and all slot state. This composable only lays
 * out the three visual slots; it reserves no full-screen bottom control inset.
 */
@Composable
internal fun CarNowPlayingLayout(
    modifier: Modifier = Modifier,
    artwork: @Composable (modifier: Modifier, geometry: CarNowPlayingGeometry) -> Unit,
    transportInfo: @Composable (modifier: Modifier, geometry: CarNowPlayingGeometry) -> Unit,
    lyrics: @Composable (modifier: Modifier, geometry: CarNowPlayingGeometry) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val geometry = carNowPlayingGeometry(maxWidth, maxHeight)

        if (geometry.mode == CarNowPlayingLayoutMode.Stacked) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(geometry.stackedArtworkHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    artwork(
                        Modifier
                            .size(geometry.artworkMaxSize)
                            .clip(RoundedCornerShape(16.dp)),
                        geometry,
                    )
                }
                TransportCard(
                    modifier = Modifier
                        .width(geometry.transportCardWidth)
                        .height(geometry.transportCardHeight),
                    geometry = geometry,
                    content = transportInfo,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    lyrics(Modifier.fillMaxSize().clipToBounds(), geometry)
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.width(geometry.leftPaneWidth).fillMaxHeight(),
                ) {
                    artwork(Modifier.fillMaxSize().clipToBounds(), geometry)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = geometry.transportCardBottomInset),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TransportCard(
                            modifier = Modifier
                                .width(geometry.transportCardWidth)
                                .height(geometry.transportCardHeight),
                            geometry = geometry,
                            content = transportInfo,
                        )
                    }
                }
                Spacer(Modifier.width(geometry.columnGap))
                Box(
                    modifier = Modifier.width(geometry.lyricsPaneWidth).fillMaxHeight(),
                ) {
                    lyrics(Modifier.fillMaxSize().clipToBounds(), geometry)
                }
            }
        }
    }
}

@Composable
private fun TransportCard(
    modifier: Modifier,
    geometry: CarNowPlayingGeometry,
    content: @Composable (modifier: Modifier, geometry: CarNowPlayingGeometry) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.64f))
            .border(1.dp, Color.White.copy(alpha = 0.13f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        content(Modifier.fillMaxWidth(), geometry)
    }
}

/** Resolve responsive proportions and the area reserved for the in-artwork transport card. */
internal fun carNowPlayingGeometry(
    maxWidth: Dp,
    maxHeight: Dp,
): CarNowPlayingGeometry {
    val width = maxWidth.coerceAtLeast(0.dp)
    val height = maxHeight.coerceAtLeast(0.dp)
    val mode = when {
        width >= 1_120.dp -> CarNowPlayingLayoutMode.Wide
        width >= 760.dp -> CarNowPlayingLayoutMode.Compact
        else -> CarNowPlayingLayoutMode.Stacked
    }
    val leftFraction = when (mode) {
        CarNowPlayingLayoutMode.Wide -> 0.45f
        CarNowPlayingLayoutMode.Compact -> 0.44f
        CarNowPlayingLayoutMode.Stacked -> 1f
    }
    val columnGap = when (mode) {
        CarNowPlayingLayoutMode.Wide -> 24.dp
        CarNowPlayingLayoutMode.Compact -> 20.dp
        CarNowPlayingLayoutMode.Stacked -> 0.dp
    }
    val transportCardHorizontalInset = when (mode) {
        CarNowPlayingLayoutMode.Wide -> 20.dp
        CarNowPlayingLayoutMode.Compact,
        CarNowPlayingLayoutMode.Stacked -> 16.dp
    }
    val transportCardBottomInset = if (mode == CarNowPlayingLayoutMode.Stacked) 0.dp else 20.dp
    val preferredTransportCardHeight = when {
        mode != CarNowPlayingLayoutMode.Stacked -> 176.dp
        height >= 420.dp -> 176.dp
        else -> (height * 0.48f).coerceAtLeast(112.dp)
    }

    val contentWidth = (width - columnGap).coerceAtLeast(0.dp)
    val leftPaneWidth = if (mode == CarNowPlayingLayoutMode.Stacked) {
        width
    } else {
        contentWidth * leftFraction
    }
    val lyricsPaneWidth = if (mode == CarNowPlayingLayoutMode.Stacked) {
        width
    } else {
        (contentWidth - leftPaneWidth).coerceAtLeast(0.dp)
    }
    val artworkMaxSize = if (mode == CarNowPlayingLayoutMode.Stacked) {
        minOf(128.dp, width * 0.34f, height * 0.22f)
    } else {
        minOf(leftPaneWidth, height)
    }
    val stackedArtworkHeight = if (mode == CarNowPlayingLayoutMode.Stacked) {
        artworkMaxSize + 12.dp
    } else 0.dp
    val cardAvailableHeight = if (mode == CarNowPlayingLayoutMode.Stacked) {
        (height - stackedArtworkHeight - 8.dp).coerceAtLeast(0.dp)
    } else {
        (height - transportCardBottomInset).coerceAtLeast(0.dp)
    }
    val transportCardHeight = minOf(preferredTransportCardHeight, cardAvailableHeight)
    val stackedHeaderHeight = if (mode == CarNowPlayingLayoutMode.Stacked) {
        stackedArtworkHeight + transportCardHeight + 8.dp
    } else 0.dp
    val transportCardWidth = if (mode == CarNowPlayingLayoutMode.Stacked) {
        (width - transportCardHorizontalInset * 2f).coerceAtLeast(0.dp)
    } else {
        (leftPaneWidth - transportCardHorizontalInset * 2f).coerceAtLeast(0.dp)
    }
    val lyricsPaneHeight = if (mode == CarNowPlayingLayoutMode.Stacked) {
        (height - stackedHeaderHeight).coerceAtLeast(0.dp)
    } else height

    return CarNowPlayingGeometry(
        mode = mode,
        leftFraction = leftFraction,
        leftPaneWidth = leftPaneWidth,
        lyricsPaneWidth = lyricsPaneWidth,
        lyricsPaneHeight = lyricsPaneHeight,
        columnGap = columnGap,
        artworkMaxSize = artworkMaxSize,
        stackedArtworkHeight = stackedArtworkHeight,
        stackedHeaderHeight = stackedHeaderHeight,
        transportCardWidth = transportCardWidth,
        transportCardHeight = transportCardHeight,
        transportCardHorizontalInset = transportCardHorizontalInset,
        transportCardBottomInset = transportCardBottomInset,
        transportTouchTargetSize = 56.dp,
        compactTransport = height < 420.dp || width < 520.dp,
    )
}
