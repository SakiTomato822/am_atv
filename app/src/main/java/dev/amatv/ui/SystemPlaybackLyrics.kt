package dev.amatv.ui

import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.LyricLine

/** Attach only the current song's resolved lyrics; playback SDK snapshots do not load them. */
internal fun PlaybackSnapshot.withSystemLyrics(
    resolvedTrackIdentity: String,
    resolvedLines: List<LyricLine>,
): PlaybackSnapshot {
    val current = currentTrack ?: return this
    if (current.lyricsIdentity() != resolvedTrackIdentity || current.lyrics == resolvedLines) return this
    return copy(currentTrack = current.copy(lyrics = resolvedLines))
}
