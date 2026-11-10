package dev.amatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.amatv.model.ContentShelf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/**
 * Apple Music Web's artist page for the car: the artist's large photo, name and Play / Shuffle
 * stay on the left; top songs, the user's saved albums and the release shelves scroll on the right.
 */
@Composable
internal fun FlymeArtistScreen(
    state: LibraryArtistState,
    firstFocus: FocusRequester,
    onBack: () -> Unit,
    onLoadMoreLibrary: () -> Unit,
    onOpenAlbum: (Track) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    loadShelfMore: suspend (String) -> List<Track> = { emptyList() },
) {
    val artist = state.artist ?: return
    // "See All" on a shelf replaces the shelves with that shelf's full grid; Back returns.
    var seeAll by remember(artist.id) { mutableStateOf<ContentShelf?>(null) }
    BackHandler(enabled = seeAll != null) { seeAll = null }
    val hero = state.catalog.artist?.takeIf { it.artworkUrl != null } ?: artist
    val topSongs = state.catalog.topSongs
    val open: (Track, List<Track>) -> Unit = { item, queue ->
        if (item.catalogKind == CatalogKind.Album) onOpenAlbum(item) else onOpenTrack(item, queue)
    }
    Box(Modifier.fillMaxSize().automationId("library-artist-detail")) {
    FitDesign(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(start = 40.dp, top = 18.dp)) {
            Column(Modifier.width(330.dp)) {
                // The back button sits outside FitDesign (below) so it matches every other page.
                Spacer(Modifier.height(54.dp))
                MotionArtwork(track = hero, modifier = Modifier.size(300.dp)
                    .border(0.75.dp, FlymeInk.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                    animate = false, cornerRadius = 16.dp)
                Text(artist.title, color = FlymeInk, fontSize = 32.sp, lineHeight = 37.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp, end = 20.dp))
                if (topSongs.isNotEmpty()) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlymePill(stringResource(R.string.player_play), FlymeGlyphs.Play, primary = true,
                            modifier = Modifier.automationId("artist-play")) { open(topSongs.first(), topSongs) }
                        FlymePill(stringResource(R.string.browse_shuffle), Icons.Rounded.Shuffle, primary = false,
                            modifier = Modifier.automationId("artist-shuffle")) {
                            val shuffled = topSongs.shuffled()
                            open(shuffled.first(), shuffled)
                        }
                    }
                }
            }
            val shown = seeAll
            if (shown != null) {
                FlymeArtistSeeAll(shown, loadShelfMore, open, Modifier.weight(1f))
            } else {
                FlymeArtistShelves(state, topSongs, onLoadMoreLibrary, open, { seeAll = it }, Modifier.weight(1f))
            }
        }
    }
        FlymeBackButton({ if (seeAll != null) seeAll = null else onBack() },
            Modifier.padding(start = 24.dp, top = 22.dp).focusRequester(firstFocus))
    }
}

@Composable
private fun FlymeArtistShelves(
    state: LibraryArtistState,
    topSongs: List<Track>,
    onLoadMoreLibrary: () -> Unit,
    open: (Track, List<Track>) -> Unit,
    onSeeAll: (ContentShelf) -> Unit,
    modifier: Modifier,
) {
    val artist = state.artist ?: return
    val nothingYet = topSongs.isEmpty() && state.catalogShelves.isEmpty() && state.albums.isEmpty()
    // No end padding: the song and card rows run to the screen edge and scroll under it.
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, top = 54.dp, bottom = 40.dp)) {
        if (nothingYet) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp, end = EDGE_GAP), Alignment.Center) {
                    if (state.catalogLoading || state.loading) AppleLoadingIndicator()
                }
            }
        }
        if (topSongs.isNotEmpty()) {
            item(key = "top-songs") {
                ArtistHeading(stringResource(R.string.artist_top_songs))
                ArtistTopSongs(topSongs, open)
            }
        }
        if (artist.isLibraryArtist && state.albums.isNotEmpty()) {
            item(key = "library") {
                ArtistHeading(stringResource(R.string.artist_in_library), Modifier.padding(top = 24.dp))
                ArtistCardRow(state.albums, onNearEnd = { if (state.nextPage != null && !state.loading) onLoadMoreLibrary() }) {
                    open(it, state.albums)
                }
            }
        }
        state.catalogShelves.forEach { shelf ->
            if (shelf.items.isEmpty()) return@forEach
            item(key = "shelf-${shelf.id}") {
                if (shelf.moreHref != null) {
                    ArtistSeeAllHeading(shelf.title, Modifier.padding(top = 24.dp)) { onSeeAll(shelf) }
                } else {
                    ArtistHeading(shelf.title, Modifier.padding(top = 24.dp))
                }
                ArtistCardRow(shelf.items) { open(it, shelf.items) }
            }
        }
    }
}

/**
 * Top songs as columns of [TOP_SONG_ROWS] that scroll sideways past the screen edge; a fling
 * settles on a column start, so a glance shows whole rows and the next column peeks in.
 */
