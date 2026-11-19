package dev.amatv.flyme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.amatv.model.Track

internal const val FOLIO_DETAIL_MOTION_MS = 300

internal class FolioBrowseNavigation {
    var root: LayoutCoordinates? = null
    var clickedArtwork: FolioArtworkOrigin? = null
}

internal class FolioArtworkOrigin(
    private val navigation: FolioBrowseNavigation,
    private val tab: Int?,
    val shape: Shape,
) {
    var coordinates: LayoutCoordinates? = null

    fun boundsIn(currentNavigation: FolioBrowseNavigation, currentTab: Int): Rect? {
        if (navigation !== currentNavigation || (tab != null && tab != currentTab)) return null
        val root = navigation.root?.takeIf { it.isAttached } ?: return null
        val artwork = coordinates?.takeIf { it.isAttached } ?: return null
        val visible = root.localBoundingBoxOf(artwork, clipBounds = true)
        if (visible.isEmpty || !visible.overlaps(Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat()))) {
            return null
        }
        return root.localBoundingBoxOf(artwork, clipBounds = false)
    }

    fun click(onClick: () -> Unit) {
        navigation.clickedArtwork = this
        onClick()
        navigation.clickedArtwork = null
    }
}

internal class FolioDetailEntry(
    val item: Track,
    val origin: FolioArtworkOrigin?,
    val openBounds: Rect?,
) {
    var returnBounds: Rect? by mutableStateOf(null)
    var header: LayoutCoordinates? = null
}

internal val LocalFolioBrowseNavigation = staticCompositionLocalOf<FolioBrowseNavigation?> { null }
internal val LocalFolioBrowseTab = staticCompositionLocalOf<Int?> { null }
internal val LocalFolioHiddenArtwork = staticCompositionLocalOf<() -> FolioArtworkOrigin?> { { null } }

@Composable
internal fun rememberFolioArtworkOrigin(shape: Shape, itemId: String? = null): FolioArtworkOrigin? {
    val navigation = LocalFolioBrowseNavigation.current ?: return null
    val tab = LocalFolioBrowseTab.current
    return remember(navigation, tab, shape, itemId) { FolioArtworkOrigin(navigation, tab, shape) }
}

@Composable
internal fun Modifier.folioArtworkOrigin(origin: FolioArtworkOrigin?): Modifier {
    if (origin == null) return this
    val hiddenArtwork = LocalFolioHiddenArtwork.current
    DisposableEffect(origin) { onDispose { origin.coordinates = null } }
    return onGloballyPositioned { origin.coordinates = it }.drawWithContent {
        if (hiddenArtwork() !== origin) drawContent()
    }
}

internal class FolioDetailCoverTransition(
    val entry: FolioDetailEntry,
    private val scope: SharedTransitionScope,
    private val visibility: AnimatedVisibilityScope,
) {
    @Composable
    fun Modifier.sharedCover(): Modifier = with(scope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(entry),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ ->
                tween(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)
            },
            enter = fadeIn(tween(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)),
            exit = fadeOut(tween(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)),
        )
    }
}

internal val LocalFolioDetailCoverTransition = staticCompositionLocalOf<FolioDetailCoverTransition?> { null }
