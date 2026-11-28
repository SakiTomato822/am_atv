package dev.amatv.flyme

import androidx.compose.ui.graphics.Color
import dev.amatv.core.LowPerformanceMode
import dev.amatv.core.SettingsState
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.Track
import dev.amatv.playback.AutoplayStatus
import kotlin.math.abs

internal val WELCOME_THEME_GLOW_TOP_RIGHT = Color(0xFF35306D)
internal val WELCOME_THEME_GLOW_BOTTOM_LEFT = Color(0xFF233A6C)

internal enum class WelcomeAuraColorSource {
    Theme,
    Album,
}

internal data class WelcomeAuraTarget(
    val alpha: Float,
    val colorSource: WelcomeAuraColorSource,
    val primaryColor: Color,
    val secondaryColor: Color,
)

internal fun resolveWelcomeAuraTarget(
    pageId: WelcomePageId,
    showcaseColors: List<Color> = emptyList(),
): WelcomeAuraTarget {
    val alpha = when (pageId) {
        WelcomePageId.Welcome -> 0.45f
        WelcomePageId.SignIn -> 1.0f
        else -> 0.90f
    }
    val usesTheme = pageId == WelcomePageId.Welcome || pageId == WelcomePageId.SignIn
    return if (usesTheme) {
        WelcomeAuraTarget(
            alpha = alpha,
            colorSource = WelcomeAuraColorSource.Theme,
            primaryColor = WELCOME_THEME_GLOW_TOP_RIGHT,
            secondaryColor = WELCOME_THEME_GLOW_BOTTOM_LEFT,
        )
    } else {
        val c1 = showcaseColors.firstOrNull() ?: WELCOME_THEME_GLOW_TOP_RIGHT
        val c2 = showcaseColors.getOrNull(1) ?: showcaseColors.firstOrNull() ?: WELCOME_THEME_GLOW_BOTTOM_LEFT
        WelcomeAuraTarget(
            alpha = alpha,
            colorSource = WelcomeAuraColorSource.Album,
            primaryColor = c1,
            secondaryColor = c2,
        )
    }
}

internal fun isWelcomeAuraMotionEnabled(
    animated: Boolean,
    lowPerformance: Boolean,
): Boolean = animated && !lowPerformance

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

internal fun welcomePlayerScale(screenWidthPx: Int, stageWidthPx: Int): Float =
    stageWidthPx.toFloat() / screenWidthPx

internal fun welcomeDemoQueue(tracks: List<Track>, currentIndex: Int) = FolioQueueSnapshot(
    rows = tracks.mapIndexed { index, track ->
        FolioQueueRow(
            key = "welcome-$index",
            track = track,
            section = when {
                index < currentIndex -> FolioQueueSectionKind.Played
                index == currentIndex -> FolioQueueSectionKind.NowPlaying
                else -> FolioQueueSectionKind.UpNext
            },
            timelineIndex = index,
            isCurrent = index == currentIndex,
        )
    },
    playedCount = currentIndex,
    upNextCount = tracks.lastIndex - currentIndex,
    autoplayCount = 0,
    isStation = false,
    autoplayEnabled = false,
    autoplayStatus = AutoplayStatus.Idle,
)
