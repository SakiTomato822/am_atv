package dev.amatv.core

import dev.amatv.data.MockCatalog
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.playback.ContextKind
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryStoreTest {

    private class FakePlayHistoryStorage(
        initial: List<PlayHistoryEntry> = emptyList(),
    ) : PlayHistoryStorage {
        var stored: List<PlayHistoryEntry> = initial
        var clearedCount = 0

        override fun load(): List<PlayHistoryEntry> = stored

        override fun save(entries: List<PlayHistoryEntry>) {
            stored = entries
        }

        override fun clear() {
            clearedCount++
            stored = emptyList()
        }
    }

    private fun song(
        id: String,
        catalogId: String? = id,
        title: String = "Song $id",
    ) = MockCatalog.tracks.first().copy(
        id = id,
        catalogId = catalogId,
        catalogKind = CatalogKind.Song,
        title = title,
        artist = "Artist $id",
        album = "Album $id",
        artworkUrl = "https://example.com/$id.jpg",
        lyrics = emptyList(),
    )

    @Test
    fun recordsAfterThirtySecondsActivePlaybackOrTrackCompletion() {
        val storage = FakePlayHistoryStorage()
        var wallMs = 1_700_000_000_000L
        val store = PlayHistoryStore(storage = storage, wallClock = { wallMs })
        val track = song("t-1", catalogId = "cat-1")

        // Start playing at t = 0
        store.onPlaybackProgress(track, isPlaying = true, elapsedRealtimeMs = 1_000L, source = PlayHistorySource.Album)
        // Advance 20s and pause -> still below 30s
        store.onPlaybackProgress(track, isPlaying = false, elapsedRealtimeMs = 21_000L, source = PlayHistorySource.Album)
        assertTrue(store.entries.value.isEmpty())
        assertEquals(10_000L, store.remainingThresholdMs())

        // Long pause (50s while paused must not count toward 30s active playback)
        store.onPlaybackProgress(track, isPlaying = true, elapsedRealtimeMs = 71_000L, source = PlayHistorySource.Album)
        store.onPlaybackProgress(track, isPlaying = true, elapsedRealtimeMs = 80_999L, source = PlayHistorySource.Album)
        assertTrue(store.entries.value.isEmpty())

        // Cross 30s active playback at 81_000L (20_000 + 10_000 = 30_000)
        wallMs += 5_000L
        store.onPlaybackProgress(track, isPlaying = true, elapsedRealtimeMs = 81_000L, source = PlayHistorySource.Album)
        assertEquals(1, store.entries.value.size)
        val entry = store.entries.value.single()
        assertEquals("cat-1", entry.songKey)
        assertEquals("cat-1", entry.catalogId)
        assertEquals(PlayHistorySource.Album, entry.source)
        assertEquals(wallMs, entry.playedAtMs)

        // Short interlude (< 30s) still records when completed (`ended = true`), falling back to id when catalogId is null
        val shortLocal = song("local-id", catalogId = null, title = "Interlude")
        wallMs += 10_000L
        store.onPlaybackProgress(shortLocal, isPlaying = true, elapsedRealtimeMs = 90_000L, source = PlayHistorySource.Radio)
        store.onTrackFinished(shortLocal, elapsedRealtimeMs = 102_000L, source = PlayHistorySource.Radio)
        assertEquals(2, store.entries.value.size)
        assertEquals("local-id", store.entries.value.first().songKey)
        assertNull(store.entries.value.first().catalogId)
        assertEquals(PlayHistorySource.Radio, store.entries.value.first().source)
    }

    @Test
    fun consecutiveDuplicatesUpdateTimestampWithoutAddingRow() {
        val storage = FakePlayHistoryStorage()
        var wallMs = 1_000_000L
        val store = PlayHistoryStore(storage = storage, wallClock = { wallMs })
        val trackA = song("a")
        val trackB = song("b")

        // First play of A finishes
        store.onPlaybackProgress(trackA, isPlaying = true, elapsedRealtimeMs = 0L, source = PlayHistorySource.Search)
        store.onTrackFinished(trackA, elapsedRealtimeMs = 35_000L, source = PlayHistorySource.Search)
        assertEquals(1, store.entries.value.size)
        assertEquals(1_000_000L, store.entries.value.first().playedAtMs)

        // Consecutive repeat of A only updates timestamp and source
        wallMs = 2_000_000L
        store.onPlaybackProgress(trackA, isPlaying = true, elapsedRealtimeMs = 40_000L, source = PlayHistorySource.Playlist)
        store.onPlaybackProgress(trackA, isPlaying = true, elapsedRealtimeMs = 70_000L, source = PlayHistorySource.Playlist)
        assertEquals(1, store.entries.value.size)
        assertEquals(2_000_000L, store.entries.value.first().playedAtMs)
        assertEquals(PlayHistorySource.Playlist, store.entries.value.first().source)

        // Playing B, then A again is non-consecutive so both A plays are retained newest-first
        wallMs = 3_000_000L
        store.onTrackFinished(trackB, elapsedRealtimeMs = 100_000L, source = PlayHistorySource.Other)
        wallMs = 4_000_000L
        store.onTrackFinished(trackA, elapsedRealtimeMs = 140_000L, source = PlayHistorySource.Other)
        assertEquals(listOf("a", "b", "a"), store.entries.value.map { it.songKey })
    }

    @Test
    fun enforcesMax1000EntriesAndEvictsOldest() {
        val storage = FakePlayHistoryStorage()
        var wallMs = 1_000L
        val store = PlayHistoryStore(storage = storage, wallClock = { wallMs })

        for (i in 1..1_005) {
            wallMs += 1_000L
            store.onTrackFinished(song("s-$i"), elapsedRealtimeMs = i * 35_000L, source = PlayHistorySource.Other)
        }

        val list = store.entries.value
        assertEquals(PLAY_HISTORY_MAX_ENTRIES, list.size)
        assertEquals("s-1005", list.first().songKey)
        assertEquals("s-6", list.last().songKey)
        assertEquals(PLAY_HISTORY_MAX_ENTRIES, storage.stored.size)
    }

    @Test
    fun clearOnSignOutWipesMemoryAndStorage() {
        val storage = FakePlayHistoryStorage()
        val store = PlayHistoryStore(storage = storage, wallClock = { 5_000L })
        store.onTrackFinished(song("s-1"), elapsedRealtimeMs = 31_000L, source = PlayHistorySource.Album)
        assertEquals(1, store.entries.value.size)

        store.clear()
        assertTrue(store.entries.value.isEmpty())
        assertTrue(storage.stored.isEmpty())
        assertEquals(1, storage.clearedCount)
    }

    @Test
    fun groupsEntriesByTodayYesterdayThisWeekAndEarlier() {
        val zone = ZoneOffset.UTC
        // Thursday 2026-11-19T12:00Z -> Week starts Monday 2026-11-16
        val nowMs = ZonedDateTime.of(2026, 11, 19, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val todayMs = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        val yesterdayMs = ZonedDateTime.of(2026, 11, 18, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val mondayMs = ZonedDateTime.of(2026, 11, 16, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val lastWeekMs = ZonedDateTime.of(2026, 11, 15, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

        val entries = listOf(
            PlayHistoryEntry("today", "today", "Today", "A", "Al", null, todayMs, PlayHistorySource.Album),
            PlayHistoryEntry("yest", "yest", "Yesterday", "A", "Al", null, yesterdayMs, PlayHistorySource.Playlist),
            PlayHistoryEntry("mon", "mon", "Monday", "A", "Al", null, mondayMs, PlayHistorySource.Radio),
            PlayHistoryEntry("old", "old", "Older", "A", "Al", null, lastWeekMs, PlayHistorySource.Search),
        )

        val sections = groupPlayHistoryByDate(entries, nowMs = nowMs, zoneId = zone)
        assertEquals(
            listOf(
                PlayHistoryDateGroup.Today to listOf("today"),
                PlayHistoryDateGroup.Yesterday to listOf("yest"),
                PlayHistoryDateGroup.ThisWeek to listOf("mon"),
                PlayHistoryDateGroup.Earlier to listOf("old"),
            ),
            sections.map { it.group to it.entries.map(PlayHistoryEntry::songKey) },
        )

        assertEquals(
            PlayHistorySource.Radio,
            resolvePlayHistorySource(ContextKind.Single, isStation = true, Destination.Home, Destination.Home),
        )
        assertEquals(
            PlayHistorySource.Album,
            resolvePlayHistorySource(ContextKind.Album, isStation = false, Destination.Library, Destination.Library),
        )
        assertEquals(
            PlayHistorySource.Playlist,
            resolvePlayHistorySource(ContextKind.LibraryPlaylist, isStation = false, Destination.Library, Destination.Library),
        )
        assertEquals(
            PlayHistorySource.Search,
            resolvePlayHistorySource(ContextKind.SongList, isStation = false, Destination.Search, Destination.Search),
        )
        assertEquals(
            PlayHistorySource.Other,
            resolvePlayHistorySource(ContextKind.SongList, isStation = false, Destination.Home, Destination.Home),
        )
    }
}
