package dev.amatv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.amatv.R
import dev.amatv.BuildConfig
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import dev.amatv.integration.AudioSpectrum
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.ui.rendering.TextureAlphaMask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class PlayerPresentation {
    Artwork,
    Lyrics,
    Queue,
}

private val LyricPrimaryWeight = if (BuildConfig.FLYME_AUTO) FontWeight.ExtraBold else FontWeight.Bold

private enum class PlayerNavigationZone {
    Controls,
    ProgressBar,
    Lyrics,
    QueueModes,
    QueueItems,
}

private data class PlayerChromeState(val controlsVisible: Boolean, val showQueue: Boolean)

private enum class QueueModeControl { Shuffle, Repeat, Autoplay, Source, Clear }

private enum class PlayerControl {
    Previous,
    PlayPause,
    Next,
    Lyrics,
    LyricsDisplay,
    Queue,
    Radio,
}

/** `DecelerateInterpolator(1.2f)` from `com.apple.android.music.player.E0.R` (line 185). */
private val ArtworkDecelerateEasing = Easing { fraction ->
    1f - (1f - fraction.coerceIn(0f, 1f)).pow(2f * MotionTokens.ArtworkCardDecelerateFactor)
}

@Composable
fun PlayerScreen(
    track: Track,
    positionAnchorMs: Long,
    playStartedRealtimeMs: Long,
    isPlaying: Boolean,
    queue: List<Track>,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    repeatMode: PlaybackRepeatMode,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    canSetRepeat: Boolean,
    canSetShuffle: Boolean,
    canSetAutoplay: Boolean,
    lyricsLoading: Boolean,
    lyricsLookupFailed: Boolean = false,
    lyricOffsetMs: Long = 0L,
    statusMessage: String?,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleAutoplay: () -> Unit,
    autoplaySource: dev.amatv.integration.AutoplaySource = dev.amatv.integration.AutoplaySource.AppleMusic,
    onToggleAutoplaySource: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    audioSpectrum: () -> AudioSpectrum = { AudioSpectrum() },
    isActiveScreen: Boolean = true,
    isVisibleOnScreen: Boolean = true,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = isPlaying,
    canSeek: Boolean = true,
    isLiveStream: Boolean = false,
    queueEntryIds: List<Long> = emptyList(),
    queueCurrentIndex: Int = -1,
    queueSessionId: Long = 0L,
    canClearQueue: Boolean = queue.size > 1,
    lyricsTransientFailure: Boolean = false,
    onRetryLyrics: () -> Unit = {},
    lowPerformanceMode: Boolean = false,
    lyricTranslationEnabled: Boolean = false,
    lyricPronunciationEnabled: Boolean = false,
    lyricTranslationLanguageTag: String = "",
    lyricPronunciationLanguageTag: String = "en-US",
    appLanguageTag: String = Locale.getDefault().toLanguageTag(),
    lyricsEnhancementsReady: Boolean = true,
    onLyricTranslationEnabledChange: (Boolean) -> Unit = {},
    onLyricPronunciationEnabledChange: (Boolean) -> Unit = {},
    onLyricTranslationLanguageChange: (String) -> Unit = {},
    onLyricPronunciationLanguageChange: (String) -> Unit = {},
    onBack: () -> Unit = {},
    lyricTextSize: LyricTextSize = LyricTextSize.Standard,
    onLyricTextSizeChange: (LyricTextSize) -> Unit = {},
) {
    val playbackStateDescription = stringResource(when {
        isPlaying -> R.string.player_state_playing
        playWhenReady -> R.string.player_state_preparing
        else -> R.string.player_state_paused
    })
    val supportsLyrics = track.catalogKind == CatalogKind.Song ||
        track.lyrics.isNotEmpty() || track.hasLyricsAvailable
    var userToggledLyrics by remember(queueSessionId) { mutableStateOf(false) }
    var showLyrics by remember(queueSessionId) { mutableStateOf(supportsLyrics && track.lyrics.isNotEmpty()) }
    var showQueue by remember(queueSessionId) { mutableStateOf(false) }
    var showLyricsSettings by remember(queueSessionId) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsActivity by remember { mutableIntStateOf(0) }
    var navigationZone by remember(queueSessionId) {
        mutableStateOf(PlayerNavigationZone.Controls)
    }
    var focusedControl by remember(queueSessionId) {
        mutableStateOf(PlayerControl.PlayPause)
    }
    var selectedLyricIndex by remember(track.id) {
        mutableIntStateOf(-1)
    }
    var isScrubbing by remember(track.id) { mutableStateOf(false) }
    var scrubPositionMs by remember(track.id) { mutableLongStateOf(0L) }
    var focusedQueueMode by remember { mutableStateOf(QueueModeControl.Repeat) }
    val availableControls = remember(supportsLyrics, canSkipPrevious, canSkipNext, canSetAutoplay) {
        buildList {
            if (canSkipPrevious) add(PlayerControl.Previous)
            add(PlayerControl.PlayPause)
            if (canSkipNext) add(PlayerControl.Next)
            if (supportsLyrics) {
                add(PlayerControl.LyricsDisplay)
                add(PlayerControl.Lyrics)
            }
            add(PlayerControl.Queue)
            if (canSetAutoplay) add(PlayerControl.Radio)
        }
    }
    LaunchedEffect(availableControls) {
        if (focusedControl !in availableControls) {
            focusedControl = PlayerControl.PlayPause
        }
    }
    LaunchedEffect(lyricsLookupFailed, track.lyrics.isEmpty(), userToggledLyrics) {
        if (lyricsLookupFailed && track.lyrics.isEmpty() && !userToggledLyrics) {
            showLyrics = false
        } else if (track.lyrics.isNotEmpty() && !userToggledLyrics && !showQueue) {
            showLyrics = true
        }
    }
    val displayQueue = remember(queue, track, queueCurrentIndex) {
        if (queueCurrentIndex >= 0 || queue.any { it.matchesPlaybackQueueEntry(track) }) queue else listOf(track) + queue
    }
    val currentQueueIndex = if (queueCurrentIndex in displayQueue.indices) queueCurrentIndex else {
        displayQueue.indexOfFirst { it.matchesPlaybackQueueEntry(track) }.coerceAtLeast(0)
    }
    val displayQueueKeys: List<Any> = remember(displayQueue, queueEntryIds) {
        if (queueEntryIds.size == displayQueue.size) queueEntryIds
        else displayQueue.mapIndexed { index, item -> "$index:${item.id}" }
    }
    var focusedQueueKey by remember(queueSessionId) { mutableStateOf<Any?>(null) }
    val focusedQueueIndex = displayQueueKeys.indexOf(focusedQueueKey).takeIf { it >= 0 } ?: currentQueueIndex
    fun focusQueueIndex(index: Int) {
        focusedQueueKey = displayQueueKeys.getOrNull(index)
    }
    LaunchedEffect(currentQueueIndex, navigationZone, controlsVisible, queueSessionId) {
        if (navigationZone != PlayerNavigationZone.QueueItems || !controlsVisible) focusQueueIndex(currentQueueIndex)
    }
    val availableQueueModes = remember(canSetShuffle, canSetRepeat, canSetAutoplay, autoplayEnabled, canClearQueue) {
        buildList {
            if (canSetShuffle && !autoplayEnabled) add(QueueModeControl.Shuffle)
            if (canSetRepeat && !autoplayEnabled) add(QueueModeControl.Repeat)
            if (canSetAutoplay) add(QueueModeControl.Autoplay)
            add(QueueModeControl.Source)
            if (canClearQueue) add(QueueModeControl.Clear)
        }
    }
    LaunchedEffect(availableQueueModes) {
        if (focusedQueueMode !in availableQueueModes) focusedQueueMode = availableQueueModes.first()
    }
    val playerFocusRequester = remember { FocusRequester() }
    var lyricsScrollTrigger by remember(track.id) { mutableIntStateOf(0) }
    var lyricsScrollDelta by remember(track.id) { mutableFloatStateOf(0f) }
    val playbackPosition = rememberPlaybackPositionState(
        trackId = track.id,
        positionAnchorMs = positionAnchorMs,
        playStartedRealtimeMs = playStartedRealtimeMs,
        isPlaying = isPlaying && isVisibleOnScreen,
        durationMs = track.durationMs,
    )
    val presentation = when {
        showQueue -> PlayerPresentation.Queue
        showLyrics && supportsLyrics -> PlayerPresentation.Lyrics
        else -> PlayerPresentation.Artwork
    }
    val displayedLyrics = remember(
        track.lyrics, lyricTranslationEnabled, lyricPronunciationEnabled, lyricsEnhancementsReady,
    ) {
        track.lyrics.map { line ->
            line.forDisplay(
                showTranslation = lyricTranslationEnabled && lyricsEnhancementsReady,
                showPronunciation = lyricPronunciationEnabled && lyricsEnhancementsReady,
            )
        }
    }
    val displayedTrack = remember(track, displayedLyrics) { track.copy(lyrics = displayedLyrics) }
    val lyricTranslationAvailable = lyricsEnhancementsReady && track.lyrics.any {
        !it.translation.isNullOrBlank() || !it.backgroundTranslation.isNullOrBlank()
    }
    val lyricPronunciationAvailable = lyricsEnhancementsReady && track.lyrics.any { line ->
        !line.pronunciationText.isNullOrBlank() || !line.backgroundPronunciationText.isNullOrBlank() ||
            line.words.any { !it.pronunciationText.isNullOrBlank() } ||
            line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }
    }

    fun activateControl(control: PlayerControl) {
        if (!isActiveScreen) return
        controlsActivity++
        controlsVisible = true
        focusedControl = control
        when (control) {
            PlayerControl.Previous -> if (canSkipPrevious) onSkipPrevious()
            PlayerControl.PlayPause -> onTogglePlayback()
            PlayerControl.Next -> if (canSkipNext) onSkipNext()
            PlayerControl.Lyrics -> {
                userToggledLyrics = true
                if (lyricsTransientFailure && track.lyrics.isEmpty()) {
                    showLyrics = true
                    onRetryLyrics()
                } else showLyrics = !showLyrics
                showQueue = false
                navigationZone = PlayerNavigationZone.Controls
            }
            PlayerControl.LyricsDisplay -> showLyricsSettings = true
            PlayerControl.Queue -> {
                showQueue = !showQueue
                if (showQueue) {
                    showLyrics = false
                    focusQueueIndex(currentQueueIndex)
                    navigationZone = PlayerNavigationZone.QueueItems
                } else navigationZone = PlayerNavigationZone.Controls
            }
            PlayerControl.Radio -> if (canSetAutoplay) onToggleAutoplay()
        }
    }

    fun activateQueueMode(mode: QueueModeControl) {
        if (!isActiveScreen) return
        controlsActivity++
        when (mode) {
            QueueModeControl.Shuffle -> if (canSetShuffle && !autoplayEnabled) onToggleShuffle()
            QueueModeControl.Repeat -> if (canSetRepeat && !autoplayEnabled) onCycleRepeat()
            QueueModeControl.Autoplay -> if (canSetAutoplay) onToggleAutoplay()
            QueueModeControl.Source -> onToggleAutoplaySource()
            QueueModeControl.Clear -> if (canClearQueue) onClearQueue()
        }
    }

    LaunchedEffect(controlsVisible, controlsActivity, isActiveScreen, isScrubbing, showLyricsSettings) {
        if (!BuildConfig.FLYME_AUTO && controlsVisible && isActiveScreen && !isScrubbing && !showLyricsSettings) {
            delay(5_000L)
            controlsVisible = false
            isScrubbing = false
        }
    }
    val chromeTransition = updateTransition(PlayerChromeState(controlsVisible, showQueue), label = "player-chrome")
    val chromeAlpha = chromeTransition.animateFloat(
        transitionSpec = {
            if (targetState.controlsVisible) tween(280, easing = MotionTokens.KaraokeLineEasing)
            else tween(360, easing = MotionTokens.KaraokeGradientExitEasing)
        }, label = "player-chrome-alpha",
    ) { state -> if (state.controlsVisible) 1f else 0f }
    val pageVisibility = LocalFocusFrameVisibility.current
    val chromeFocusVisibility = remember(chromeAlpha, pageVisibility) {
        derivedStateOf { chromeAlpha.value * (pageVisibility?.value ?: 1f) }
    }
    LaunchedEffect(isActiveScreen) {
        if (isActiveScreen) {
            withFrameNanos { }
            runCatching { playerFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier
            .automationId(if (isActiveScreen) "player-screen" else "player-retained")
            .semantics { stateDescription = playbackStateDescription }
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (!isActiveScreen || event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                if (showLyricsSettings) {
                    return@onPreviewKeyEvent if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        showLyricsSettings = false
                        true
                    } else {
                        false
                    }
                }
                val wasHidden = !controlsVisible
                controlsVisible = true
                controlsActivity++
                if (wasHidden) {
                    navigationZone = PlayerNavigationZone.Controls
                    return@onPreviewKeyEvent when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        -> true
                        else -> false
                    }
                }

                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (isScrubbing) {
                            isScrubbing = false
                            true
                        } else when (navigationZone) {
                            PlayerNavigationZone.Lyrics -> {
                                navigationZone = PlayerNavigationZone.ProgressBar
                                true
                            }
                            PlayerNavigationZone.QueueItems,
                            PlayerNavigationZone.QueueModes -> {
                                navigationZone = PlayerNavigationZone.Controls
                                true
                            }
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        when (navigationZone) {
                            PlayerNavigationZone.Lyrics -> {
                                val targetLine = displayedLyrics.getOrNull(selectedLyricIndex)
                                if (targetLine != null && canSeek && !isLiveStream) {
                                    val delta = targetLine.startMs - playbackPosition.value
                                    onSeekBy(delta)
                                }
                            }
                            PlayerNavigationZone.ProgressBar -> {
                                if (isScrubbing && canSeek && !isLiveStream) {
                                    val targetMs = scrubPositionMs
                                    isScrubbing = false
                                    val delta = targetMs - playbackPosition.value
                                    onSeekBy(delta)
                                } else {
                                    onTogglePlayback()
                                }
                            }
                            PlayerNavigationZone.QueueItems -> {
                                if (showQueue) {
                                    if (focusedQueueIndex in displayQueue.indices) onSelectTrack(focusedQueueIndex)
                                }
                            }
                            PlayerNavigationZone.QueueModes -> {
                                if (showQueue) {
                                    activateQueueMode(focusedQueueMode)
                                }
                            }
                            PlayerNavigationZone.Controls -> {
                                activateControl(focusedControl)
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when (navigationZone) {
                            PlayerNavigationZone.ProgressBar -> {
                                if (!canSeek || isLiveStream) return@onPreviewKeyEvent true
                                val currentAnchor = if (isScrubbing) scrubPositionMs else playbackPosition.value
                                isScrubbing = true
                                scrubPositionMs = (currentAnchor - 5_000L).coerceIn(0L, track.durationMs)
                            }
                            PlayerNavigationZone.Lyrics -> {
                                // Keep lyrics navigation focused on UP/DOWN
                            }
                            PlayerNavigationZone.QueueItems -> {
                                if (showQueue) {
                                    focusQueueIndex((focusedQueueIndex - 1).coerceAtLeast(0))
                                }
                            }
                            PlayerNavigationZone.QueueModes -> {
                                if (showQueue) {
                                    val index = availableQueueModes.indexOf(focusedQueueMode).coerceAtLeast(0)
                                    focusedQueueMode = availableQueueModes[(index - 1).coerceAtLeast(0)]
                                }
                            }
                            PlayerNavigationZone.Controls -> {
                                val index = availableControls.indexOf(focusedControl).coerceAtLeast(0)
                                focusedControl = availableControls[(index - 1).coerceAtLeast(0)]
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (navigationZone) {
                            PlayerNavigationZone.ProgressBar -> {
                                if (!canSeek || isLiveStream) return@onPreviewKeyEvent true
                                val currentAnchor = if (isScrubbing) scrubPositionMs else playbackPosition.value
                                isScrubbing = true
                                scrubPositionMs = (currentAnchor + 5_000L).coerceIn(0L, track.durationMs)
                            }
                            PlayerNavigationZone.Lyrics -> {
                                // Keep lyrics navigation focused on UP/DOWN
                            }
                            PlayerNavigationZone.QueueItems -> {
                                if (showQueue) {
                                    focusQueueIndex((focusedQueueIndex + 1)
                                        .coerceAtMost(displayQueue.lastIndex.coerceAtLeast(0)))
                                }
                            }
                            PlayerNavigationZone.QueueModes -> {
                                if (showQueue) {
                                    val index = availableQueueModes.indexOf(focusedQueueMode).coerceAtLeast(0)
                                    focusedQueueMode = availableQueueModes[(index + 1).coerceAtMost(availableQueueModes.lastIndex)]
                                }
                            }
                            PlayerNavigationZone.Controls -> {
                                val index = availableControls.indexOf(focusedControl).coerceAtLeast(0)
                                focusedControl = availableControls[
                                    (index + 1).coerceAtMost(availableControls.lastIndex)
                                ]
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        isScrubbing = false
                        when (navigationZone) {
                            PlayerNavigationZone.Controls -> {
                                navigationZone = PlayerNavigationZone.ProgressBar
                            }
                            PlayerNavigationZone.ProgressBar -> {
                                if (showQueue) {
                                    focusQueueIndex(currentQueueIndex)
                                    navigationZone = PlayerNavigationZone.QueueItems
                                } else if (presentation == PlayerPresentation.Lyrics && displayedLyrics.isNotEmpty()) {
                                    navigationZone = PlayerNavigationZone.Lyrics
                                    val currentIdx = LyricTimeline.activeLineAt(
                                        lines = displayedLyrics,
                                        positionMs = playbackPosition.value,
                                        applyTimeProcessorOffsets = true,
                                    )
                                    selectedLyricIndex = if (currentIdx >= 0) currentIdx else 0
                                }
                            }
                            PlayerNavigationZone.Lyrics -> {
                                if (selectedLyricIndex > 0) {
                                    selectedLyricIndex--
                                }
                            }
                            PlayerNavigationZone.QueueItems -> {
                                navigationZone = PlayerNavigationZone.QueueModes
                            }
                            PlayerNavigationZone.QueueModes -> {
                                // Stay at top
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        isScrubbing = false
                        when (navigationZone) {
                            PlayerNavigationZone.QueueModes -> {
                                navigationZone = PlayerNavigationZone.QueueItems
                            }
                            PlayerNavigationZone.QueueItems -> {
                                navigationZone = PlayerNavigationZone.ProgressBar
                            }
                            PlayerNavigationZone.Lyrics -> {
                                if (selectedLyricIndex < displayedLyrics.lastIndex) {
                                    selectedLyricIndex++
                                } else {
                                    navigationZone = PlayerNavigationZone.ProgressBar
                                }
                            }
                            PlayerNavigationZone.ProgressBar -> {
                                navigationZone = PlayerNavigationZone.Controls
                            }
                            PlayerNavigationZone.Controls -> {
                                // Stay in controls
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(playerFocusRequester)
            .focusable(enabled = isActiveScreen),
    ) {
        PlayerBackdrop(
            track = track,
            animate = isVisibleOnScreen && isPlaying && !lowPerformanceMode,
            audioSpectrum = audioSpectrum,
            modifier = Modifier.fillMaxSize(),
        )
        PlayerStage(
            track = displayedTrack,
            presentation = presentation,
            supportsLyrics = supportsLyrics,
            isVisibleOnScreen = isVisibleOnScreen,
            // Native playback briefly reports !isPlaying while the next item is
            // preparing. Keep the artwork's play scale stable for that expected
            // gap; an explicit pause clears playWhenReady immediately.
            isPlaying = (isPlaying || playWhenReady) && isVisibleOnScreen,
            audioIsPlaying = isPlaying && isVisibleOnScreen,
            audioSpectrum = audioSpectrum,
            controlsVisible = controlsVisible,
            positionState = playbackPosition,
            lyricOffsetMs = lyricOffsetMs,
            lyricsLoading = lyricsLoading,
            lyricsLookupFailed = lyricsLookupFailed,
            lyricsTransientFailure = lyricsTransientFailure,
            selectedLyricIndex = if (navigationZone == PlayerNavigationZone.Lyrics) selectedLyricIndex else null,
            manualScrollTrigger = lyricsScrollTrigger,
            manualScrollDelta = lyricsScrollDelta,
            displayQueue = displayQueue,
            queueKeys = displayQueueKeys,
            currentQueueIndex = currentQueueIndex,
            queueSessionId = queueSessionId,
            queueFocusVisibility = chromeFocusVisibility,
            focusedQueueIndex = focusedQueueIndex,
            selectionVisible = navigationZone == PlayerNavigationZone.QueueItems,
            lowPerformanceMode = lowPerformanceMode,
            lyricTextSize = lyricTextSize,
            onSelectQueueTrack = { index ->
                if (isActiveScreen && showQueue) {
                    controlsActivity++
                    focusQueueIndex(index)
                    onSelectTrack(index)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (BuildConfig.FLYME_AUTO && isActiveScreen) {
            FocusSurface(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(56.dp)
                    .automationId("player-back"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.browse_back),
                    modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
        }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 84.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        chromeTransition.AnimatedVisibility(
            visible = { it.controlsVisible && it.showQueue },
            enter = fadeIn(tween(280, easing = MotionTokens.KaraokeLineEasing)),
            exit = fadeOut(tween(360, easing = MotionTokens.KaraokeGradientExitEasing)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
        ) {
            CompositionLocalProvider(LocalFocusFrameVisibility provides chromeFocusVisibility) {
                QueueModeControls(
                    repeatMode, shuffleEnabled, autoplayEnabled, autoplaySource,
                    canSetRepeat, canSetShuffle, canSetAutoplay, canClearQueue,
                    focusedQueueMode.takeIf { navigationZone == PlayerNavigationZone.QueueModes },
                    onModeClick = ::activateQueueMode,
                )
            }
        }
        chromeTransition.AnimatedVisibility(
            visible = { it.controlsVisible },
            enter = fadeIn(
                tween(280, easing = MotionTokens.KaraokeLineEasing),
            ) + slideInVertically(
                tween(320, easing = MotionTokens.KaraokeLineEasing),
            ) { it / 4 },
            exit = fadeOut(
                tween(360, easing = MotionTokens.KaraokeGradientExitEasing),
            ) + slideOutVertically(
                tween(380, easing = MotionTokens.KaraokeLineEasing),
            ) { it / 4 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            CompositionLocalProvider(LocalFocusFrameVisibility provides chromeFocusVisibility) {
            Column(
                modifier = Modifier
                    .automationId("player-controls")
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f)),
                        ),
                    )
                    .padding(start = 88.dp, end = 84.dp, bottom = 26.dp, top = 36.dp),
            ) {
                Progress(
                    positionState = playbackPosition,
                    durationMs = track.durationMs,
                    isLiveStream = isLiveStream,
                    isFocused = navigationZone == PlayerNavigationZone.ProgressBar,
                    scrubPositionMs = if (isScrubbing) scrubPositionMs else null,
                    onSeekTo = { target ->
                        if (isActiveScreen) onSeekBy(target - playbackPosition.value)
                    },
                    enabled = isActiveScreen && canSeek && !isLiveStream,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
                            .padding(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerButton(
                            controlId = "player-previous",
                            onClick = { activateControl(PlayerControl.Previous) },
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.player_previous_track),
                            enabled = canSkipPrevious,
                            focused = navigationZone == PlayerNavigationZone.Controls &&
                                focusedControl == PlayerControl.Previous,
                            width = 46.dp,
                        )
                        PlayerButton(
                            controlId = "player-play-pause",
                            onClick = { activateControl(PlayerControl.PlayPause) },
                            icon = if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playWhenReady) R.string.player_pause else R.string.player_play),
                            focused = navigationZone == PlayerNavigationZone.Controls &&
                                focusedControl == PlayerControl.PlayPause,
                            width = 56.dp,
                        )
                        PlayerButton(
                            controlId = "player-next",
                            onClick = { activateControl(PlayerControl.Next) },
                            icon = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.player_next_track),
                            enabled = canSkipNext,
                            focused = navigationZone == PlayerNavigationZone.Controls &&
                                focusedControl == PlayerControl.Next,
                            width = 46.dp,
                        )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
                            .padding(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        if (supportsLyrics) {
                            PlayerButton(
                                controlId = "player-lyrics-display",
                                onClick = { activateControl(PlayerControl.LyricsDisplay) },
                                icon = Icons.Rounded.Translate,
                                contentDescription = stringResource(R.string.player_lyrics_display_settings),
                                selected = lyricTranslationEnabled || lyricPronunciationEnabled,
                                focused = navigationZone == PlayerNavigationZone.Controls &&
                                    focusedControl == PlayerControl.LyricsDisplay,
                                width = 46.dp,
                            )
                            PlayerButton(
                                controlId = "player-lyrics",
                                onClick = { activateControl(PlayerControl.Lyrics) },
                                icon = Icons.Rounded.Lyrics,
                                contentDescription = stringResource(R.string.player_lyrics),
                                selected = showLyrics,
                                focused = navigationZone == PlayerNavigationZone.Controls &&
                                    focusedControl == PlayerControl.Lyrics,
                                width = 46.dp,
                            )
                        }
                        PlayerButton(
                            controlId = "player-queue",
                            onClick = { activateControl(PlayerControl.Queue) },
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = stringResource(R.string.player_queue),
                            selected = showQueue,
                            focused = navigationZone == PlayerNavigationZone.Controls &&
                                focusedControl == PlayerControl.Queue,
                            width = 46.dp,
                        )
                        QueueModeButton(
                            icon = Icons.Rounded.AllInclusive,
                            label = stringResource(R.string.player_radio),
                            selected = autoplayEnabled,
                            focused = navigationZone == PlayerNavigationZone.Controls &&
                                focusedControl == PlayerControl.Radio,
                            enabled = canSetAutoplay,
                            ytmBadge = false,
                            frameKey = "player-main-radio",
                            onClick = { activateControl(PlayerControl.Radio) },
                        )
                        }
                    }
                }
            }
            }
        }

        if (showLyricsSettings) {
            LyricsDisplaySettingsDialog(
                translationEnabled = lyricTranslationEnabled,
                pronunciationEnabled = lyricPronunciationEnabled,
                translationAvailable = lyricTranslationAvailable,
                pronunciationAvailable = lyricPronunciationAvailable,
                translationLanguageTag = lyricTranslationLanguageTag,
                pronunciationLanguageTag = lyricPronunciationLanguageTag,
                appLanguageTag = appLanguageTag,
                enhancementsReady = lyricsEnhancementsReady,
                enhancementsFailed = lyricsTransientFailure && track.lyrics.isNotEmpty(),
                onTranslationChange = onLyricTranslationEnabledChange,
                onPronunciationChange = onLyricPronunciationEnabledChange,
                onTranslationLanguageChange = onLyricTranslationLanguageChange,
                onPronunciationLanguageChange = onLyricPronunciationLanguageChange,
                onDismiss = { showLyricsSettings = false },
                lyricTextSize = lyricTextSize,
                onLyricTextSizeChange = onLyricTextSizeChange,
            )
        }

    }
}


@Composable
private fun Progress(
    positionState: State<Long>,
    durationMs: Long,
    isFocused: Boolean = false,
    scrubPositionMs: Long? = null,
    onSeekTo: (Long) -> Unit,
    enabled: Boolean,
    compact: Boolean = false,
    isLiveStream: Boolean = false,
) {
    if (isLiveStream) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).automationId("player-live-status"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(Color.White, CircleShape))
            Text(stringResource(R.string.player_live_stream), fontSize = 14.sp)
        }
        return
    }
    if (BuildConfig.FLYME_AUTO) {
        var touchPosition by remember(positionState, durationMs) { mutableStateOf<Float?>(null) }
        val displayed = touchPosition ?: positionState.value.toFloat()
        Column(Modifier.fillMaxWidth().automationId("player-touch-progress")) {
            Slider(
                value = displayed.coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { touchPosition = it },
                onValueChangeFinished = {
                    touchPosition?.let { onSeekTo(it.toLong()) }
                    touchPosition = null
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                enabled = enabled && durationMs > 0L,
                modifier = Modifier.fillMaxWidth().height(if (compact) 40.dp else 56.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(displayed.toLong()), fontSize = 14.sp)
                Text("−${formatTime((durationMs - displayed.toLong()).coerceAtLeast(0L))}", fontSize = 14.sp)
            }
        }
        return
    }
    // The clock animates the bar in drawing; labels only need one update per second.
    val displayedSecond by remember(positionState, scrubPositionMs) {
        derivedStateOf { (scrubPositionMs ?: positionState.value) / 1_000L }
    }
    val positionMs = displayedSecond * 1_000L
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val isScrubPreview = scrubPositionMs != null

    val barHeight by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 4.dp,
        animationSpec = tween(220, easing = MotionTokens.KaraokeLineEasing),
        label = "scrubber-bar-height",
    )
    val thumbRadius by animateDpAsState(
        targetValue = when {
            isScrubPreview -> 10.dp
            isFocused -> 8.dp
            else -> 0.dp
        },
        animationSpec = tween(220, easing = MotionTokens.KaraokeLineEasing),
        label = "scrubber-thumb-radius",
    )
    val trackAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.35f else 0.20f,
        animationSpec = tween(200),
        label = "scrubber-track-alpha",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (isScrubPreview) 1f else if (isFocused) 0.95f else 0.60f,
        animationSpec = tween(200),
        label = "scrubber-text-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusFrameTarget(isFocused, "player-progress", 12.dp)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight),
            ) {
                val totalWidth = size.width
                val h = size.height
                val activeMs = scrubPositionMs ?: positionState.value
                val progress = (activeMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                val playedWidth = totalWidth * progress
                val cornerRadius = CornerRadius(h / 2f, h / 2f)

                // 1. Unfilled track capsule
                drawRoundRect(
                    color = Color.White.copy(alpha = trackAlpha),
                    size = size,
                    cornerRadius = cornerRadius,
                )

                // 2. Filled played progress capsule
                if (playedWidth > 0f) {
                    drawRoundRect(
                        color = Color.White,
                        size = Size(playedWidth, h),
                        cornerRadius = cornerRadius,
                    )
                }

                // 3. Apple TV Scrubber Knob Thumb
                val thumbPx = thumbRadius.toPx()
                if (thumbPx > 0.5f) {
                    val knobCenter = Offset(
                        x = playedWidth.coerceIn(thumbPx, totalWidth - thumbPx),
                        y = h / 2f,
                    )
                    // Drop shadow / glow
                    drawCircle(
                        color = Color.Black.copy(alpha = if (isScrubPreview) 0.50f else 0.35f),
                        radius = thumbPx + 3f,
                        center = knobCenter,
                    )
                    // Solid white Apple scrubber knob
                    drawCircle(
                        color = Color.White,
                        radius = thumbPx,
                        center = knobCenter,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(positionMs),
                fontSize = 13.sp,
                fontWeight = if (isScrubPreview) FontWeight.Bold else FontWeight.Medium,
                color = Color.White.copy(alpha = textAlpha),
            )
            Text(
                text = "−${formatTime(remainingMs)}",
                fontSize = 13.sp,
                fontWeight = if (isScrubPreview) FontWeight.Bold else FontWeight.Medium,
                color = Color.White.copy(alpha = textAlpha),
            )
        }
    }
}

@Composable
private fun PlayerButton(
    controlId: String,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean,
    width: Dp = 46.dp,
    height: Dp = 40.dp,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            focused -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 550f),
        label = "player-button-scale",
    )
    val background by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> Color.White.copy(alpha = 0.22f)
            pressed -> Color.White.copy(alpha = 0.15f)
            else -> Color.Transparent
        },
        animationSpec = tween(160), label = "player-button-container",
    )
    Box(
        modifier = Modifier
            .automationId(controlId)
            .semantics {
                this.selected = focused
                this.contentDescription = contentDescription
            }
            .size(width = if (BuildConfig.FLYME_AUTO) maxOf(width, 56.dp) else width,
                height = if (BuildConfig.FLYME_AUTO) maxOf(height, 56.dp) else height)
            .focusFrameTarget(focused, "player-control:$controlId", 20.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                enabled = interactive,
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            )
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(160), label = "player-control-icon") { displayedIcon ->
            Icon(
                imageVector = displayedIcon,
                contentDescription = null,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.25f)
                    focused -> Color(0xFF18181A)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.88f)
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private enum class LyricsLanguagePicker { Translation, Pronunciation }

private val TranslationLanguageTags = listOf(
    "", "zh-TW", "zh-CN", "en-US", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE",
)

@Composable
private fun LyricsDisplaySettingsDialog(
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
    translationAvailable: Boolean,
    pronunciationAvailable: Boolean,
    translationLanguageTag: String,
    pronunciationLanguageTag: String,
    appLanguageTag: String,
    enhancementsReady: Boolean,
    enhancementsFailed: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onTranslationLanguageChange: (String) -> Unit,
    onPronunciationLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    lyricTextSize: LyricTextSize,
    onLyricTextSizeChange: (LyricTextSize) -> Unit,
) {
    var languagePicker by remember { mutableStateOf<LyricsLanguagePicker?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(languagePicker) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val effectiveTranslationTag = translationLanguageTag.ifBlank { appLanguageTag }
    val appLocale = remember(appLanguageTag) {
        Locale.forLanguageTag(appLanguageTag).takeUnless { it.language.isBlank() } ?: Locale.getDefault()
    }
    fun languageName(tag: String): String = Locale.forLanguageTag(tag)
        .getDisplayName(appLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }

    CompositionLocalProvider(LocalFocusFrameEnabled provides false) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(
                        when (languagePicker) {
                            LyricsLanguagePicker.Translation -> R.string.player_lyrics_choose_translation_language
                            LyricsLanguagePicker.Pronunciation -> R.string.player_lyrics_choose_pronunciation_script
                            null -> R.string.player_lyrics_display_title
                        },
                    ),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(
                    modifier = Modifier.width(520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (languagePicker) {
                        LyricsLanguagePicker.Translation -> {
                            TranslationLanguageTags.forEachIndexed { index, tag ->
                                val effectiveTag = tag.ifBlank { appLanguageTag }
                                val label = if (tag.isBlank()) {
                                    stringResource(
                                        R.string.player_lyrics_language_follow_app_format,
                                        languageName(effectiveTag),
                                    )
                                } else {
                                    languageName(tag)
                                }
                                LyricsLanguageChoiceRow(
                                    label = label,
                                    selected = tag == translationLanguageTag,
                                    onClick = {
                                        onTranslationLanguageChange(tag)
                                        languagePicker = null
                                    },
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                        LyricsLanguagePicker.Pronunciation -> {
                            val options = listOf("en-US", appLanguageTag).distinct()
                            options.forEachIndexed { index, tag ->
                                LyricsLanguageChoiceRow(
                                    label = if (tag == "en-US") {
                                        stringResource(R.string.player_lyrics_pronunciation_latin)
                                    } else {
                                        stringResource(
                                            R.string.player_lyrics_pronunciation_app_script_format,
                                            languageName(tag),
                                        )
                                    },
                                    selected = tag == pronunciationLanguageTag,
                                    onClick = {
                                        onPronunciationLanguageChange(tag)
                                        languagePicker = null
                                    },
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                        null -> {
                            if (BuildConfig.FLYME_AUTO) {
                                LyricsDisplayValueRow(
                                    title = stringResource(R.string.flyme_lyrics_text_size),
                                    value = stringResource(when (lyricTextSize) {
                                        LyricTextSize.Standard -> R.string.flyme_text_standard
                                        LyricTextSize.Large -> R.string.flyme_text_large
                                        LyricTextSize.ExtraLarge -> R.string.flyme_text_extra_large
                                    }),
                                    onClick = { onLyricTextSizeChange(lyricTextSize.next()) },
                                    modifier = Modifier.automationId("lyrics-text-size"),
                                )
                            }
                            LyricsLanguageSummaryRow(
                                title = stringResource(R.string.player_lyrics_source_language),
                                value = stringResource(R.string.player_lyrics_source_auto),
                            )
                            LyricsDisplaySettingRow(
                                title = stringResource(R.string.player_lyrics_translation),
                                description = stringResource(when {
                                    translationEnabled && enhancementsFailed -> R.string.player_lyrics_enhancement_load_failed
                                    translationEnabled && !enhancementsReady -> R.string.player_lyrics_enhancement_loading
                                    translationEnabled && !translationAvailable -> R.string.player_lyrics_translation_unavailable
                                    else -> R.string.player_lyrics_translation_hint
                                }),
                                enabled = translationEnabled,
                                onClick = { onTranslationChange(!translationEnabled) },
                                modifier = Modifier.focusRequester(firstFocus),
                            )
                            LyricsDisplayValueRow(
                                title = stringResource(R.string.player_lyrics_translate_to),
                                value = if (translationLanguageTag.isBlank()) {
                                    stringResource(
                                        R.string.player_lyrics_language_follow_app_format,
                                        languageName(effectiveTranslationTag),
                                    )
                                } else languageName(effectiveTranslationTag),
                                onClick = { languagePicker = LyricsLanguagePicker.Translation },
                            )
                            LyricsDisplaySettingRow(
                                title = stringResource(R.string.player_lyrics_pronunciation),
                                description = stringResource(when {
                                    pronunciationEnabled && enhancementsFailed -> R.string.player_lyrics_enhancement_load_failed
                                    pronunciationEnabled && !enhancementsReady -> R.string.player_lyrics_enhancement_loading
                                    pronunciationEnabled && !pronunciationAvailable -> R.string.player_lyrics_pronunciation_unavailable
                                    else -> R.string.player_lyrics_pronunciation_hint
                                }),
                                enabled = pronunciationEnabled,
                                onClick = { onPronunciationChange(!pronunciationEnabled) },
                            )
                            LyricsDisplayValueRow(
                                title = stringResource(R.string.player_lyrics_pronunciation_script),
                                value = if (pronunciationLanguageTag == "en-US") {
                                    stringResource(R.string.player_lyrics_pronunciation_latin)
                                } else {
                                    stringResource(
                                        R.string.player_lyrics_pronunciation_app_script_format,
                                        languageName(pronunciationLanguageTag),
                                    )
                                },
                                onClick = { languagePicker = LyricsLanguagePicker.Pronunciation },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                FocusSurface(
                    onClick = {
                        if (languagePicker != null) languagePicker = null else onDismiss()
                    },
                    modifier = Modifier.width(112.dp).height(42.dp),
                    cornerRadius = 21.dp,
                    background = Color.White.copy(alpha = 0.12f),
                    focusedBackground = Color.White,
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(
                                if (languagePicker == null) R.string.player_lyrics_display_done
                                else R.string.player_lyrics_language_back,
                            ),
                            color = if (focused) Color(0xFF18181A) else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            containerColor = Color(0xFF252529),
            titleContentColor = Color.White,
            textContentColor = Color.White,
        )
    }
}

@Composable
private fun LyricsDisplaySettingRow(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(68.dp).semantics { selected = enabled },
        cornerRadius = 14.dp,
        showBorder = true,
        padding = PaddingValues(horizontal = 16.dp),
        background = Color.White.copy(alpha = 0.08f),
        focusedBackground = Color.White.copy(alpha = 0.18f),
    ) { _ ->
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 13.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.player_lyrics_display_on else R.string.player_lyrics_display_off,
                    ),
                    color = if (enabled) Color(0xFF18181A) else Color.White.copy(alpha = 0.66f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LyricsLanguageSummaryRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
        Text(value, color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp)
    }
}

@Composable
private fun LyricsDisplayValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        cornerRadius = 14.dp,
        showBorder = true,
        padding = PaddingValues(horizontal = 16.dp),
        background = Color.White.copy(alpha = 0.08f),
        focusedBackground = Color.White.copy(alpha = 0.18f),
    ) { _ ->
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                value,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

@Composable
private fun LyricsLanguageChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp).semantics { this.selected = selected },
        cornerRadius = 13.dp,
        showBorder = true,
        padding = PaddingValues(horizontal = 16.dp),
        background = if (selected) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
        focusedBackground = Color.White.copy(alpha = 0.22f),
    ) { _ ->
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (selected) {
                Text("✓", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Unified GPU-composited Now Playing stage that keeps the album artwork column,
 * viewport-mounted `LyricsPanel`, and `QueuePanel` warm in the composition tree:
 * - Toggling between `Artwork` and `Lyrics` smoothly glides the single persistent
 *   `MotionArtwork` + `TrackIdentity` column horizontally between the screen center and
 *   the left `88.dp` anchor (`380ms`, `KaraokeLineEasing`) while fading/sliding the
 *   retained lyric viewport purely in `graphicsLayer`, keeping measured rows warm.
 * - Toggling `controlsVisible` smoothly glides all active stage elements vertically by
 *   artwork column offset (`-48.dp` when controls visible -> `0.dp` when auto-hidden).
 */
@Composable
private fun PlayerStage(
    track: Track,
    presentation: PlayerPresentation,
    supportsLyrics: Boolean,
    isVisibleOnScreen: Boolean,
    isPlaying: Boolean,
    audioIsPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    controlsVisible: Boolean,
    positionState: State<Long>,
    lyricOffsetMs: Long = 0L,
    lyricsLoading: Boolean,
    lyricsLookupFailed: Boolean,
    lyricsTransientFailure: Boolean,
    selectedLyricIndex: Int? = null,
    manualScrollTrigger: Int,
    manualScrollDelta: Float,
    displayQueue: List<Track>,
    queueKeys: List<Any>,
    currentQueueIndex: Int,
    queueSessionId: Long,
    queueFocusVisibility: State<Float>,
    focusedQueueIndex: Int,
    selectionVisible: Boolean,
    lowPerformanceMode: Boolean = false,
    onSelectQueueTrack: (Int) -> Unit,
    lyricTextSize: LyricTextSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val targetStageOffsetPx = with(density) {
        if (controlsVisible) MotionTokens.StageControlsVisibleVerticalOffsetDp.dp.toPx() else 0f
    }
    val stageOffsetPx = animateFloatAsState(
        targetValue = targetStageOffsetPx,
        animationSpec = tween(
            durationMillis = MotionTokens.StageControlsShiftDurationMillis,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "player-stage-vertical-offset",
    )
    val supportingStageOffsetPx = animateFloatAsState(
        targetValue = with(density) {
            if (controlsVisible) {
                MotionTokens.SupportingStageControlsVisibleVerticalOffsetDp.dp.toPx()
            } else 0f
        },
        animationSpec = tween(
            durationMillis = MotionTokens.StageControlsShiftDurationMillis,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "supporting-stage-vertical-offset",
    )
    val artworkPlayingScale = animateFloatAsState(
        targetValue = if (isPlaying) MotionTokens.ArtworkCardScalePlaying
        else MotionTokens.ArtworkCardScalePaused,
        animationSpec = tween(
            durationMillis = MotionTokens.ArtworkCardScaleDurationMillis,
            easing = ArtworkDecelerateEasing,
        ),
        label = "player-artwork-card-scale",
    )
    val splitLayoutFraction = animateFloatAsState(
        targetValue = if (presentation == PlayerPresentation.Lyrics) 1f else 0f,
        animationSpec = tween(
            durationMillis = 380,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "player-artwork-split-fraction",
    )
    val artworkColumnAlpha = animateFloatAsState(
        targetValue = if (presentation == PlayerPresentation.Queue) 0f else 1f,
        animationSpec = tween(
            durationMillis = 280,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "player-artwork-column-alpha",
    )
    val lyricsAlpha = animateFloatAsState(
        targetValue = if (presentation == PlayerPresentation.Lyrics) 1f else 0f,
        animationSpec = tween(
            durationMillis = 340,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "player-lyrics-stage-alpha",
    )
    val queueAlpha = animateFloatAsState(
        targetValue = if (presentation == PlayerPresentation.Queue) 1f else 0f,
        animationSpec = tween(
            durationMillis = 280,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "player-queue-stage-alpha",
    )
    // Retain each stage through its exit while keeping frame-varying values out
    // of composition. The layer/placement readers below own their animations.
    var lyricsVisited by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id, presentation, isVisibleOnScreen) {
        if (isVisibleOnScreen && presentation == PlayerPresentation.Lyrics) lyricsVisited = true
    }
    val lyricsStageVisible by remember(presentation, lyricsAlpha, track.id) {
        derivedStateOf { lyricsVisited || presentation == PlayerPresentation.Lyrics || lyricsAlpha.value > 0.001f }
    }
    val artworkStageVisible by remember(artworkColumnAlpha) {
        derivedStateOf { artworkColumnAlpha.value > 0.001f }
    }
    val queueStageVisible by remember(presentation, queueAlpha) {
        derivedStateOf { presentation == PlayerPresentation.Queue || queueAlpha.value > 0.001f }
    }
    val slideDistancePx = with(density) { 26.dp.toPx() }

    BoxWithConstraints(modifier = modifier) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val columnWidthDp = 288.dp
        val columnWidthPx = with(density) { columnWidthDp.toPx() }
        val sideMarginDp = (maxWidth * (88f / 960f)).coerceIn(80.dp, 160.dp)
        val leftColumnStartPx = with(density) { sideMarginDp.toPx() }
        val centeredColumnStartPx = ((viewportWidthPx - columnWidthPx) / 2f).coerceAtLeast(0f)

        // Center the lyrics column in the right half with expanded width
        val lyricsStartDp = (maxWidth * 0.43f).coerceAtLeast(sideMarginDp + columnWidthDp + 36.dp)
        val lyricsEndDp = (maxWidth * 0.065f).coerceIn(52.dp, 80.dp)

        // Retain measured viewport rows; never create the whole song's word trees on arrival.
        if (lyricsStageVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = lyricsStartDp, end = lyricsEndDp,
                        top = if (BuildConfig.FLYME_AUTO) 16.dp else 0.dp,
                        bottom = if (BuildConfig.FLYME_AUTO && controlsVisible) 196.dp else 0.dp)
                    .graphicsLayer {
                        alpha = lyricsAlpha.value
                        translationX = (1f - lyricsAlpha.value) * slideDistancePx
                    },
            ) {
                if (track.lyrics.isNotEmpty()) {
                    val lyricScale = if (BuildConfig.FLYME_AUTO) lyricTextSize.scale else 1f
                    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * lyricScale)) {
                    key(lyricScale) {
                    LyricsPanel(
                        lines = track.lyrics,
                        isVisible = isVisibleOnScreen && presentation == PlayerPresentation.Lyrics,
                        positionState = positionState,
                        lyricOffsetMs = lyricOffsetMs,
                        controlsVisible = controlsVisible,
                        stageOffsetPx = supportingStageOffsetPx,
                        selectedLyricIndex = selectedLyricIndex,
                        manualScrollTrigger = manualScrollTrigger,
                        manualScrollDelta = manualScrollDelta,
                    )
                    }
                    }
                } else {
                    LyricsUnavailablePanel(
                        isLoading = lyricsLoading,
                        confirmedUnavailable = lyricsLookupFailed,
                        hasFailed = lyricsTransientFailure,
                        stageOffsetPx = supportingStageOffsetPx,
                    )
                }
            }
        }

        // Persistent Album Artwork + Track Identity column (smoothly glides between Center and Left column)
        if (artworkStageVisible) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    modifier = Modifier
                        .width(columnWidthDp)
                        .graphicsLayer {
                            val currentX = centeredColumnStartPx +
                                (leftColumnStartPx - centeredColumnStartPx) * splitLayoutFraction.value
                            translationX = currentX
                            translationY = stageOffsetPx.value
                            alpha = artworkColumnAlpha.value
                            val queueScale = 0.95f + 0.05f * artworkColumnAlpha.value
                            scaleX = queueScale
                            scaleY = queueScale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MotionArtwork(
                        track = track,
                        modifier = Modifier
                            .size(268.dp)
                            .graphicsLayer {
                                val modeBoost = 1.08f - 0.08f * splitLayoutFraction.value
                                val combinedScale = artworkPlayingScale.value * modeBoost
                                scaleX = combinedScale
                                scaleY = combinedScale
                            },
                        animate = isPlaying && !lowPerformanceMode,
                        cornerRadius = 20.dp,
                        crossfadeTrackChanges = true,
                    )
                    Spacer(Modifier.height(20.dp))
                    TrackIdentity(
                        track = track,
                        titleSize = 20,
                        isPlaying = audioIsPlaying,
                        audioSpectrum = audioSpectrum,
                        bandsEnabled = isVisibleOnScreen && presentation != PlayerPresentation.Queue,
                    )
                }
            }
        }

        // Queue presentation overlay
        if (queueStageVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = queueAlpha.value
                        translationY = (1f - queueAlpha.value) * (slideDistancePx * 0.6f)
                    },
            ) {
                CompositionLocalProvider(LocalFocusFrameVisibility provides queueFocusVisibility) {
                QueuePanel(
                    queue = displayQueue,
                    queueKeys = queueKeys,
                    currentIndex = currentQueueIndex,
                    sessionId = queueSessionId,
                    controlsVisible = controlsVisible,
                    selectedIndex = focusedQueueIndex,
                    selectionVisible = selectionVisible,
                    isPlaying = audioIsPlaying,
                    audioSpectrum = audioSpectrum,
                    bandsEnabled = isVisibleOnScreen && presentation == PlayerPresentation.Queue,
                    onSelectTrack = onSelectQueueTrack,
                    interactive = presentation == PlayerPresentation.Queue && LocalFocusTargetsEnabled.current,
                )
                }
            }
        }
    }
}

@Composable
private fun LyricsUnavailablePanel(
    isLoading: Boolean,
    confirmedUnavailable: Boolean,
    hasFailed: Boolean,
    stageOffsetPx: State<Float>,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .graphicsLayer { translationY = stageOffsetPx.value },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            AppleLoadingIndicator()
        } else if (confirmedUnavailable || hasFailed) {
            Text(
                text = stringResource(if (confirmedUnavailable) {
                    R.string.player_lyrics_unavailable
                } else R.string.player_lyrics_load_failed),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TrackIdentity(
    track: Track,
    titleSize: Int,
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    bandsEnabled: Boolean,
) {
    // Keep the centred artwork column's height independent of fallback font
    // metrics (for example moving between Latin and CJK song names).
    Column(Modifier.fillMaxWidth().height((titleSize * 2 + 14).dp)) {
    Crossfade(targetState = track.title, animationSpec = tween(220), label = "track-title-change") { title ->
    Text(
        text = title,
        fontSize = titleSize.sp,
        lineHeight = (titleSize + 8).sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.95f),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    }
    Spacer(Modifier.height(4.dp))
    ArtistWithAudioBands(
        artist = track.artist,
        fontSize = (titleSize - 4).sp,
        lineHeight = (titleSize + 2).sp,
        color = Color.White.copy(alpha = 0.64f),
        showBands = true,
        isPlaying = isPlaying,
        audioSpectrum = audioSpectrum,
        bandsEnabled = bandsEnabled,
    )
    }
}

@Composable
private fun ArtistWithAudioBands(
    artist: String,
    fontSize: TextUnit,
    color: Color,
    showBands: Boolean,
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    bandsEnabled: Boolean,
    lineHeight: TextUnit = TextUnit.Unspecified,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = artist,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).alignBy(LastBaseline),
        )
        if (showBands) {
            Spacer(Modifier.width(if (compact) 6.dp else 7.dp))
            AudioBandIndicator(
                isPlaying = isPlaying,
                audioSpectrum = audioSpectrum,
                enabled = bandsEnabled,
                modifier = Modifier
                    .size(if (compact) 12.dp else 13.dp, if (compact) 10.dp else 12.dp)
                    .alignBy { it.measuredHeight },
            )
        }
    }
}

/**
 * 60Hz VSYNC phase-locked playback position clock.
 * Advances monotonically on every display frame without restarting on snapshot polls,
 * smoothly correcting WebView IPC jitter while snapping immediately on seeks (>350ms).
 */
@Composable
private fun rememberPlaybackPositionState(
    trackId: String,
    positionAnchorMs: Long,
    playStartedRealtimeMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
): State<Long> {
    val currentAnchorMs by rememberUpdatedState(positionAnchorMs)
    val currentAnchorRealtimeMs by rememberUpdatedState(playStartedRealtimeMs)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val visualPositionMs = remember(trackId, isPlaying) {
        val elapsed = if (isPlaying) (SystemClock.elapsedRealtime() - playStartedRealtimeMs).coerceAtLeast(0L) else 0L
        mutableLongStateOf((positionAnchorMs + elapsed).coerceIn(0L, durationMs.coerceAtLeast(1L)))
    }
    // A restored/paused cursor belongs to this composition, not a later effect.
    // Otherwise a fresh LyricsPanel snapshots the old position for one frame.
    val pausedPosition = rememberUpdatedState(positionAnchorMs.coerceIn(0L, durationMs.coerceAtLeast(1L)))

    LaunchedEffect(trackId, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrameRealtimeMs = SystemClock.elapsedRealtime()
        while (true) {
            withFrameNanos {
                val nowMs = SystemClock.elapsedRealtime()
                val frameDeltaMs = (nowMs - lastFrameRealtimeMs).coerceIn(0L, 64L)
                lastFrameRealtimeMs = nowMs
                val maxDuration = currentDurationMs.coerceAtLeast(1L)
                val rawElapsed = (nowMs - currentAnchorRealtimeMs).coerceAtLeast(0L)
                val targetMs = (currentAnchorMs + rawElapsed).coerceIn(0L, maxDuration)
                val currentMs = visualPositionMs.longValue
                val predictedMs = currentMs + frameDeltaMs
                val errorMs = targetMs - predictedMs
                val nextMs = if (abs(errorMs) > 350L) {
                    targetMs
                } else {
                    val stepMs = (frameDeltaMs + (errorMs * 0.25f).roundToLong())
                        .coerceIn(0L, frameDeltaMs * 2L + 8L)
                    currentMs + stepMs
                }
                visualPositionMs.longValue = nextMs.coerceIn(0L, maxDuration)
            }
        }
    }
    return if (isPlaying) visualPositionMs else pausedPosition
}

private sealed interface LyricsDisplayItem {
    val key: String

    data class Line(
        val lineIndex: Int,
        val line: LyricLine,
    ) : LyricsDisplayItem {
        override val key: String = "line-$lineIndex-${line.startMs}-${line.endMs}"
    }

    data class Songwriters(
        val text: String,
    ) : LyricsDisplayItem {
        override val key: String = "songwriters-footer"
    }
}

internal class LyricsLayoutInfo {
    private var measuredWidth = -1
    private var measuredHeights = FloatArray(0)

    fun heightCache(width: Int, count: Int): FloatArray {
        if (width != measuredWidth || measuredHeights.size != count) {
            measuredWidth = width
            measuredHeights = FloatArray(count) { Float.NaN }
        }
        return measuredHeights
    }

    var itemKeys: List<String> = emptyList()
    var itemTops by mutableStateOf(FloatArray(0))
        private set
    var itemHeights: FloatArray = FloatArray(0)
    var totalHeight by mutableFloatStateOf(0f)

    fun update(keys: List<String>, tops: FloatArray, heights: FloatArray, total: Float) {
        itemKeys = keys
        if (!itemTops.contentEquals(tops)) itemTops = tops
        itemHeights = heights
        if (abs(totalHeight - total) > 0.5f) {
            totalHeight = total
        }
    }

    fun targetScrollFor(itemIndex: Int, focalAnchorY: Float, viewportHeightPx: Float): Float {
        // Effects run after layout. Read both the tops and scroll range here;
        // the composition that created the effect may still have seen height 0.
        return initialLyricScroll(itemTops, itemIndex, focalAnchorY,
            (totalHeight - viewportHeightPx).coerceAtLeast(0f))
    }
}

private data class StaggerScrollTransition(
    val fromScroll: Float,
    val toScroll: Float,
    val startRealtimeMs: Long,
    val durationMs: Int,
    val anchorItemIndex: Int,
)

private data class LyricsRetentionWindow(
    val enabled: Boolean,
    val firstLineIndex: Int,
    val endLineIndex: Int,
    val terminalRelease: TerminalLyricRelease?,
)

/**
 * Reproduces `PlayerLyricsViewFragment`, `M.java`, `Ua.B` (custom staggered FLIP
 * `RecyclerView.ItemAnimator`), and `AlphaGradientFrameLayout`:
 * - Active line is anchored at `LyricsTvScrollAnchorTopFraction` (`36%` of full-bleed TV height,
 *   slightly above vertical center so the previous line remains clearly visible above it).
 * - Smoothly shifts vertically by `stageOffsetPx` (`-28.dp` when bottom controls are visible -> `0.dp`
 *   when controls auto-hide) in lockstep with the left album artwork column.
 * - Top (`9%`) and bottom (`24%` when controls visible / `14%` when hidden) viewport edges are masked
 *   via `BlendMode.DstIn` (`AlphaGradientFrameLayout` in `PlayerLyricsViewFragment.j2`).
 * - Instrumental 3-dot interludes (`• • •`) render in a dedicated slot without mutating `displayItems`
 *   indices or `itemTops[]`, eliminating any layout jump on interlude exit.
 */
@Composable
private fun LyricsPanel(
    lines: List<LyricLine>,
    isVisible: Boolean,
    positionState: State<Long>,
    lyricOffsetMs: Long = 0L,
    controlsVisible: Boolean,
    stageOffsetPx: State<Float>,
    selectedLyricIndex: Int? = null,
    manualScrollTrigger: Int = 0,
    manualScrollDelta: Float = 0f,
) {
    val isStaticMode = remember(lines) { lines.all { it.isStatic } }
    val effectiveLyricPositionState = remember(positionState, lyricOffsetMs) {
        derivedStateOf {
            (positionState.value + lyricOffsetMs).coerceAtLeast(0L)
        }
    }
    val songwriterNames = remember(lines) {
        val writers = lines.firstOrNull()?.songwriters.orEmpty()
        if (writers.isNotEmpty()) writers.joinToString(", ") else null
    }
    val songwritersFooter = songwriterNames?.let { stringResource(R.string.player_songwriters_format, it) }
    val lyricSchedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val terminalRelease = remember(lines, lyricSchedule) {
        terminalLyricRelease(
            lines, lyricSchedule.finalClusterStartIndex, MotionTokens.KaraokeRushGradientMillis.toLong(),
        )
    }
    val lyricVisualPositionState = rememberTerminalLyricPosition(
        lyricSchedule, effectiveLyricPositionState, terminalRelease, isVisible,
    )
    val allInstrumentalGaps = lyricSchedule.instrumentalGaps
    // Publish only singer/anchor/interlude boundaries into composition. Linking
    // every row's derived predicates to the frame clock makes the recomposer
    // revalidate the entire graph even when no composable body needs to run.
    val lyricSnapshotState = remember(lyricSchedule, effectiveLyricPositionState, isStaticMode, isVisible) {
        mutableStateOf(Snapshot.withoutReadObservation {
            if (isStaticMode) LyricTimeline.Snapshot(emptyList(), -1, null)
            else lyricSchedule.snapshotAt(effectiveLyricPositionState.value)
        })
    }
    LaunchedEffect(lyricSchedule, effectiveLyricPositionState, isStaticMode, isVisible) {
        if (isStaticMode) return@LaunchedEffect
        // snapshotFlow suppresses structurally equal snapshots; drawing and
        // placement retain their independent, full-rate word/scroll clocks.
        snapshotFlow { lyricSchedule.snapshotAt(effectiveLyricPositionState.value) }
            .collect { lyricSnapshotState.value = it }
    }
    val lyricSnapshot = lyricSnapshotState.value
    val activeInstrumentalGap = lyricSnapshot.instrumentalGap
    val instrumentalScrollAnchor = lyricSnapshot.instrumentalScrollAnchor
    val baseScrollAnchorLineIndex = if (isStaticMode) -1
    else lyricSnapshot.scrollAnchorLineIndex.takeIf { it >= 0 } ?: 0

    val displayItems = remember(lines, songwritersFooter) {
        buildList {
            lines.forEachIndexed { index, line ->
                add(LyricsDisplayItem.Line(index, line))
            }
            if (songwritersFooter != null) {
                add(LyricsDisplayItem.Songwriters(songwritersFooter))
            }
        }
    }
    val displayItemKeys = remember(displayItems) { displayItems.map { it.key } }

    val textDensity = LocalDensity.current
    val layoutInfo = remember(lines, textDensity.density, textDensity.fontScale) { LyricsLayoutInfo() }
    val mountedRowsState = remember(lines) { mutableStateOf<Set<Int>>(emptySet()) }
    val mountBudget = remember(lines) { LyricRowMountBudget() }
    val transitionState = remember(lines, isVisible) {
        mutableStateOf(
            StaggerScrollTransition(
                fromScroll = 0f,
                toScroll = 0f,
                startRealtimeMs = 0L,
                durationMs = MotionTokens.KaraokeItemChangeMillis,
                anchorItemIndex = 0,
            ),
        )
    }
    var previousAnchorKey by remember(lines, isVisible) { mutableStateOf<String?>(null) }
    var previousAnchorDisplayIndex by remember(lines, isVisible) { mutableIntStateOf(-1) }
    var previousAnchorLineIndex by remember(lines, isVisible) { mutableIntStateOf(-1) }
    var isUserScrolling by remember(lines) { mutableStateOf(false) }
    var userScrollOffset by remember(lines) { mutableFloatStateOf(0f) }
    val frameRealtimeMs = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val bottomFadeFraction by animateFloatAsState(
        targetValue = if (controlsVisible) {
            MotionTokens.LyricsBottomFadeHeightFraction
        } else {
            MotionTokens.LyricsBottomFadeControlsHiddenFraction
        },
        animationSpec = tween(
            durationMillis = MotionTokens.StageControlsShiftDurationMillis,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "lyrics-bottom-fade-fraction",
    )

    LaunchedEffect(transitionState.value) {
        val activeTransition = transitionState.value
        val maxDelay = MotionTokens.staggerDelayForRowBelowActive(MotionTokens.KaraokeStaggerMaxRows)
        val endMs = activeTransition.startRealtimeMs + activeTransition.durationMs + maxDelay + 32L
        while (SystemClock.elapsedRealtime() <= endMs) {
            withFrameNanos {
                frameRealtimeMs.longValue = SystemClock.elapsedRealtime()
            }
        }
    }

    LaunchedEffect(manualScrollTrigger) {
        if (manualScrollTrigger > 0) {
            isUserScrolling = true
            delay(2_500L)
            isUserScrolling = false
        }
    }

    val topEdgeMask = remember { TextureAlphaMask() }
    val bottomEdgeMask = remember { TextureAlphaMask() }
    BoxWithConstraints(
        modifier = Modifier
            .automationId("lyrics-panel")
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Exact AlphaGradientFrameLayout mask from PlayerLyricsViewFragment.j2 (lines 1791-1812)
                val h = size.height
                if (h > 0f) {
                    val topFadeStop = MotionTokens.LyricsTopFadeHeightFraction.coerceIn(0f, 0.4f)
                    val bottomFadeStart = (1f - bottomFadeFraction).coerceIn(topFadeStop, 1f)
                    val canvas = drawContext.canvas.nativeCanvas
                    topEdgeMask.draw(canvas, 0f, 0f, size.width, h,
                        0f, topFadeStop * h, 0f, 1f, vertical = true)
                    bottomEdgeMask.draw(canvas, 0f, 0f, size.width, h,
                        bottomFadeStart * h, h, 1f, 0f, vertical = true)
                }
            },
    ) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val focalAnchorY = (viewportHeightPx * MotionTokens.LyricsTvScrollAnchorTopFraction)
            .roundToInt()
            .toFloat() - with(density) { 2.dp.toPx() }
        val maxScroll = (layoutInfo.totalHeight - viewportHeightPx).coerceAtLeast(0f)
        val nextClusterAfterBase = lyricSchedule.nextClusterStartIndex(baseScrollAnchorLineIndex)
        val retainedBaseAnchorLineIndex = retainedLyricAnchorBase(
            clusterStartIndex = baseScrollAnchorLineIndex,
            previousAnchorIndex = previousAnchorLineIndex,
            nextClusterStartIndex = nextClusterAfterBase,
        )
        val scrollAnchorLineIndex = if (isStaticMode) {
            -1
        } else {
            resolveActiveLyricAnchor(
                baseAnchorIndex = retainedBaseAnchorLineIndex,
                activeLineIndices = lyricSnapshot.activeLineIndices,
                tops = layoutInfo.itemTops,
                heights = layoutInfo.itemHeights,
                focalAnchorY = focalAnchorY,
                viewportHeightPx = viewportHeightPx,
                stageOffsetPx = stageOffsetPx.value,
                maxScroll = maxScroll,
            )
        }
        val effectiveAnchorIndex = selectedLyricIndex ?: scrollAnchorLineIndex
        val anchorDisplayIndex = effectiveAnchorIndex.coerceIn(0, (displayItems.size - 1).coerceAtLeast(0))
        val anchorKey = if (selectedLyricIndex == null && instrumentalScrollAnchor != null) {
            // The interlude and the following lyric share a fixed layout position,
            // but entering the slot must scroll even before that lyric is active.
            "instrumental-anchor-${instrumentalScrollAnchor.beforeLineIndex}-${instrumentalScrollAnchor.startMs}"
        } else displayItems.getOrNull(anchorDisplayIndex)?.key

        val advancesWithinCurrentCluster = previousAnchorLineIndex >= baseScrollAnchorLineIndex &&
            (nextClusterAfterBase < 0 || previousAnchorLineIndex < nextClusterAfterBase) &&
            anchorDisplayIndex >= previousAnchorLineIndex &&
            anchorDisplayIndex in lyricSnapshot.activeLineIndices
        val isNaturalSingingAdvance = previousAnchorDisplayIndex >= 0 &&
            (advancesWithinCurrentCluster ||
                scrollAnchorLineIndex == lyricSchedule.nextClusterStartIndex(previousAnchorLineIndex))
        val snapToAnchor = previousAnchorDisplayIndex == -1 ||
            (!isNaturalSingingAdvance && abs(anchorDisplayIndex - previousAnchorDisplayIndex) > 2)
        // Skipped phrases must not briefly light up before a seek's scroll snap.
        // Manual browsing uses its own focus/secondary colors instead of retention.
        val retainCompletedHighlight = !snapToAnchor && !isUserScrolling && selectedLyricIndex == null
        // Keep all outgoing rows that can still be visibly fading, even when
        // several short phrases retarget a scroll before it has completed.
        val retentionMinimumTop = minOf(transitionState.value.fromScroll, transitionState.value.toScroll) +
            focalAnchorY - with(density) { 148.dp.toPx() } // 64dp fade + 84dp interlude slot
        val retentionStartIndex = layoutInfo.itemTops.indexOfFirst { it >= retentionMinimumTop }
            .takeIf { it >= 0 } ?: lines.size
        val retentionEndIndex = lyricSchedule.nextClusterStartIndex(scrollAnchorLineIndex)
            .takeIf { it >= 0 } ?: lines.size
        val retentionWindowState = rememberUpdatedState(
            LyricsRetentionWindow(
                retainCompletedHighlight, retentionStartIndex, retentionEndIndex, terminalRelease,
            ),
        )

        LaunchedEffect(anchorKey, anchorDisplayIndex, layoutInfo.totalHeight, isUserScrolling, isVisible) {
            if (
                !isVisible ||
                isStaticMode ||
                isUserScrolling ||
                anchorDisplayIndex < 0 ||
                layoutInfo.totalHeight <= 0f
            ) return@LaunchedEffect
            if (anchorKey == previousAnchorKey && previousAnchorDisplayIndex != -1) {
                previousAnchorDisplayIndex = anchorDisplayIndex
                return@LaunchedEffect
            }
            val target = layoutInfo.targetScrollFor(anchorDisplayIndex, focalAnchorY, viewportHeightPx)
            val now = SystemClock.elapsedRealtime()
            val currentTransition = transitionState.value
            val currentScroll = evaluateRowScrollAt(
                transition = currentTransition,
                rowIndex = currentTransition.anchorItemIndex,
                nowRealtimeMs = now,
            )
            val durationMs = if (selectedLyricIndex != null) {
                240
            } else if (
                previousAnchorLineIndex >= 0 &&
                scrollAnchorLineIndex == lyricSchedule.nextClusterStartIndex(previousAnchorLineIndex) &&
                lines.getOrNull(previousAnchorLineIndex)?.timing == LyricTiming.Word
            ) {
                LyricTimeline.dynamicKaraokeClusterAdvanceMs(
                    lines, previousAnchorLineIndex, scrollAnchorLineIndex,
                )
            } else {
                MotionTokens.KaraokeItemChangeMillis
            }

            if (snapToAnchor) {
                transitionState.value = StaggerScrollTransition(
                    fromScroll = target,
                    toScroll = target,
                    startRealtimeMs = now,
                    durationMs = 0,
                    anchorItemIndex = anchorDisplayIndex,
                )
            } else {
                transitionState.value = StaggerScrollTransition(
                    fromScroll = currentScroll,
                    toScroll = target,
                    startRealtimeMs = now,
                    durationMs = durationMs,
                    anchorItemIndex = anchorDisplayIndex,
                )
            }
            userScrollOffset = target
            previousAnchorKey = anchorKey
            previousAnchorDisplayIndex = anchorDisplayIndex
            previousAnchorLineIndex = selectedLyricIndex ?: scrollAnchorLineIndex
        }

        LaunchedEffect(manualScrollTrigger) {
            if (manualScrollTrigger > 0 && manualScrollDelta != 0f) {
                val now = SystemClock.elapsedRealtime()
                val currentTransition = transitionState.value
                val base = if (isUserScrolling) {
                    userScrollOffset
                } else {
                    evaluateRowScrollAt(currentTransition, currentTransition.anchorItemIndex, now)
                }
                val newTarget = (base + manualScrollDelta).coerceIn(0f, maxScroll)
                userScrollOffset = newTarget
                transitionState.value = StaggerScrollTransition(
                    fromScroll = base,
                    toScroll = newTarget,
                    startRealtimeMs = now,
                    durationMs = 220,
                    anchorItemIndex = anchorDisplayIndex.coerceAtLeast(0),
                )
            }
        }

        val estimatedHeights = remember(displayItems, maxWidth, density.density, density.fontScale) {
            FloatArray(displayItems.size) { index ->
                when (val item = displayItems[index]) {
                    is LyricsDisplayItem.Line -> estimateLyricRowHeight(
                        item.line, with(density) { maxWidth.toPx() }, density.density, density.fontScale,
                    )
                    is LyricsDisplayItem.Songwriters -> with(density) { 74.dp.toPx() }
                }
            }
        }
        val currentTransition = transitionState.value
        val requestedRows = lyricViewportMountOrder(
            tops = layoutInfo.itemTops,
            heights = layoutInfo.itemHeights,
            viewportHeight = viewportHeightPx,
            fromScroll = currentTransition.fromScroll,
            toScroll = currentTransition.toScroll,
            anchorIndex = anchorDisplayIndex,
            priorityIndices = lyricSnapshot.activeLineIndices,
            overscan = with(density) { 120.dp.toPx() },
        )
        LaunchedEffect(requestedRows, isVisible) {
            if (!isVisible) return@LaunchedEffect
            val retained = mountedRowsState.value.intersect(requestedRows.toSet())
            if (retained != mountedRowsState.value) mountedRowsState.value = retained
            while (requestedRows.any { it !in mountedRowsState.value }) {
                withFrameNanos { frame ->
                    mountBudget.next(requestedRows, mountedRowsState.value, frame)?.let { index ->
                        mountedRowsState.value = mountedRowsState.value + index
                    }
                }
            }
        }
        val currentGap = activeInstrumentalGap
        LyricsScrollLayout(
            displayItemKeys = displayItemKeys,
            estimatedHeights = estimatedHeights,
            onAnchorHeightCorrection = { userScrollOffset += it },
            allInstrumentalGaps = allInstrumentalGaps,
            activeInstrumentalGap = currentGap,
            positionState = effectiveLyricPositionState,
            transitionState = transitionState,
            nowRealtimeState = frameRealtimeMs,
            layoutInfo = layoutInfo,
            viewportHeightPx = viewportHeightPx,
            focalAnchorY = focalAnchorY,
            stageOffsetPx = stageOffsetPx,
            isStaticMode = isStaticMode,
            initialAnchorItemIndex = anchorDisplayIndex,
            bootstrapVisible = isVisible,
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    if (dev.amatv.BuildConfig.BUILD_TYPE == "benchmark") {
                        stateDescription = when {
                            isStaticMode -> "static"
                            activeInstrumentalGap != null -> "interlude:$anchorDisplayIndex"
                            else -> "line:$anchorDisplayIndex"
                        }
                    }
                },
        ) {
            LyricsRows(
                displayItems = displayItems,
                mountedRowsState = mountedRowsState,
                snapshotState = lyricSnapshotState,
                retentionWindowState = retentionWindowState,
                layoutInfo = layoutInfo,
                transitionState = transitionState,
                frameRealtimeState = frameRealtimeMs,
                positionState = lyricVisualPositionState,
                allInstrumentalGaps = allInstrumentalGaps,
                focalAnchorY = focalAnchorY,
                selectedLyricIndex = selectedLyricIndex,
                isStaticMode = isStaticMode,
                isUserScrolling = isUserScrolling,
            )
            if (currentGap != null) {
                key("instrumental-slot-${currentGap.beforeLineIndex}-${currentGap.startMs}") {
                    Box(Modifier.layoutId(-1)) {
                        InstrumentalInterludeRow(
                            gap = currentGap,
                            positionState = effectiveLyricPositionState,
                            isUserScrolling = isUserScrolling,
                        )
                    }
                }
            }
        }
    }
}

/** Only the final visual tail may outlive the media clock; scrolling keeps its actual position. */
@Composable
private fun rememberTerminalLyricPosition(
    schedule: LyricTimeline.Schedule,
    positionState: State<Long>,
    release: TerminalLyricRelease?,
    isVisible: Boolean,
): State<Long> {
    // The player replaces its clock State when paused. Keep this tail clock alive
    // across that replacement so reaching the song end cannot restart a long note.
    val currentPositionState = rememberUpdatedState(positionState)
    val tailPosition = remember(schedule) { mutableLongStateOf(0L) }
    val visualPosition = remember(schedule) {
        derivedStateOf {
            val actual = currentPositionState.value.value
            if (release == null || actual < release.vocalsEndMs) actual
            else maxOf(actual, tailPosition.longValue)
        }
    }
    LaunchedEffect(schedule, isVisible) {
        if (!isVisible || release == null) return@LaunchedEffect
        snapshotFlow { currentPositionState.value.value >= release.vocalsEndMs }
            .collectLatest { completed ->
                if (!completed) {
                    tailPosition.longValue = 0L
                    return@collectLatest
                }
                val startPosition = maxOf(currentPositionState.value.value, tailPosition.longValue)
                val startedRealtimeMs = SystemClock.elapsedRealtime()
                while (tailPosition.longValue < release.releaseMs) {
                    withFrameNanos {
                        tailPosition.longValue = terminalLyricTailPosition(
                            currentPositionState.value.value,
                            startPosition,
                            SystemClock.elapsedRealtime() - startedRealtimeMs,
                            release,
                        )
                    }
                }
            }
    }
    return visualPosition
}

/** Stable restart boundary: a new scroll/focus does not rebuild every word in the song. */
@Composable
private fun LyricsRows(
    displayItems: List<LyricsDisplayItem>,
    mountedRowsState: State<Set<Int>>,
    snapshotState: State<LyricTimeline.Snapshot>,
    retentionWindowState: State<LyricsRetentionWindow>,
    layoutInfo: LyricsLayoutInfo,
    transitionState: State<StaggerScrollTransition>,
    frameRealtimeState: State<Long>,
    positionState: State<Long>,
    allInstrumentalGaps: List<LyricTimeline.InstrumentalGap>,
    focalAnchorY: Float,
    selectedLyricIndex: Int?,
    isStaticMode: Boolean,
    isUserScrolling: Boolean,
) {
    mountedRowsState.value.sorted().forEach { index ->
        val item = displayItems.getOrNull(index) ?: return@forEach
        key(item.key) {
            Box(Modifier.layoutId(index).automationId("lyrics-row-$index")) {
            when (item) {
                is LyricsDisplayItem.Line -> LyricsLineRow(
                    item = item,
                    snapshotState = snapshotState,
                    retentionWindowState = retentionWindowState,
                    layoutInfo = layoutInfo,
                    transitionState = transitionState,
                    frameRealtimeState = frameRealtimeState,
                    positionState = positionState,
                    allInstrumentalGaps = allInstrumentalGaps,
                    focalAnchorY = focalAnchorY,
                    isFocused = selectedLyricIndex == item.lineIndex,
                    isStaticMode = isStaticMode,
                    isUserScrolling = isUserScrolling,
                )
                is LyricsDisplayItem.Songwriters -> SongwritersFooterRow(item.text)
            }
            }
        }
    }
}

@Composable
private fun LyricsLineRow(
    item: LyricsDisplayItem.Line,
    snapshotState: State<LyricTimeline.Snapshot>,
    retentionWindowState: State<LyricsRetentionWindow>,
    layoutInfo: LyricsLayoutInfo,
    transitionState: State<StaggerScrollTransition>,
    frameRealtimeState: State<Long>,
    positionState: State<Long>,
    allInstrumentalGaps: List<LyricTimeline.InstrumentalGap>,
    focalAnchorY: Float,
    isFocused: Boolean,
    isStaticMode: Boolean,
    isUserScrolling: Boolean,
) {
    // Only the singers entering/leaving a cluster restart their expensive text
    // subtree. Other rows retain the same State objects and measured flexboxes.
    val isActive by remember(item.lineIndex, snapshotState) {
        derivedStateOf { item.lineIndex in snapshotState.value.activeLineIndices }
    }
    val retentionEnabled by remember(item.lineIndex, retentionWindowState) {
        derivedStateOf {
            val window = retentionWindowState.value
            window.enabled && item.lineIndex in window.firstLineIndex until window.endLineIndex
        }
    }
    val density = LocalDensity.current
    val fadeDistance = with(density) { 64.dp.toPx() }
    val interludeAdvance = with(density) { 84.dp.toPx() }
    val renderedRowTop = remember(
        item, layoutInfo, allInstrumentalGaps, interludeAdvance, positionState, retentionEnabled,
        transitionState, frameRealtimeState,
    ) {
        derivedStateOf {
            if (!retentionEnabled || item.lineIndex !in layoutInfo.itemTops.indices) Float.NaN
            else layoutInfo.itemTops[item.lineIndex] - evaluateRowScrollAt(
                transitionState.value, item.lineIndex, frameRealtimeState.value,
            ) + evaluateInstrumentalDisplacementFraction(
                allInstrumentalGaps, item.lineIndex, positionState.value,
            ) * interludeAdvance
        }
    }
    val terminalReleaseMs = remember(item.lineIndex, retentionWindowState) {
        derivedStateOf {
            retentionWindowState.value.terminalRelease?.takeIf {
                item.lineIndex >= it.firstLineIndex
            }?.releaseMs
        }
    }
    val retainedPresence = remember(
        item, renderedRowTop, positionState, focalAnchorY, fadeDistance, terminalReleaseMs,
    ) {
        derivedStateOf {
            if (renderedRowTop.value.isNaN()) 0f
            else LyricTimeline.completedLinePresence(
                line = item.line,
                positionMs = positionState.value,
                rowTop = renderedRowTop.value,
                focalAnchorY = focalAnchorY,
                fadeDistance = fadeDistance,
                terminalReleaseMs = terminalReleaseMs.value,
            )
        }
    }
    val backgroundEndMs = remember(item.line) { item.line.backgroundWords.maxOfOrNull { it.endMs } }
    val retainedBackgroundPresence = remember(
        item, renderedRowTop, positionState, focalAnchorY, fadeDistance, backgroundEndMs, terminalReleaseMs,
    ) {
        derivedStateOf {
            if (backgroundEndMs == null || renderedRowTop.value.isNaN()) 0f
            else LyricTimeline.completedLinePresence(
                line = item.line,
                positionMs = positionState.value,
                rowTop = renderedRowTop.value,
                focalAnchorY = focalAnchorY,
                fadeDistance = fadeDistance,
                completionMs = backgroundEndMs,
                terminalReleaseMs = terminalReleaseMs.value,
            )
        }
    }
    KaraokeLyricLine(
        line = item.line,
        isActive = isActive,
        retainedPresence = retainedPresence,
        retainedBackgroundPresence = retainedBackgroundPresence,
        isFocused = isFocused,
        isStaticMode = isStaticMode,
        isUserScrolling = isUserScrolling,
        positionState = positionState,
    )
}

/**
 * Evaluates the exact per-row scroll offset at [nowRealtimeMs] using `Ua.B`'s
 * 4-row quadratic stagger (`Ua/B.java:110-131`):
 * - Rows `<= anchorItemIndex` move with `delay = 0ms`.
 * - Rows `> anchorItemIndex` move with `delay = staggerDelayForRowBelowActive(rowIndex - anchorItemIndex)`
 *   (`65ms, 114ms, 146ms, 163ms`) and `KaraokeLineEasing` (`PathInterpolator(0.4f, 0.1f, 0.0f, 1.0f)`).
 */
private fun evaluateRowScrollAt(
    transition: StaggerScrollTransition,
    rowIndex: Int,
    nowRealtimeMs: Long,
): Float {
    if (transition.durationMs <= 0 || transition.fromScroll == transition.toScroll) {
        return transition.toScroll
    }
    val rowDelta = if (transition.toScroll >= transition.fromScroll) {
        (rowIndex - transition.anchorItemIndex).coerceAtLeast(0)
    } else {
        0
    }
    val delayMs = MotionTokens.staggerDelayForRowBelowActive(rowDelta)
    val elapsed = nowRealtimeMs - transition.startRealtimeMs - delayMs
    if (elapsed <= 0L) return transition.fromScroll
    if (elapsed >= transition.durationMs) return transition.toScroll
    val fraction = (elapsed.toFloat() / transition.durationMs.toFloat()).coerceIn(0f, 1f)
    val eased = MotionTokens.KaraokeLineEasing.transform(fraction)
    return transition.fromScroll + (transition.toScroll - transition.fromScroll) * eased
}

/**
 * Evaluates the continuous `[0f, 1f]` vertical displacement fraction for rows `>= gap.beforeLineIndex`
 * caused by an instrumental interlude gap (`gap >= 7000ms`), ensuring zero layout jump when the
 * 3-dot interlude enters or exits:
 * - Intro gap (`gap.startMs == 0L`): starts open (`1f`), and starting at `gap.endMs - 500ms`
 *   (coinciding with the 3-dot shrink phase and `line[0]` activation) smoothly collapses `1f -> 0f`
 *   over `750ms` (`KaraokeLineEasing`) with `Ua.B` row stagger.
 * - Mid-song gap (`gap.startMs > 0L`): opens `0f -> 1f` over `750ms` at `gap.startMs`, holds `1f`,
 *   and collapses `1f -> 0f` over `750ms` starting at `gap.endMs - 500ms` with `Ua.B` row stagger.
 */
private fun evaluateInstrumentalDisplacementFraction(
    gaps: List<LyricTimeline.InstrumentalGap>,
    rowIndex: Int,
    positionMs: Long,
): Float {
    if (gaps.isEmpty()) return 0f
    var totalFraction = 0f
    val collapseDurationMs = MotionTokens.KaraokeItemChangeMillis.toFloat()
    for (gIdx in gaps.indices) {
        val gap = gaps[gIdx]
        if (rowIndex < gap.beforeLineIndex) continue
        val collapseStartMs = gap.endMs + LyricTimeline.DEFAULT_LINE_OFFSET_MS // gap.endMs - 500ms
        val rowStaggerMs = MotionTokens.staggerDelayForRowBelowActive(
            (rowIndex - gap.beforeLineIndex).coerceAtLeast(0),
        )
        val collapseEndMs = collapseStartMs + MotionTokens.KaraokeItemChangeMillis + rowStaggerMs
        if (positionMs < gap.startMs || positionMs >= collapseEndMs) continue

        val fraction = when {
            positionMs >= collapseStartMs -> {
                val elapsed = (positionMs - collapseStartMs - rowStaggerMs).toFloat()
                if (elapsed <= 0f) {
                    1f
                } else {
                    val p = (elapsed / collapseDurationMs).coerceIn(0f, 1f)
                    1f - MotionTokens.KaraokeLineEasing.transform(p)
                }
            }
            gap.startMs > 0L && positionMs < gap.startMs + MotionTokens.KaraokeItemChangeMillis -> {
                val p = ((positionMs - gap.startMs).toFloat() / collapseDurationMs).coerceIn(0f, 1f)
                MotionTokens.KaraokeLineEasing.transform(p)
            }
            else -> 1f
        }
        totalFraction += fraction
    }
    return totalFraction.coerceIn(0f, 1f)
}

@Composable
private fun LyricsScrollLayout(
    displayItemKeys: List<String>,
    estimatedHeights: FloatArray,
    onAnchorHeightCorrection: (Float) -> Unit,
    allInstrumentalGaps: List<LyricTimeline.InstrumentalGap>,
    activeInstrumentalGap: LyricTimeline.InstrumentalGap?,
    positionState: State<Long>,
    transitionState: androidx.compose.runtime.MutableState<StaggerScrollTransition>,
    nowRealtimeState: State<Long>,
    layoutInfo: LyricsLayoutInfo,
    viewportHeightPx: Float,
    focalAnchorY: Float,
    stageOffsetPx: State<Float>,
    isStaticMode: Boolean,
    initialAnchorItemIndex: Int,
    bootstrapVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // PlayerLyricsViewFragment.j2: between_line_spacing (32dp or 40dp static) - padding_top (2dp) - padding_bottom (10dp) = 20dp (or 28dp static)
    val spacingPx = with(density) { (if (isStaticMode) 28.dp else 20.dp).toPx() }
    val interludeSlotAdvancePx = with(density) { 64.dp.toPx() } + spacingPx
    val topPaddingPx = if (isStaticMode) with(density) { 36.dp.toPx() } else focalAnchorY
    val bottomPaddingPx = if (isStaticMode) with(density) { 100.dp.toPx() } else (viewportHeightPx - focalAnchorY)

    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val lineCount = displayItemKeys.size
        val heightCache = layoutInfo.heightCache(constraints.maxWidth, lineCount)
        val linePlaceables = HashMap<Int, androidx.compose.ui.layout.Placeable>()
        var interludePlaceable: androidx.compose.ui.layout.Placeable? = null
        for (measurable in measurables) {
            val index = measurable.layoutId as? Int ?: continue
            val placeable = measurable.measure(childConstraints)
            if (index == -1) interludePlaceable = placeable
            else if (index in 0 until lineCount) {
                linePlaceables[index] = placeable
                heightCache[index] = placeable.height.toFloat()
            }
        }

        val tops = FloatArray(lineCount)
        val heights = FloatArray(lineCount)
        var currentY = topPaddingPx
        for (i in 0 until lineCount) {
            tops[i] = currentY
            val height = heightCache[i].takeUnless { it.isNaN() } ?: estimatedHeights[i]
            heights[i] = height
            currentY += height + spacingPx
        }
        val total = if (lineCount > 0) currentY - spacingPx + bottomPaddingPx else 0f
        // Refining an unseen prefix must not move the singing line on screen.
        // Both endpoints move equally, retaining the exact stagger/easing phase.
        val transition = transitionState.value
        val isOpening = bootstrapVisible && transition.startRealtimeMs == 0L
        val correction = lyricAnchorHeightCorrection(layoutInfo.itemTops, tops, transition.anchorItemIndex)
        if (isOpening) {
            val scroll = if (isStaticMode) 0f else initialLyricScroll(
                tops, initialAnchorItemIndex, focalAnchorY, (total - viewportHeightPx).coerceAtLeast(0f),
            )
            transitionState.value = StaggerScrollTransition(
                fromScroll = scroll,
                toScroll = scroll,
                startRealtimeMs = SystemClock.elapsedRealtime(),
                durationMs = 0,
                anchorItemIndex = initialAnchorItemIndex,
            )
        } else if (abs(correction) > 0.01f) {
            transitionState.value = transition.copy(
                fromScroll = transition.fromScroll + correction,
                toScroll = transition.toScroll + correction,
            )
            onAnchorHeightCorrection(correction)
        }
        layoutInfo.update(displayItemKeys, tops, heights, total)

        val measuredInterlude = interludePlaceable
        layout(constraints.maxWidth, constraints.maxHeight) {
            val activeTransition = transitionState.value
            val nowRealtimeMs = SystemClock.elapsedRealtime().coerceAtLeast(nowRealtimeState.value)
            val posMs = positionState.value
            val stageOffset = stageOffsetPx.value
            val interludeAdvance = if (measuredInterlude != null) {
                measuredInterlude.height.toFloat() + spacingPx
            } else {
                interludeSlotAdvancePx
            }

            if (measuredInterlude != null && activeInstrumentalGap != null) {
                val targetIdx = activeInstrumentalGap.beforeLineIndex.coerceIn(
                    0,
                    (lineCount - 1).coerceAtLeast(0),
                )
                if (targetIdx in tops.indices) {
                    val slotScroll = evaluateRowScrollAt(activeTransition, targetIdx, nowRealtimeMs)
                    val slotY = tops[targetIdx] - slotScroll + stageOffset
                    if (
                        slotY + measuredInterlude.height >= -80f &&
                        slotY <= constraints.maxHeight + 80f
                    ) {
                        measuredInterlude.placeRelative(0, slotY.roundToInt())
                    }
                }
            }

            for ((i, placeable) in linePlaceables) {
                val rowScroll = evaluateRowScrollAt(activeTransition, i, nowRealtimeMs)
                val interludeOffset = evaluateInstrumentalDisplacementFraction(
                    gaps = allInstrumentalGaps,
                    rowIndex = i,
                    positionMs = posMs,
                ) * interludeAdvance
                val y = tops[i] - rowScroll + interludeOffset + stageOffset
                if (y + heights[i] >= -80f && y <= constraints.maxHeight + 80f) {
                    placeable.placeRelative(0, y.roundToInt())
                }
            }
        }
    }
}

/** Three gradually lit dots, with a continuous pulse-to-exit handoff. */
@Composable
private fun InstrumentalInterludeRow(
    gap: LyricTimeline.InstrumentalGap,
    positionState: State<Long>,
    isUserScrolling: Boolean,
) {
    val unlitAlpha = if (isUserScrolling) {
        MotionTokens.KaraokeSecondaryAlpha
    } else {
        MotionTokens.KaraokeTertiaryAlpha
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 84.dp, top = 18.dp, bottom = 26.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(66.dp)
                .height(20.dp)
                .graphicsLayer {
                    val pos = positionState.value
                    val pulseScale = InterludeMotion.scale(gap, pos)
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = InterludeMotion.alpha(gap, pos)
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        ) {
            val pos = positionState.value
            val elapsedInGap = (pos - gap.startMs).coerceAtLeast(0L)
            val dotRadius = 5.dp.toPx()
            val dotStepX = 22.dp.toPx()
            val firstCenterX = 11.dp.toPx()
            val centerY = size.height / 2f

            for (dotIndex in 0 until 3) {
                val enterElapsed = (elapsedInGap - dotIndex * MotionTokens.InstrumentalEnterStaggerMillis)
                    .coerceAtLeast(0L)
                val enterFraction = (enterElapsed.toFloat() / MotionTokens.InstrumentalEnterMillis)
                    .coerceIn(0f, 1f)
                val enterAlpha = enterFraction * enterFraction

                val tintFraction = InterludeMotion.dotFraction(gap, pos, dotIndex)
                val tintProgress = MotionTokens.InstrumentalDotTintEasing.transform(tintFraction)
                val dotTintAlpha = unlitAlpha + (MotionTokens.KaraokePrimaryAlpha - unlitAlpha) * tintProgress

                drawCircle(
                    color = Color.White.copy(alpha = (dotTintAlpha * enterAlpha).coerceIn(0f, 1f)),
                    radius = dotRadius,
                    center = Offset(firstCenterX + dotIndex * dotStepX, centerY),
                )
            }
        }
    }
}

/**
 * Songwriters footer row (`K0.j(songInfo)` in `A.java`, `U0.java`, `L.java`,
 * `lyrics_line_songwriters.xml`): `15sp` Regular, `lineHeight = 19sp`,
 * `player_vibrant_secondary` (`0.45f`), `paddingTop = 26dp, paddingBottom = 10dp`.
 */
@Composable
private fun SongwritersFooterRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 84.dp, top = 26.dp, bottom = 10.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = MotionTokens.KaraokeSecondaryAlpha),
        )
    }
}

/**
 * Renders a lyric line using the exact single-pass `FullWidthAlphaGradientFlexboxLayout`
 * architecture from `A.java` (Syllable/Karaoke), `U0.java` (Line-synced), and `L.java` (Static):
 * - Single-pass white glyphs masked by per-flex-line `BlendMode.DstIn` 4-stop horizontal gradients
 *   (no duplicate unlit/lit `FlowRow` layers, eliminating emphasis double-vision).
 * - Dedicated `bg_line_flexbox` below `karaoke_line_flexbox` (`marginTop = 4dp`, `24sp` Bold,
 *   lit alpha `0.35f`, unlit alpha `0.18f`) for `ttm:role="x-bg"` background vocals.
 * - Duet alignment (`0.85f` width fraction when `isMultiAgentSong`, right-aligned when `isOppositeAgent`).
 * - Stacked pronunciation above words (`17sp` SemiBold, lit alpha `0.35f`) and translation below
 *   (`24sp` SemiBold for main translation, `19sp` SemiBold for background vocal translation).
 */
@Composable
private fun KaraokeLyricLine(
    line: LyricLine,
    isActive: Boolean,
    retainedPresence: State<Float>,
    retainedBackgroundPresence: State<Float>,
    isFocused: Boolean = false,
    isStaticMode: Boolean,
    isUserScrolling: Boolean,
    positionState: State<Long>,
) {
    val hasMainPronunciation = !line.pronunciationText.isNullOrBlank() ||
        line.words.any { !it.pronunciationText.isNullOrBlank() }
    val compactMainLocalization = hasMainPronunciation && !line.translation.isNullOrBlank()
    val hasBackgroundPronunciation = !line.backgroundPronunciationText.isNullOrBlank() ||
        line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }
    val compactBackgroundLocalization = hasBackgroundPronunciation &&
        !line.backgroundTranslation.isNullOrBlank()

    if (isStaticMode || line.timing == LyricTiming.None) {
        val staticPronunciation = line.pronunciationText
            ?: line.words.mapNotNull(LyricWord::pronunciationText).joinToString(" ").takeIf(String::isNotBlank)
        val staticMainFontSize = if (compactMainLocalization) 22.sp else 24.sp
        val staticMainLineHeight = if (compactMainLocalization) 29.sp else 32.sp
        val staticPronunciationFontSize = if (compactMainLocalization) 15.sp else 17.sp
        val staticPronunciationLineHeight = if (compactMainLocalization) 19.sp else 22.sp
        val staticTranslationFontSize = if (compactMainLocalization) 17.sp else 19.sp
        val staticTranslationLineHeight = if (compactMainLocalization) 22.sp else 25.sp
        val staticLocalizationGap = if (compactMainLocalization) 2.dp else 4.dp
        val staticBackgroundPronunciation = line.backgroundPronunciationText
            ?: line.backgroundWords.mapNotNull(LyricWord::pronunciationText)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
        val staticBackgroundTypography = backgroundVocalTypography(
            flymeAuto = BuildConfig.FLYME_AUTO,
            isStaticMode = true,
            compactMainLocalization = compactMainLocalization,
            compactBackgroundLocalization = compactBackgroundLocalization,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 84.dp, top = 2.dp, bottom = 10.dp)
                .then(if (isFocused) Modifier.background(
                    Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp),
                ) else Modifier)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Column {
                if (!staticPronunciation.isNullOrBlank()) {
                    Text(
                        text = staticPronunciation,
                        fontSize = staticPronunciationFontSize,
                        lineHeight = staticPronunciationLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.58f),
                        modifier = Modifier.padding(bottom = staticLocalizationGap),
                    )
                }
                Text(
                    text = line.text,
                    fontSize = staticMainFontSize,
                    lineHeight = staticMainLineHeight,
                    fontWeight = LyricPrimaryWeight,
                    color = Color.White.copy(alpha = if (isFocused) 1f else MotionTokens.KaraokePrimaryAlpha),
                )
                if (!line.translation.isNullOrBlank()) {
                    Text(
                        text = line.translation,
                        fontSize = staticTranslationFontSize,
                        lineHeight = staticTranslationLineHeight,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier.padding(top = staticLocalizationGap),
                    )
                }
                if (BuildConfig.FLYME_AUTO && !staticBackgroundPronunciation.isNullOrBlank()) {
                    Text(
                        text = staticBackgroundPronunciation,
                        fontSize = staticBackgroundTypography.pronunciationSizeSp.sp,
                        lineHeight = staticBackgroundTypography.pronunciationLineHeightSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.48f),
                        modifier = Modifier.padding(
                            top = 4.dp,
                            bottom = staticBackgroundTypography.localizationGapDp.dp,
                        ),
                    )
                }
                if (line.backgroundWords.isNotEmpty()) {
                    Text(
                        text = "(${line.backgroundText})",
                        fontSize = staticBackgroundTypography.fontSizeSp.sp,
                        lineHeight = staticBackgroundTypography.lineHeightSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.52f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!line.backgroundTranslation.isNullOrBlank()) {
                    Text(
                        text = line.backgroundTranslation,
                        fontSize = staticBackgroundTypography.translationSizeSp.sp,
                        lineHeight = staticBackgroundTypography.translationLineHeightSp.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.48f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        return
    }

    val isLineSyncMode = line.timing == LyricTiming.Line
    val isMainActive by remember(line, isActive, positionState) {
        derivedStateOf { isActive && positionState.value < line.mainEndMs }
    }
    val isBackgroundActive by remember(line, isActive, positionState) {
        derivedStateOf {
            isActive && line.backgroundWords.isNotEmpty() &&
                positionState.value < line.backgroundWords.maxOf { it.endMs }
        }
    }
    val isRtl = remember(line.text) { LyricTimeline.isRightToLeft(line.text) }
    val alignEnd = line.isOppositeAgent != isRtl
    val maxWidthFraction = if (line.isMultiAgentSong) {
        MotionTokens.KaraokeDuetLineWidthPercent
    } else {
        1f
    }
    val hasRetainedPresence by remember(retainedPresence, retainedBackgroundPresence) {
        derivedStateOf { retainedPresence.value > 0f || retainedBackgroundPresence.value > 0f }
    }

    // Exact scale animation from A.java (0ms/800ms activate, 150ms/800ms deactivate)
    // and U0.java (150ms/500ms activate, 150ms/600ms deactivate) with E.f43134h (LineScaleEasing)
    val activeScale = animateFloatAsState(
        targetValue = if (isActive || hasRetainedPresence) {
            MotionTokens.KaraokeLineHighlightScale
        }
        else MotionTokens.KaraokeLineNormalScale,
        animationSpec = if (isLineSyncMode) {
            if (isActive) {
                tween(
                    durationMillis = MotionTokens.LineSyncScaleActivateMillis,
                    delayMillis = MotionTokens.LineSyncActivateDelayMillis,
                    easing = MotionTokens.LineScaleEasing,
                )
            } else {
                tween(
                    durationMillis = MotionTokens.LineSyncScaleDeactivateMillis,
                    delayMillis = MotionTokens.LineSyncDeactivateDelayMillis,
                    easing = MotionTokens.LineScaleEasing,
                )
            }
        } else {
            if (isActive) {
                tween(
                    durationMillis = MotionTokens.KaraokeLineActivateMillis,
                    delayMillis = MotionTokens.KaraokeLineActivateDelayMillis,
                    easing = MotionTokens.LineScaleEasing,
                )
            } else {
                tween(
                    durationMillis = MotionTokens.KaraokeLineDeactivateMillis,
                    delayMillis = MotionTokens.KaraokeLineDeactivateDelayMillis,
                    easing = MotionTokens.LineScaleEasing,
                )
            }
        },
        label = "lyric-line-scale",
    )

    val timedPresence = animateVocalPresence(isMainActive, isLineSyncMode, "lyric-line-presence")
    val timedBackgroundPresence = animateVocalPresence(
        isBackgroundActive, isLineSyncMode, "lyric-background-presence",
    )
    // Keep frame-varying alpha in draw/layer scopes. Passing a Float here would
    // recompose and remeasure the entire word flexbox on every fade frame.
    val activePresence = rememberContinuousVocalPresence(timedPresence, retainedPresence)
    val backgroundPresence = rememberContinuousVocalPresence(timedBackgroundPresence, retainedBackgroundPresence)

    val unlitAlpha = if (isFocused) {
        MotionTokens.KaraokePrimaryAlpha
    } else if (isUserScrolling) {
        MotionTokens.KaraokeSecondaryAlpha
    } else {
        MotionTokens.KaraokeTertiaryAlpha
    }
    val fullLineStartPadding = if (alignEnd) 0.dp else 6.dp
    val fullLineEndPadding = if (alignEnd) 6.dp else 0.dp
    val mainFontSize = if (compactMainLocalization) 31.sp else 34.sp
    val mainLineHeight = if (compactMainLocalization) 37.sp else 41.sp
    val mainPronunciationFontSize = if (compactMainLocalization) 15.sp else 17.sp
    val mainPronunciationLineHeight = if (compactMainLocalization) 19.sp else 22.sp
    val mainTranslationFontSize = if (compactMainLocalization) 21.sp else 24.sp
    val mainTranslationLineHeight = if (compactMainLocalization) 25.sp else 29.sp
    val mainLocalizationGap = if (compactMainLocalization) 2.dp else 4.dp
    val backgroundTypography = backgroundVocalTypography(
        flymeAuto = BuildConfig.FLYME_AUTO,
        isStaticMode = false,
        compactMainLocalization = compactMainLocalization,
        compactBackgroundLocalization = compactBackgroundLocalization,
    )
    val backgroundFontSize = backgroundTypography.fontSizeSp.sp
    val backgroundLineHeight = backgroundTypography.lineHeightSp.sp
    val backgroundPronunciationFontSize = backgroundTypography.pronunciationSizeSp.sp
    val backgroundPronunciationLineHeight = backgroundTypography.pronunciationLineHeightSp.sp
    val backgroundTranslationFontSize = backgroundTypography.translationSizeSp.sp
    val backgroundTranslationLineHeight = backgroundTypography.translationLineHeightSp.sp
    val backgroundLocalizationGap = backgroundTypography.localizationGapDp.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)
            .then(if (isFocused) Modifier.background(
                Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp),
            ) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(maxWidthFraction)
                .graphicsLayer {
                    scaleX = activeScale.value
                    scaleY = activeScale.value
                    // Align anchor strictly to top of rendered box: pivotY = 0f ensures top edge never shifts
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (alignEnd) 1f else 0f,
                        pivotFractionY = 0f,
                    )
                },
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            if (isLineSyncMode) {
                LineSyncedContent(
                    line = line,
                    activePresence = activePresence,
                    backgroundPresence = backgroundPresence,
                    unlitAlpha = unlitAlpha,
                    alignEnd = alignEnd,
                    compactMainLocalization = compactMainLocalization,
                    compactBackgroundLocalization = compactBackgroundLocalization,
                )
            } else {
                // Main vocal FullWidthAlphaGradientFlexboxLayout (karaoke_line_flexbox)
                if (!line.pronunciationText.isNullOrBlank() &&
                    line.words.none { !it.pronunciationText.isNullOrBlank() }
                ) {
                    Text(
                        text = line.pronunciationText,
                        fontSize = mainPronunciationFontSize,
                        lineHeight = mainPronunciationLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = fullLineStartPadding,
                                end = fullLineEndPadding,
                                bottom = mainLocalizationGap,
                            )
                            .graphicsLayer {
                            alpha = unlitAlpha +
                                (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) * activePresence.value
                        },
                    )
                }
                if (line.words.isNotEmpty()) {
                    KaraokeVocalFlexbox(
                        line = line,
                        words = line.words,
                        isBackgroundVocal = false,
                        isActive = isMainActive,
                        activePresence = activePresence,
                        unlitAlpha = unlitAlpha,
                        alignEnd = alignEnd,
                        positionState = positionState,
                        fontSize = mainFontSize,
                        lineHeight = mainLineHeight,
                        pronunciationFontSize = mainPronunciationFontSize,
                        pronunciationLineHeight = mainPronunciationLineHeight,
                        pronunciationGap = mainLocalizationGap,
                    )
                }
                if (!line.translation.isNullOrBlank()) {
                    Text(
                        text = line.translation,
                        fontSize = mainTranslationFontSize,
                        lineHeight = mainTranslationLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = fullLineStartPadding,
                                end = fullLineEndPadding,
                                top = mainLocalizationGap,
                            )
                            .graphicsLayer {
                            alpha = unlitAlpha +
                                (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) * activePresence.value
                        },
                    )
                }
                // Background vocal FullWidthAlphaGradientFlexboxLayout (bg_line_flexbox)
                if (line.backgroundWords.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    if (!line.backgroundPronunciationText.isNullOrBlank() &&
                        line.backgroundWords.none { !it.pronunciationText.isNullOrBlank() }
                    ) {
                        Text(
                            text = line.backgroundPronunciationText,
                            fontSize = backgroundPronunciationFontSize,
                            lineHeight = backgroundPronunciationLineHeight,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = fullLineStartPadding,
                                    end = fullLineEndPadding,
                                    bottom = backgroundLocalizationGap,
                                )
                                .graphicsLayer {
                                alpha = unlitAlpha +
                                    (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) *
                                    backgroundPresence.value
                            },
                        )
                    }
                    KaraokeVocalFlexbox(
                        line = line,
                        words = line.backgroundWords,
                        isBackgroundVocal = true,
                        isActive = isBackgroundActive,
                        activePresence = backgroundPresence,
                        unlitAlpha = unlitAlpha,
                        alignEnd = alignEnd,
                        positionState = positionState,
                        fontSize = backgroundFontSize,
                        lineHeight = backgroundLineHeight,
                        pronunciationFontSize = backgroundPronunciationFontSize,
                        pronunciationLineHeight = backgroundPronunciationLineHeight,
                        pronunciationGap = backgroundLocalizationGap,
                    )
                }
                if (!line.backgroundTranslation.isNullOrBlank()) {
                    Text(
                        text = line.backgroundTranslation,
                        fontSize = backgroundTranslationFontSize,
                        lineHeight = backgroundTranslationLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = fullLineStartPadding,
                                end = fullLineEndPadding,
                                top = backgroundLocalizationGap,
                            )
                            .graphicsLayer {
                            alpha = unlitAlpha +
                                (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) * backgroundPresence.value
                        },
                    )
                }
            }
        }
    }
}

