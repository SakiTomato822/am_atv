package dev.amatv.flyme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.amatv.integration.MusicServices
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
    val albumCount = shelves.firstOrNull { it.id.endsWith("-full-albums") }?.items?.size ?: 0
    val more = remember { mutableStateMapOf<String, FolioDetailData<List<Track>>>() }
    var showAllSongs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun requestMore(shelfId: String, href: String) {
        more[shelfId] = FolioDetailData()
        scope.launch { more[shelfId] = loadFolioArtistShelf(services, href) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(start = spec.px, end = spec.pr)) {
        val columns = adaptiveColumnCount(maxWidth, spec.liw, spec.lstGap)
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item(key = "header") {
                val meta = if (page != null) {
                    val songLabel = pluralStringResource(R.plurals.folio_detail_artist_songs, songs.size, songs.size)
                    val albumLabel = pluralStringResource(R.plurals.folio_detail_albums, albumCount, albumCount)
                    "$songLabel · $albumLabel"
                } else null
                FolioDetailHeader(
                    item = page?.artist ?: artist,
                    layout = layout,
                    meta = meta,
                    playable = songs.isNotEmpty(),
                    onPlay = { shuffle ->
                        val queue = if (shuffle) songs.shuffled() else songs
                        onOpenItem(queue.first(), queue)
                    },
                )
            }
            if (data.failed || (songs.isEmpty() && shelves.isEmpty())) {
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
