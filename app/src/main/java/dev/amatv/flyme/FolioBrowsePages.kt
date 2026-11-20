package dev.amatv.flyme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.ContentState
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track

@Composable
internal fun FolioHomePage(
    contentState: ContentState,
    connectionState: MusicConnectionState,
    statusMessage: String?,
    bottomPadding: Dp,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val tierSpec = LocalFolioTierSpec.current
    val homeShelves = contentState.shelvesFor(Destination.Home)
    val shelves = remember(homeShelves) { homeShelfOrder(homeShelves) }
    val loading = contentState.isLoading(Destination.Home)

    if (shelves.isEmpty()) {
        FolioEmptyOrLoadingState(
            loading = loading,
            message = statusMessage ?: stringResource(R.string.browse_content_unavailable),
            showSignIn = connectionState == MusicConnectionState.ReadyToSignIn,
            onRetry = onRetry,
            onSignIn = onSignIn,
            bottomPadding = bottomPadding,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerWidth = (maxWidth - tierSpec.px - tierSpec.pr).coerceAtLeast(1.dp)
        val cols = adaptiveColumnCount(innerWidth, tierSpec.liw, tierSpec.lstGap)
        val shelfRows = remember(shelves, cols) {
            shelves.map { shelf ->
                val items = if (shelf.id == "recent-tracks") recentTrackPreview(shelf.items, cols) else shelf.items
                items.chunked(cols)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            shelves.forEachIndexed { shelfIdx, shelf ->
                item(key = "home-sec-${shelf.id}", contentType = "section-title") {
                    FolioSectionTitle(shelf.title)
                }
                val rows = shelfRows[shelfIdx]
                itemsIndexed(
                    rows,
                    key = { rowIdx, _ -> "home-row-${shelf.id}-$rowIdx" },
                    contentType = { _, _ -> "list-card-row" },
                ) { _, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tierSpec.lstGap),
                    ) {
                        for (c in 0 until cols) {
                            val item = rowItems.getOrNull(c)
                            if (item != null) {
                                FolioListRowCard(
                                    item = item,
                                    onClick = { onOpenItem(item, shelf.items) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (shelfIdx < shelves.lastIndex) {
                    item(key = "home-sp-${shelf.id}", contentType = "shelf-gap") { Spacer(Modifier.height(14.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun FolioBrowsePage(
    contentState: ContentState,
    connectionState: MusicConnectionState,
    statusMessage: String?,
    bottomPadding: Dp,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val tierSpec = LocalFolioTierSpec.current
    val shelves = contentState.shelvesFor(Destination.Browse)
    val loading = contentState.isLoading(Destination.Browse)

    if (shelves.isEmpty()) {
        FolioEmptyOrLoadingState(
            loading = loading,
            message = statusMessage ?: stringResource(R.string.browse_content_unavailable),
            showSignIn = connectionState == MusicConnectionState.ReadyToSignIn,
            onRetry = onRetry,
            onSignIn = onSignIn,
            bottomPadding = bottomPadding,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerWidth = (maxWidth - tierSpec.px - tierSpec.pr).coerceAtLeast(1.dp)
        val cols = adaptiveColumnCount(innerWidth, tierSpec.gw, 14.dp)
        val shelfRows = remember(shelves, cols) { shelves.map { it.items.chunked(cols) } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            shelves.forEachIndexed { shelfIdx, shelf ->
                item(key = "browse-sec-${shelf.id}", contentType = "section-title") {
                    FolioSectionTitle(shelf.title)
                }
                val rows = shelfRows[shelfIdx]
                itemsIndexed(
                    rows,
                    key = { rowIdx, _ -> "browse-row-${shelf.id}-$rowIdx" },
                    contentType = { _, _ -> "grid-card-row" },
                ) { rowIdx, rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (rowIdx < rows.lastIndex) 16.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        for (c in 0 until cols) {
                            val item = rowItems.getOrNull(c)
                            if (item != null) {
                                FolioGridCard(
                                    item = item,
                                    onClick = { onOpenItem(item, shelf.items) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (shelfIdx < shelves.lastIndex) {
                    item(key = "browse-sp-${shelf.id}", contentType = "shelf-gap") { Spacer(Modifier.height(14.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun FolioEmptyOrLoadingState(
    loading: Boolean,
    message: String,
    showSignIn: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    bottomPadding: Dp,
    retryLabel: String? = null,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = tierSpec.px, end = tierSpec.pr, bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = palette.fg2,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.browse_loading),
                    color = palette.fg2,
                    fontFamily = fontFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = message,
                    color = palette.fg2,
                    fontFamily = fontFamily,
                    fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolioChip(
                        label = retryLabel ?: stringResource(R.string.browse_reload),
                        selected = false,
                        onClick = onRetry,
                    )
                    if (showSignIn && retryLabel == null) {
                        FolioChip(
                            label = stringResource(R.string.app_sign_in),
                            selected = true,
                            onClick = onSignIn,
                        )
                    }
                }
            }
        }
    }
}

/** H5 puts what was played recently at the top of Listen Now; the rest keeps Apple's order. */
internal fun homeShelfOrder(shelves: List<ContentShelf>): List<ContentShelf> =
    shelves.sortedBy {
        when (it.id) {
            "recent" -> 0
            "recent-tracks", "demo-recent" -> 1
            else -> 2
        }
    }

internal fun recentTrackPreview(tracks: List<Track>, columns: Int): List<Track> =
    tracks.take(if (columns == 1) 4 else columns * 2)
