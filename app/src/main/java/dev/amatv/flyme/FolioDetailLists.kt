package dev.amatv.flyme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.core.PlaybackState
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry

internal fun LazyListScope.folioDetailSongs(
    songs: List<Track>,
    columns: Int,
    gap: Dp,
    playback: PlaybackState,
    showCover: Boolean,
    showArtist: Boolean,
    onPlay: (Int) -> Unit,
) {
    val ranges = folioDetailColumnRanges(songs.size, columns)
    val rowKeys = stableLazyKeys((0 until ranges.first().count()).map { row ->
        ranges.mapNotNull { range -> (range.first + row).takeIf { it in range }?.let { songs[it].id } }.toString()
    })
    items(rowKeys.size, key = { rowKeys[it] }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (range in ranges) {
                val index = range.first + row
                if (index in range) {
                    val track = songs[index]
                    FolioSongRow(
                        track = track,
                        index = index,
                        showCover = showCover,
                        showArtist = showArtist,
                        isCurrent = playback.hasCurrentTrack && track.matchesPlaybackQueueEntry(playback.track),
                        isPlaying = playback.isPlaying,
                        onClick = { onPlay(index) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun LazyListScope.folioDetailCards(
    cards: List<Track>,
    columns: Int,
    gap: Dp,
    grid: Boolean,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val rows = cards.chunked(columns)
    val rowKeys = stableLazyKeys(rows.map { row -> row.map(Track::id).toString() })
    items(rows.size, key = { rowKeys[it] }) { index ->
        val row = rows[index]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (card in row) {
                val open = { onOpenItem(card, cards) }
                if (grid) {
                    FolioGridCard(card, open, Modifier.weight(1f).padding(bottom = 16.dp))
                } else {
                    FolioListRowCard(card, open, Modifier.weight(1f))
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

internal fun stableLazyKeys(ids: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return ids.map { id ->
        val occurrence = occurrences.getOrDefault(id, 0)
        occurrences[id] = occurrence + 1
        "$occurrence:$id"
    }
}

@Composable
internal fun FolioDetailStatus(message: String, retry: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioDetailLabel(message, Modifier.weight(1f))
        if (retry != null) FolioChip(stringResource(R.string.browse_reload), false, retry)
    }
}
