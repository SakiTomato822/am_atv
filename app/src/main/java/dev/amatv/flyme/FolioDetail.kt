package dev.amatv.flyme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import dev.amatv.model.Track

/**
 * Detail pages (B2): the top of [stack] is shown over the pager, under the capsule and the player.
 * An empty stack shows nothing. [onOpenItem] plays or opens an item the same way the root pages do;
 * [onBack] pops one page.
 */
@Composable
internal fun BoxScope.FolioDetailLayer(
    stack: List<Track>,
    statusTop: Dp,
    bottomPadding: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
) {
}
