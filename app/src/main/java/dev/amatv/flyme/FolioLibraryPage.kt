package dev.amatv.flyme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.ContentState
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import java.text.Collator
import java.util.Locale

private enum class FolioLibraryTab(val category: LibraryCategory, val labelRes: Int) {
    Playlists(LibraryCategory.Playlists, R.string.library_tab_playlists),
    Albums(LibraryCategory.Albums, R.string.library_tab_albums),
    Artists(LibraryCategory.Artists, R.string.app_library_artists),
    Songs(LibraryCategory.Songs, R.string.library_tab_songs),
}

private object FolioLibrarySession {
    var tab by mutableStateOf(FolioLibraryTab.Playlists)
    val sorts = mutableStateMapOf<FolioLibraryTab, Pair<LibrarySort, Boolean>>()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolioLibraryPage(
    contentState: ContentState,
    active: Boolean,
    connectionState: MusicConnectionState,
    currentTrack: Track,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onSignIn: () -> Unit,
    onLoadMoreShelf: (String) -> Unit,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val shelves = contentState.shelvesFor(Destination.Library)
    val loading = contentState.isLoading(Destination.Library)

    if (connectionState != MusicConnectionState.SignedIn && shelves.isEmpty()) {
        FolioEmptyOrLoadingState(
            loading = false,
            message = stringResource(R.string.browse_library_sign_in),
            showSignIn = connectionState == MusicConnectionState.ReadyToSignIn,
            onRetry = onSignIn,
            onSignIn = onSignIn,
            retryLabel = stringResource(R.string.app_sign_in),
            bottomPadding = bottomPadding,
        )
        return
    }

    val selectedTab = FolioLibrarySession.tab
    val shelfId = selectedTab.category.shelfId
    val sortOptions = selectedTab.category.sorts()
    val (sort, descending) = FolioLibrarySession.sorts[selectedTab]
        ?: sortOptions.first().let { it to it.descendingFirst }
    val shelf = shelves.firstOrNull { it.id == shelfId }
        ?: shelves.firstOrNull { it.id == "demo-library" }
    val pagination = contentState.shelfPagination[shelfId]
    val collator = remember(LocalConfiguration.current.locales) { Collator.getInstance(Locale.getDefault()) }
    val tracks = remember(shelf, sort, descending, collator) {
        sortLibrary(shelf?.items.orEmpty(), sort, descending, collator)
    }
    val listState = key(selectedTab) { rememberLazyListState() }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 1 && lastVisible >= total - 4
        }
    }
    val needsAllPages = sort != LibrarySort.Default || descending
    LaunchedEffect(active, loading, nearEnd, needsAllPages, shelfId, pagination, contentState.libraryNextPages[shelfId]) {
        if (active && !loading && pagination?.hasMore == true && !pagination.loading && !pagination.failed &&
            (needsAllPages || nearEnd || tracks.isEmpty())
        ) {
            onLoadMoreShelf(shelfId)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerWidth = (maxWidth - tierSpec.px - tierSpec.pr).coerceAtLeast(1.dp)
        val cols = adaptiveColumnCount(innerWidth, tierSpec.gw, 14.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            item(key = "lib-chips") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FolioLibraryTab.entries.forEach { tab ->
                            FolioChip(
                                label = stringResource(tab.labelRes),
                                selected = tab == selectedTab,
                                onClick = { FolioLibrarySession.tab = tab },
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sortOptions.forEach { option ->
                            val selected = option == sort
                            val title = stringResource(option.label)
                            val direction = stringResource(
                                if (descending) R.string.library_sort_descending else R.string.library_sort_ascending,
                            )
                            FolioChip(
                                label = if (selected) "$title ${if (descending) "\u2193" else "\u2191"}" else title,
                                selected = selected,
                                modifier = if (selected) Modifier.semantics { stateDescription = direction } else Modifier,
                                onClick = {
                                    FolioLibrarySession.sorts[selectedTab] =
                                        option to if (selected) !descending else option.descendingFirst
                                },
                            )
                        }
                    }
                }
            }

            when {
                tracks.isEmpty() && loading -> {
                    item(key = "lib-loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = palette.fg2,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                tracks.isEmpty() -> {
                    item(key = "lib-empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = if (pagination?.failed == true) {
                                    stringResource(R.string.browse_load_more_error)
                                } else {
                                    stringResource(R.string.library_empty)
                                },
                                color = palette.fg2,
                                fontFamily = fontFamily,
                                fontSize = 15.sp,
                            )
                            if (pagination?.failed == true) {
                                FolioChip(
                                    label = stringResource(R.string.browse_load_more_retry),
                                    selected = false,
                                    onClick = { onLoadMoreShelf(shelfId) },
                                )
                            }
                        }
                    }
                }
                selectedTab == FolioLibraryTab.Songs -> {
                    itemsIndexed(tracks, key = { index, item -> "lib-song-${item.id}#$index" }) { index, track ->
                        FolioSongRow(
                            track = track,
                            index = index,
                            showCover = true,
                            showArtist = true,
                            isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                            isPlaying = isPlaying,
                            onClick = { onOpenItem(track, tracks) },
                        )
                    }
                }
                else -> {
                    val rows = tracks.chunked(cols)
                    itemsIndexed(rows, key = { rowIdx, _ -> "lib-grid-${shelfId}-$rowIdx" }) { rowIdx, rowItems ->
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
                                        onClick = { onOpenItem(item, tracks) },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            if (pagination != null && (pagination.loading || pagination.failed) && tracks.isNotEmpty()) {
                item(key = "lib-more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        if (pagination.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = palette.fg2,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            FolioChip(
                                label = stringResource(R.string.browse_load_more_retry),
                                selected = false,
                                onClick = { onLoadMoreShelf(shelfId) },
                            )
                        }
                    }
                }
            }
        }
    }
}