/** Retention still decides when dimming may begin; this only bounds its visible falling speed. */
@Composable
private fun rememberContinuousVocalPresence(
    timedPresence: State<Float>,
    retainedPresence: State<Float>,
): State<Float> {
    val target = remember(timedPresence, retainedPresence) {
        derivedStateOf { maxOf(timedPresence.value, retainedPresence.value) }
    }
    val presence = remember { mutableFloatStateOf(Snapshot.withoutReadObservation { target.value }) }
    LaunchedEffect(target) {
        var lastTickMs = SystemClock.elapsedRealtime()
        snapshotFlow { target.value }.collectLatest { desired ->
            val now = SystemClock.elapsedRealtime()
            // A stationary bright line may have been held for many seconds. Start
            // its release from the painted value, not from that idle time interval.
            val elapsed = (now - lastTickMs).takeIf { it in 0L..64L } ?: 0L
            presence.floatValue = advanceLyricPresence(presence.floatValue, desired, elapsed)
            lastTickMs = now
            while (presence.floatValue > desired) {
                withFrameNanos {
                    val frameMs = SystemClock.elapsedRealtime()
                    presence.floatValue = advanceLyricPresence(
                        presence.floatValue, desired, frameMs - lastTickMs,
                    )
                    lastTickMs = frameMs
                }
            }
        }
    }
    return presence
}

