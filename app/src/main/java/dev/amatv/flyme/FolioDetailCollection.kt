package dev.amatv.flyme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.integration.LibraryCollection
import dev.amatv.integration.MusicServices
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlin.math.roundToInt

@Composable
internal fun FolioCollectionDetail(
    collection: Track,
    layout: FolioDetailLayout,
    services: MusicServices,
    playback: PlaybackState,
    bottomPadding: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
) {
    val spec = LocalFolioTierSpec.current
    val album = collection.catalogKind == CatalogKind.Album
    val (data, retry) = rememberFolioDetailData(
        cached = { services.cachedCollectionTracks(collection) },
        saved = { services.savedCollectionTracks(collection) },
    ) { services.collectionTracks(collection) }
    val songs = data.value.orEmpty()
    val related = if (album && collection.catalogId != null) {
        rememberFolioDetailData(
            cached = { null },
        ) { listOfNotNull(services.catalogArtistOf(collection)) }
    } else null
    val linkedArtist = related?.first?.value?.singleOrNull()
    val otherAlbums = if (linkedArtist != null) {
        val (artistData, retryArtist) = rememberFolioArtistData(linkedArtist, services)
        artistData.value?.shelves.orEmpty().firstOrNull { it.id.endsWith("-full-albums") }
            ?.items.orEmpty().filter { it.catalogId != collection.catalogId } to
            retryArtist.takeIf { artistData.failed }
    } else null
    val kind = stringResource(if (album) R.string.browse_kind_album else R.string.browse_kind_playlist)
    val meta = if (data.value == null) {
        kind
    } else {
        val minutes = (songs.sumOf { it.durationMs } / 60_000.0).roundToInt()
        listOf(
            kind,
            pluralStringResource(R.plurals.folio_detail_songs, songs.size, songs.size),
            pluralStringResource(R.plurals.folio_detail_minutes, minutes, minutes),
        ).joinToString(" · ")
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(start = spec.px, end = spec.pr)) {
        val gap = 14.dp
        val columns = adaptiveColumnCount(maxWidth, spec.gw, gap)
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item(key = "header") {
                val byline = collection.artist.ifBlank { linkedArtist?.title.orEmpty() }
                FolioDetailHeader(
                    item = collection,
                    layout = layout,
                    meta = meta,
                    playable = songs.isNotEmpty(),
                    onPlay = { shuffle -> onPlayCollection(collection, songs, 0, shuffle) },
                    byline = {
                        if (album && byline.isNotBlank()) {
                            val open = if (linkedArtist != null) {
                                Modifier.clickable(role = Role.Button) {
                                    onOpenItem(linkedArtist, listOf(linkedArtist))
                                }
                            } else Modifier
                            Text(
                                text = byline,
                                color = if (linkedArtist != null) LocalFolioPalette.current.accent
                                    else LocalFolioPalette.current.fg2,
                                fontFamily = LocalFolioFontFamily.current,
                                fontSize = 17.sp,
                                modifier = open,
                            )
                        } else if (!album) {
                            val personal = collection.resourceType == LibraryCollection.Playlists.resourceType &&
                                collection.catalogId == null
                            val subtitle = if (personal) {
                                stringResource(R.string.folio_detail_my_playlist)
                            } else byline
                            if (subtitle.isNotBlank()) FolioDetailLabel(subtitle)
                        }
                    },
                )
            }
            if (data.failed || songs.isEmpty()) {
                item(key = "status") {
                    when {
                        data.failed -> FolioDetailStatus(stringResource(R.string.browse_tracks_error_detail), retry)
                        data.loading -> FolioDetailStatus(stringResource(R.string.browse_tracks_loading))
                        else -> FolioDetailStatus(stringResource(R.string.browse_tracks_empty))
                    }
                }
            }
            folioDetailSongs(songs, layout.collectionColumns, 40.dp, playback, false, !album) { index ->
                onPlayCollection(collection, songs, index, false)
            }
            if (otherAlbums != null && otherAlbums.first.isNotEmpty() && linkedArtist != null) {
                item(key = "related") {
                    FolioSectionTitle(
                        stringResource(R.string.folio_detail_more_albums, linkedArtist.title),
                        Modifier.padding(top = 28.dp),
                    )
                }
                folioDetailCards(otherAlbums.first, columns, gap, true, onOpenItem)
            }
            val retryRelated = related?.second?.takeIf { related.first.failed } ?: otherAlbums?.second
            if (retryRelated != null) {
                item(key = "related-error") {
                    FolioDetailStatus(stringResource(R.string.browse_load_more_error), retryRelated)
                }
            }
        }
    }
}
