package dev.amatv.flyme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.core.canShowFullAlbum
import dev.amatv.core.catalogAlbum
import dev.amatv.integration.LibraryCollection
import dev.amatv.integration.MusicServices
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlin.math.roundToInt

@Composable
internal fun FolioCollectionDetail(
    collection: Track,
    layout: FolioDetailLayout,
    services: MusicServices,
    playback: PlaybackState,
    bottomPadding: Dp,
    homeShelves: List<ContentShelf>,
    onOpenItem: (Track, List<Track>) -> Unit,
    onPlayCollection: (Track, List<Track>, Int, Boolean) -> Unit,
    onRegisterScrollToTop: (suspend () -> Unit) -> Unit = {},
) {
    val spec = LocalFolioTierSpec.current
    val album = collection.catalogKind == CatalogKind.Album
    var fullAlbum by remember { mutableStateOf(false) }
    val playbackCollection = if (fullAlbum) collection.catalogAlbum() else collection
    val (data, retry) = key(playbackCollection) {
        rememberFolioDetailData(
            cached = { services.cachedCollectionTracks(playbackCollection) },
            saved = { services.savedCollectionTracks(playbackCollection) },
        ) { services.collectionTracks(playbackCollection) }
    }
    val songs = data.value.orEmpty()
    val discGroups = remember(songs, album) { groupAlbumTracksByDisc(songs, isAlbum = album) }
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
    val playlistAlbums = if (album) emptyList() else remember(homeShelves) { folioPlaylistRecommendations(homeShelves) }
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

    val listState = rememberFolioScrollToTopState(onRegisterScrollToTop)
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = spec.px, end = spec.pr)) {
        val gap = 14.dp
        val columns = adaptiveColumnCount(maxWidth, spec.gw, gap)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
            item(key = "header") {
                val byline = collection.artist.ifBlank { linkedArtist?.title.orEmpty() }
                FolioDetailHeader(
                    item = collection,
                    layout = layout,
                    meta = meta,
                    playable = songs.isNotEmpty(),
                    listState = listState,
                    onPlay = { shuffle -> onPlayCollection(playbackCollection, songs, 0, shuffle) },
                    byline = {
                        if (album && byline.isNotBlank()) {
                            val open = if (linkedArtist != null) {
                                Modifier.folioBrowsePressable {
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
            if (discGroups != null) {
                val rowKeyOccurrences = mutableMapOf<String, Int>()
                discGroups.forEachIndexed { groupIndex, group ->
                    item(key = "disc-${group.discNumber}") {
                        FolioDetailLabel(
                            text = stringResource(R.string.folio_detail_disc, group.discNumber),
                            modifier = Modifier.padding(
                                start = 8.dp,
                                top = if (groupIndex == 0) 0.dp else FolioSpacing.PivotToContent * 2,
                                bottom = FolioSpacing.PivotToContent,
                            ),
                        )
                    }
                    folioDetailIndexedSongs(
                        entries = group.tracks,
                        columns = layout.collectionColumns,
                        gap = 40.dp,
                        playback = playback,
                        showCover = false,
                        showArtist = false,
                        keyOccurrences = rowKeyOccurrences,
                        onPlay = { index -> onPlayCollection(playbackCollection, songs, index, false) },
                    )
                }
            } else {
                folioDetailSongs(songs, layout.collectionColumns, 40.dp, playback, false, !album) { index ->
                    onPlayCollection(playbackCollection, songs, index, false)
                }
            }
            if (collection.canShowFullAlbum && songs.isNotEmpty()) {
                item(key = "full-album") {
                    FolioChip(
                        label = stringResource(if (fullAlbum) R.string.album_show_library else R.string.album_show_complete),
                        selected = false,
                        onClick = { fullAlbum = !fullAlbum },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
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
            if (playlistAlbums.isNotEmpty()) {
                item(key = "playlist-recommendations") {
                    FolioSectionTitle(
                        stringResource(R.string.folio_detail_playlist_recommendations),
                        Modifier.padding(top = 28.dp),
                    )
                }
                folioDetailCards(playlistAlbums, columns, gap, true, onOpenItem)
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

internal data class FolioAlbumDiscGroup(
    val discNumber: Int,
    val tracks: List<FolioIndexedTrack>,
)

internal fun folioDiscRowNumber(track: Track, indexInDisc: Int): Int =
    track.trackNumber?.takeIf { it > 0 } ?: (indexInDisc + 1)

/**
 * Groups an album's tracks by ascending [Track.discNumber] only when it is an album, every track
 * carries a non-null disc number, and at least two distinct discs exist. Preserves API order inside
 * each disc and keeps each track's original queue index.
 */
internal fun groupAlbumTracksByDisc(
    tracks: List<Track>,
    isAlbum: Boolean,
): List<FolioAlbumDiscGroup>? {
    if (!isAlbum || tracks.isEmpty()) return null
    if (tracks.any { it.discNumber == null }) return null
    val byDisc = linkedMapOf<Int, MutableList<Pair<Int, Track>>>()
    tracks.forEachIndexed { originalIndex, track ->
        val disc = track.discNumber!!
        byDisc.getOrPut(disc) { mutableListOf() }.add(originalIndex to track)
    }
    if (byDisc.size < 2) return null
    return byDisc.entries
        .sortedBy { it.key }
        .map { (discNumber, entries) ->
            FolioAlbumDiscGroup(
                discNumber = discNumber,
                tracks = entries.mapIndexed { indexInDisc, (originalIndex, track) ->
                    FolioIndexedTrack(
                        originalIndex = originalIndex,
                        track = track,
                        displayNumber = folioDiscRowNumber(track, indexInDisc),
                    )
                },
            )
        }
}

internal fun folioPlaylistRecommendations(homeShelves: List<ContentShelf>): List<Track> =
    homeShelves.asSequence()
        .filter { it.id.startsWith("recommendation:") }
        .flatMap { it.items.asSequence() }
        .filter { it.catalogKind == CatalogKind.Album }
        .distinctBy { it.catalogId ?: it.id }
        .take(3)
        .toList()
