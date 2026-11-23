package dev.amatv.flyme

import dev.amatv.core.LowPerformanceMode
import dev.amatv.core.SettingsState
import dev.amatv.integration.MusicConnectionState
import kotlin.math.abs

internal enum class WelcomePageId {
    Welcome,
    SignIn,
    Lyrics,
    Styles,
    Gapless,
    Preferences,
}

internal fun welcomePageSequence(connectionState: MusicConnectionState): List<WelcomePageId> =
    if (connectionState == MusicConnectionState.SignedIn) {
        listOf(
            WelcomePageId.Welcome,
            WelcomePageId.Lyrics,
            WelcomePageId.Styles,
            WelcomePageId.Gapless,
            WelcomePageId.Preferences,
        )
    } else {
        listOf(
            WelcomePageId.Welcome,
            WelcomePageId.SignIn,
            WelcomePageId.Lyrics,
            WelcomePageId.Styles,
            WelcomePageId.Gapless,
            WelcomePageId.Preferences,
        )
    }

internal fun shouldPlayWelcomeBgm(
    isMusicActive: Boolean,
    isAppPlaying: Boolean,
    userMuted: Boolean,
): Boolean = !isMusicActive && !isAppPlaying && !userMuted

internal const val WELCOME_DRAG_DISTANCE_FRACTION = 0.08f
internal const val WELCOME_DRAG_VELOCITY_THRESHOLD_PX = 650f

internal fun computeWelcomeDragOffsetPx(
    rawDragX: Float,
    currentIndex: Int,
    pageCount: Int,
): Float {
    val atStartEdge = currentIndex <= 0 && rawDragX > 0f
    val atEndEdge = currentIndex >= pageCount - 1 && rawDragX < 0f
    return if (atStartEdge || atEndEdge) rawDragX * 0.3f else rawDragX
}

internal fun resolveWelcomeDragPageDelta(
    dragX: Float,
    velocityX: Float,
    containerWidthPx: Float,
    currentIndex: Int,
    pageCount: Int,
): Int {
    if (pageCount <= 1 || containerWidthPx <= 0f) return 0
    val distanceTriggered = abs(dragX) > containerWidthPx * WELCOME_DRAG_DISTANCE_FRACTION
    val velocityTriggered = abs(velocityX) >= WELCOME_DRAG_VELOCITY_THRESHOLD_PX
    val delta = when {
        distanceTriggered -> if (dragX < 0f) 1 else -1
        velocityTriggered -> if (velocityX < 0f) 1 else -1
        else -> 0
    }
    val target = (currentIndex + delta).coerceIn(0, pageCount - 1)
    return target - currentIndex
}

internal fun shouldShowUnsignedHomeBanner(connectionState: MusicConnectionState): Boolean =
    connectionState != MusicConnectionState.SignedIn

internal fun shouldTriggerAutoLowPerfHint(
    settings: SettingsState,
    alreadyShown: Boolean,
): Boolean = !alreadyShown &&
    settings.lowPerformanceMode == LowPerformanceMode.Auto &&
    settings.autoLowPerformanceReason != null

internal fun shouldShowPivotScrubHint(
    rootSwipeCountInRun: Int,
    alreadyShown: Boolean,
): Boolean = !alreadyShown && rootSwipeCountInRun >= 3

internal fun shouldShowStyleToggleHint(
    playerOpen: Boolean,
    hasCurrentTrack: Boolean,
    alreadyShown: Boolean,
): Boolean = !alreadyShown && playerOpen && hasCurrentTrack

internal fun shouldRenderWelcomeLyricsLines(
    showLyricsDemo: Boolean,
    gaplessActive: Boolean,
    activeTrackIndex: Int,
): Boolean = showLyricsDemo && (!gaplessActive || activeTrackIndex == 0)

internal fun welcomeRightPanelAlpha(
    showLoudnessBars: Boolean,
    gaplessActive: Boolean,
): Float = when {
    showLoudnessBars -> 0.1f
    gaplessActive -> 0.35f
    else -> 1f
}
