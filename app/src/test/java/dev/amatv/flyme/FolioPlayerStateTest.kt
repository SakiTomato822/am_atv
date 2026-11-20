package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.LyricsLoadPhase
import dev.amatv.core.LyricsState
import dev.amatv.core.PlaybackState
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricAgentType
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
        assertFalse(alwaysOpenControls(FolioTier.Wide, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
        assertFalse(alwaysOpenControls(FolioTier.Medium, LyricBackgroundStyle.Flow, hasLyricsOrPending = true, FolioPlayerDisplayState.Lyrics))
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
    fun queueChoiceStartsOffOnColdStart() {
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
        val group = longLine.copy(agentId = "v3", agentType = LyricAgentType.Group)
        val chorusSongSize = computeSongPosterFontSize(listOf(shortLine, group), availableWidthDp = 360f, baseSp = 40f)
        assertTrue(mixedSongSize < shortOnlySize)
        assertEquals(40f, mixedSongSize, 0.01f)
        assertEquals(mixedSongSize, chorusSongSize, 0.01f)
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
        assertEquals(56f * 0.40f, carTypo.translationSp, 0.01f)
        assertEquals(56f * 0.46f, carTypo.nextSp, 0.01f)

        // Short-lined song on Wide grows up to 1.3x (--pfs * 1.3 = 72.8sp), and translation tracks 0.4x mainSp.
        val shortOnlyCar = listOf(LyricLine(words = listOf(LyricWord("慢一点", 0L, 1000L))))
        val shortCarTypo = posterTypographyFor(shortOnlyCar, availableWidthDp = 576f, baseSp = carSpec.pfs.value)
        assertEquals(56f * 1.3f, shortCarTypo.mainSp, 0.01f)
        assertEquals(shortCarTypo.mainSp * 0.40f, shortCarTypo.translationSp, 0.01f)

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
    fun duetPosterColorsBySingerAndKeepsGroupInkPrimary() {
        val chorus = LyricLine(
            words = listOf(LyricWord("不该就这样离开", 16_000L, 20_000L)),
            agentId = "v1000",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            agentType = LyricAgentType.Group,
        )
        val namedGroupIdButPerson = LyricLine(
            words = listOf(LyricWord("副歌独唱", 18_000L, 19_000L)),
            agentId = "group_lead",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            agentType = LyricAgentType.Person,
        )
        val v1000Character = LyricLine(
            words = listOf(LyricWord("角色声线", 19_000L, 19_500L)),
            agentId = "v1000",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            agentType = LyricAgentType.Character,
        )
        val anyIdGroup = LyricLine(
            words = listOf(LyricWord("大合唱", 19_500L, 20_000L)),
            agentId = "v3",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            agentType = LyricAgentType.Group,
        )
        val unattributed = LyricLine(
            words = listOf(LyricWord("最后一句", 20_000L, 24_000L)),
            agentId = null,
            isOppositeAgent = true,
            isMultiAgentSong = true,
            agentType = LyricAgentType.Person,
        )

        assertFalse(isOppositeSingerLine(chorus))
        assertTrue(isOppositeSingerLine(namedGroupIdButPerson))
        assertTrue(isOppositeSingerLine(v1000Character))
        assertFalse(isOppositeSingerLine(anyIdGroup))
        assertFalse(isOppositeSingerLine(unattributed))

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
    fun motionTokensFollowH5TimingAndReducedMotion() {
        assertEquals(280, FolioPlayerMotionTokens.CTL_OPACITY_MS)
        assertEquals(320, FolioPlayerMotionTokens.CTL_TRANSLATE_MS)
        assertEquals(14f, FolioPlayerMotionTokens.CTL_OFFSET_DP, 0.01f)
        assertEquals(240, FolioPlayerMotionTokens.MENU_OPEN_MS)
        assertEquals(180, FolioPlayerMotionTokens.MENU_CLOSE_MS)
        assertEquals(240, FolioPlayerMotionTokens.QUEUE_POPOVER_OPEN_MS)
        assertEquals(180, FolioPlayerMotionTokens.QUEUE_POPOVER_CLOSE_MS)
        assertEquals(160, FolioPlayerMotionTokens.TOGGLE_PILL_MS)
        assertEquals(200, FolioPlayerMotionTokens.PLAY_ICON_SWITCH_MS)
        assertEquals(140, FolioPlayerMotionTokens.PLAY_ICON_SWITCH_OUT_MS)
        assertEquals(800, FolioPlayerMotionTokens.POSTER_LIQ_IN_MAIN_MS)
        assertEquals(700, FolioPlayerMotionTokens.POSTER_LIQ_IN_SUB_MS)
        assertEquals(600, FolioPlayerMotionTokens.POSTER_LIQ_OUT_MS)

        assertTrue(isReducedMotionEnabled(0f, animatorsEnabled = true))
        assertTrue(isReducedMotionEnabled(1f, animatorsEnabled = false))
        assertFalse(isReducedMotionEnabled(1f, animatorsEnabled = true))

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

    @Test
    fun effectiveBackgroundStyleFollowsDisplayModeAndPreservesQueueEntryStyle() {
        // Poster always forces Soak; Lyrics always forces Flow, regardless of settings.
        assertEquals(
            LyricBackgroundStyle.Soak,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.Poster,
                settingsStyle = LyricBackgroundStyle.Flow,
            ),
        )
        assertEquals(
            LyricBackgroundStyle.Flow,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.Lyrics,
                settingsStyle = LyricBackgroundStyle.Soak,
            ),
        )
        // Queue preserves the style entered from (Soak from Poster, Flow from Lyrics).
        assertEquals(
            LyricBackgroundStyle.Soak,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.Queue,
                settingsStyle = LyricBackgroundStyle.Flow,
                lastContentBackgroundStyle = LyricBackgroundStyle.Soak,
            ),
        )
        assertEquals(
            LyricBackgroundStyle.Flow,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.Queue,
                settingsStyle = LyricBackgroundStyle.Soak,
                lastContentBackgroundStyle = LyricBackgroundStyle.Flow,
            ),
        )
        // None uses settings style.
        assertEquals(
            LyricBackgroundStyle.Soak,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.None,
                settingsStyle = LyricBackgroundStyle.Soak,
            ),
        )
        assertEquals(
            LyricBackgroundStyle.Flow,
            resolveEffectiveBackgroundStyle(
                displayState = FolioPlayerDisplayState.None,
                settingsStyle = LyricBackgroundStyle.Flow,
            ),
        )
    }

    @Test
    fun posterPreferredFollowsPersistedLyricStyleAndTtToggleRoundTrips() {
        assertTrue(resolveEffectivePosterPreferred(LyricBackgroundStyle.Soak))
        assertFalse(resolveEffectivePosterPreferred(LyricBackgroundStyle.Flow))

        // Tt persists Soak <-> Flow directly into SettingsStore instead of a volatile process override.
        assertEquals(LyricBackgroundStyle.Flow, nextLyricStyleForPosterToggle(currentlyPosterPreferred = true))
        assertEquals(LyricBackgroundStyle.Soak, nextLyricStyleForPosterToggle(currentlyPosterPreferred = false))
    }

    @Test
    fun wideCarControlsWidthAndExpandedCoverShrinkClearance() {
        val collapsedCover = computeWideCarCollapsedCoverSizeDp(leftColumnWidthDp = 604.dp, bodyHeightDp = 594.dp)
        assertEquals(400f, collapsedCover.value, 0.01f)
        assertEquals(464f, computeWideCarControlsWidthDp(collapsedCover).value, 0.01f)

        // On 594dp body height and 226dp expanded card, cover shrinks so bottom clears card top by >= 24dp.
        val expandedCover = computeWideCarExpandedCoverSizeDp(
            collapsedCoverDp = collapsedCover,
            bodyHeightDp = 594.dp,
            controlsCardHeightDp = 226.dp,
        )
        val coverBottom = 8f + expandedCover.value
        val cardTop = 594f - 4f - 226f
        assertTrue("coverBottom=$coverBottom cardTop=$cardTop", cardTop - coverBottom >= 24f - 0.01f)
        assertTrue(expandedCover < collapsedCover)

        // Taller screens (>= 672dp body height) keep full 400dp cover without shrinking.
        val tallExpandedCover = computeWideCarExpandedCoverSizeDp(
            collapsedCoverDp = collapsedCover,
            bodyHeightDp = 700.dp,
            controlsCardHeightDp = 226.dp,
        )
        assertEquals(400f, tallExpandedCover.value, 0.01f)
    }

    @Test
    fun posterLyricRolesMatchUpcomingAlphasAndScaleRatios() {
        assertEquals(listOf(0.50f, 0.32f, 0.18f), posterUpcomingAlphas)
        assertEquals(0f, posterRoleAlpha(PosterLyricRole.ExitingPrevious, previousAlpha = 0.68f), 0.001f)
        assertEquals(0f, posterRoleAlpha(PosterLyricRole.EnteringBelow, previousAlpha = 0.68f), 0.001f)
        assertEquals(0.68f, posterRoleAlpha(PosterLyricRole.Previous, previousAlpha = 0.68f), 0.001f)
        assertEquals(1f, posterRoleAlpha(PosterLyricRole.Focus, previousAlpha = 0.68f), 0.001f)
        assertEquals(0.50f, posterRoleAlpha(PosterLyricRole.Upcoming0, previousAlpha = 0.68f), 0.001f)
        assertEquals(0.32f, posterRoleAlpha(PosterLyricRole.Upcoming1, previousAlpha = 0.68f), 0.001f)
        assertEquals(0.18f, posterRoleAlpha(PosterLyricRole.Upcoming2, previousAlpha = 0.68f), 0.001f)

        val typo = posterTypographyFor(
            lines = listOf(LyricLine(words = listOf(LyricWord("这是一句比较长的中文主唱歌词测试缩放比例", 0L, 1000L)))),
            availableWidthDp = 576f,
            baseSp = 56f,
        )
        assertEquals(1f, posterRoleScale(PosterLyricRole.Focus, typo), 0.001f)
        assertEquals(typo.prevSp / typo.mainSp, posterRoleScale(PosterLyricRole.Previous, typo), 0.001f)
        assertEquals(typo.nextSp / typo.mainSp, posterRoleScale(PosterLyricRole.Upcoming0, typo), 0.001f)
    }

    @Test
    fun miniWindowSupportAndButtonVisibilityOnlyOnCarFullScreen() {
        assertFalse(canSupportMiniWindow(hasFlymeWindowMode = false, isUiPreview = false))
        assertTrue(canSupportMiniWindow(hasFlymeWindowMode = true, isUiPreview = false))
        assertTrue(canSupportMiniWindow(hasFlymeWindowMode = false, isUiPreview = true))

        for (tier in FolioTier.entries) {
            assertFalse(shouldShowMiniWindowButton(canOpenMini = false, isMiniWindow = false, tier = tier))
            // Inside the mini-window itself, the mini/full-screen button never appears.
            assertFalse(shouldShowMiniWindowButton(canOpenMini = true, isMiniWindow = true, tier = tier))
        }

        val fullScreenTiersWithButton = setOf(FolioTier.Wide, FolioTier.Ultrawide, FolioTier.Short)
        for (tier in FolioTier.entries) {
            assertEquals(
                "tier=$tier",
                tier in fullScreenTiersWithButton,
                shouldShowMiniWindowButton(canOpenMini = true, isMiniWindow = false, tier = tier),
            )
        }
    }

    @Test
    fun narrowTierMovesAddAndFavoriteToControlsCardWhileOthersKeepInHeader() {
        for (tier in FolioTier.entries) {
            val expected = if (tier == FolioTier.Narrow) {
                FolioTrackActionLocation.ControlsCard
            } else {
                FolioTrackActionLocation.Header
            }
            assertEquals("tier=$tier", expected, trackActionLocationForTier(tier))
        }
        // Narrow still keeps the song title in the top header when controls are expanded.
        assertTrue(showHeaderTitle(FolioTier.Narrow, controlsExpanded = true, displayState = FolioPlayerDisplayState.Poster))
        assertFalse(showHeaderTitle(FolioTier.Narrow, controlsExpanded = false, displayState = FolioPlayerDisplayState.Poster))
    }

    @Test
    fun posterRoleLayoutWidthFitsEachRoleAtItsTargetScale() {
        val typo = posterTypographyFor(
            lines = listOf(LyricLine(words = listOf(LyricWord("这是一句中等长度的主唱歌词", 0L, 1000L)))),
            availableWidthDp = 500f,
            baseSp = 40f,
        )
        val panelWidthPx = 640
        val focusWidth = computePosterRoleLayoutWidthPx(panelWidthPx, posterRoleScale(PosterLyricRole.Focus, typo))
        val prevWidth = computePosterRoleLayoutWidthPx(panelWidthPx, posterRoleScale(PosterLyricRole.Previous, typo))
        val nextWidth = computePosterRoleLayoutWidthPx(panelWidthPx, posterRoleScale(PosterLyricRole.Upcoming0, typo))

        assertEquals(panelWidthPx, focusWidth)
        assertTrue("prevWidth=$prevWidth should exceed panelWidth=$panelWidthPx", prevWidth > panelWidthPx)
        assertTrue("nextWidth=$nextWidth should exceed panelWidth=$panelWidthPx", nextWidth > panelWidthPx)
        assertEquals(panelWidthPx.toFloat(), nextWidth * posterRoleScale(PosterLyricRole.Upcoming0, typo), 2f)
    }

    @Test
    fun lyricsDisplayAvailabilityActiveStateAndPlacement() {
        assertFalse(isLyricDisplayActive(translationEnabled = false, pronunciationEnabled = false))
        assertTrue(isLyricDisplayActive(translationEnabled = true, pronunciationEnabled = false))
        assertTrue(isLyricDisplayActive(translationEnabled = false, pronunciationEnabled = true))
        assertTrue(isLyricDisplayActive(translationEnabled = true, pronunciationEnabled = true))

        val linePronLine = LyricLine(
            words = listOf(LyricWord("君の名は", 0L, 1000L)),
            translation = "你的名字",
            pronunciationText = "kimi no na wa",
        )
        val wordPronLine = LyricLine(
            words = listOf(
                LyricWord("桜", 0L, 500L, pronunciationText = "sakura"),
                LyricWord("舞う", 500L, 1000L, pronunciationText = "mau"),
            ),
        )
        val plainLine = LyricLine(words = listOf(LyricWord("Plain", 0L, 1000L)))

        assertEquals("kimi no na wa", posterPronunciationText(linePronLine))
        assertEquals("sakura mau", posterPronunciationText(wordPronLine))

        assertFalse(lyricTranslationAvailable(listOf(linePronLine), enhancementsReady = false))
        assertTrue(lyricTranslationAvailable(listOf(linePronLine), enhancementsReady = true))
        assertFalse(lyricTranslationAvailable(listOf(plainLine), enhancementsReady = true))

        assertFalse(lyricPronunciationAvailable(listOf(wordPronLine), enhancementsReady = false))
        assertTrue(lyricPronunciationAvailable(listOf(wordPronLine), enhancementsReady = true))
        assertFalse(lyricPronunciationAvailable(listOf(plainLine), enhancementsReady = true))

        assertEquals(
            FolioLyricsOptionStatus.Hint,
            resolveLyricsOptionStatus(optionEnabled = false, optionAvailable = false, enhancementsReady = true, enhancementsFailed = false),
        )
        assertEquals(
            FolioLyricsOptionStatus.LoadFailed,
            resolveLyricsOptionStatus(optionEnabled = true, optionAvailable = false, enhancementsReady = false, enhancementsFailed = true),
        )
        assertEquals(
            FolioLyricsOptionStatus.Loading,
            resolveLyricsOptionStatus(optionEnabled = true, optionAvailable = false, enhancementsReady = false, enhancementsFailed = false),
        )
        assertEquals(
            FolioLyricsOptionStatus.Unavailable,
            resolveLyricsOptionStatus(optionEnabled = true, optionAvailable = false, enhancementsReady = true, enhancementsFailed = false),
        )
        assertEquals(
            FolioLyricsOptionStatus.Hint,
            resolveLyricsOptionStatus(optionEnabled = true, optionAvailable = true, enhancementsReady = true, enhancementsFailed = false),
        )

        assertEquals(FolioLyricsSettingsPlacement.AnchoredPopover, lyricsSettingsPlacementForTier(FolioTier.Wide))
        assertEquals(FolioLyricsSettingsPlacement.AnchoredPopover, lyricsSettingsPlacementForTier(FolioTier.Ultrawide))
        assertEquals(FolioLyricsSettingsPlacement.BottomSheet, lyricsSettingsPlacementForTier(FolioTier.Narrow))
        assertEquals(FolioLyricsSettingsPlacement.BottomSheet, lyricsSettingsPlacementForTier(FolioTier.Short))
        assertEquals(FolioLyricsSettingsPlacement.BottomSheet, lyricsSettingsPlacementForTier(FolioTier.Medium))
    }

    @Test
    fun queueControlsAvailabilityAndSnapshotRepeatShuffleState() {
        // Normal queue with upNext items enables all three controls.
        val normalFull = resolveQueueControlAvailability(isStation = false, upNextCount = 2, autoplayCount = 0)
        assertTrue(normalFull.canSetRepeat)
        assertTrue(normalFull.canSetShuffle)
        assertTrue(normalFull.canClearQueue)

        // Normal queue with only autoplay recommendations also allows clearing upcoming.
        val normalAutoplayOnly = resolveQueueControlAvailability(isStation = false, upNextCount = 0, autoplayCount = 3)
        assertTrue(normalAutoplayOnly.canSetRepeat)
        assertTrue(normalAutoplayOnly.canSetShuffle)
        assertTrue(normalAutoplayOnly.canClearQueue)

        // Normal queue with nothing upcoming disables Clear but keeps Repeat & Shuffle enabled.
        val normalEmptyUpcoming = resolveQueueControlAvailability(isStation = false, upNextCount = 0, autoplayCount = 0)
        assertTrue(normalEmptyUpcoming.canSetRepeat)
        assertTrue(normalEmptyUpcoming.canSetShuffle)
        assertFalse(normalEmptyUpcoming.canClearQueue)

        // Station queues disable all three mode controls.
        val station = resolveQueueControlAvailability(isStation = true, upNextCount = 5, autoplayCount = 5)
        assertFalse(station.canSetRepeat)
        assertFalse(station.canSetShuffle)
        assertFalse(station.canClearQueue)

        val current = QueueEntry(1L, sampleTrack("t1", "Song 1"), EntryOrigin.Context)
        val basePlayback = PlaybackState(
            track = current.track,
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
        val snap = buildFolioQueueSnapshot(
            queueState = QueueState(
                current = current,
                repeat = PlaybackRepeatMode.One,
                shuffle = true,
            ),
            playbackState = basePlayback,
        )
        assertEquals(PlaybackRepeatMode.One, snap.repeatMode)
        assertTrue(snap.shuffleEnabled)

        val stationTrack = sampleTrack("st1", "Radio").copy(catalogKind = CatalogKind.Station)
        val stationEntry = QueueEntry(2L, stationTrack, EntryOrigin.Context)
        val stationSnap = buildFolioQueueSnapshot(
            queueState = QueueState(
                current = stationEntry,
                repeat = PlaybackRepeatMode.All,
                shuffle = true,
            ),
            playbackState = basePlayback,
        )
        assertTrue(stationSnap.isStation)
        assertEquals(PlaybackRepeatMode.Off, stationSnap.repeatMode)
        assertFalse(stationSnap.shuffleEnabled)
    }

    @Test
    fun lyricOffsetClampsRangeAndFormatsAdvanceDelayLabels() {
        assertEquals(-3000L, FOLIO_LYRIC_OFFSET_MIN_MS)
        assertEquals(3000L, FOLIO_LYRIC_OFFSET_MAX_MS)

        assertEquals(200L, adjustFolioLyricOffsetMs(currentOffsetMs = 100L, deltaMs = 100L))
        assertEquals(-100L, adjustFolioLyricOffsetMs(currentOffsetMs = -50L, deltaMs = -50L))
        assertEquals(3000L, adjustFolioLyricOffsetMs(currentOffsetMs = 2950L, deltaMs = 100L))
        assertEquals(-3000L, adjustFolioLyricOffsetMs(currentOffsetMs = -2950L, deltaMs = -100L))

        val advanceState = resolveFolioLyricOffsetState(200L)
        assertEquals(FolioLyricOffsetDirection.Advance, advanceState.direction)
        assertEquals(200L, advanceState.magnitudeMs)

        val delayState = resolveFolioLyricOffsetState(-100L)
        assertEquals(FolioLyricOffsetDirection.Delay, delayState.direction)
        assertEquals(100L, delayState.magnitudeMs)

        val zeroState = resolveFolioLyricOffsetState(0L)
        assertEquals(FolioLyricOffsetDirection.Unadjusted, zeroState.direction)
        assertEquals(0L, zeroState.magnitudeMs)

        val advanceFmt = "歌词提前 %1\$d 毫秒"
        val delayFmt = "歌词延后 %1\$d 毫秒"
        val unadjusted = "未调整"
        assertEquals("歌词提前 200 毫秒", formatFolioLyricOffsetLabel(200L, advanceFmt, delayFmt, unadjusted))
        assertEquals("歌词延后 100 毫秒", formatFolioLyricOffsetLabel(-100L, advanceFmt, delayFmt, unadjusted))
        assertEquals("未调整", formatFolioLyricOffsetLabel(0L, advanceFmt, delayFmt, unadjusted))
    }

    @Test
    fun wideQueueStartPaddingAvoidsExpandedControlsAndRestoresOnCollapse() {
        // Wide (e) expanded: 4dp card start offset + 464dp card width + 24dp gap = 492dp.
        assertEquals(
            492f,
            computeWideQueueStartPaddingDp(
                tier = FolioTier.Wide,
                controlsExpanded = true,
                controlsCardWidthDp = 464.dp,
            ).value,
            0.01f,
        )

        // Wide (e) collapsed: full width (0dp start padding).
        assertEquals(
            0f,
            computeWideQueueStartPaddingDp(
                tier = FolioTier.Wide,
                controlsExpanded = false,
                controlsCardWidthDp = 464.dp,
            ).value,
            0.01f,
        )

        // Other tiers (including Ultrawide with 3-column layout and Short) keep 0dp.
        for (tier in FolioTier.entries) {
            if (tier == FolioTier.Wide) continue
            assertEquals(
                "tier=$tier",
                0f,
                computeWideQueueStartPaddingDp(
                    tier = tier,
                    controlsExpanded = true,
                    controlsCardWidthDp = 464.dp,
                ).value,
                0.01f,
            )
        }
    }

    @Test
    fun pressFeedbackResolvesRestrainedScaleAndOverlay() {
        assertEquals(
            FolioPressDefaults.SCALE_CARD,
            resolveFolioPressScale(
                pressed = true,
                enabled = true,
                compact = false,
                lowPerformance = false,
                reducedMotion = false,
            ),
            0.001f,
        )
        assertEquals(
            FolioPressDefaults.SCALE_COMPACT,
            resolveFolioPressScale(
                pressed = true,
                enabled = true,
                compact = true,
                lowPerformance = false,
                reducedMotion = false,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            resolveFolioPressScale(
                pressed = true,
                enabled = true,
                compact = true,
                lowPerformance = true,
                reducedMotion = false,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            resolveFolioPressScale(
                pressed = true,
                enabled = true,
                compact = false,
                lowPerformance = false,
                reducedMotion = true,
            ),
            0.001f,
        )
        assertEquals(
            FolioPressDefaults.LIGHT_OVERLAY_ALPHA,
            resolveFolioPressOverlayAlpha(pressed = true, enabled = true, isLight = true),
            0.001f,
        )
        assertEquals(
            FolioPressDefaults.DARK_OVERLAY_ALPHA,
            resolveFolioPressOverlayAlpha(pressed = true, enabled = true, isLight = false),
            0.001f,
        )
        assertEquals(0f, resolveFolioPressOverlayAlpha(pressed = false, enabled = true, isLight = true), 0.001f)
    }

    @Test
    fun anchorTransformOriginAndLightTopScrimResolveAccurately() {
        val origin = computeAnchorTransformOrigin(
            anchorCenterX = 360f,
            anchorCenterY = 480f,
            panelWidth = 400f,
            panelHeight = 400f,
        )
        assertEquals(0.90f, origin.pivotFractionX, 0.01f)
        assertEquals(1.20f, origin.pivotFractionY, 0.01f)
        assertEquals(
            TransformOrigin.Center,
            computeAnchorTransformOrigin(0f, 0f, 0f, 0f),
        )

        val lightBase = Color(0xFFF3EFF6)
        val darkBg = Color(0xFF16151C)
        assertEquals(
            Color.Transparent,
            resolvePosterTopScrimColor(
                isLight = true,
                useDarkHeaderInk = false,
                lightBaseColor = lightBase,
                darkBgColor = darkBg,
            ),
        )
        assertEquals(
            lightBase,
            resolvePosterTopScrimColor(
                isLight = true,
                useDarkHeaderInk = true,
                lightBaseColor = lightBase,
                darkBgColor = darkBg,
            ),
        )
        assertEquals(
            darkBg,
            resolvePosterTopScrimColor(
                isLight = false,
                useDarkHeaderInk = false,
                lightBaseColor = lightBase,
                darkBgColor = darkBg,
            ),
        )
    }

    @Test
    fun lightThemeGlassAlphaReducedForTiersAAndBWhileCarAndDarkStayUnchanged() {
        val lightA = resolveFolioGlassColors(FolioGlassTier.FullA, isLight = true)
        val lightB = resolveFolioGlassColors(FolioGlassTier.BlurB, isLight = true)
        val lightC = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight = true)
        assertTrue(lightA.fill.alpha in 0.22f..0.28f)
        assertTrue(lightB.fill.alpha in 0.22f..0.28f)
        assertEquals(0.55f, lightC.fill.alpha, 0.001f)

        val darkA = resolveFolioGlassColors(FolioGlassTier.FullA, isLight = false)
        val darkC = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight = false)
        assertEquals(0.35f, darkA.fill.alpha, 0.001f)
        assertEquals(0.45f, darkC.fill.alpha, 0.001f)
    }

    @Test
    fun flowLandscapeCoverLayoutSizesAndCentersAcrossPhoneTabletAndCar() {
        // 1. Phone landscape (Short tier: 850x393 dp, collapsed capsule 64dp)
        assertEquals(FolioTier.Short, tierOf(850f, 393f))
        val phoneCollapsed = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = 336.dp,
            leftColumnHeightDp = 349.dp,
            availableHeightAboveControlsDp = 237.dp,
            minBaselineCoverDp = 120.dp,
            minVisibleCoverDp = 96.dp,
        )
        assertTrue(phoneCollapsed.showCover)
        assertEquals(209.4f, phoneCollapsed.coverSizeDp.value, 0.1f)
        assertTrue(phoneCollapsed.coverSizeDp.value >= 120f)
        assertTrue(phoneCollapsed.coverSizeDp.value <= 237f)

        // Expanded card on compact phone landscape leaves <96dp -> hide cover cleanly to prevent overlap
        val phoneExpanded = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = 336.dp,
            leftColumnHeightDp = 349.dp,
            availableHeightAboveControlsDp = 75.dp,
            minBaselineCoverDp = 120.dp,
            minVisibleCoverDp = 96.dp,
        )
        assertFalse(phoneExpanded.showCover)
        assertEquals(0f, phoneExpanded.coverSizeDp.value, 0.01f)

        // 2. Tablet landscape (Wide tier: 1280x800 dp, bodyHeight ~ 692dp, leftColumn inner ~ 568dp)
        assertEquals(FolioTier.Wide, tierOf(1280f, 800f))
        val tabletBaseline = computeWideCarCollapsedCoverSizeDp(leftColumnWidthDp = 604.dp, bodyHeightDp = 692.dp)
        assertEquals(400f, tabletBaseline.value, 0.01f)
        val tabletCollapsed = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = 568.dp,
            leftColumnHeightDp = 692.dp,
            availableHeightAboveControlsDp = 558.dp,
            minBaselineCoverDp = tabletBaseline,
        )
        assertTrue(tabletCollapsed.showCover)
        assertEquals(415.2f, tabletCollapsed.coverSizeDp.value, 0.1f)
        assertTrue(tabletCollapsed.coverSizeDp.value >= tabletBaseline.value)

        // 3. Car head unit (1920x1080 @ 160 dpi -> 1280x720 dp rendered at 1.5 density, Wide tier)
        val (carW, carH) = folioLayoutDp(1920f, 1080f, 1f)
        assertEquals(1280f, carW, 0.01f)
        assertEquals(720f, carH, 0.01f)
        assertEquals(FolioTier.Wide, tierOf(carW, carH))
        val carBodyHeight = 594.dp
        val carBaseline = computeWideCarCollapsedCoverSizeDp(leftColumnWidthDp = 604.dp, bodyHeightDp = carBodyHeight)
        assertEquals(400f, carBaseline.value, 0.01f)
        val carCollapsed = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = 568.dp,
            leftColumnHeightDp = carBodyHeight,
            availableHeightAboveControlsDp = 460.dp,
            minBaselineCoverDp = carBaseline,
        )
        assertTrue(carCollapsed.showCover)
        // Must not shrink below existing 400dp baseline on car even though 594 * 0.6 = 356.4dp
        assertEquals(400f, carCollapsed.coverSizeDp.value, 0.01f)

        val carExpanded = computeFlowLandscapeCoverLayout(
            leftColumnWidthDp = 568.dp,
            leftColumnHeightDp = carBodyHeight,
            availableHeightAboveControlsDp = 340.dp,
            minBaselineCoverDp = carBaseline,
        )
        assertTrue(carExpanded.showCover)
        assertEquals(340f, carExpanded.coverSizeDp.value, 0.01f)
    }

    @Test
    fun marqueeDefaultsAndConditionsRespectReducedMotionAndLowPerformance() {
        assertEquals(1_500, FolioMarqueeDefaults.INITIAL_DELAY_MS)
        assertEquals(1_500, FolioMarqueeDefaults.REPEAT_DELAY_MS)
        assertEquals(30f, FolioMarqueeDefaults.VELOCITY.value, 0.01f)
        assertTrue(FolioMarqueeDefaults.EDGE_FADE_WIDTH.value >= 8f)

        assertTrue(shouldRunFolioMarquee(reducedMotion = false, lowPerformance = false, text = "Fortnight"))
        assertFalse(shouldRunFolioMarquee(reducedMotion = true, lowPerformance = false, text = "Fortnight"))
        assertFalse(shouldRunFolioMarquee(reducedMotion = false, lowPerformance = true, text = "Fortnight"))
        assertFalse(shouldRunFolioMarquee(reducedMotion = false, lowPerformance = false, text = ""))
    }
}
