package dev.amatv.flyme

import dev.amatv.core.CURRENT_WELCOME_VERSION
import dev.amatv.core.ContentState
import dev.amatv.core.LowPerformanceMode
import dev.amatv.core.SettingsState
import dev.amatv.core.WelcomePreferences
import dev.amatv.core.shouldShowWelcome
import dev.amatv.integration.MusicConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioWelcomeTest {

    private class FakeWelcomePreferences : WelcomePreferences {
        override var welcomeVersion: Int = 0
        override var bgmMuted: Boolean = false
        override var hintLowPerfShown: Boolean = false
        override var hintPivotScrubShown: Boolean = false
        override var hintStyleToggleShown: Boolean = false
    }

    @Test
    fun pageSequenceSkipsSignInOnlyWhenAlreadySignedIn() {
        val signedIn = welcomePageSequence(MusicConnectionState.SignedIn)
        assertEquals(
            listOf(
                WelcomePageId.Welcome,
                WelcomePageId.Lyrics,
                WelcomePageId.Styles,
                WelcomePageId.Gapless,
                WelcomePageId.Preferences,
            ),
            signedIn,
        )

        val ready = welcomePageSequence(MusicConnectionState.ReadyToSignIn)
        assertEquals(6, ready.size)
        assertEquals(WelcomePageId.SignIn, ready[1])

        val demo = welcomePageSequence(MusicConnectionState.Demo)
        assertEquals(6, demo.size)
        assertEquals(WelcomePageId.SignIn, demo[1])
    }

    @Test
    fun welcomeVersionGateShowsBelowCurrentVersionAndHidesAtCurrent() {
        assertEquals(1, CURRENT_WELCOME_VERSION)
        assertTrue(shouldShowWelcome(0))
        assertFalse(shouldShowWelcome(1))
        assertFalse(shouldShowWelcome(2))
    }

    @Test
    fun bgmPlaybackBlockedBySystemMusicAppPlaybackOrMutePreference() {
        assertTrue(shouldPlayWelcomeBgm(isMusicActive = false, isAppPlaying = false, userMuted = false))
        assertFalse(shouldPlayWelcomeBgm(isMusicActive = true, isAppPlaying = false, userMuted = false))
        assertFalse(shouldPlayWelcomeBgm(isMusicActive = false, isAppPlaying = true, userMuted = false))
        assertFalse(shouldPlayWelcomeBgm(isMusicActive = false, isAppPlaying = false, userMuted = true))
    }

    @Test
    fun inAppHintsFollowOneShotAndAccountRules() {
        val prefs = FakeWelcomePreferences()

        // Tip 1: unsigned home banner tracks account state continuously (never one-shot)
        assertTrue(shouldShowUnsignedHomeBanner(MusicConnectionState.ReadyToSignIn))
        assertTrue(shouldShowUnsignedHomeBanner(MusicConnectionState.Demo))
        assertFalse(shouldShowUnsignedHomeBanner(MusicConnectionState.SignedIn))

        // Tip 2: auto low performance fires only once when Auto mode detects hardware/jank
        val autoHardware = SettingsState(
            lowPerformanceMode = LowPerformanceMode.Auto,
            lowPerformanceHardware = true,
        )
        val manualOn = SettingsState(
            lowPerformanceMode = LowPerformanceMode.On,
            lowPerformanceHardware = true,
        )
        assertFalse(shouldTriggerAutoLowPerfHint(manualOn, prefs.hintLowPerfShown))
        assertTrue(shouldTriggerAutoLowPerfHint(autoHardware, prefs.hintLowPerfShown))
        prefs.hintLowPerfShown = true
        assertFalse(shouldTriggerAutoLowPerfHint(autoHardware, prefs.hintLowPerfShown))

        // Tip 3a: pivot scrub bubble appears on 3rd root swipe in run, once
        assertFalse(shouldShowPivotScrubHint(rootSwipeCountInRun = 1, alreadyShown = prefs.hintPivotScrubShown))
        assertFalse(shouldShowPivotScrubHint(rootSwipeCountInRun = 2, alreadyShown = prefs.hintPivotScrubShown))
        assertTrue(shouldShowPivotScrubHint(rootSwipeCountInRun = 3, alreadyShown = prefs.hintPivotScrubShown))
        prefs.hintPivotScrubShown = true
        assertFalse(shouldShowPivotScrubHint(rootSwipeCountInRun = 4, alreadyShown = prefs.hintPivotScrubShown))

        // Tip 3b: style toggle bubble appears on first player open with track, once
        assertFalse(shouldShowStyleToggleHint(playerOpen = false, hasCurrentTrack = true, alreadyShown = prefs.hintStyleToggleShown))
        assertFalse(shouldShowStyleToggleHint(playerOpen = true, hasCurrentTrack = false, alreadyShown = prefs.hintStyleToggleShown))
        assertTrue(shouldShowStyleToggleHint(playerOpen = true, hasCurrentTrack = true, alreadyShown = prefs.hintStyleToggleShown))
        prefs.hintStyleToggleShown = true
        assertFalse(shouldShowStyleToggleHint(playerOpen = true, hasCurrentTrack = true, alreadyShown = prefs.hintStyleToggleShown))
    }

    @Test
    fun dragReleaseResolvesByDistanceThresholdVelocityAndEdgeBounds() {
        val width = 1000f

        // Beyond 8% distance advances / retreats
        assertEquals(1, resolveWelcomeDragPageDelta(-90f, 0f, width, currentIndex = 1, pageCount = 6))
        assertEquals(-1, resolveWelcomeDragPageDelta(90f, 0f, width, currentIndex = 2, pageCount = 6))

        // Below 8% distance stays put if slow, commits if fast flick
        assertEquals(0, resolveWelcomeDragPageDelta(-40f, -120f, width, currentIndex = 1, pageCount = 6))
        assertEquals(1, resolveWelcomeDragPageDelta(-40f, -800f, width, currentIndex = 1, pageCount = 6))
        assertEquals(-1, resolveWelcomeDragPageDelta(30f, 800f, width, currentIndex = 2, pageCount = 6))

        // Boundary damping and clamp at first/last page
        assertEquals(0, resolveWelcomeDragPageDelta(200f, 900f, width, currentIndex = 0, pageCount = 6))
        assertEquals(0, resolveWelcomeDragPageDelta(-200f, -900f, width, currentIndex = 5, pageCount = 6))
        assertEquals(30f, computeWelcomeDragOffsetPx(100f, currentIndex = 0, pageCount = 6), 0.01f)
        assertEquals(-30f, computeWelcomeDragOffsetPx(-100f, currentIndex = 5, pageCount = 6), 0.01f)
        assertEquals(100f, computeWelcomeDragOffsetPx(100f, currentIndex = 1, pageCount = 6), 0.01f)
    }

    @Test
    fun emptyEditorialContentProducesEmptyWallAndShowcaseWithoutMockFallback() {
        val emptyState = ContentState()
        assertTrue(buildWelcomeWallRows(emptyState).isEmpty())
        assertTrue(
            selectWelcomeShowcaseTracks(
                connectionState = MusicConnectionState.ReadyToSignIn,
                contentState = emptyState,
                playHistoryEntries = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun gaplessDemoDimsLyricsAndReplacesWithNextTrackMetadataUntilLeavingPage() {
        // Lyrics/Styles/Preferences show lead track lyrics at 1.0f alpha
        assertTrue(shouldRenderWelcomeLyricsLines(showLyricsDemo = true, gaplessActive = false, activeTrackIndex = 0))
        assertEquals(1.0f, welcomeRightPanelAlpha(showLoudnessBars = false, gaplessActive = false), 0.001f)

        // On Gapless page, dims to 0.35f; track 0 still matches lead lyrics, tracks 1+ swap to title+artist
        assertEquals(0.35f, welcomeRightPanelAlpha(showLoudnessBars = false, gaplessActive = true), 0.001f)
        assertTrue(shouldRenderWelcomeLyricsLines(showLyricsDemo = true, gaplessActive = true, activeTrackIndex = 0))
        assertFalse(shouldRenderWelcomeLyricsLines(showLyricsDemo = true, gaplessActive = true, activeTrackIndex = 1))
        assertFalse(shouldRenderWelcomeLyricsLines(showLyricsDemo = true, gaplessActive = true, activeTrackIndex = 2))

        // Leaving Gapless back to Preferences resets gaplessActive = false, activeTrackIndex = 0 -> restores lyrics
        assertTrue(shouldRenderWelcomeLyricsLines(showLyricsDemo = true, gaplessActive = false, activeTrackIndex = 0))
        assertEquals(0.10f, welcomeRightPanelAlpha(showLoudnessBars = true, gaplessActive = false), 0.001f)
    }
}
