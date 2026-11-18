package dev.amatv.flyme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.ContentState
import dev.amatv.core.LibraryShelfPagination
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry

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
    val shelves = contentState.shelvesFor(Destination.Home)
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
                item(key = "home-sec-${shelf.id}") {
                    FolioSectionTitle(shelf.title)
                }
                val rows = shelf.items.chunked(cols)
                itemsIndexed(rows, key = { rowIdx, _ -> "home-row-${shelf.id}-$rowIdx" }) { _, rowItems ->
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
                    item(key = "home-sp-${shelf.id}") { Spacer(Modifier.height(14.dp)) }
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
                item(key = "browse-sec-${shelf.id}") {
                    FolioSectionTitle(shelf.title)
                }
                val rows = shelf.items.chunked(cols)
                itemsIndexed(rows, key = { rowIdx, _ -> "browse-row-${shelf.id}-$rowIdx" }) { rowIdx, rowItems ->
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
                    item(key = "browse-sp-${shelf.id}") { Spacer(Modifier.height(14.dp)) }
                }
            }
        }
    }
}

private enum class FolioLibraryTab(val shelfId: String, val labelRes: Int) {
    Playlists("lib-playlists", R.string.library_tab_playlists),
    Albums("lib-albums", R.string.library_tab_albums),
    Artists("lib-artists", R.string.app_library_artists),
    Songs("lib-songs", R.string.library_tab_songs),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolioLibraryPage(
    contentState: ContentState,
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

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val selectedTab = FolioLibraryTab.entries[selectedTabIndex]
    val shelf: ContentShelf? = shelves.firstOrNull { it.id == selectedTab.shelfId }
        ?: shelves.firstOrNull { it.id == "demo-library" }
    val pagination: LibraryShelfPagination? = contentState.shelfPagination[selectedTab.shelfId]
    val listState = rememberLazyListState()

    val nearEnd by remember(shelf?.items?.size) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 1 && lastVisible >= total - 4
        }
    }
    LaunchedEffect(nearEnd, selectedTab.shelfId, pagination?.hasMore, pagination?.loading, pagination?.failed) {
        if (nearEnd && pagination?.hasMore == true && !pagination.loading && !pagination.failed) {
            onLoadMoreShelf(selectedTab.shelfId)
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
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolioLibraryTab.entries.forEachIndexed { idx, tab ->
                        FolioChip(
                            label = stringResource(tab.labelRes),
                            selected = idx == selectedTabIndex,
                            onClick = { selectedTabIndex = idx },
                        )
                    }
                }
            }

            when {
                shelf == null && loading -> {
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
                shelf == null || shelf.items.isEmpty() -> {
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
                                    onClick = { onLoadMoreShelf(selectedTab.shelfId) },
                                )
                            }
                        }
                    }
                }
                selectedTab == FolioLibraryTab.Songs -> {
                    itemsIndexed(shelf.items, key = { index, item -> "lib-song-${item.id}#$index" }) { index, track ->
                        FolioSongRow(
                            track = track,
                            index = index,
                            showCover = true,
                            showArtist = true,
                            isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                            isPlaying = isPlaying,
                            onClick = { onOpenItem(track, shelf.items) },
                        )
                    }
                }
                else -> {
                    val rows = shelf.items.chunked(cols)
                    itemsIndexed(rows, key = { rowIdx, _ -> "lib-grid-${selectedTab.shelfId}-$rowIdx" }) { rowIdx, rowItems ->
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
                }
            }

            if (pagination != null && (pagination.loading || pagination.failed) && shelf?.items?.isNotEmpty() == true) {
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
                                onClick = { onLoadMoreShelf(selectedTab.shelfId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolioSearchPage(
    contentState: ContentState,
    currentTrack: Track,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onSearch: (String) -> Unit,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }

    val results = contentState.searchResults
    val songs = remember(results) {
        results.filter { it.catalogKind == CatalogKind.Song || it.catalogKind == CatalogKind.MusicVideo }
    }
    val collections = remember(results) {
        results.filter { it.catalogKind != CatalogKind.Song && it.catalogKind != CatalogKind.MusicVideo }
    }
    val suggestions = remember(contentState.recentTracks) {
        contentState.recentTracks.map { it.artist }.filter { it.isNotBlank() }.distinct().take(4)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerWidth = (maxWidth - tierSpec.px - tierSpec.pr).coerceAtLeast(1.dp)
        val listCols = adaptiveColumnCount(innerWidth, tierSpec.liw, tierSpec.lstGap)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            item(key = "search-bar") {
                val shape = RoundedCornerShape(24.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp)
                        .height(48.dp)
                        .clip(shape)
                        .background(palette.rb)
                        .border(1.dp, palette.glassb, shape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = palette.fg2,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browse_search_hint),
                                color = palette.fg2,
                                fontFamily = fontFamily,
                                fontSize = 15.sp,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = palette.fg,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    val trimmed = query.trim()
                                    if (trimmed.isNotEmpty()) {
                                        submittedQuery = trimmed
                                        onSearch(trimmed)
                                    }
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.browse_search_clear),
                            tint = palette.fg2,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    query = ""
                                    submittedQuery = ""
                                },
                        )
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                item(key = "search-suggestions") {
                    FolioSectionTitle(stringResource(R.string.browse_search_history))
                    FlowRow(
                        modifier = Modifier.padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestions.forEach { term ->
                            FolioChip(
                                label = term,
                                selected = term == submittedQuery,
                                onClick = {
                                    query = term
                                    submittedQuery = term
                                    onSearch(term)
                                },
                            )
                        }
                    }
                }
            }

            when {
                contentState.searchLoading -> {
                    item(key = "search-loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = palette.fg2,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                results.isEmpty() && submittedQuery.isNotEmpty() -> {
                    item(key = "search-empty") {
                        Text(
                            text = stringResource(R.string.app_search_no_results_format, submittedQuery),
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                else -> {
                    if (songs.isNotEmpty()) {
                        item(key = "search-songs-title") {
                            FolioSectionTitle(stringResource(R.string.browse_search_songs))
                        }
                        itemsIndexed(songs, key = { index, item -> "search-song-${item.id}#$index" }) { index, track ->
                            FolioSongRow(
                                track = track,
                                index = index,
                                showCover = true,
                                showArtist = true,
                                isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                                isPlaying = isPlaying,
                                onClick = { onOpenItem(track, songs) },
                            )
                        }
                    }
                    if (collections.isNotEmpty()) {
                        item(key = "search-collections-title") {
                            Spacer(Modifier.height(10.dp))
                            FolioSectionTitle(stringResource(R.string.browse_search_albums))
                        }
                        val rows = collections.chunked(listCols)
                        itemsIndexed(rows, key = { rowIdx, _ -> "search-col-$rowIdx" }) { _, rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(tierSpec.lstGap),
                            ) {
                                for (c in 0 until listCols) {
                                    val item = rowItems.getOrNull(c)
                                    if (item != null) {
                                        FolioListRowCard(
                                            item = item,
                                            onClick = { onOpenItem(item, collections) },
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
            }
        }
    }
}

@Composable
private fun FolioEmptyOrLoadingState(
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