@Composable
private fun animateVocalPresence(isActive: Boolean, isLineSyncMode: Boolean, label: String): State<Float> =
    animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = if (isLineSyncMode) {
            tween(
                durationMillis = if (isActive) MotionTokens.LineSyncColorActivateMillis
                else MotionTokens.LineSyncColorDeactivateMillis,
                delayMillis = if (isActive) MotionTokens.LineSyncActivateDelayMillis
                else MotionTokens.LineSyncDeactivateDelayMillis,
                easing = MotionTokens.KaraokeGradientExitEasing,
            )
        } else {
            tween(
                durationMillis = if (isActive) MotionTokens.KaraokeHighlightActivateMillis
                else MotionTokens.KaraokeHighlightDeactivateMillis,
                delayMillis = if (isActive) 0 else MotionTokens.KaraokeHighlightDeactivateDelayMillis,
                easing = LinearEasing,
            )
        },
        label = label,
    )

/**
 * Line-synced (`U0.java`, `lyrics_line.xml`) rendering:
 * - Optional pronunciation line (`17sp` SemiBold, `lineHeight = 22sp`, `marginBottom = 4dp`, alpha `0.35f` when active).
 * - Main line (`34sp` Bold, `lineHeight = 41sp`, alpha `0.94f` when active).
 * - Optional translation line (`24sp` SemiBold, `lineHeight = 29sp`, `marginTop = 4dp`, alpha `0.35f` when active).
 * - Optional background vocal line (`24sp` Bold, `lineHeight = 29sp`, `marginTop = 4dp`, alpha `0.35f` when active).
 */
