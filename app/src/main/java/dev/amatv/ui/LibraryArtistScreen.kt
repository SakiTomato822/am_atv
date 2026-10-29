package dev.amatv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track

@Composable
internal fun LibraryArtistScreen(
    state: LibraryArtistState,
    currentTrack: Track,
    firstFocus: FocusRequester,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAlbum: (Track) -> Unit,
) {
    val artist = state.artist ?: return
    val shelfId = "library-artist-albums"
    Column(Modifier.fillMaxSize().padding(32.dp).automationId("library-artist-detail")) {
        FocusSurface(onClick = onBack,
            modifier = Modifier.height(56.dp).focusRequester(firstFocus),
            padding = PaddingValues(horizontal = 20.dp), cornerRadius = 14.dp) {
            Text("‹  ${artist.title}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        HomeScreen(destination = Destination.Browse, currentTrack = currentTrack,
            homeShelves = listOf(ContentShelf(shelfId, stringResource(R.string.app_library_albums), state.albums)),
            homeLoading = state.loading && state.albums.isEmpty(),
            homeShelfPagination = mapOf(shelfId to LibraryShelfPagination(
                hasMore = state.nextPage != null, loading = state.loading, failed = state.failed)),
            onLoadMoreShelf = { if (state.failed) onRetry() else onLoadMore() },
            connectionState = MusicConnectionState.SignedIn,
            onRetry = onRetry,
            onOpenTrack = { album, _ -> onOpenAlbum(album) },
            navigationFocusRequester = firstFocus,
            modifier = Modifier.fillMaxSize())
    }
}
