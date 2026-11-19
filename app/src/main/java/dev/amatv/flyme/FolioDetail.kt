package dev.amatv.flyme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.integration.MusicServices
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import dev.amatv.artwork.rememberArtworkPalette
import kotlin.math.roundToInt

/** The pager remains composed beneath the detail layer, retaining its page and scroll position. */
@Composable
internal fun FolioDetailLayer(
    stack: List<FolioDetailEntry>,
    navigation: FolioBrowseNavigation,
    modifier: Modifier,
    homeShelves: List<ContentShelf>,
    statusTop: Dp,
    navBottom: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
    services: MusicServices,
    playback: PlaybackState,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    handleBack: Boolean,
    browseContent: @Composable BoxScope.() -> Unit,
) {
    BackHandler(enabled = stack.isNotEmpty() && handleBack, onBack = onBack)
    val motion = LocalFolioBrowseMotion.current
    val transition = updateTransition(stack, label = "Folio detail stack")
    val changingEntry = (stack - transition.currentState.toSet()).lastOrNull()
        ?: (transition.currentState - stack.toSet()).lastOrNull()
    val changingOrigin = if (changingEntry in stack) changingEntry?.openBounds else changingEntry?.returnBounds
    val hiddenArtwork = if (motion.spatial && changingOrigin != null) changingEntry?.origin else null
    val coverSpec = tween<Float>(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)
    val slideSpec = tween<IntOffset>(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)

    SharedTransitionLayout(modifier) {
        val sharedScope = this
        Box(Modifier.fillMaxSize().onGloballyPositioned { navigation.root = it }) {
            Box(
                Modifier.matchParentSize().then(
                    if (stack.isNotEmpty()) Modifier.semantics { hideFromAccessibility() } else Modifier,
                ),
            ) {
                CompositionLocalProvider(LocalFolioHiddenArtwork provides { hiddenArtwork }) {
                    browseContent()
                }
            }
            // Keep covered pages composed, so a detail reached from a shelf can return to that shelf's scroll position.
            (transition.currentState + stack).distinct().forEach { entry ->
                key(entry) {
                    val opening = entry in stack
                    val sourceBounds = if (opening) entry.openBounds else entry.returnBounds
                    val shared = motion.spatial && sourceBounds != null
                    if (motion.spatial && entry === changingEntry && sourceBounds != null) {
                        val density = LocalDensity.current
                        transition.AnimatedVisibility(
                            visible = { entry !in it },
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                        ) {
                            val cover = FolioDetailCoverTransition(entry, sharedScope, this)
                            FolioArtwork(
                                track = entry.item,
                                modifier = with(cover) {
                                    Modifier
                                        .absoluteOffset {
                                            IntOffset(sourceBounds.left.roundToInt(), sourceBounds.top.roundToInt())
                                        }
                                        .size(
                                            width = with(density) { sourceBounds.width.toDp() },
                                            height = with(density) { sourceBounds.height.toDp() },
                                        )
                                        .sharedCover()
                                },
                                shape = checkNotNull(entry.origin).shape,
                            )
                        }
                    }
                    transition.AnimatedVisibility(
                        visible = { entry in it },
                        modifier = Modifier.matchParentSize().then(
                            if (entry !== stack.lastOrNull()) Modifier.semantics { hideFromAccessibility() } else Modifier,
                        ),
                        enter = when {
                            !motion.animated -> EnterTransition.None
                            shared || motion.lowPerformance -> fadeIn(coverSpec)
                            else -> slideInHorizontally(slideSpec) { it }
                        },
                        exit = when {
                            !motion.animated -> ExitTransition.None
                            shared || motion.lowPerformance -> fadeOut(coverSpec)
                            else -> slideOutHorizontally(slideSpec) { it }
                        },
                    ) {
                        val cover = remember(entry, sharedScope, this) {
                            FolioDetailCoverTransition(entry, sharedScope, this)
                        }
                        CompositionLocalProvider(
                            LocalFolioDetailCoverTransition provides cover,
                            LocalFolioHiddenArtwork provides { hiddenArtwork },
                        ) {
                            FolioDetailPage(
                                entry.item, statusTop, navBottom, onOpenItem, onBack, services, playback,
                                onPlayCollection, homeShelves,
                            )
                        }
                    }
                }
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