@Composable
private fun LineSyncedContent(
    line: LyricLine,
    activePresence: State<Float>,
    backgroundPresence: State<Float>,
    unlitAlpha: Float,
    alignEnd: Boolean,
    compactMainLocalization: Boolean,
    compactBackgroundLocalization: Boolean,
) {
    val mainAlphaModifier = Modifier.graphicsLayer {
        alpha = unlitAlpha + (MotionTokens.KaraokePrimaryAlpha - unlitAlpha) * activePresence.value
    }
    val subAlphaModifier = Modifier.graphicsLayer {
        alpha = unlitAlpha + (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) * activePresence.value
    }
    val backgroundAlphaModifier = Modifier.graphicsLayer {
        alpha = unlitAlpha + (MotionTokens.KaraokeBgVocalLitAlpha - unlitAlpha) * backgroundPresence.value
    }
    val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
    val mainFontSize = if (compactMainLocalization) 31.sp else 34.sp
    val mainLineHeight = if (compactMainLocalization) 37.sp else 41.sp
    val pronunciationFontSize = if (compactMainLocalization) 15.sp else 17.sp
    val pronunciationLineHeight = if (compactMainLocalization) 19.sp else 22.sp
    val translationFontSize = if (compactMainLocalization) 21.sp else 24.sp
    val translationLineHeight = if (compactMainLocalization) 25.sp else 29.sp
    val mainGap = if (compactMainLocalization) 2.dp else 4.dp
    val backgroundTypography = backgroundVocalTypography(
        flymeAuto = BuildConfig.FLYME_AUTO,
        isStaticMode = false,
        compactMainLocalization = compactMainLocalization,
        compactBackgroundLocalization = compactBackgroundLocalization,
    )
    val backgroundFontSize = backgroundTypography.fontSizeSp.sp
    val backgroundLineHeight = backgroundTypography.lineHeightSp.sp
    val backgroundPronunciationFontSize = backgroundTypography.pronunciationSizeSp.sp
    val backgroundPronunciationLineHeight = backgroundTypography.pronunciationLineHeightSp.sp
    val backgroundTranslationFontSize = backgroundTypography.translationSizeSp.sp
    val backgroundTranslationLineHeight = backgroundTypography.translationLineHeightSp.sp
    val backgroundGap = backgroundTypography.localizationGapDp.dp

    if (!line.pronunciationText.isNullOrBlank()) {
        Text(
            text = line.pronunciationText,
            fontSize = pronunciationFontSize,
            lineHeight = pronunciationLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().then(subAlphaModifier).padding(bottom = mainGap),
        )
    }
    Text(
        text = line.text,
        fontSize = mainFontSize,
        lineHeight = mainLineHeight,
        fontWeight = LyricPrimaryWeight,
        color = Color.White,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth().then(mainAlphaModifier),
    )
    if (!line.translation.isNullOrBlank()) {
        Text(
            text = line.translation,
            fontSize = translationFontSize,
            lineHeight = translationLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().then(subAlphaModifier).padding(top = mainGap),
        )
    }
    if (line.backgroundWords.isNotEmpty()) {
        if (!line.backgroundPronunciationText.isNullOrBlank()) {
            Text(
                text = line.backgroundPronunciationText,
                fontSize = backgroundPronunciationFontSize,
                lineHeight = backgroundPronunciationLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().then(backgroundAlphaModifier).padding(top = backgroundGap),
            )
        }
        Text(
            text = "(${line.backgroundText})",
            fontSize = backgroundFontSize,
            lineHeight = backgroundLineHeight,
            fontWeight = if (BuildConfig.FLYME_AUTO) FontWeight.SemiBold else LyricPrimaryWeight,
            color = Color.White,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().then(backgroundAlphaModifier).padding(top = backgroundGap),
        )
    }
    if (!line.backgroundTranslation.isNullOrBlank()) {
        Text(
            text = line.backgroundTranslation,
            fontSize = backgroundTranslationFontSize,
            lineHeight = backgroundTranslationLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().then(backgroundAlphaModifier).padding(top = backgroundGap),
        )
    }
}