@Composable
private fun ArtistTopSongs(songs: List<Track>, open: (Track, List<Track>) -> Unit) {
    val state = rememberLazyGridState()
    LazyHorizontalGrid(
        rows = GridCells.Fixed(TOP_SONG_ROWS),
        state = state,
        modifier = Modifier.fillMaxWidth().height(TOP_SONG_HEIGHT * TOP_SONG_ROWS + TOP_SONG_GAP * (TOP_SONG_ROWS - 1)),
        contentPadding = PaddingValues(end = EDGE_GAP),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(TOP_SONG_GAP),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
    ) {
        gridItemsIndexed(songs, key = { index, song -> "${song.id}#$index" }) { _, song ->
            ArtistSongRow(song, Modifier.width(TOP_SONG_WIDTH)) { open(song, songs) }
        }
    }
}

private const val TOP_SONG_ROWS = 4
private val TOP_SONG_HEIGHT = 56.dp
private val TOP_SONG_GAP = 4.dp
private val TOP_SONG_WIDTH = 340.dp
private val EDGE_GAP = 40.dp

/** A shelf title with Apple Music's chevron: tapping it opens the whole shelf. */
@Composable
private fun ArtistSeeAllHeading(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.padding(bottom = 4.dp),
        cornerRadius = 8.dp,
        focusedScale = 1.02f,
        padding = PaddingValues(end = 6.dp),
        background = Color.Transparent,
        focusedBackground = FlymeInk.copy(alpha = 0.08f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = FlymeInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = FlymeInk.copy(alpha = 0.55f),
                modifier = Modifier.size(26.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * One shelf in full: the items already shown, then the rest from Apple, as a grid of cards.
 * The rest loads once per shelf; a failed load keeps what is there.
 */
@Composable
private fun FlymeArtistSeeAll(
    shelf: ContentShelf,
    loadMore: suspend (String) -> List<Track>,
    open: (Track, List<Track>) -> Unit,
    modifier: Modifier,
) {
    var items by remember(shelf.id) { mutableStateOf(shelf.items) }
    var loading by remember(shelf.id) { mutableStateOf(shelf.moreHref != null) }
    LaunchedEffect(shelf.id) {
        val href = shelf.moreHref ?: return@LaunchedEffect
        val more = runCatching { loadMore(href) }.getOrDefault(emptyList())
        items = (items + more).distinctBy { it.id }
        loading = false
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = EDGE_GAP, top = 54.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Text(shelf.title, color = FlymeInk, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp)
        }
        gridItemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { _, item ->
            ArtistCard(item, Modifier.fillMaxWidth()) { open(item, items) }
        }
        if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) { AppleLoadingIndicator() }
        }
    }
}

@Composable
private fun ArtistHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FlymeInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
        modifier = modifier.padding(bottom = 10.dp, end = EDGE_GAP))
}

@Composable
private fun ArtistSongRow(song: Track, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(TOP_SONG_HEIGHT),
        cornerRadius = 8.dp,
        focusedScale = 1.01f,
        padding = PaddingValues(horizontal = 6.dp),
        background = Color.Transparent,
        focusedBackground = FlymeInk.copy(alpha = 0.08f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MotionArtwork(track = song, modifier = Modifier.size(44.dp), animate = false, cornerRadius = 5.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                ExplicitTitle(song.title, song.isExplicit,
                    TextStyle(color = FlymeInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), badgeSize = 13.dp)
                val detail = song.album.takeIf { it.isNotBlank() && it != song.title } ?: song.artist
                Text(detail, color = FlymeInk.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistCardRow(items: List<Track>, onNearEnd: () -> Unit = {}, onOpen: (Track) -> Unit) {
    val state = rememberLazyListState()
    val latestNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(state, items.size) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 4 }
            .collect { if (it) latestNearEnd() }
    }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = EDGE_GAP)) {
        itemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { _, item ->
            ArtistCard(item, Modifier.width(150.dp)) { onOpen(item) }
        }
    }
}

@Composable
private fun ArtistCard(item: Track, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        cornerRadius = 10.dp,
        focusedScale = 1.04f,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) {
        Column {
            MotionArtwork(track = item, modifier = Modifier.fillMaxWidth().aspectRatio(1f), animate = false,
                cornerRadius = if (item.catalogKind == CatalogKind.Artist) 500.dp else 9.dp)
            ExplicitTitle(item.title, item.isExplicit,
                TextStyle(color = FlymeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 8.dp), badgeSize = 12.dp)
            // Albums carry only their kind in this field; songs show their album.
            val detail = if (item.catalogKind == CatalogKind.Song) {
                item.album.takeIf { it.isNotBlank() && it != item.title }
            } else null
            if (detail != null) {
                Text(detail, color = FlymeInk.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Apple's rounded action button: filled white for the primary action, translucent otherwise. */
@Composable
private fun FlymePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        cornerRadius = 21.dp,
        focusedScale = 1.04f,
        padding = PaddingValues(horizontal = 20.dp),
        background = if (primary) FlymeInk else FlymeInk.copy(alpha = 0.12f),
        focusedBackground = if (primary) FlymeInk else FlymeInk.copy(alpha = 0.22f),
    ) {
        val content = if (primary) FlymeOnInk else FlymeInk
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 7.dp))
        }
    }
}
