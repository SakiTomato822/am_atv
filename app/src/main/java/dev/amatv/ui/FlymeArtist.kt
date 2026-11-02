package dev.amatv.ui

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
import androidx.compose.foundation.lazy.LazyColumn
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
) {
    val artist = state.artist ?: return
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
                    .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                    animate = false, cornerRadius = 16.dp)
                Text(artist.title, color = Color.White, fontSize = 32.sp, lineHeight = 37.sp,
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
            FlymeArtistShelves(state, topSongs, onLoadMoreLibrary, open, Modifier.weight(1f))
        }
    }
        FlymeBackButton(onBack, Modifier.padding(start = 24.dp, top = 22.dp).focusRequester(firstFocus))
    }
}

@Composable
private fun FlymeArtistShelves(
    state: LibraryArtistState,
    topSongs: List<Track>,
    onLoadMoreLibrary: () -> Unit,
    open: (Track, List<Track>) -> Unit,
    modifier: Modifier,
) {
    val artist = state.artist ?: return
    val nothingYet = topSongs.isEmpty() && state.catalogShelves.isEmpty() && state.albums.isEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 40.dp, top = 54.dp, bottom = 40.dp)) {
        if (nothingYet) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) {
                    if (state.catalogLoading || state.loading) AppleLoadingIndicator()
                }
            }
        }
        if (topSongs.isNotEmpty()) {
            item(key = "top-songs") {
                ArtistHeading(stringResource(R.string.artist_top_songs))
                topSongs.take(20).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        pair.forEach { song ->
                            ArtistSongRow(song, Modifier.weight(1f)) { open(song, topSongs) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
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
                ArtistHeading(shelf.title, Modifier.padding(top = 24.dp))
                ArtistCardRow(shelf.items) { open(it, shelf.items) }
            }
        }
    }
}

@Composable
private fun ArtistHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
        modifier = modifier.padding(bottom = 10.dp))
}

@Composable
private fun ArtistSongRow(song: Track, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        cornerRadius = 8.dp,
        focusedScale = 1.01f,
        padding = PaddingValues(horizontal = 6.dp),
        background = Color.Transparent,
        focusedBackground = Color.White.copy(alpha = 0.08f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MotionArtwork(track = song, modifier = Modifier.size(44.dp), animate = false, cornerRadius = 5.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                ExplicitTitle(song.title, song.isExplicit,
                    TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), badgeSize = 13.dp)
                val detail = song.album.takeIf { it.isNotBlank() && it != song.title } ?: song.artist
                Text(detail, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1,
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
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { _, item ->
            FocusSurface(
                onClick = { onOpen(item) },
                modifier = Modifier.width(150.dp),
                cornerRadius = 10.dp,
                focusedScale = 1.04f,
                background = Color.Transparent,
                focusedBackground = Color.Transparent,
            ) {
                Column {
                    MotionArtwork(track = item, modifier = Modifier.size(150.dp), animate = false,
                        cornerRadius = if (item.catalogKind == CatalogKind.Artist) 75.dp else 9.dp)
                    ExplicitTitle(item.title, item.isExplicit,
                        TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 8.dp), badgeSize = 12.dp)
                    // Albums carry only their kind in this field; songs show their album.
                    val detail = if (item.catalogKind == CatalogKind.Song) {
                        item.album.takeIf { it.isNotBlank() && it != item.title }
                    } else null
                    if (detail != null) {
                        Text(detail, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
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
        background = if (primary) Color.White else Color.White.copy(alpha = 0.12f),
        focusedBackground = if (primary) Color.White else Color.White.copy(alpha = 0.22f),
    ) {
        val content = if (primary) Color(0xFF17151C) else Color.White
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 7.dp))
        }
    }
}