private class MaskRowData(
    val indices: IntArray,
    val top: Float,
    val bottom: Float,
)

private class MaskRowCache(wordCount: Int) {
    val wordBounds: Array<Rect?> = arrayOfNulls(wordCount)
    var rows: List<MaskRowData> = emptyList()
    val alphaMask = TextureAlphaMask()
}

/**
 * Plans wrapped karaoke rows while preserving ordinary non-breaking syllable groups.
 * A malformed or unusually long group is allowed to break between its timed words;
 * keeping it atomic would place glyphs outside the measured width.
 */
internal fun lyricFlexRows(
    groupIds: List<Int?>,
    wordWidths: IntArray,
    maxWidth: Int,
): List<IntArray> {
    require(groupIds.size == wordWidths.size)
    if (groupIds.isEmpty()) return emptyList()

    val availableWidth = maxWidth.coerceAtLeast(1)
    val units = buildList {
        var index = 0
        while (index < groupIds.size) {
            val groupId = groupIds[index]
            if (groupId == null) {
                add(intArrayOf(index++))
            } else {
                var end = index + 1
                while (end < groupIds.size && groupIds[end] == groupId) end++
                add(IntArray(end - index) { offset -> index + offset })
                index = end
            }
        }
    }

    val rows = ArrayList<IntArray>(4)
    val current = ArrayList<Int>(8)
    var currentWidth = 0

    fun flush() {
        if (current.isEmpty()) return
        rows += current.toIntArray()
        current.clear()
        currentWidth = 0
    }

    fun appendUnit(indices: IntArray) {
        val unitWidth = indices.sumOf { wordWidths[it].coerceAtLeast(0) }
        if (current.isNotEmpty() && currentWidth + unitWidth > availableWidth) flush()
        indices.forEach(current::add)
        currentWidth += unitWidth
    }

    for (unit in units) {
        val unitWidth = unit.sumOf { wordWidths[it].coerceAtLeast(0) }
        if (unit.size > 1 && unitWidth > availableWidth) {
            // Retain word boundaries and timing, but do not let one producer-side
            // emphasis group defeat the viewport's hard width constraint.
            for (wordIndex in unit) appendUnit(intArrayOf(wordIndex))
        } else {
            appendUnit(unit)
        }
    }
    flush()
    return rows
}

