package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.LyricsLoadPhase
import dev.amatv.core.LyricsState
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
import org.junit.Assert.assertNotEquals
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
        assertTrue(alwaysOpenControls(FolioTier.Wide, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Medium, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Ultrawide, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertFalse(alwaysOpenControls(FolioTier.Short, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertFalse(alwaysOpenControls(FolioTier.Wide, LyricBackgroundStyle.Soak, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertTrue(alwaysOpenControls(FolioTier.Narrow, LyricBackgroundStyle.Soak, hasLyricsOrPending = false, FolioPlayerDisplayState.None))
        assertFalse(alwaysOpenControls(FolioTier.Narrow, LyricBackgroundStyle.Soak, hasLyricsOrPending = false, FolioPlayerDisplayState.Queue))
    }

    @Test
    fun sessionStartsCollapsedOnColdStartAndSurvivesWindowSwitchState() {
        assertFalse(FolioPlayerSession.openState.value)
        FolioPlayerSession.openState.value = true
        assertTrue(FolioPlayerSession.openState.value)
    }

    @Test
    fun posterAndQueueChoiceStartOffOnColdStart() {
        assertFalse(FolioPlayerSession.posterPreferredState.value)
        assertFalse(FolioPlayerSession.queueOpenState.value)
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
        assertEquals(40f, mixedSongSize, 0.01f)
    }

    @Test
    fun posterTypographyMatchesH5RatiosOnWideCarAndNarrowPhone() {
        val longCarLines = listOf(
            LyricLine(words = listOf(LyricWord("慢一点 再慢一点", 0L, 1000L))),
            LyricLine(words = listOf(LyricWord("这是一句比较长的中文主唱歌词测试在车机右半栏时的最小基准字号", 1000L, 2000L))),
        )
        val carSpec = playerSpecForTier(FolioTier.Wide)
        // Right half of 1280dp car layout minus padding is ~576dp; long line floors at --pfs = 56sp.
        val carTypo = posterTypographyFor(longCarLines, availableWidthDp = 576f, baseSp = carSpec.pfs.value)
        assertEquals(56f, carTypo.mainSp, 0.01f)
        assertEquals(56f * 1.16f, carTypo.mainLineHeightSp, 0.01f)
        assertEquals(56f * 0.42f, carTypo.prevSp, 0.01f)
        assertEquals(56f * 0.50f, carTypo.backingSp, 0.01f)
        assertEquals(56f * 0.38f, carTypo.translationSp, 0.01f)
        assertEquals(56f * 0.46f, carTypo.nextSp, 0.01f)

        // Short-lined song on Wide grows up to 1.3x (--pfs * 1.3 = 72.8sp).
        val shortOnlyCar = listOf(LyricLine(words = listOf(LyricWord("慢一点", 0L, 1000L))))
        val shortCarTypo = posterTypographyFor(shortOnlyCar, availableWidthDp = 576f, baseSp = carSpec.pfs.value)
        assertEquals(56f * 1.3f, shortCarTypo.mainSp, 0.01f)

        // Phone Narrow (--pfs = 40sp, width ~354dp)
        val phoneSpec = playerSpecForTier(FolioTier.Narrow)
        val phoneTypo = posterTypographyFor(longCarLines, availableWidthDp = 354f, baseSp = phoneSpec.pfs.value)
        assertEquals(40f, phoneTypo.mainSp, 0.01f)
        assertEquals(40f * 0.42f, phoneTypo.prevSp, 0.01f)
        assertEquals(40f * 0.50f, phoneTypo.backingSp, 0.01f)
        assertEquals(40f * 0.46f, phoneTypo.nextSp, 0.01f)
    }

    @Test
    fun formatsRemainingTimeWithLeadingMinusSign() {
        assertEquals("-0:51", formatFolioRemainingTime(positionMs = 129_000L, durationMs = 180_000L))
        assertEquals("-3:00", formatFolioRemainingTime(positionMs = 0L, durationMs = 180_000L))
        assertEquals("-0:00", formatFolioRemainingTime(positionMs = 200_000L, durationMs = 180_000L))
        assertEquals("", formatFolioRemainingTime(positionMs = 10_000L, durationMs = 0L))
    }

    @Test
    fun backingOnlyLinesNeverBecomePosterLeadOrUpcomingPreview() {
        val lead1 = LyricLine(words = listOf(LyricWord("慢一点 再慢一点", 5_000L, 10_000L)))
        val backingOnly = LyricLine(
            words = emptyList(),
            backgroundWords = listOf(LyricWord("慢一点", 7_000L, 12_000L)),
        )
        val lead2 = LyricLine(words = listOf(LyricWord("让风从窗缝里进来", 12_000L, 17_000L)))
        val lead3 = LyricLine(words = listOf(LyricWord("到家之前都算在路上", 17_000L, 22_000L)))
        val lines = listOf(lead1, backingOnly, lead2, lead3)

        assertTrue(isPosterBackingOnlyLine(backingOnly))
        assertFalse(isPosterBackingOnlyLine(lead1))

        // During the backing-only timestamp (10_500ms, after lead1 ended, backingOnly singing),
        // Poster keeps lead1 as focusLeadIndex, illuminates "(慢一点)" in activeBackingText,
        // and previews only lead2 & lead3 (skipping backingOnly).
        val proj = projectPosterLyrics(
            lines = lines,
            positionMs = 10_500L,
            activeLineIndices = setOf(1),
        )
        assertEquals(0, proj.focusLeadIndex)
        assertTrue(proj.hasStartedLead)
        assertEquals("(慢一点)", proj.activeBackingText)
        assertEquals(listOf("让风从窗缝里进来", "到家之前都算在路上"), proj.upcomingLeadTexts)

        // Before backingOnly starts (5_500ms), activeBackingText is empty and preview omits backingOnly.
        val earlyProj = projectPosterLyrics(
            lines = lines,
            positionMs = 5_500L,
            activeLineIndices = setOf(0),
        )
        assertEquals(0, earlyProj.focusLeadIndex)
        assertEquals("", earlyProj.activeBackingText)
        assertEquals(listOf("让风从窗缝里进来", "到家之前都算在路上"), earlyProj.upcomingLeadTexts)
    }

    @Test
    fun duetPosterColorsBySingerHandlesChorusAsPrimaryAndMovesLaterOverlapToSmallLines() {
        val jay = LyricLine(
            words = listOf(LyricWord("假装我们还在一块", 1_000L, 4_000L)),
            agentId = "v1",
            isOppositeAgent = false,
            isMultiAgentSong = true,
        )
        val amei = LyricLine(
            words = listOf(LyricWord("我真的演不出来", 4_000L, 8_000L)),
            agentId = "v2",
            isOppositeAgent = true,
            isMultiAgentSong = true,
        )
        // Simultaneous overlap: jay2 starts first at 9_000ms, amei2 overlaps starting at 10_000ms.
        val jayOverlap = LyricLine(
            words = listOf(LyricWord("还是没能走到未来", 9_000L, 14_000L)),
            agentId = "v1",
            isOppositeAgent = false,
            isMultiAgentSong = true,
        )
        val ameiOverlap = LyricLine(
            words = listOf(LyricWord("难道非要这样分开", 10_000L, 15_000L)),
            agentId = "v2",
            isOppositeAgent = true,
            isMultiAgentSong = true,
        )
        val chorus = LyricLine(
            words = listOf(LyricWord("不该就这样离开", 16_000L, 20_000L)),
            agentId = "v1000",
            isOppositeAgent = true,
            isMultiAgentSong = true,
        )
        val unattributed = LyricLine(
            words = listOf(LyricWord("最后一句", 20_000L, 24_000L)),
            agentId = null,
            isOppositeAgent = false,
            isMultiAgentSong = true,
        )

        assertFalse(isOppositeSingerLine(jay))
        assertTrue(isOppositeSingerLine(amei))
        assertFalse(isOppositeSingerLine(chorus))
        assertFalse(isOppositeSingerLine(unattributed))

        val lines = listOf(jay, amei, jayOverlap, ameiOverlap, chorus, unattributed)

        // When amei is singing solo (5_000ms), focus is opposite singer, previous is primary, previews reflect agent.
        val ameiSolo = projectPosterLyrics(lines, positionMs = 5_000L, activeLineIndices = setOf(1))
        assertEquals(1, ameiSolo.focusLeadIndex)
        assertTrue(ameiSolo.isFocusOppositeSinger)
        assertFalse(ameiSolo.previousLine!!.isOppositeSinger)
        assertFalse(ameiSolo.upcomingLines[0].isOppositeSinger) // jayOverlap

        // When jayOverlap (9_000) & ameiOverlap (10_000) both sing at 11_000ms:
        // earlier line (jayOverlap) stays big, ameiOverlap joins smallLines colored as opposite singer,
        // and preview skips ameiOverlap to chorus (which is treated as primary singer).
        val overlap = projectPosterLyrics(lines, positionMs = 11_000L, activeLineIndices = setOf(2, 3))
        assertEquals(2, overlap.focusLeadIndex)
        assertFalse(overlap.isFocusOppositeSinger)
        assertEquals(1, overlap.smallLines.size)
        assertEquals("难道非要这样分开", overlap.smallLines[0].text)
        assertTrue(overlap.smallLines[0].isOppositeSinger)
        assertEquals(listOf("不该就这样离开", "最后一句"), overlap.upcomingLines.map { it.text })
        assertFalse(overlap.upcomingLines[0].isOppositeSinger) // chorus -> primary

        // Duet accent colors remain distinct from primary ink & high-contrast on both themes.
        val warmPalette = listOf(Color(0xFF1A2238), Color(0xFFE07A5F), Color(0xFF3D5A80))
        val darkDuet = selectDuetAccentColor(warmPalette, folioPlayerBaseColor(warmPalette, isLight = false), isLight = false)
        val lightDuet = selectDuetAccentColor(warmPalette, folioPlayerBaseColor(warmPalette, isLight = true), isLight = true)
        assertNotEquals(Color.White, darkDuet)
        assertNotEquals(Color(0xFF16141B), lightDuet)
        assertTrue(contrastRatio(darkDuet, folioPlayerBaseColor(warmPalette, isLight = false)) >= 4.8f)
        assertTrue(contrastRatio(lightDuet, folioPlayerBaseColor(warmPalette, isLight = true)) >= 4.8f)

        // Monochrome artwork still selects a readable distinguishing duet tint.
        val bwPalette = listOf(Color(0xFFEEEEEE), Color(0xFF888888), Color(0xFF222222))
        val darkBwDuet = selectDuetAccentColor(bwPalette, folioPlayerBaseColor(bwPalette, isLight = false), isLight = false)
        val lightBwDuet = selectDuetAccentColor(bwPalette, folioPlayerBaseColor(bwPalette, isLight = true), isLight = true)
        assertTrue(contrastRatio(darkBwDuet, folioPlayerBaseColor(bwPalette, isLight = false)) >= 4.8f)
        assertTrue(contrastRatio(lightBwDuet, folioPlayerBaseColor(bwPalette, isLight = true)) >= 4.8f)
    }

    @Test
    fun shortTierUsesH5ControlWidthAndLyricsStart() {
        val shortSpec = playerSpecForTier(FolioTier.Short)
        assertEquals(336f, shortSpec.controlCardWidth.value, 0.01f)
        assertEquals(24f, shortSpec.fs.value, 0.01f)
        assertEquals(34f, shortSpec.pfs.value, 0.01f)
        assertEquals(384f, shortTierLyricsStartDp(914f).value, 0.01f)
    }

    @Test
    fun lyricsAvailabilityCoversPendingUnavailableFailedAndReadyWithoutFlashingNone() {
        val pendingMismatched = resolveLyricsAvailability(
            hasCurrentTrack = true,
            trackCatalogId = "new-song",
            trackCatalogKind = CatalogKind.Song,
            isSignedIn = true,
            displayedLineCount = 0,
            lyricsState = LyricsState(trackIdentity = "old-song", phase = LyricsLoadPhase.Ready),
            currentIdentity = "new-song",
        )
        assertEquals(FolioLyricsAvailability.Pending, pendingMismatched)
        assertEquals(
            FolioPlayerDisplayState.Poster,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = true, lyricsAvailability = pendingMismatched, hasTimedLyrics = false),
        )
        assertEquals(
            FolioPlayerDisplayState.Lyrics,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = false, lyricsAvailability = pendingMismatched, hasTimedLyrics = false),
        )

        val pendingLoading = resolveLyricsAvailability(
            hasCurrentTrack = true,
            trackCatalogId = "new-song",
            trackCatalogKind = CatalogKind.Song,
            isSignedIn = true,
            displayedLineCount = 0,
            lyricsState = LyricsState(trackIdentity = "new-song", phase = LyricsLoadPhase.Loading),
            currentIdentity = "new-song",
        )
        assertEquals(FolioLyricsAvailability.Pending, pendingLoading)

        val unavailable = resolveLyricsAvailability(
            hasCurrentTrack = true,
            trackCatalogId = "new-song",
            trackCatalogKind = CatalogKind.Song,
            isSignedIn = true,
            displayedLineCount = 0,
            lyricsState = LyricsState(trackIdentity = "new-song", phase = LyricsLoadPhase.Unavailable),
            currentIdentity = "new-song",
        )
        assertEquals(FolioLyricsAvailability.Unavailable, unavailable)
        assertEquals(
            FolioPlayerDisplayState.None,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = true, lyricsAvailability = unavailable, hasTimedLyrics = false),
        )

        val failed = resolveLyricsAvailability(
            hasCurrentTrack = true,
            trackCatalogId = "new-song",
            trackCatalogKind = CatalogKind.Song,
            isSignedIn = true,
            displayedLineCount = 0,
            lyricsState = LyricsState(trackIdentity = "new-song", phase = LyricsLoadPhase.Failed),
            currentIdentity = "new-song",
        )
        assertEquals(FolioLyricsAvailability.Unavailable, failed)

        val ready = resolveLyricsAvailability(
            hasCurrentTrack = true,
            trackCatalogId = "new-song",
            trackCatalogKind = CatalogKind.Song,
            isSignedIn = true,
            displayedLineCount = 12,
            lyricsState = LyricsState(trackIdentity = "new-song", phase = LyricsLoadPhase.Ready),
            currentIdentity = "new-song",
        )
        assertEquals(FolioLyricsAvailability.Ready, ready)
        assertEquals(
            FolioPlayerDisplayState.Poster,
            resolvePlayerDisplayState(FolioTier.Narrow, queueOpen = false, posterPreferred = true, lyricsAvailability = ready, hasTimedLyrics = true),
        )
    }

    @Test
    fun headerAdaptiveColorDetectsTopStripLuminanceAndUnifiesAllButtons() {
        // White top quarter over dark lower body -> top strip must still evaluate as bright (> 0.50).
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val splitBitmap = IntArray(40) { idx -> if (idx < 10) white else black }
        val splitTopLuma = computeTopStripLuminance(splitBitmap, width = 4, height = 10, topFraction = 0.22f)
        assertTrue(isTopBackdropLight(splitTopLuma))

        // Dark top strip over bright bottom -> top strip evaluates as dark (< 0.50).
        val inverseBitmap = IntArray(40) { idx -> if (idx < 10) black else white }
        val inverseTopLuma = computeTopStripLuminance(inverseBitmap, width = 4, height = 10, topFraction = 0.22f)
        assertFalse(isTopBackdropLight(inverseTopLuma))

        // Narrow Soak over bright photo uses dark `#16141B` header ink regardless of dark app theme.
        assertTrue(resolveHeaderUseDarkInk(FolioTier.Narrow, LyricBackgroundStyle.Soak, FolioPlayerDisplayState.Lyrics, themeIsLight = false, topStripLuminance = 0.85f))
        assertFalse(resolveHeaderUseDarkInk(FolioTier.Narrow, LyricBackgroundStyle.Soak, FolioPlayerDisplayState.Lyrics, themeIsLight = true, topStripLuminance = 0.12f))
        // Wide car / Flow follow theme surface.
        assertFalse(resolveHeaderUseDarkInk(FolioTier.Wide, LyricBackgroundStyle.Soak, FolioPlayerDisplayState.Lyrics, themeIsLight = false, topStripLuminance = 0.90f))

        val darkInkScheme = resolveHeaderColorScheme(useDarkInk = true)
        assertEquals(Color(0xFF16141B), darkInkScheme.fg)
        assertTrue(darkInkScheme.posterActiveBg.alpha in 0.08f..0.20f)
        assertTrue(darkInkScheme.glassBg.alpha in 0.08f..0.20f)

        val lightInkScheme = resolveHeaderColorScheme(useDarkInk = false)
        assertEquals(Color.White, lightInkScheme.fg)
        assertTrue(lightInkScheme.posterActiveBg.alpha in 0.10f..0.24f)
    }

    @Test
    fun motionTokensAndPosterCharacterLightingFollowH5TimingAndReducedMotion() {
        assertEquals(280, FolioPlayerMotionTokens.CTL_OPACITY_MS)
        assertEquals(350, FolioPlayerMotionTokens.CTL_TRANSLATE_MS)
        assertEquals(14f, FolioPlayerMotionTokens.CTL_OFFSET_DP, 0.01f)
        assertEquals(800, FolioPlayerMotionTokens.POSTER_LIQ_IN_MAIN_MS)
        assertEquals(700, FolioPlayerMotionTokens.POSTER_LIQ_IN_SUB_MS)
        assertEquals(600, FolioPlayerMotionTokens.POSTER_LIQ_OUT_MS)

        assertTrue(isReducedMotionEnabled(0f, animatorsEnabled = true))
        assertTrue(isReducedMotionEnabled(1f, animatorsEnabled = false))
        assertFalse(isReducedMotionEnabled(1f, animatorsEnabled = true))

        // Character starts illuminating right at its timestamp (no delay) and reaches 1f after 200ms.
        assertEquals(0.40f, posterCharAlpha(999L, charStartMs = 1000L), 0.01f)
        assertEquals(0.40f, posterCharAlpha(1000L, charStartMs = 1000L), 0.01f)
        assertEquals(0.70f, posterCharAlpha(1100L, charStartMs = 1000L), 0.01f)
        assertEquals(1.00f, posterCharAlpha(1200L, charStartMs = 1000L), 0.01f)

        // Reduced motion lights immediately at timestamp.
        assertEquals(1.00f, posterCharAlpha(1000L, charStartMs = 1000L, reducedMotion = true), 0.01f)
    }

    @Test
    fun collapsedCapsuleArtworkAppearsOnlyInPortraitNonPosterModes() {
        val portraitTiers = setOf(FolioTier.Narrow, FolioTier.Medium)
        val states = listOf(
            FolioPlayerDisplayState.Lyrics,
            FolioPlayerDisplayState.Poster,
            FolioPlayerDisplayState.Queue,
            FolioPlayerDisplayState.None,
        )
        for (tier in FolioTier.entries) {
            for (state in states) {
                val expected = tier in portraitTiers && state != FolioPlayerDisplayState.Poster
                assertEquals(
                    "tier=$tier state=$state",
                    expected,
                    showCollapsedCapsuleArtwork(tier, state),
                )
            }
        }
    }

    @Test
    fun collapsedLayoutResolvesMix5PerTierAndDisplayState() {
        val allStates = listOf(
            FolioPlayerDisplayState.Lyrics,
            FolioPlayerDisplayState.Poster,
            FolioPlayerDisplayState.Queue,
            FolioPlayerDisplayState.None,
        )
        for (tier in listOf(FolioTier.Narrow, FolioTier.Short, FolioTier.Ultrawide)) {
            for (state in allStates) {
                assertEquals(
                    "tier=$tier state=$state",
                    FolioCollapsedLayout.Capsule,
                    resolveCollapsedLayout(tier, state),
                )
            }
        }
        for (state in allStates) {
            assertEquals(
                "Medium state=$state",
                FolioCollapsedLayout.CircleButtonAndHairline,
                resolveCollapsedLayout(FolioTier.Medium, state),
            )
        }
        assertEquals(
            FolioCollapsedLayout.UnderCoverInfo,
            resolveCollapsedLayout(FolioTier.Wide, FolioPlayerDisplayState.Lyrics),
        )
        for (state in listOf(FolioPlayerDisplayState.Poster, FolioPlayerDisplayState.Queue, FolioPlayerDisplayState.None)) {
            assertEquals(
                "Wide state=$state",
                FolioCollapsedLayout.CircleButtonAndHairline,
                resolveCollapsedLayout(FolioTier.Wide, state),
            )
        }
    }

    @Test
    fun collapsedHeaderTitleModeFollowsMediumAndWideRules() {
        assertEquals(
            FolioCollapsedHeaderTitleMode.LargeWithCover,
            resolveCollapsedHeaderTitleMode(FolioTier.Medium, FolioPlayerDisplayState.Lyrics),
        )
        assertEquals(
            FolioCollapsedHeaderTitleMode.Compact,
            resolveCollapsedHeaderTitleMode(FolioTier.Medium, FolioPlayerDisplayState.Poster),
        )
        assertEquals(
            FolioCollapsedHeaderTitleMode.Compact,
            resolveCollapsedHeaderTitleMode(FolioTier.Medium, FolioPlayerDisplayState.None),
        )
        assertEquals(
            FolioCollapsedHeaderTitleMode.None,
            resolveCollapsedHeaderTitleMode(FolioTier.Wide, FolioPlayerDisplayState.Lyrics),
        )
        assertEquals(
            FolioCollapsedHeaderTitleMode.Compact,
            resolveCollapsedHeaderTitleMode(FolioTier.Wide, FolioPlayerDisplayState.Poster),
        )
        assertEquals(
            FolioCollapsedHeaderTitleMode.Compact,
            resolveCollapsedHeaderTitleMode(FolioTier.Wide, FolioPlayerDisplayState.Queue),
        )
        for (tier in listOf(FolioTier.Narrow, FolioTier.Short, FolioTier.Ultrawide)) {
            for (state in FolioPlayerDisplayState.entries) {
                assertEquals(
                    FolioCollapsedHeaderTitleMode.None,
                    resolveCollapsedHeaderTitleMode(tier, state),
                )
            }
        }
    }

    @Test
    fun collapsedCapsuleStartPaddingIs20DpWhenArtworkHidden() {
        assertEquals(10f, collapsedCapsuleStartPaddingDp(showArtwork = true).value, 0.01f)
        assertEquals(20f, collapsedCapsuleStartPaddingDp(showArtwork = false).value, 0.01f)
    }

    @Test
    fun subtitleFormatsArtistAndAlbumWithEmDashOrOmitsSeparatorWhenBlank() {
        assertEquals("Artist — Album", formatFolioPlayerSubtitle("Artist", "Album"))
        assertEquals("Artist — Album", formatFolioPlayerSubtitle("  Artist  ", " Album "))
        assertEquals("Artist", formatFolioPlayerSubtitle("Artist", ""))
        assertEquals("Artist", formatFolioPlayerSubtitle("Artist", "   "))
        assertEquals("Album", formatFolioPlayerSubtitle("", "Album"))
        assertEquals("", formatFolioPlayerSubtitle("  ", ""))
    }
}
