package dev.amatv.model

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueIdentityTest {
    @Test
    fun queuePositionWinsWhenMusicKitUsesDifferentWrapperIds() {
        val current = track(id = "now-playing-wrapper", catalogId = "song-1", queueId = 3)
        val queueEntry = track(id = "queue-wrapper", catalogId = "song-1", queueId = 3)
        val duplicateLaterInQueue = track(id = "queue-wrapper-2", catalogId = "song-1", queueId = 8)

        assertTrue(current.matchesPlaybackQueueEntry(queueEntry))
        assertFalse(current.matchesPlaybackQueueEntry(duplicateLaterInQueue))
    }

    @Test
    fun catalogAndLocalIdsRemainSafeFallbacks() {
        assertTrue(
            track("snapshot-a", "song-1").matchesPlaybackQueueEntry(
                track("snapshot-b", "song-1"),
            ),
        )
        assertTrue(track("local", null).matchesPlaybackQueueEntry(track("local", null)))
        assertFalse(track("local-a", null).matchesPlaybackQueueEntry(track("local-b", null)))
    }

    private fun track(id: String, catalogId: String?, queueId: Long? = null) = Track(
        id = id,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000,
        palette = listOf(Color.Black),
        lyrics = emptyList(),
        catalogId = catalogId,
        playbackQueueId = queueId,
    )
}
