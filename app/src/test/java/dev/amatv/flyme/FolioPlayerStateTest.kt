package dev.amatv.flyme

import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.PlaybackState
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import dev.amatv.playback.AutoplayStatus
import dev.amatv.playback.EntryOrigin
import dev.amatv.playback.QueueEntry
import dev.amatv.playback.QueueState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioPlayerStateTest {

    @After
    fun resetSession() {
        FolioPlayerSession.reset()
    }

    private fun sampleTrack(
        id: String,
        title: String,
        kind: CatalogKind = CatalogKind.Song,
    ): Track = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogKind = kind,
    )

    @Test
    fun playerSpecMatchesAllFiveH5Tiers() {
        assertEquals(28f, playerSpecForTier(FolioTier.Narrow).fs.value, 0.01f)
        assertEquals(40f, playerSpecForTier(FolioTier.Narrow).pfs.value, 0.01f)
        assertEquals(0.30f, playerSpecForTier(FolioTier.Narrow).anchorFraction, 0.001f)
        assertFalse(playerSpecForTier(FolioTier.Narrow).isHorizontalQueue)

        assertEquals(24f, playerSpecForTier(FolioTier.Short).fs.value, 0.01f)
        assertEquals(34f, playerSpecForTier(FolioTier.Short).pfs.value, 0.01f)
        assertTrue(playerSpecForTier(FolioTier.Short).isHorizontalQueue)

        assertEquals(30f, playerSpecForTier(FolioTier.Medium).fs.value, 0.01f)
        assertEquals(44f, playerSpecForTier(FolioTier.Medium).pfs.value, 0.01f)
        assertFalse(playerSpecForTier(FolioTier.Medium).isHorizontalQueue)

        assertEquals(34f, playerSpecForTier(FolioTier.Wide).fs.value, 0.01f)
        assertEquals(56f, playerSpecForTier(FolioTier.Wide).pfs.value, 0.01f)
        assertTrue(playerSpecForTier(FolioTier.Wide).isHorizontalCarControls)
        assertTrue(playerSpecForTier(FolioTier.Wide).isHorizontalQueue)

        assertEquals(32f, playerSpecForTier(FolioTier.Ultrawide).fs.value, 0.01f)
        assertEquals(52f, playerSpecForTier(FolioTier.Ultrawide).pfs.value, 0.01f)
        assertFalse(playerSpecForTier(FolioTier.Ultrawide).isHorizontalQueue)
    }

    @Test
    fun resolvesFourDisplayStatesAndQueueVisibilityPerTier() {
        assertEquals(
            FolioPlayerDisplayState.Lyrics,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = false, hasLyrics = true, hasTimedLyrics = true),
        )
        assertEquals(
            FolioPlayerDisplayState.Poster,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = true, hasLyrics = true, hasTimedLyrics = true),
        )
        // Static lyrics stay in Lyrics mode even if Poster is preferred.
        assertEquals(
            FolioPlayerDisplayState.Lyrics,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = true, hasLyrics = true, hasTimedLyrics = false),
        )
        assertEquals(
            FolioPlayerDisplayState.None,
            resolvePlayerDisplayState(FolioTier.Wide, queueOpen = false, posterPreferred = true, hasLyrics = false, hasTimedLyrics = false),
        )
        assertEquals(
            FolioPlayerDisplayState.Queue,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = true, posterPreferred = true, hasLyrics = true, hasTimedLyrics = true),
        )
        // Ultrawide keeps its center lyric column instead of switching into overlay Queue.
        assertEquals(
            FolioPlayerDisplayState.Poster,
            resolvePlayerDisplayState(FolioTier.Ultrawide, queueOpen = true, posterPreferred = true, hasLyrics = true, hasTimedLyrics = true),
        )

        // Queue panel rules
        assertFalse(isQueuePanelVisible(FolioTier.Narrow, FolioPlayerDisplayState.None))
        assertTrue(isQueuePanelVisible(FolioTier.Narrow, FolioPlayerDisplayState.Queue))
        assertTrue(isQueuePanelVisible(FolioTier.Short, FolioPlayerDisplayState.None))
        assertTrue(isQueuePanelVisible(FolioTier.Medium, FolioPlayerDisplayState.None))
        assertTrue(isQueuePanelVisible(FolioTier.Wide, FolioPlayerDisplayState.None))
        assertTrue(isQueuePanelVisible(FolioTier.Ultrawide, FolioPlayerDisplayState.Lyrics))
    }

    @Test
    fun headerTitleAppearsOnlyOnExpandedNarrowWithContent() {
        assertFalse(showHeaderTitle(FolioTier.Narrow, controlsExpanded = false, displayState = FolioPlayerDisplayState.Lyrics))
        assertTrue(showHeaderTitle(FolioTier.Narrow, controlsExpanded = true, displayState = FolioPlayerDisplayState.Lyrics))
        assertFalse(showHeaderTitle(FolioTier.Narrow, controlsExpanded = true, displayState = FolioPlayerDisplayState.None))
        assertFalse(showHeaderTitle(FolioTier.Wide, controlsExpanded = true, displayState = FolioPlayerDisplayState.Lyrics))
        assertFalse(showCardMeta(FolioTier.Narrow))
        assertTrue(showCardMeta(FolioTier.Wide))
    }

    @Test
    fun alwaysOpenControlsFollowsFlowAndNoLyricsRules() {
        assertTrue(alwaysOpenControls(FolioTier.Wide, LyricBackgroundStyle.Flow, hasLyrics = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Medium, LyricBackgroundStyle.Flow, hasLyrics = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Ultrawide, LyricBackgroundStyle.Flow, hasLyrics = true, FolioPlayerDisplayState.Lyrics))
        assertFalse(alwaysOpenControls(FolioTier.Short, LyricBackgroundStyle.Flow, hasLyrics = true, FolioPlayerDisplayState.Lyrics))
        assertFalse(alwaysOpenControls(FolioTier.Wide, LyricBackgroundStyle.Soak, hasLyrics = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Narrow, LyricBackgroundStyle.Soak, hasLyrics = false, FolioPlayerDisplayState.None))
        assertFalse(alwaysOpenControls(FolioTier.Narrow, LyricBackgroundStyle.Soak, hasLyrics = false, FolioPlayerDisplayState.Queue))
    }

    @Test
    fun sessionStartsCollapsedOnColdStartAndSurvivesWindowSwitchState() {
        assertFalse(FolioPlayerSession.openState.value)
        FolioPlayerSession.openState.value = true
        assertTrue(FolioPlayerSession.openState.value)
    }

    @Test
    fun buildQueueSnapshotGroupsPlayedNowUpNextAndAutoplay() {
        val t0 = sampleTrack("0", "Old")
        val t1 = sampleTrack("1", "Current")
        val t2 = sampleTrack("2", "Next")
        val t3 = sampleTrack("3", "Auto")
        val e0 = QueueEntry(0L, t0, EntryOrigin.Context, wasPlayed = true)
        val e1 = QueueEntry(1L, t1, EntryOrigin.Context, wasPlayed = true)
        val e2 = QueueEntry(2L, t2, EntryOrigin.Context, wasPlayed = false)
        val e3 = QueueEntry(3L, t3, EntryOrigin.Autoplay, wasPlayed = false)
        val queueState = QueueState(
            history = listOf(e0),
            current = e1,
            upNext = listOf(e2),
            autoplay = listOf(e3),
            context = null,
            shuffle = false,
            repeat = PlaybackRepeatMode.Off,
            infinite = true,
            autoplayStatus = AutoplayStatus.Idle,
            awaitingNext = false,
            sessionId = 1L,
        )
        val playbackState = PlaybackState(
            track = t1,
            hasCurrentTrack = true,
            positionAnchorMs = 0L,
            playStartedRealtimeMs = 0L,
            isPlaying = true,
            playWhenReady = true,
            playbackPrepared = true,
            playbackMessage = null,
            stationHistory = emptyList(),
            snapshot = null,
            startupSessionLoaded = true,
        )

        val snap = buildFolioQueueSnapshot(queueState, playbackState)
        assertEquals(4, snap.rows.size)
        assertEquals(FolioQueueSectionKind.Played, snap.rows[0].section)
        assertEquals(0, snap.rows[0].timelineIndex)
        assertEquals(FolioQueueSectionKind.NowPlaying, snap.rows[1].section)
        assertTrue(snap.rows[1].isCurrent)
        assertEquals(FolioQueueSectionKind.UpNext, snap.rows[2].section)
        assertEquals(FolioQueueSectionKind.Autoplay, snap.rows[3].section)
        assertEquals(1, snap.playedCount)
        assertEquals(1, snap.upNextCount)
        assertEquals(1, snap.autoplayCount)
    }

    @Test
    fun buildQueueSnapshotHandlesStationHistory() {
        val station = sampleTrack("st", "Radio", kind = CatalogKind.Station)
        val prev = sampleTrack("s1", "Prev Song")
        val cur = sampleTrack("s2", "Live Song")
        val queueState = QueueState(
            history = emptyList(),
            current = QueueEntry(10L, station, EntryOrigin.Context, wasPlayed = true),
            upNext = emptyList(),
            autoplay = emptyList(),
            context = null,
            shuffle = false,
            repeat = PlaybackRepeatMode.Off,
            infinite = false,
            autoplayStatus = AutoplayStatus.Idle,
            awaitingNext = false,
            sessionId = 1L,
        )
        val playbackState = PlaybackState(
            track = cur,
            hasCurrentTrack = true,
            positionAnchorMs = 0L,
            playStartedRealtimeMs = 0L,
            isPlaying = true,
            playWhenReady = true,
            playbackPrepared = true,
            playbackMessage = null,
            stationHistory = listOf(prev, cur),
            snapshot = null,
            startupSessionLoaded = true,
        )

        val snap = buildFolioQueueSnapshot(queueState, playbackState)
        assertTrue(snap.isStation)
        assertEquals(2, snap.rows.size)
        assertEquals(FolioQueueSectionKind.Played, snap.rows[0].section)
        assertNull(snap.rows[0].timelineIndex)
        assertEquals("Prev Song", snap.rows[0].track.title)
        assertEquals(FolioQueueSectionKind.NowPlaying, snap.rows[1].section)
        assertTrue(snap.rows[1].isCurrent)
    }

    @Test
    fun posterFontSizeStaysBoundedByLongestLineInSong() {
        val shortLine = LyricLine(words = listOf(LyricWord("Hi", 0L, 1000L)))
        val longLine = LyricLine(words = listOf(LyricWord("这是一句非常非常长的歌词需要适当缩小字号防止过度折行", 0L, 1000L)))
        val shortOnlySize = computeSongPosterFontSize(listOf(shortLine), availableWidthDp = 360f, baseSp = 40f)
        val mixedSongSize = computeSongPosterFontSize(listOf(shortLine, longLine), availableWidthDp = 360f, baseSp = 40f)
        assertTrue(mixedSongSize < shortOnlySize)
        assertTrue(mixedSongSize >= 40f * 0.72f)
    }
}
