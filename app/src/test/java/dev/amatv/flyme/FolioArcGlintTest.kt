package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import dev.amatv.core.PlaybackState
import dev.amatv.core.isTrackLoading
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioArcGlintTest {

    private fun testTrack(
        id: String = "track-1",
        catalogId: String? = "cat-1",
        palette: List<Color> = emptyList(),
    ): Track = Track(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        durationMs = 210_000L,
        palette = palette,
        lyrics = emptyList(),
        catalogId = catalogId,
        catalogKind = CatalogKind.Song,
    )

    private fun testSnapshot(
        currentTrack: Track? = testTrack(),
        positionMs: Long = 0L,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        errorMessage: String? = null,
        gaplessAdvanceCatalogId: String? = null,
        isEnded: Boolean = false,
        isPrepared: Boolean = true,
    ): PlaybackSnapshot = PlaybackSnapshot(
        currentTrack = currentTrack,
        hasPlayerCurrentTrack = currentTrack != null,
        queue = listOfNotNull(currentTrack),
        positionMs = positionMs,
        durationMs = 210_000L,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        canSkipNext = true,
        canSkipPrevious = true,
        repeatMode = PlaybackRepeatMode.Off,
        shuffleEnabled = false,
        autoplayEnabled = false,
        canSetRepeat = true,
        canSetShuffle = true,
        canSetAutoplay = true,
        errorMessage = errorMessage,
        gaplessAdvanceCatalogId = gaplessAdvanceCatalogId,
        capturedAtElapsedRealtimeMs = 0L,
        isEnded = isEnded,
        isPrepared = isPrepared,
    )

    private fun testPlaybackState(
        track: Track = testTrack(),
        hasCurrentTrack: Boolean = true,
        isPlaying: Boolean = false,
        playWhenReady: Boolean = true,
        playbackPrepared: Boolean = false,
        playbackMessage: String? = null,
        snapshot: PlaybackSnapshot? = null,
    ): PlaybackState = PlaybackState(
        track = track,
        hasCurrentTrack = hasCurrentTrack,
        positionAnchorMs = 0L,
        playStartedRealtimeMs = 0L,
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        playbackPrepared = playbackPrepared,
        playbackMessage = playbackMessage,
        stationHistory = emptyList(),
        snapshot = snapshot,
        startupSessionLoaded = true,
    )

    @Test
    fun isTrackLoadingTruthTableCoversAllStagesAndExclusions() {
        val trackA = testTrack(id = "a", catalogId = "cat-a")
        val trackB = testTrack(id = "b", catalogId = "cat-b")

        // Stage 1: resolve / prepare in flight (playWhenReady && !playbackPrepared)
        assertTrue(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = false,
                snapshot = null,
            ).isTrackLoading,
        )

        // Stage 2: prepared and backing player reports buffering on current track
        assertTrue(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, isBuffering = true),
            ).isTrackLoading,
        )

        // Stage 3: stale ended / error snapshot from previous track while resolving new track
        assertTrue(
            testPlaybackState(
                track = trackB,
                playWhenReady = true,
                playbackPrepared = false,
                snapshot = testSnapshot(currentTrack = trackA, isEnded = true, errorMessage = "old error"),
            ).isTrackLoading,
        )

        // Stage 4: prepared at 0ms before audio playback actually begins (NativePlaybackIntent Phase.Ready gap)
        assertTrue(
            testPlaybackState(
                track = trackA,
                isPlaying = false,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, positionMs = 0L, isPlaying = false, isBuffering = false),
            ).isTrackLoading,
        )

        // Exclusion: mid-song external pause (e.g. audio focus loss after audio already started)
        assertFalse(
            testPlaybackState(
                track = trackA,
                isPlaying = false,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, positionMs = 15_000L, isPlaying = false, isBuffering = false),
            ).isTrackLoading,
        )

        // Exclusion: steady playback (prepared & not buffering)
        assertFalse(
            testPlaybackState(
                track = trackA,
                isPlaying = true,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, isPlaying = true, isBuffering = false),
            ).isTrackLoading,
        )

        // Exclusion: gapless adoption (prepared & playing without buffering)
        assertFalse(
            testPlaybackState(
                track = trackB,
                isPlaying = true,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(
                    currentTrack = trackB,
                    isPlaying = true,
                    isBuffering = false,
                    gaplessAdvanceCatalogId = "cat-b",
                ),
            ).isTrackLoading,
        )

        // Exclusion: paused track switch (playWhenReady == false)
        assertFalse(
            testPlaybackState(
                track = trackB,
                playWhenReady = false,
                playbackPrepared = false,
                snapshot = null,
            ).isTrackLoading,
        )

        // Exclusion: no current track
        assertFalse(
            testPlaybackState(
                track = trackA,
                hasCurrentTrack = false,
                playWhenReady = true,
                playbackPrepared = false,
            ).isTrackLoading,
        )

        // Exclusion: non-catalog track (catalogId == null)
        assertFalse(
            testPlaybackState(
                track = testTrack(id = "local", catalogId = null),
                playWhenReady = true,
                playbackPrepared = false,
            ).isTrackLoading,
        )

        // Exclusion: playback error message
        assertFalse(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = false,
                playbackMessage = "Network error",
            ).isTrackLoading,
        )

        // Exclusion: snapshot error on current track or global snapshot error (currentTrack == null)
        assertFalse(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = false,
                snapshot = testSnapshot(currentTrack = trackA, errorMessage = "DRM failed"),
            ).isTrackLoading,
        )
        assertFalse(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = false,
                snapshot = testSnapshot(currentTrack = null, errorMessage = "Not signed in"),
            ).isTrackLoading,
        )

        // Exclusion: ended current track
        assertFalse(
            testPlaybackState(
                track = trackA,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, isBuffering = true, isEnded = true),
            ).isTrackLoading,
        )

        // Exclusion: prepared track with stale buffering snapshot belonging to a different track
        assertFalse(
            testPlaybackState(
                track = trackB,
                playWhenReady = true,
                playbackPrepared = true,
                snapshot = testSnapshot(currentTrack = trackA, isBuffering = true),
            ).isTrackLoading,
        )
    }

    @Test
    fun loadingShimmerAntiFlashEnforcesDelayMinDisplayAndFadeOut() {
        val machine = FolioLoadingShimmerStateMachine()

        // Short load (<160ms) cancels with zero flash
        assertEquals(FolioLoadingShimmerPhase.PendingDelay, machine.update(0L, isTrackLoading = true, switchActive = false))
        assertEquals(0f, machine.alphaAt(0L), 0.001f)
        assertEquals(160L, machine.nextEventDelayMs(0L))

        assertEquals(FolioLoadingShimmerPhase.PendingDelay, machine.update(150L, isTrackLoading = true, switchActive = false))
        assertEquals(0f, machine.alphaAt(150L), 0.001f)
        assertEquals(10L, machine.nextEventDelayMs(150L))

        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(155L, isTrackLoading = false, switchActive = false))
        assertEquals(0f, machine.alphaAt(155L), 0.001f)
        assertNull(machine.nextEventDelayMs(155L))

        // Load reaching 160ms enters Active and holds for at least 480ms
        assertEquals(FolioLoadingShimmerPhase.PendingDelay, machine.update(1_000L, isTrackLoading = true, switchActive = false))
        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(1_160L, isTrackLoading = true, switchActive = false))
        assertEquals(1f, machine.alphaAt(1_160L), 0.001f)
        assertNull(machine.nextEventDelayMs(1_160L))

        // Loading ends 100ms after showing -> must hold remaining 380ms
        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(1_260L, isTrackLoading = false, switchActive = false))
        assertEquals(1f, machine.alphaAt(1_260L), 0.001f)
        assertEquals(380L, machine.nextEventDelayMs(1_260L))

        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(1_639L, isTrackLoading = false, switchActive = false))
        assertEquals(1L, machine.nextEventDelayMs(1_639L))

        // At 1_160 + 480 = 1_640ms -> enters FadingOut for 240ms
        assertEquals(FolioLoadingShimmerPhase.FadingOut, machine.update(1_640L, isTrackLoading = false, switchActive = false))
        assertEquals(1f, machine.alphaAt(1_640L), 0.001f)
        assertEquals(240L, machine.nextEventDelayMs(1_640L))

        val midAlpha = machine.alphaAt(1_760L)
        assertTrue("Expected mid fade-out alpha in (0, 1), was $midAlpha", midAlpha in 0.05f..0.95f)

        // At 1_640 + 240 = 1_880ms -> returns to Idle
        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(1_880L, isTrackLoading = false, switchActive = false))
        assertEquals(0f, machine.alphaAt(1_880L), 0.001f)

        // Re-entering loading mid-fade returns straight to Active without another 160ms wait
        machine.update(2_000L, isTrackLoading = true, switchActive = false)
        machine.update(2_160L, isTrackLoading = true, switchActive = false)
        machine.update(2_640L, isTrackLoading = false, switchActive = false)
        assertEquals(FolioLoadingShimmerPhase.FadingOut, machine.phase)
        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(2_720L, isTrackLoading = true, switchActive = false))
        assertEquals(1f, machine.alphaAt(2_720L), 0.001f)
    }

    @Test
    fun loadingShimmerNeverOverlapsTrackSwitchAndHandsOverImmediately() {
        val machine = FolioLoadingShimmerStateMachine()

        // While switch sweep is active, loading shimmer stays completely hidden (Idle / alpha 0)
        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(0L, isTrackLoading = true, switchActive = true))
        assertEquals(0f, machine.alphaAt(0L), 0.001f)
        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(500L, isTrackLoading = true, switchActive = true))
        assertEquals(0f, machine.alphaAt(500L), 0.001f)

        // When switch sweep ends while still loading, handover enters Active immediately (0ms delay)
        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(620L, isTrackLoading = true, switchActive = false))
        assertEquals(1f, machine.alphaAt(620L), 0.001f)

        // Minimum display time (480ms) still applies after handover
        assertEquals(FolioLoadingShimmerPhase.Active, machine.update(720L, isTrackLoading = false, switchActive = false))
        assertEquals(380L, machine.nextEventDelayMs(720L))

        // Starting a new track switch while shimmer is Active suppresses shimmer immediately
        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(800L, isTrackLoading = true, switchActive = true))
        assertEquals(0f, machine.alphaAt(800L), 0.001f)

        // Ending a track switch when track is already ready stays Idle
        assertEquals(FolioLoadingShimmerPhase.Idle, machine.update(1_420L, isTrackLoading = false, switchActive = false))
        assertEquals(0f, machine.alphaAt(1_420L), 0.001f)
    }

    @Test
    fun trackSwitchGlassTimelineFollowsOption2BAndIsStrictNoOpInLowPerfOrReducedMotion() {
        // Normal mode timeline checkpoints (0..240ms gather, 240..700ms release, 240..620ms sweep)
        val t0 = sampleTrackSwitchGlass(0, lowPerformance = false, reducedMotion = false)
        assertEquals(1f, t0.coverScale, 0.001f)
        assertEquals(1f, t0.innerCoverScale, 0.001f)
        assertEquals(0f, t0.frostAlpha, 0.001f)
        assertEquals(0f, t0.glassRimAlpha, 0.001f)
        assertEquals(0f, t0.glassSweepAlpha, 0.001f)
        assertTrue(t0.switchSweepActive)

        val t120 = sampleTrackSwitchGlass(120, lowPerformance = false, reducedMotion = false)
        assertEquals(FolioGatherMotion.TRACK_FROST_MID_ALPHA, t120.frostAlpha, 0.005f)
        assertTrue(t120.coverScale < 1f)
        assertTrue(t120.innerCoverScale > 1f)
        assertEquals(0f, t120.glassSweepAlpha, 0.001f)
        assertTrue(t120.switchSweepActive)

        val t240 = sampleTrackSwitchGlass(240, lowPerformance = false, reducedMotion = false)
        assertEquals(FolioGatherMotion.TRACK_COVER_GATHER_SCALE, t240.coverScale, 0.001f)
        assertEquals(FolioGatherMotion.TRACK_INNER_COVER_GATHER_SCALE, t240.innerCoverScale, 0.001f)
        assertEquals(FolioGatherMotion.TRACK_COVER_GATHER_ALPHA, t240.coverAlpha, 0.001f)
        assertEquals(FolioGatherMotion.TRACK_FROST_PEAK_ALPHA, t240.frostAlpha, 0.001f)
        assertEquals(1f, t240.glassRimAlpha, 0.001f)
        assertEquals(0f, t240.glassSweepProgress, 0.001f)
        assertTrue(t240.switchSweepActive)

        val t380 = sampleTrackSwitchGlass(380, lowPerformance = false, reducedMotion = false)
        assertEquals(FolioGatherMotion.TRACK_FROST_RELEASE_MID_ALPHA, t380.frostAlpha, 0.005f)
        assertEquals(1f, t380.glassSweepAlpha, 0.001f)
        assertTrue(t380.glassSweepProgress in 0.1f..0.9f)
        assertTrue(t380.switchSweepActive)

        val t520 = sampleTrackSwitchGlass(520, lowPerformance = false, reducedMotion = false)
        assertEquals(0f, t520.frostAlpha, 0.001f)
        assertEquals(1f, t520.glassSweepAlpha, 0.001f)
        assertTrue(t520.switchSweepActive)

        // At 620ms (240 + 380ms), switch arc sweep completes and hands over cleanly
        val t620 = sampleTrackSwitchGlass(620, lowPerformance = false, reducedMotion = false)
        assertEquals(0f, t620.frostAlpha, 0.001f)
        assertEquals(1f, t620.glassSweepProgress, 0.001f)
        assertEquals(0f, t620.glassSweepAlpha, 0.001f)
        assertEquals(FolioGatherMotion.TRACK_GLASS_RIM_TAIL_ALPHA, t620.glassRimAlpha, 0.005f)
        assertFalse(t620.switchSweepActive)

        val t700 = sampleTrackSwitchGlass(700, lowPerformance = false, reducedMotion = false)
        assertEquals(FolioTrackSwitchGlassSample(), t700)

        // Low-performance & reduced-motion: every glass & parallax value is strictly a no-op
        val checkpoints = listOf(0, 60, 120, 240, 320, 380, 520, 620, 700)
        val neutral = FolioTrackSwitchGlassSample()
        for (ms in checkpoints) {
            val lowPerf = sampleTrackSwitchGlass(ms, lowPerformance = true, reducedMotion = false)
            assertEquals("lowPerformance must be no-op at ${ms}ms", neutral, lowPerf)
            val reduced = sampleTrackSwitchGlass(ms, lowPerformance = false, reducedMotion = true)
            assertEquals("reducedMotion must be no-op at ${ms}ms", neutral, reduced)
        }
    }

    @Test
    fun arcGlintSpecsAndCoverLuminanceMatchDesignParameters() {
        assertEquals(0.36f, FolioArcGlintSpec.Loading.bandWidthRatio, 0.001f)
        assertEquals(1.70f, FolioArcGlintSpec.Loading.arcRadiusRatio, 0.001f)
        assertEquals(0.42f, FolioArcGlintSpec.Loading.darkCoreAlpha, 0.001f)
        assertEquals(0.56f, FolioArcGlintSpec.Loading.lightCoreAlpha, 0.001f)
        assertEquals(0.52f, FolioArcGlintSpec.Loading.edgeTintFactor, 0.001f)
        assertEquals(1300, FolioArcGlintSpec.Loading.sweepMs)
        assertEquals(240, FolioArcGlintSpec.Loading.pauseMs)
        assertFalse(FolioArcGlintSpec.Loading.dispersionFringe)
        assertEquals(0.20f, FolioLoadingShimmerTiming.LOW_PERF_BREATH_ALPHA, 0.001f)
        assertEquals(0.30f, FolioLoadingShimmerTiming.LOW_PERF_BREATH_MIN_FACTOR, 0.001f)

        assertEquals(0.22f, FolioArcGlintSpec.TrackSwitch.bandWidthRatio, 0.001f)
        assertEquals(1.55f, FolioArcGlintSpec.TrackSwitch.arcRadiusRatio, 0.001f)
        assertEquals(0.32f, FolioArcGlintSpec.TrackSwitch.darkCoreAlpha, 0.001f)
        assertEquals(0.44f, FolioArcGlintSpec.TrackSwitch.lightCoreAlpha, 0.001f)
        assertEquals(380, FolioArcGlintSpec.TrackSwitch.sweepMs)
        assertTrue(FolioArcGlintSpec.TrackSwitch.dispersionFringe)

        assertTrue(folioCoverLuminance(listOf(Color(0xFF14121A))) < FOLIO_COVER_LIGHT_LUMINANCE_THRESHOLD)
        assertTrue(folioCoverLuminance(listOf(Color(0xFFF4F1EC))) > FOLIO_COVER_LIGHT_LUMINANCE_THRESHOLD)
        assertEquals(0.25f, folioCoverLuminance(emptyList()), 0.001f)
    }
}
