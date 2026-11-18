package dev.amatv.flyme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import dev.amatv.model.Track

/**
 * The player (B1b). Drawn inside FolioApp's root Box, above the pager, the detail layer and the capsule.
 * Reads playback, queue and lyrics state from the core stores itself; [onClose] hides it again;
 * [onOpenDetail] closes the player and opens an artist or album page.
 */
@Composable
internal fun BoxScope.FolioPlayerLayer(
    open: Boolean,
    statusTop: Dp,
    navBottom: Dp,
    onClose: () -> Unit,
    onOpenDetail: (Track) -> Unit,
) {
}