/**
 * Single-pass `FullWidthAlphaGradientFlexboxLayout` for either main vocals (`34sp` Bold,
 * lit alpha `0.94f`) or background vocals (`24sp` Bold, lit alpha `0.35f`).
 * - Groups non-breaking sub-word syllables (`emphasisGroupId != null`) into atomic clusters
 *   (`lyrics_karaoke_non_breaking_span.xml`) so line wrapping never splits a word across rows.
 * - Computes local word `Rect` bounds and flex rows directly during `Layout` measure without
 *   `onGloballyPositioned` or `positionInRoot()`.
 * - Allocates `CompositingStrategy.Offscreen` and reads `positionState.value` ONLY when the line
 *   is actively highlighted (`isActive || activePresence > 0.001f`). Inactive lines render with a
 *   simple `graphicsLayer { alpha = unlitAlpha }` and zero per-frame invalidation.
 */
@Composable
private fun KaraokeVocalFlexbox(
    line: LyricLine,
    words: List<LyricWord>,
    isBackgroundVocal: Boolean,
    isActive: Boolean,
    activePresence: State<Float>,
    unlitAlpha: Float,
    alignEnd: Boolean,
    positionState: State<Long>,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    pronunciationFontSize: TextUnit,
    pronunciationLineHeight: TextUnit,
    pronunciationGap: Dp,
) {
    val litAlpha = if (isBackgroundVocal) {
        MotionTokens.KaraokeBgVocalLitAlpha
    } else {
        MotionTokens.KaraokePrimaryAlpha
    }
    val isHighlighting by remember(isActive, activePresence) {
        derivedStateOf { isActive || activePresence.value > 0.001f }
    }
    val isRtl = remember(words) {
        LyricTimeline.isRightToLeft(words.joinToString("") { it.text })
    }
    val rowCache = remember(words) { MaskRowCache(words.size) }
    val density = LocalDensity.current
    val rowSpacingPx = with(density) { 4.dp.roundToPx() }

    val containerModifier = if (isHighlighting) {
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawKaraokeFlexboxMask(
                    words = words,
                    isRtl = isRtl,
                    positionMs = positionState.value,
                    activePresence = activePresence.value,
                    litAlpha = litAlpha,
                    unlitAlpha = unlitAlpha,
                    rowCache = rowCache,
                )
            }
    } else {
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = unlitAlpha
            }
    }

    Layout(
        modifier = containerModifier,
        content = {
            for (wordIdx in words.indices) {
                KaraokeWordItem(
                    line = line,
                    words = words,
                    wordIndex = wordIdx,
                    isBackgroundVocal = isBackgroundVocal,
                    animateMotion = isHighlighting,
                    activePresence = activePresence,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    pronunciationFontSize = pronunciationFontSize,
                    pronunciationLineHeight = pronunciationLineHeight,
                    pronunciationGap = pronunciationGap,
                    positionState = positionState,
                )
            }
        },
    ) { measurables, constraints ->
        val innerPaddingPx = with(density) { 12.dp.roundToPx() }
        val startBleedPx = with(density) { 6.dp.toPx() }
        val contentMaxWidth = (constraints.maxWidth - innerPaddingPx).coerceAtLeast(1)
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = contentMaxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val wordX = IntArray(placeables.size)
        val wordY = IntArray(placeables.size)
        val builtRows = ArrayList<MaskRowData>(4)

        var rowTop = 0

        val rows = lyricFlexRows(
            groupIds = words.map(LyricWord::emphasisGroupId),
            wordWidths = IntArray(placeables.size) { placeables[it].width },
            maxWidth = contentMaxWidth,
        )
        for (rowIndices in rows) {
            val rowWidth = rowIndices.sumOf { placeables[it].width }
            var rowBaseline = 0
            var rowBelowBaseline = 0
            val baselines = IntArray(rowIndices.size)
            for (indexInRow in rowIndices.indices) {
                val placeable = placeables[rowIndices[indexInRow]]
                val baseline = placeable[LastBaseline]
                    .takeUnless { it == androidx.compose.ui.layout.AlignmentLine.Unspecified }
                    ?.coerceIn(0, placeable.height)
                    ?: placeable.height
                baselines[indexInRow] = baseline
                rowBaseline = maxOf(rowBaseline, baseline)
                rowBelowBaseline = maxOf(rowBelowBaseline, placeable.height - baseline)
            }
            val rowHeight = rowBaseline + rowBelowBaseline
            var x = if (alignEnd) {
                (constraints.maxWidth - rowWidth - startBleedPx).coerceAtLeast(0f).roundToInt()
            } else {
                startBleedPx.roundToInt()
            }
            val topF = rowTop.toFloat()
            val bottomF = (rowTop + rowHeight).toFloat()

            for (indexInRow in rowIndices.indices) {
                val wordIndex = rowIndices[indexInRow]
                val placeable = placeables[wordIndex]
                val y = rowTop + rowBaseline - baselines[indexInRow]
                wordX[wordIndex] = x
                wordY[wordIndex] = y
                rowCache.wordBounds[wordIndex] = Rect(
                    left = x.toFloat(),
                    top = topF,
                    right = (x + placeable.width).toFloat(),
                    bottom = bottomF,
                )
                x += placeable.width
            }
            builtRows.add(
                MaskRowData(
                    indices = rowIndices,
                    top = topF,
                    bottom = bottomF,
                ),
            )
            rowTop += rowHeight + rowSpacingPx
        }

        rowCache.rows = builtRows
        val totalHeight = if (builtRows.isNotEmpty()) {
            (rowTop - rowSpacingPx).coerceAtLeast(0)
        } else {
            0
        }

        layout(constraints.maxWidth, totalHeight) {
            for (i in placeables.indices) {
                placeables[i].placeRelative(wordX[i], wordY[i])
            }
        }
    }
}

