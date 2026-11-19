package dev.amatv.flyme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.integration.CatalogArtistPage
import dev.amatv.integration.LibraryCollection
import dev.amatv.integration.MusicServices
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun rememberFolioArtistData(artist: Track, services: MusicServices) =
    rememberFolioDetailData(
        cached = { services.cachedArtistPage(artist) },
        saved = { services.savedArtistPage(artist) },
        complete = CatalogArtistPage::complete,
    ) { services.catalogArtistPage(artist) }

private data class FolioArtistAlbums(val items: List<Track>, val nextPage: String? = null)

@Composable
private fun rememberFolioArtistAlbums(
    artist: Track,
    services: MusicServices,
): Pair<FolioDetailData<FolioArtistAlbums>, () -> Unit> {
    var albums by remember {
        val cached = services.cachedArtistAlbums(artist)?.let { FolioArtistAlbums(it.items, it.nextPage) }
        mutableStateOf(FolioDetailData(cached))
    }
    var requestedPage by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        val next = requestedPage
        val previous = albums.value
        loadFolioDetail(
            cached = previous,
            saved = { services.savedArtistAlbums(artist)?.let { FolioArtistAlbums(it) } },
            fetch = {
                val page = services.libraryArtistAlbumsPage(artist, next)
                val items = if (next == null) page.items else mergeLibraryPage(checkNotNull(previous).items, page.items)
                FolioArtistAlbums(items, page.nextPage)
            },
            complete = { true },
            publish = { albums = it },
        )
    }
    return albums to {
        if (!albums.failed) requestedPage = albums.value?.nextPage
        attempt++
    }
}

@Composable
internal fun FolioArtistDetail(
    artist: Track,
    data: FolioDetailData<CatalogArtistPage>,
    retry: () -> Unit,
    layout: FolioDetailLayout,
    services: MusicServices,
    playback: PlaybackState,
    bottomPadding: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val spec = LocalFolioTierSpec.current
    val page = data.value
    val songs = page?.topSongs.orEmpty()
    val shelves = page?.shelves.orEmpty()
    val library = if (artist.resourceType == LibraryCollection.Artists.resourceType) {
        rememberFolioArtistAlbums(artist, services)
    } else null
    val hasCatalog = page?.artist != null || songs.isNotEmpty() || shelves.isNotEmpty()
    val albumCount = shelves.firstOrNull { it.id.endsWith("-full-albums") }?.items?.size ?: 0
    val more = remember { mutableStateMapOf<String, FolioDetailData<List<Track>>>() }
    var showAllSongs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun requestMore(shelfId: String, href: String) {
        more[shelfId] = FolioDetailData()
        scope.launch { more[shelfId] = loadFolioArtistShelf(services, href) }
    }

    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = spec.px, end = spec.pr)) {
        val columns = adaptiveColumnCount(maxWidth, spec.liw, spec.lstGap)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
            item(key = "header") {
                val meta = if (page != null && (library == null || hasCatalog)) {
                    val songLabel = pluralStringResource(R.plurals.folio_detail_artist_songs, songs.size, songs.size)
                    val albumLabel = pluralStringResource(R.plurals.folio_detail_albums, albumCount, albumCount)
                    "$songLabel · $albumLabel"
                } else null
                FolioDetailHeader(
                    item = page?.artist ?: artist,
                    layout = layout,
                    meta = meta,
                    playable = songs.isNotEmpty(),
                    listState = listState,
                    onPlay = { shuffle ->
                        val queue = if (shuffle) songs.shuffled() else songs
                        onOpenItem(queue.first(), queue)
                    },
                )
            }
            if (library != null) {
                val (albums, loadMoreOrRetry) = library
                val items = albums.value?.items.orEmpty()
                val next = albums.value?.nextPage
                item(key = "library-albums") {
                    FolioSectionTitle(stringResource(R.string.folio_detail_library_albums))
                }
                folioDetailCards(items, columns, spec.lstGap, false, onOpenItem)
                if (albums.failed || albums.loading || next != null || items.isEmpty()) {
                    item(key = "library-albums-status") {
                        when {
                            albums.failed -> FolioDetailStatus(
                                stringResource(R.string.browse_load_more_error), loadMoreOrRetry,
                            )
                            albums.loading -> FolioDetailStatus(stringResource(R.string.browse_loading))
                            next != null -> FolioChip(stringResource(R.string.folio_detail_view_more), false, loadMoreOrRetry)
                            else -> FolioDetailStatus(stringResource(R.string.library_empty))
                        }
                    }
                }
            }
            if ((library == null || hasCatalog) && (data.failed || (songs.isEmpty() && shelves.isEmpty()))) {
                item(key = "status") {
                    when {
                        data.failed -> FolioDetailStatus(
                            stringResource(R.string.folio_detail_artist_error), retry,
                        )
                        data.loading -> FolioDetailStatus(stringResource(R.string.browse_loading))
                        else -> FolioDetailStatus(stringResource(R.string.folio_detail_artist_empty))
                    }
                }
            }
            if (songs.isNotEmpty()) {
                item(key = "top-songs") { FolioSectionTitle(stringResource(R.string.artist_top_songs)) }
                folioDetailSongs(
                    if (showAllSongs) songs else songs.take(8),
                    layout.topSongColumns, 24.dp, playback, showCover = true, showArtist = true,
                ) { index -> onOpenItem(songs[index], songs) }
                if (songs.size > 8 && !showAllSongs) {
                    item(key = "top-songs-more") {
                        FolioChip(stringResource(R.string.folio_detail_view_more), false, { showAllSongs = true })
                    }
                }
            }
            for (shelf in shelves) {
                shelfContent(shelf, columns, spec.lstGap, more[shelf.id], onOpenItem, ::requestMore)
            }
        }
    }
}

private fun LazyListScope.shelfContent(
    shelf: ContentShelf,
    columns: Int,
    gap: Dp,
    extra: FolioDetailData<List<Track>>?,
    onOpenItem: (Track, List<Track>) -> Unit,
    loadMore: (String, String) -> Unit,
) {
    item(key = shelf.id) { FolioSectionTitle(shelf.title) }
    val cards = (shelf.items + extra?.value.orEmpty()).distinctBy { it.id }
    folioDetailCards(cards, columns, gap, false, onOpenItem)
    val href = shelf.moreHref
    if (href != null && extra?.value == null) {
        item(key = "${shelf.id}-more") {
            val requestMore = { loadMore(shelf.id, href) }
            when {
                extra?.loading == true -> FolioDetailStatus(stringResource(R.string.browse_loading))
                extra?.failed == true -> FolioDetailStatus(stringResource(R.string.browse_load_more_error), requestMore)
                else -> FolioChip(stringResource(R.string.folio_detail_view_more), false, requestMore)
            }
        }
    }
}

private suspend fun loadFolioArtistShelf(services: MusicServices, href: String): FolioDetailData<List<Track>> = try {
    FolioDetailData(services.catalogArtistViewMore(href), loading = false)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    FolioDetailData(loading = false, failed = true)
}
