package dev.amatv.flyme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.integration.MusicServices
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import dev.amatv.ui.rememberArtworkPalette

/** The pager remains composed beneath the detail layer, retaining its page and scroll position. */
@Composable
internal fun BoxScope.FolioDetailLayer(
    stack: List<Track>,
    homeShelves: List<ContentShelf>,
    statusTop: Dp,
    navBottom: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
    services: MusicServices,
    playback: PlaybackState,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    handleBack: Boolean,
) {
    BackHandler(enabled = stack.isNotEmpty() && handleBack, onBack = onBack)
    AnimatedContent(
        targetState = stack,
        modifier = Modifier.matchParentSize(),
        contentKey = { it.isNotEmpty() },
        transitionSpec = {
            val motion = tween<IntOffset>(400, easing = FolioMotion.PageEasing)
            slideInHorizontally(motion) { it } togetherWith slideOutHorizontally(motion) { it }
        },
        label = "Folio detail",
    ) { entries ->
        val item = entries.lastOrNull()
        if (item != null) {
            key(entries.size, item) {
                FolioDetailPage(
                    item, statusTop, navBottom, onOpenItem, onBack, services, playback,
                    onPlayCollection, onOpenPlayer, homeShelves,
                )
            }
        }
    }
}

@Composable
private fun FolioDetailPage(
    item: Track,
    statusTop: Dp,
    navBottom: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
    services: MusicServices,
    playback: PlaybackState,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    homeShelves: List<ContentShelf>,
) {
    val spec = LocalFolioTierSpec.current
    val palette = LocalFolioPalette.current
    val layout = folioDetailLayout(spec.tier)
    val artist = if (item.catalogKind == CatalogKind.Artist) rememberFolioArtistData(item, services) else null
    val bottomPadding = navBottom + spec.capsp + 28.dp
    val artwork = artist?.first?.value?.artist ?: item
    val colors = rememberArtworkPalette(artwork.artworkUrl, artwork.palette)
    val tint = if (palette.isLight) 0.32f else 0.42f
    Box(Modifier.fillMaxSize().folioAmbientBackground(palette)) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to colors.first().copy(alpha = tint),
                        0.24f to colors.last().copy(alpha = tint * 0.7f),
                        0.56f to colors.last().copy(alpha = 0f),
                    ),
                )
                .pointerInput(Unit) {
                    // Own touches in the empty parts of the overlay, covering the pager below.
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
        )
        Column(Modifier.fillMaxSize().padding(top = statusTop + folioDetailTopMargin)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(spec.ib)
                    .padding(start = spec.px, end = spec.pr),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolioCircleButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.browse_back),
                    onClick = onBack,
                )
                if (!spec.usesBottomCapsule && playback.hasCurrentTrack) {
                    FolioMiniPlayerDot(playback.track, onOpenPlayer)
                }
            }
            Spacer(Modifier.height(folioDetailBodyGap))

            if (artist != null) {
                FolioArtistDetail(
                    item, artist.first, artist.second, layout, services, playback,
                    bottomPadding, onOpenItem,
                )
            } else {
                FolioCollectionDetail(
                    item, layout, services, playback, bottomPadding, homeShelves, onOpenItem, onPlayCollection,
                )
            }
        }
    }
}
