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

internal data class FolioIndexedTrack(
    val originalIndex: Int,
    val track: Track,
    val displayNumber: Int,
)

internal fun LazyListScope.folioDetailSongs(
    songs: List<Track>,
    columns: Int,
    gap: Dp,
    playback: PlaybackState,
    showCover: Boolean,
    showArtist: Boolean,
    onPlay: (Int) -> Unit,
) {
    val entries = songs.mapIndexed { index, track ->
        FolioIndexedTrack(originalIndex = index, track = track, displayNumber = index + 1)
    }
    folioDetailIndexedSongs(
        entries = entries,
        columns = columns,
        gap = gap,
        playback = playback,
        showCover = showCover,
        showArtist = showArtist,
        onPlay = onPlay,
    )
}

internal fun LazyListScope.folioDetailIndexedSongs(
    entries: List<FolioIndexedTrack>,
    columns: Int,
    gap: Dp,
    playback: PlaybackState,
    showCover: Boolean,
    showArtist: Boolean,
    keyOccurrences: MutableMap<String, Int> = mutableMapOf(),
    onPlay: (Int) -> Unit,
) {
    val ranges = folioDetailColumnRanges(entries.size, columns)
    val rowKeys = stableLazyKeys(
        ids = (0 until ranges.first().count()).map { row ->
            ranges.mapNotNull { range ->
                (range.first + row).takeIf { it in range }?.let { entries[it].track.id }
            }.toString()
        },
        occurrences = keyOccurrences,
    )
    items(rowKeys.size, key = { rowKeys[it] }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (range in ranges) {
                val pos = range.first + row
                if (pos in range) {
                    val entry = entries[pos]
                    val track = entry.track
                    FolioSongRow(
                        track = track,
                        index = entry.originalIndex,
                        displayNumber = entry.displayNumber,
                        showCover = showCover,
                        showArtist = showArtist,
                        isCurrent = playback.hasCurrentTrack && track.matchesPlaybackQueueEntry(playback.track),
                        isPlaying = playback.isPlaying,
                        onClick = { onPlay(entry.originalIndex) },
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

internal fun stableLazyKeys(
    ids: List<String>,
    occurrences: MutableMap<String, Int> = mutableMapOf(),
): List<String> = ids.map { id ->
    val occurrence = occurrences.getOrDefault(id, 0)
    occurrences[id] = occurrence + 1
    "$occurrence:$id"
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
