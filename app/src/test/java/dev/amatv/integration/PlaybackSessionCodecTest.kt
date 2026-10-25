package dev.amatv.integration

import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionCodecTest {
    private fun track(id: String) = Track(id, "Song $id", "Artist", "Album", 180_000,
        emptyList(), emptyList(), catalogId = id)
    private fun session(count: Int, current: Int = count - 1): PlaybackSession = PlaybackSession(
        queue = (1..count).map { track("$it") }, currentIndex = current, positionMs = 42_000,
        wasPlaying = true, destination = Destination.NowPlaying, previousDestination = Destination.Home,
        radioActive = true, radioSeed = track("1"), radioContinuation = "cursor",
        autoplayEntryIndices = (count - 10 until count).filter { it >= 0 }.toSet(),
        queueEntryIds = (1..count).map { it.toLong() }, playedEntryIds = (1..current).map { it.toLong() }.toSet(),
        contextQueue = listOf(track("1")), contextEntryIds = listOf(1), sessionId = 3,
        persistenceId = "logical-session", savedRevision = 100,
    )

    @Test fun longTimelineRoundTripRetainsEveryEntryIdOriginAndPlayedFlag() {
        val original = session(1_500, 1_250)
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(original))!!
        assertEquals(1_500, decoded.queue.size)
        assertEquals(original.queueEntryIds, decoded.queueEntryIds)
        assertEquals(original.playedEntryIds, decoded.playedEntryIds)
        assertEquals(original.autoplayEntryIndices, decoded.autoplayEntryIndices)
        assertEquals(original.currentIndex, decoded.currentIndex)
        assertEquals(original.contextEntryIds, decoded.contextEntryIds)
    }

    @Test fun repeatedSongMetadataUsesDictionaryAndNeverStoresLyricsOrNativeQueueIds() {
        val lyrics = listOf(LyricLine(listOf(LyricWord("DO_NOT_PERSIST_LYRICS", 0, 1_000))))
        val repeated = track("1").copy(lyrics = lyrics, playbackQueueId = 777)
        val original = session(500).copy(queue = List(500) { repeated }, contextQueue = listOf(repeated))
        val encoded = PlaybackSessionCodec.encode(original)
        assertFalse(encoded.contains("DO_NOT_PERSIST_LYRICS"))
        assertEquals(1, JSONObject(encoded).getJSONArray("tracks").length())
        val decoded = PlaybackSessionCodec.decode(encoded)!!
        assertEquals(500, decoded.queueEntryIds.distinct().size)
        assertTrue(decoded.queue.all { it.lyrics.isEmpty() && it.playbackQueueId == null })
    }

    @Test fun smallCheckpointOverlaysRecentTailWithoutDroppingFullHistory() {
        val full = session(1_000, 900)
        val latest = session(1_005, 1_002).copy(savedRevision = 101, positionMs = 81_000)
        val checkpoint = PlaybackSessionCodec.encodeCheckpoint(latest)
        assertTrue(PlaybackSessionCodec.decode(checkpoint)!!.queue.size <= 11)
        val restored = PlaybackSessionCodec.merge(full, checkpoint)!!
        assertEquals(1_005, restored.queue.size)
        assertEquals(1L, restored.queueEntryIds.first())
        assertEquals(1_002, restored.currentIndex)
        assertEquals(81_000L, restored.positionMs)
        assertEquals(full.contextEntryIds, restored.contextEntryIds)
    }

    @Test fun checkpointMovingBackwardKeepsTheFullFutureAndStableIds() {
        val full = session(1_000, 900)
        val selectedHistory = full.copy(currentIndex = 400, positionMs = 0, savedRevision = 101)
        val restored = PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(selectedHistory))!!
        assertEquals(full.queueEntryIds, restored.queueEntryIds)
        assertEquals(400, restored.currentIndex)
    }

    @Test fun olderAsyncCursorCannotOverrideANewerAtomicSnapshot() {
        val full = session(1_000, 900).copy(savedRevision = 200)
        val oldCursor = session(1_000, 800).copy(savedRevision = 199)
        assertEquals(900, PlaybackSessionCodec.merge(full, PlaybackSessionCodec.encodeCheckpoint(oldCursor))!!.currentIndex)
    }

    @Test fun newLogicalContextNeverReattachesThePreviousSessionsHistory() {
        val old = session(1_000)
        val next = session(3, 0).copy(persistenceId = "another-context", savedRevision = 101)
        val restored = PlaybackSessionCodec.merge(old, PlaybackSessionCodec.encodeCheckpoint(next))!!
        assertEquals("another-context", restored.persistenceId)
        assertEquals(3, restored.queue.size)
    }

    @Test fun versionOneMigrationPreservesValidQueueAndAssignsAppEntryIds() {
        val old = JSONObject().put("version", 1)
            .put("queue", org.json.JSONArray(listOf(track("1").toSessionJson(), track("1").toSessionJson())))
            .put("currentIndex", 1).put("positionMs", 45_000).put("wasPlaying", true)
        val restored = PlaybackSessionCodec.decode(old.toString())!!
        assertEquals(listOf(1L, 2L), restored.queueEntryIds)
        assertEquals(1, restored.currentIndex)
        assertEquals(45_000L, restored.positionMs)
        assertEquals(setOf(1L), restored.playedEntryIds)
    }
}