/**
 * Renders a single word/syllable inside `KaraokeVocalFlexbox`, including:
 * - Optional stacked pronunciation word above (`lyrics_pronunciation_vertical_group.xml`).
 * - Spring lift (`-2dp`, `dampingRatio = 0.93f, stiffness = 25f`) starting at `word.startMs - 100ms`.
 * - Character-split long-emphasis wave (`z0` in `A.java:2078-2491`) when eligible
 *   (`duration >= 1000ms`, `charCount in 1..7`, non-CJK, non-excluded script, main vocal).
 */
@Composable
private fun KaraokeWordItem(
    line: LyricLine,
    words: List<LyricWord>,
    wordIndex: Int,
    isBackgroundVocal: Boolean,
    animateMotion: Boolean,
    activePresence: State<Float>,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    pronunciationFontSize: TextUnit,
    pronunciationLineHeight: TextUnit,
    pronunciationGap: Dp,
    positionState: State<Long>,
) {
    val word = words[wordIndex]
    val renderedString = remember(word, wordIndex, words.size, line.joinWithSpaces) {
        if (word.displayText != word.text) {
            word.displayText
        } else if (line.joinWithSpaces && word.hasTrailingWhitespace && wordIndex < words.lastIndex) {
            "${word.text} "
        } else {
            word.text
        }
    }

    val charTimings = remember(line, wordIndex, isBackgroundVocal) {
        if (isBackgroundVocal) {
            emptyList()
        } else {
            LyricTimeline.characterEmphasisTimings(
                line = line,
                wordIndex = wordIndex,
                wordOffsetMs = LyricTimeline.KARAOKE_WORD_OFFSET_MS,
            )
        }
    }

    val groupIndices = remember(words, wordIndex) {
        val gid = word.emphasisGroupId
        if (gid != null) {
            words.indices.filter { words[it].emphasisGroupId == gid }
        } else {
            listOf(wordIndex)
        }
    }
    val charStartOffsetInGroup = remember(words, groupIndices, wordIndex) {
        var offset = 0
        for (idx in groupIndices) {
            if (idx == wordIndex) break
            offset += words[idx].text.trim().length
        }
        offset
    }

    val density = LocalDensity.current
    val liftDistancePx = with(density) { MotionTokens.KaraokeWordLiftDp.dp.toPx() }
    val shadowRadiusPx = with(density) { MotionTokens.KaraokeEmphasisShadowDp.dp.toPx() }
    val baseTextStyle = LocalTextStyle.current
    val shadowStyle = remember(shadowRadiusPx, baseTextStyle) {
        baseTextStyle.copy(
            shadow = Shadow(
                color = Color.White,
                blurRadius = shadowRadiusPx,
            ),
        )
    }

    val wordStartWithOffset = word.startMs + LyricTimeline.KARAOKE_WORD_OFFSET_MS
    val vocalEndMs = if (isBackgroundVocal) {
        remember(words) { words.maxOfOrNull { it.endMs } ?: line.mainEndMs }
    } else line.mainEndMs
    val emphasisEasing = remember { { fraction: Float -> MotionTokens.KaraokeEmphasisEasing.transform(fraction) } }

    val wordModifier = if (animateMotion) {
        Modifier.graphicsLayer {
            val lift = LyricTimeline.wordLiftFraction(
                positionMs = positionState.value,
                riseStartMs = wordStartWithOffset,
                vocalEndMs = vocalEndMs,
                activePresence = activePresence.value,
                dampingRatio = MotionTokens.KaraokeSpringDampingRatio,
                stiffness = MotionTokens.KaraokeSpringStiffness,
            )
            translationY = liftDistancePx * lift
        }
    } else {
        Modifier
    }

    Column(
        // The wider of the lyric and pronunciation owns the word cell. Anchor both
        // to the same leading edge so every pronunciation starts directly above
        // the lyric syllable it describes; the parent layout aligns lyric baselines
        // across the complete flex row.
        horizontalAlignment = Alignment.Start,
        modifier = wordModifier,
    ) {
        if (!word.pronunciationText.isNullOrBlank()) {
            Text(
                text = if (word.hasTrailingWhitespace && wordIndex < words.lastIndex) {
                    "${word.pronunciationText} "
                } else {
                    word.pronunciationText
                },
                fontSize = pronunciationFontSize,
                lineHeight = pronunciationLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(
                    alpha = MotionTokens.KaraokeBgVocalLitAlpha / MotionTokens.KaraokePrimaryAlpha,
                ),
                modifier = Modifier.padding(bottom = pronunciationGap),
            )
        }

        if (charTimings.isEmpty()) {
            Text(
                text = renderedString,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = if (isBackgroundVocal && BuildConfig.FLYME_AUTO) {
                    FontWeight.SemiBold
                } else LyricPrimaryWeight,
                color = Color.White,
            )
        } else {
            // Character-split long-emphasis wave (`z0` in A.java lines 2078-2491)
            val charWidthsPx = remember(renderedString) {
                FloatArray(renderedString.length)
            }
            val charBaselines = remember(renderedString) {
                FloatArray(renderedString.length) { 0.8f }
            }
            val isWordRtl = remember(renderedString) {
                LyricTimeline.isRightToLeft(renderedString)
            }

            Row(verticalAlignment = Alignment.Bottom) {
                for (localCharIdx in 0 until renderedString.length) {
                    val ch = renderedString[localCharIdx]
                    if (ch == ' ') {
                        Text(
                            text = " ",
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontWeight = if (isBackgroundVocal && BuildConfig.FLYME_AUTO) {
                                FontWeight.SemiBold
                            } else LyricPrimaryWeight,
                            color = Color.White,
                        )
                    } else {
                        val groupCharIdx = (charStartOffsetInGroup + localCharIdx)
                            .coerceIn(0, charTimings.lastIndex)
                        val charTiming = charTimings[groupCharIdx]
                        val charBoxModifier = if (animateMotion) {
                            Modifier.graphicsLayer {
                                val pos = positionState.value
                                val presence = activePresence.value
                                val amount = LyricTimeline.characterEmphasisAmountAt(
                                    charTiming, pos, vocalEndMs, presence, emphasisEasing,
                                )
                                val charScale = 1f + (charTiming.maximumScale - 1f) * amount
                                val visibleLengths = renderedString.trimEnd().length
                                val halfExpansions = List(visibleLengths) { cIdx ->
                                    val gIdx = (charStartOffsetInGroup + cIdx)
                                        .coerceIn(0, charTimings.lastIndex)
                                    val cAmount = LyricTimeline.characterEmphasisAmountAt(
                                        charTimings[gIdx], pos, vocalEndMs, presence, emphasisEasing,
                                    )
                                    val cScale = 1f + (charTimings[gIdx].maximumScale - 1f) * cAmount
                                    val w = if (cIdx in charWidthsPx.indices) charWidthsPx[cIdx] else 0f
                                    (cScale - 1f) * w * 0.5f
                                }
                                val offsets = LyricTimeline.symmetricExpansionOffsets(
                                    halfExpansions = halfExpansions,
                                    isRtl = isWordRtl,
                                )
                                scaleX = charScale
                                scaleY = charScale
                                translationX = offsets.getOrElse(localCharIdx) { 0f }
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = 0.5f,
                                    pivotFractionY = charBaselines[localCharIdx],
                                )
                            }
                        } else {
                            Modifier
                        }
                        Box(modifier = charBoxModifier) {
                            if (animateMotion) {
                                Text(
                                    text = ch.toString(),
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    fontWeight = if (isBackgroundVocal && BuildConfig.FLYME_AUTO) {
                                        FontWeight.SemiBold
                                    } else LyricPrimaryWeight,
                                    color = Color.Transparent,
                                    style = shadowStyle,
                                    modifier = Modifier.graphicsLayer {
                                        val amount = LyricTimeline.characterEmphasisAmountAt(
                                            charTiming,
                                            positionState.value,
                                            vocalEndMs,
                                            activePresence.value,
                                            emphasisEasing,
                                        )
                                        alpha = MotionTokens.KaraokeEmphasisShadowAlpha * amount
                                    },
                                )
                            }
                            Text(
                                text = ch.toString(),
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = if (isBackgroundVocal && BuildConfig.FLYME_AUTO) {
                                    FontWeight.SemiBold
                                } else LyricPrimaryWeight,
                                color = Color.White,
                                onTextLayout = { result ->
                                    charWidthsPx[localCharIdx] = result.size.width.toFloat()
                                    if (result.size.height > 0) {
                                        charBaselines[localCharIdx] =
                                            (result.lastBaseline / result.size.height).coerceIn(0f, 1f)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exact reproduction of `FullWidthAlphaGradientFlexboxLayout` + `A.i0` / `A.q0` / `A.h0`
 * from Apple Music 6.5.3 (`A.java` lines 1126-1171, 1244-1251, 4303-4484):
 * - Draws a single `BlendMode.DstIn` clamped two-color gradient with the original four-stop feather.
 * - Uses `litColor = Color.White.copy(alpha = effectiveLitAlpha)` and
 *   `unlitColor = Color.White.copy(alpha = unlitAlpha)` so both lit and unlit portions are
 *   masked in a single pass over the same white glyphs!
 * - Applies `h0` feather adjustment (`1.0f * feather` at the end of a flex row, `0.0f` for CJK,
 *   `0.5f / 0.25f / 0.12f * feather` for non-CJK based on word/row width ratio `0.2f / 0.4f`)
 *   and the `500ms` tail sweep (`KARAOKE_SPLIT_ON_CHARS_LANGUAGES_RUSH_GRADIENT_TAG`).
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKaraokeFlexboxMask(
    words: List<LyricWord>,
    isRtl: Boolean,
    positionMs: Long,
    activePresence: Float,
    litAlpha: Float,
    unlitAlpha: Float,
    rowCache: MaskRowCache,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val wordBounds = rowCache.wordBounds

    val effectiveLitAlpha = unlitAlpha + (litAlpha - unlitAlpha) * activePresence
    val litColor = Color.White.copy(alpha = effectiveLitAlpha)
    val unlitColor = Color.White.copy(alpha = unlitAlpha)

    if (rowCache.rows.isEmpty() || activePresence <= 0.001f) {
        drawRect(
            color = unlitColor,
            topLeft = Offset.Zero,
            size = size,
            blendMode = BlendMode.DstIn,
        )
        return
    }

    val featherPx = MotionTokens.KaraokeGradientFeatherDp.dp.toPx()
    val featherFraction = (featherPx / size.width).coerceIn(0.001f, 1f)
    val effectivePosMs = positionMs - LyricTimeline.KARAOKE_WORD_OFFSET_MS

    rowCache.rows.forEachIndexed { rowIndex, row ->
        val first = row.indices.first()
        val last = row.indices.last()
        val firstWord = words[first]
        val lastWord = words[last]
        val isLastWordCjk = LyricTimeline.containsSplitOnCharsScript(lastWord.text)
        val tailRushMs = if (isLastWordCjk) 0L else MotionTokens.KaraokeRushGradientMillis.toLong()

        val rowTop = if (rowIndex == 0) 0f else {
            (rowCache.rows[rowIndex - 1].bottom + row.top) / 2f
        }
        val rowBottom = if (rowIndex == rowCache.rows.lastIndex) size.height else {
            (row.bottom + rowCache.rows[rowIndex + 1].top) / 2f
        }

        val firstBounds = wordBounds[first]
        val lastBounds = wordBounds[last]
        val rowTextWidth = if (firstBounds != null && lastBounds != null) {
            abs(maxOf(firstBounds.right, lastBounds.right) - minOf(firstBounds.left, lastBounds.left))
                .coerceAtLeast(1f)
        } else {
            size.width
        }

        // Compute monotonic cumulative progress across words on this flex row (`aVar.f35409i` in A.i0)
        var rowProgress = if (isRtl) 1f else 0f
        for (localIdx in row.indices.indices) {
            val wordIdx = row.indices[localIdx]
            val word = words[wordIdx]
            val bounds = wordBounds[wordIdx] ?: continue
            val isLastOnRow = wordIdx == last
            val isCjk = LyricTimeline.containsSplitOnCharsScript(word.text)

            // Exact h0(...) feather adjustment from A.java lines 1126-1171
            val h0Bonus = when {
                isLastOnRow -> featherFraction * 1.0f
                isCjk -> 0f
                else -> {
                    val ratio = bounds.width / rowTextWidth
                    when {
                        ratio <= 0.2f -> featherFraction * 0.5f
                        ratio <= 0.4f -> featherFraction * 0.25f
                        else -> featherFraction * 0.12f
                    }
                }
            }

            val wordStartFraction = ((if (isRtl) bounds.right else bounds.left) / size.width)
                .coerceIn(0f, 1f)
            val wordSpanFraction = (bounds.width / size.width).coerceAtLeast(0f)
            val startAnchor = if (!isRtl) {
                maxOf(rowProgress, wordStartFraction)
            } else {
                minOf(rowProgress, wordStartFraction)
            }
            val adjustedSpan = (wordSpanFraction - if (!isRtl) {
                startAnchor - wordStartFraction
            } else {
                wordStartFraction - startAnchor
            } + h0Bonus).coerceAtLeast(0f)

            if (effectivePosMs < word.startMs) {
                break
            }
            val wordFrac = ((effectivePosMs - word.startMs).toFloat() /
                word.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            rowProgress = if (!isRtl) {
                startAnchor + adjustedSpan * wordFrac
            } else {
                startAnchor - adjustedSpan * wordFrac
            }

            if (isLastOnRow && effectivePosMs > word.endMs && tailRushMs > 0L) {
                val rushFrac = ((effectivePosMs - word.endMs).toFloat() / tailRushMs).coerceIn(0f, 1f)
                val rushTarget = if (!isRtl) 1f + featherFraction else -featherFraction
                rowProgress += (rushTarget - rowProgress) * rushFrac
            }
            if (effectivePosMs <= word.endMs) {
                break
            }
        }

        val isFullyUnlit = effectivePosMs < firstWord.startMs
        val isFullyLit = effectivePosMs >= lastWord.endMs + tailRushMs

        when {
            isFullyUnlit -> {
                drawRect(
                    color = unlitColor,
                    topLeft = Offset(0f, rowTop),
                    size = Size(size.width, rowBottom - rowTop),
                    blendMode = BlendMode.DstIn,
                )
            }
            isFullyLit -> {
                drawRect(
                    color = litColor,
                    topLeft = Offset(0f, rowTop),
                    size = Size(size.width, rowBottom - rowTop),
                    blendMode = BlendMode.DstIn,
                )
            }
            else -> {
                val ramp = karaokeMaskRamp(rowProgress, featherFraction, isRtl)
                rowCache.alphaMask.draw(
                    canvas = drawContext.canvas.nativeCanvas,
                    left = 0f, top = rowTop, right = size.width, bottom = rowBottom,
                    start = ramp.start * size.width, end = ramp.end * size.width,
                    startAlpha = if (isRtl) unlitAlpha else effectiveLitAlpha,
                    endAlpha = if (isRtl) effectiveLitAlpha else unlitAlpha,
                )
            }
        }
    }
}

@Composable
private fun QueuePanel(
    queue: List<Track>,
    queueKeys: List<Any>,
    currentIndex: Int,
    sessionId: Long,
    controlsVisible: Boolean,
    selectedIndex: Int,
    selectionVisible: Boolean,
    isPlaying: Boolean,
    audioSpectrum: () -> AudioSpectrum,
    bandsEnabled: Boolean,
    onSelectTrack: (Int) -> Unit,
    interactive: Boolean,
) {
    val density = LocalDensity.current
    val targetStageOffsetPx = with(density) {
        if (controlsVisible) {
            MotionTokens.SupportingStageControlsVisibleVerticalOffsetDp.dp.toPx()
        } else 0f
    }
    val stageOffsetPx by animateFloatAsState(
        targetValue = targetStageOffsetPx,
        animationSpec = tween(
            durationMillis = MotionTokens.StageControlsShiftDurationMillis,
            easing = MotionTokens.KaraokeLineEasing,
        ),
        label = "queue-stage-vertical-offset",
    )
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    var positioned by remember(sessionId) { mutableStateOf(false) }
    val targetIndex = if (selectionVisible) selectedIndex else currentIndex
    LaunchedEffect(targetIndex, sessionId) {
        if (queue.isEmpty()) return@LaunchedEffect
        val index = targetIndex.coerceIn(queue.indices)
        if (positioned) listState.animateScrollToItem(index)
        else { listState.scrollToItem(index); positioned = true }
    }

    BoxWithConstraints(Modifier.fillMaxSize().automationId("queue-panel")) {
        val cardWidth = 176.dp
        val sideInset = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 64.dp)
                .graphicsLayer {
                    translationY = stageOffsetPx
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = sideInset, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(
                    items = queue,
                    key = { index, item -> queueKeys.getOrNull(index) ?: "$index:${item.id}" },
                ) { index, item ->
                    val isCurrent = index == currentIndex
                    val focused = selectionVisible && index == selectedIndex
                    val scale by animateFloatAsState(
                        targetValue = if (focused) 1.035f else 1f,
                        animationSpec = tween(
                            durationMillis = 180,
                            easing = MotionTokens.KaraokeLineEasing,
                        ),
                        label = "queue-focus-scale",
                    )
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .clickable(enabled = interactive) { onSelectTrack(index) },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MotionArtwork(
                                track = item,
                                modifier = Modifier
                                    .size(176.dp)
                                    .focusFrameTarget(
                                        selected = focused,
                                        key = "player-queue:${queueKeys.getOrNull(index) ?: index}",
                                        cornerRadius = 16.dp,
                                        visualScale = scale,
                                    )
                                    .zIndex(if (focused) 1f else 0f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                                animate = focused,
                                cornerRadius = 16.dp,
                            )
                            Spacer(Modifier.height(11.dp))
                            Text(
                                text = item.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(
                                    alpha = if (focused || isCurrent) 1f else 0.72f,
                                ),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ArtistWithAudioBands(
                                artist = item.artist,
                                fontSize = 12.sp,
                                color = Color.White.copy(
                                    alpha = if (focused || isCurrent) 0.68f else 0.46f,
                                ),
                                showBands = isCurrent,
                                isPlaying = isPlaying,
                                audioSpectrum = audioSpectrum,
                                bandsEnabled = bandsEnabled,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueModeControls(
    repeatMode: PlaybackRepeatMode,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    autoplaySource: dev.amatv.integration.AutoplaySource,
    canSetRepeat: Boolean,
    canSetShuffle: Boolean,
    canSetAutoplay: Boolean,
    canClearQueue: Boolean,
    focusedMode: QueueModeControl?,
    onModeClick: (QueueModeControl) -> Unit,
) {
            Row(Modifier.automationId("queue-mode-controls"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QueueModeButton(
                    icon = Icons.Rounded.Shuffle,
                    label = stringResource(R.string.player_shuffle),
                    selected = shuffleEnabled,
                    focused = focusedMode == QueueModeControl.Shuffle,
                    enabled = canSetShuffle && !autoplayEnabled,
                    frameKey = "player-mode:shuffle",
                    onClick = { onModeClick(QueueModeControl.Shuffle) },
                )
                QueueModeButton(
                    icon = if (repeatMode == PlaybackRepeatMode.One) {
                        Icons.Rounded.RepeatOne
                    } else Icons.Rounded.Repeat,
                    label = stringResource(when (repeatMode) {
                        PlaybackRepeatMode.Off -> R.string.player_repeat_off
                        PlaybackRepeatMode.One -> R.string.player_repeat_one
                        PlaybackRepeatMode.All -> R.string.player_repeat_all
                    }),
                    selected = repeatMode != PlaybackRepeatMode.Off,
                    focused = focusedMode == QueueModeControl.Repeat,
                    enabled = canSetRepeat && !autoplayEnabled,
                    frameKey = "player-mode:repeat",
                    onClick = { onModeClick(QueueModeControl.Repeat) },
                )
                QueueModeButton(
                    icon = Icons.Rounded.AllInclusive,
                    label = stringResource(R.string.player_autoplay),
                    selected = autoplayEnabled,
                    focused = focusedMode == QueueModeControl.Autoplay,
                    enabled = canSetAutoplay,
                    frameKey = "player-mode:autoplay",
                    onClick = { onModeClick(QueueModeControl.Autoplay) },
                    ytmBadge = false,
                )
                QueueModeButton(
                    icon = Icons.Rounded.AutoAwesome,
                    label = stringResource(R.string.player_source_format, stringResource(
                        if (autoplaySource == dev.amatv.integration.AutoplaySource.YouTubeMusic) {
                            R.string.player_provider_ytm
                        } else R.string.player_provider_apple_music,
                    )),
                    selected = true,
                    focused = focusedMode == QueueModeControl.Source,
                    enabled = true,
                    frameKey = "player-mode:source",
                    onClick = { onModeClick(QueueModeControl.Source) },
                    ytmBadge = false,
                )
                QueueModeButton(
                    icon = Icons.Rounded.ClearAll,
                    label = stringResource(R.string.player_clear_queue),
                    selected = false,
                    focused = focusedMode == QueueModeControl.Clear,
                    enabled = canClearQueue,
                    frameKey = "player-mode:clear",
                    onClick = { onModeClick(QueueModeControl.Clear) },
                    ytmBadge = false,
                )
            }
}

@Composable
private fun QueueModeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    focused: Boolean,
    enabled: Boolean,
    frameKey: String,
    ytmBadge: Boolean = false,
    onClick: () -> Unit,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(180, easing = MotionTokens.KaraokeLineEasing),
        label = "queue-mode-focus",
    )
    Row(
        modifier = Modifier
            .focusFrameTarget(focused, frameKey, 22.dp, visualScale = scale)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = interactive, onClick = onClick)
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.94f)
                    selected && ytmBadge -> Color(0xFFE32636).copy(alpha = 0.38f)
                    selected -> Color.White.copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.1f)
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                focused -> Color.Black
                else -> Color.White
            },
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                focused -> Color.Black
                else -> Color.White.copy(alpha = 0.82f)
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (ytmBadge) {
            Text(
                text = stringResource(R.string.player_provider_ytm),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE32636))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
