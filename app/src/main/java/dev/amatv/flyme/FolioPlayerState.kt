package dev.amatv.flyme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.LyricsLoadPhase
import dev.amatv.core.LyricsState
import dev.amatv.core.PlaybackState
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricAgentType
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.renderLyricWords
import dev.amatv.playback.AutoplayStatus
import dev.amatv.playback.QueueEntry
import dev.amatv.playback.QueueState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Process-scoped player sheet visibility (non-persisted): stays open across full-screen <->
 * small-window transitions in the same process, while cold starts always initialize collapsed.
 */
object FolioPlayerSession {
    val openState: MutableState<Boolean> = mutableStateOf(false)
    val queueOpenState: MutableState<Boolean> = mutableStateOf(false)

    fun reset() {
        openState.value = false
        queueOpenState.value = false
    }
}

enum class FolioPlayerDisplayState {
    Lyrics,
    Poster,
    Queue,
    None,
}

@Immutable
data class FolioPlayerTierSpec(
    val tier: FolioTier,
    val fs: TextUnit,
    val pfs: TextUnit,
    val lyricGap: Dp,
    val pib: Dp,
    val rd: Dp,
    val pp: Dp,
    val qc: Dp,
    val qt: TextUnit,
    val cw2: Dp,
    val anchorFraction: Float,
    val horizontalPad: Dp,
    val topPad: Dp,
    val bottomPad: Dp,
    val soakCoverWidth: Dp,
    val soakCoverHeightFraction: Float,
    val controlCardWidth: Dp,
    val isHorizontalCarControls: Boolean,
    val isHorizontalQueue: Boolean,
)

fun playerSpecForTier(tier: FolioTier): FolioPlayerTierSpec = when (tier) {
    FolioTier.Narrow -> FolioPlayerTierSpec(
        tier = tier,
        fs = 28.sp,
        pfs = 40.sp,
        lyricGap = 18.dp,
        pib = 44.dp,
        rd = 54.dp,
        pp = 66.dp,
        qc = 48.dp,
        qt = 16.sp,
        cw2 = 104.dp,
        anchorFraction = 0.30f,
        horizontalPad = 18.dp,
        topPad = 10.dp,
        bottomPad = 14.dp,
        soakCoverWidth = Dp.Unspecified,
        soakCoverHeightFraction = 0.46f,
        controlCardWidth = Dp.Unspecified,
        isHorizontalCarControls = false,
        isHorizontalQueue = false,
    )
    FolioTier.Short -> FolioPlayerTierSpec(
        tier = tier,
        fs = 24.sp,
        pfs = 34.sp,
        lyricGap = 14.dp,
        pib = 40.dp,
        rd = 46.dp,
        pp = 56.dp,
        qc = 44.dp,
        qt = 15.sp,
        cw2 = 104.dp,
        anchorFraction = 0.40f,
        horizontalPad = 24.dp,
        topPad = 8.dp,
        bottomPad = 12.dp,
        soakCoverWidth = 560.dp,
        soakCoverHeightFraction = 1f,
        controlCardWidth = 336.dp,
        isHorizontalCarControls = false,
        isHorizontalQueue = true,
    )
    FolioTier.Medium -> FolioPlayerTierSpec(
        tier = tier,
        fs = 30.sp,
        pfs = 44.sp,
        lyricGap = 18.dp,
        pib = 48.dp,
        rd = 58.dp,
        pp = 70.dp,
        qc = 52.dp,
        qt = 17.sp,
        cw2 = 160.dp,
        anchorFraction = 0.32f,
        horizontalPad = 28.dp,
        topPad = 14.dp,
        bottomPad = 18.dp,
        soakCoverWidth = Dp.Unspecified,
        soakCoverHeightFraction = 0.40f,
        controlCardWidth = 420.dp,
        isHorizontalCarControls = false,
        isHorizontalQueue = false,
    )
    FolioTier.Wide -> FolioPlayerTierSpec(
        tier = tier,
        fs = 34.sp,
        pfs = 56.sp,
        lyricGap = 22.dp,
        pib = 56.dp,
        rd = 66.dp,
        pp = 80.dp,
        qc = 56.dp,
        qt = 18.sp,
        cw2 = 160.dp,
        anchorFraction = 0.38f,
        horizontalPad = 36.dp,
        topPad = 18.dp,
        bottomPad = 20.dp,
        soakCoverWidth = 760.dp,
        soakCoverHeightFraction = 1f,
        controlCardWidth = Dp.Unspecified,
        isHorizontalCarControls = true,
        isHorizontalQueue = true,
    )
    FolioTier.Ultrawide -> FolioPlayerTierSpec(
        tier = tier,
        fs = 32.sp,
        pfs = 52.sp,
        lyricGap = 20.dp,
        pib = 56.dp,
        rd = 64.dp,
        pp = 76.dp,
        qc = 52.dp,
        qt = 17.sp,
        cw2 = 160.dp,
        anchorFraction = 0.40f,
        horizontalPad = 40.dp,
        topPad = 18.dp,
        bottomPad = 20.dp,
        soakCoverWidth = 720.dp,
        soakCoverHeightFraction = 1f,
        controlCardWidth = 360.dp,
        isHorizontalCarControls = false,
        isHorizontalQueue = false,
    )
}

enum class FolioLyricsAvailability {
    Pending,
    Ready,
    Unavailable,
}

/**
 * Resolves whether the current track's lyrics are still loading (`Pending`), loaded (`Ready`),
 * or confirmed absent/failed (`Unavailable`). Switching tracks stays `Pending` while waiting on a
 * new `Song` so the player preserves the user's selected lyrics/poster view instead of flashing `None`.
 */
fun resolveLyricsAvailability(
    hasCurrentTrack: Boolean,
    trackCatalogId: String?,
    trackCatalogKind: CatalogKind,
    isSignedIn: Boolean,
    displayedLineCount: Int,
    lyricsState: LyricsState,
    currentIdentity: String,
): FolioLyricsAvailability {
    if (!hasCurrentTrack) return FolioLyricsAvailability.Unavailable
    if (displayedLineCount > 0) return FolioLyricsAvailability.Ready
    if (trackCatalogId.isNullOrBlank() || trackCatalogKind != CatalogKind.Song || !isSignedIn) {
        return FolioLyricsAvailability.Unavailable
    }
    if (lyricsState.trackIdentity != currentIdentity) {
        return FolioLyricsAvailability.Pending
    }
    return when (lyricsState.phase) {
        LyricsLoadPhase.Idle,
        LyricsLoadPhase.Loading -> FolioLyricsAvailability.Pending
        LyricsLoadPhase.Ready,
        LyricsLoadPhase.Unavailable,
        LyricsLoadPhase.Failed -> FolioLyricsAvailability.Unavailable
    }
}

/** Resolves the primary content state from H5 `sync()` / `s-lyrics|s-poster|s-queue|s-none`. */
fun resolvePlayerDisplayState(
    tier: FolioTier,
    queueOpen: Boolean,
    posterPreferred: Boolean,
    lyricsAvailability: FolioLyricsAvailability,
    hasTimedLyrics: Boolean,
): FolioPlayerDisplayState = when {
    queueOpen && tier != FolioTier.Ultrawide -> FolioPlayerDisplayState.Queue
    lyricsAvailability == FolioLyricsAvailability.Unavailable -> FolioPlayerDisplayState.None
    posterPreferred && (hasTimedLyrics || lyricsAvailability == FolioLyricsAvailability.Pending) ->
        FolioPlayerDisplayState.Poster
    else -> FolioPlayerDisplayState.Lyrics
}

fun resolvePlayerDisplayState(
    tier: FolioTier,
    queueOpen: Boolean,
    posterPreferred: Boolean,
    hasLyrics: Boolean,
    hasTimedLyrics: Boolean,
): FolioPlayerDisplayState = resolvePlayerDisplayState(
    tier = tier,
    queueOpen = queueOpen,
    posterPreferred = posterPreferred,
    lyricsAvailability = if (hasLyrics) FolioLyricsAvailability.Ready else FolioLyricsAvailability.Unavailable,
    hasTimedLyrics = hasTimedLyrics,
)

/** Queue panel visibility: pinned 3rd column on `u`, overlay on `Queue`, fallback on `m`/`t`/`e` `None`. */
fun isQueuePanelVisible(
    tier: FolioTier,
    displayState: FolioPlayerDisplayState,
): Boolean = when (tier) {
    FolioTier.Ultrawide -> true
    FolioTier.Narrow -> displayState == FolioPlayerDisplayState.Queue
    FolioTier.Short, FolioTier.Medium, FolioTier.Wide ->
        displayState == FolioPlayerDisplayState.Queue || displayState == FolioPlayerDisplayState.None
}

/** H5 `.t-m` lyrics left offset: 384dp on typical landscape phones, keeping 336dp controls intact. */
fun shortTierLyricsStartDp(windowWidthDp: Float): Dp =
    if (windowWidthDp >= 680f) 384.dp else 360.dp

/** H5 layout variant for collapsed Now Playing controls per tier and display mode (`⑤`). */
enum class FolioCollapsedLayout {
    Capsule,
    CircleButtonAndHairline,
    UnderCoverInfo,
}

/**
 * Resolves the collapsed controls variant:
 * - `Narrow` / `Short` / `Ultrawide`: classic bottom capsule (`①`)
 * - `Medium`: right-bottom 64dp circular glass button + left hairline progress bar (`③`)
 * - `Wide` in `Lyrics`: under-cover info + 56dp circular glass button + 400dp hairline (`④`)
 * - `Wide` in `Poster` / `Queue` / `None`: left-bottom 64dp circular glass button + right hairline (`③`)
 */
fun resolveCollapsedLayout(
    tier: FolioTier,
    displayState: FolioPlayerDisplayState,
): FolioCollapsedLayout = when (tier) {
    FolioTier.Medium -> FolioCollapsedLayout.CircleButtonAndHairline
    FolioTier.Wide -> if (displayState == FolioPlayerDisplayState.Lyrics) {
        FolioCollapsedLayout.UnderCoverInfo
    } else {
        FolioCollapsedLayout.CircleButtonAndHairline
    }
    FolioTier.Narrow,
    FolioTier.Short,
    FolioTier.Ultrawide -> FolioCollapsedLayout.Capsule
}

/** Persistent collapsed title mode inside `.hdr .tt` on `Medium` (`③`) and `Wide` Poster/fallback (`③`). */
enum class FolioCollapsedHeaderTitleMode {
    None,
    Compact,
    LargeWithCover,
}

/** Resolves `.hdr .tt` collapsed header appearance (hidden while expanded or on `c`/`m`/`u` and `e` Lyrics). */
fun resolveCollapsedHeaderTitleMode(
    tier: FolioTier,
    displayState: FolioPlayerDisplayState,
): FolioCollapsedHeaderTitleMode = when (tier) {
    FolioTier.Medium -> if (displayState == FolioPlayerDisplayState.Lyrics) {
        FolioCollapsedHeaderTitleMode.LargeWithCover
    } else {
        FolioCollapsedHeaderTitleMode.Compact
    }
    FolioTier.Wide -> if (displayState != FolioPlayerDisplayState.Lyrics) {
        FolioCollapsedHeaderTitleMode.Compact
    } else {
        FolioCollapsedHeaderTitleMode.None
    }
    FolioTier.Narrow,
    FolioTier.Short,
    FolioTier.Ultrawide -> FolioCollapsedHeaderTitleMode.None
}

/** Left start padding inside collapsed `.pmeta` capsule: keeps text off the rounded edge when thumbnail is hidden. */
fun collapsedCapsuleStartPaddingDp(showArtwork: Boolean): Dp =
    if (showArtwork) 10.dp else 20.dp

/** Unified second line `"歌手 — 专辑"`, omitting separator when album is blank (matching H5 `A(t.artist).name + ' — ' + album`). */
fun formatFolioPlayerSubtitle(
    artist: String,
    album: String,
): String {
    val cleanArtist = artist.trim()
    val cleanAlbum = album.trim()
    return when {
        cleanArtist.isNotEmpty() && cleanAlbum.isNotEmpty() -> "$cleanArtist — $cleanAlbum"
        cleanArtist.isNotEmpty() -> cleanArtist
        else -> cleanAlbum
    }
}

/**
 * Collapsed capsule `.pmeta .pc` shows thumbnail ONLY on portrait tiers (`Narrow` / `Medium`) when
 * not in Poster mode, matching H5 `.player.s-poster .pmeta .pc,.t-m/.t-e/.t-u .player .pmeta .pc{display:none}`.
 */
fun showCollapsedCapsuleArtwork(
    tier: FolioTier,
    displayState: FolioPlayerDisplayState,
): Boolean = (tier == FolioTier.Narrow || tier == FolioTier.Medium) &&
    displayState != FolioPlayerDisplayState.Poster

/**
 * Always-open controls rule:
 * - `Ultrawide` in `Flow` keeps Column 1 controls pinned open.
 * - `Narrow` with no lyrics keeps controls open unless viewing Queue.
 * - `Medium` and `Wide` stay collapsible in both `Lyrics` (`Flow`) and `Poster` (`Soak`) so C3 ③/④
 *   collapsed states and C4 compact left-column controls remain reachable.
 */
fun alwaysOpenControls(
    tier: FolioTier,
    style: LyricBackgroundStyle,
    hasLyricsOrPending: Boolean,
    displayState: FolioPlayerDisplayState,
): Boolean {
    val flowUltrawide = style == LyricBackgroundStyle.Flow && tier == FolioTier.Ultrawide
    val narrowNoLyrics = tier == FolioTier.Narrow &&
        !hasLyricsOrPending &&
        displayState != FolioPlayerDisplayState.Queue
    return flowUltrawide || narrowNoLyrics
}

/**
 * Resolves the effective player backdrop style bound to the active display mode:
 * - `Poster` always uses `Soak` (cover backdrop)
 * - `Lyrics` always uses `Flow` (animated flowing backdrop)
 * - `Queue` keeps the last style active before opening Queue (falling back to [settingsStyle])
 * - `None` (no lyrics) follows [settingsStyle]
 */
fun resolveEffectiveBackgroundStyle(
    displayState: FolioPlayerDisplayState,
    settingsStyle: LyricBackgroundStyle,
    lastContentBackgroundStyle: LyricBackgroundStyle = settingsStyle,
): LyricBackgroundStyle = when (displayState) {
    FolioPlayerDisplayState.Poster -> LyricBackgroundStyle.Soak
    FolioPlayerDisplayState.Lyrics -> LyricBackgroundStyle.Flow
    FolioPlayerDisplayState.Queue -> lastContentBackgroundStyle
    FolioPlayerDisplayState.None -> settingsStyle
}

/**
 * Resolves whether Poster display is preferred from the persisted [settingsStyle] (`Soak` -> Poster,
 * `Flow` -> Scrolling Lyrics).
 */
fun resolveEffectivePosterPreferred(settingsStyle: LyricBackgroundStyle): Boolean =
    settingsStyle == LyricBackgroundStyle.Soak

/** Maps toggling `Tt` in the player header back to the persisted [LyricBackgroundStyle]. */
fun nextLyricStyleForPosterToggle(currentlyPosterPreferred: Boolean): LyricBackgroundStyle =
    if (currentlyPosterPreferred) LyricBackgroundStyle.Flow else LyricBackgroundStyle.Soak

/** Collapsed cover size for Wide (`e`) car / tablet landscape (`<= 400.dp`). */
fun computeWideCarCollapsedCoverSizeDp(
    leftColumnWidthDp: Dp,
    bodyHeightDp: Dp,
): Dp = (bodyHeightDp - 134.dp)
    .coerceAtLeast(160.dp)
    .coerceAtMost((leftColumnWidthDp * 0.78f).coerceAtMost(400.dp))

/** Compact Wide (`e`) controls card width = collapsed cover width + 64dp (464dp for 400dp cover). */
fun computeWideCarControlsWidthDp(collapsedCoverWidthDp: Dp): Dp = collapsedCoverWidthDp + 64.dp

const val WIDE_CAR_CONTROLS_START_OFFSET_DP: Float = 4f
const val WIDE_CAR_COLLAPSED_BAR_START_DP: Float = 36f
const val WIDE_QUEUE_CONTROLS_GAP_DP: Float = 24f

/**
 * Computes the outer width (including the 36.dp start offset) for Wide (`e`) collapsed
 * [FolioCollapsedLayout.CircleButtonAndHairline] controls, aligning its right edge with the
 * under-cover Flow collapsed bar (`36.dp + coverWidthDp`) while clamping to [leftColumnWidthDp]
 * so the hairline progress bar never crosses into the right lyrics column.
 */
fun computeWideCollapsedHairlineBarWidthDp(
    leftColumnWidthDp: Dp,
    coverWidthDp: Dp,
    startOffsetDp: Dp = WIDE_CAR_COLLAPSED_BAR_START_DP.dp,
): Dp = (startOffsetDp + coverWidthDp).coerceAtMost(leftColumnWidthDp)

/**
 * Start padding inside the Wide (`e`) body for the horizontal Queue panel so it sits clear of the
 * expanded bottom-left controls card (`controlsRightEdge + 24dp`) and expands to full body width
 * when the controls collapse.
 */
fun computeWideQueueStartPaddingDp(
    tier: FolioTier,
    controlsExpanded: Boolean,
    controlsCardWidthDp: Dp,
    controlsStartOffsetDp: Dp = WIDE_CAR_CONTROLS_START_OFFSET_DP.dp,
    gapDp: Dp = WIDE_QUEUE_CONTROLS_GAP_DP.dp,
): Dp = if (tier == FolioTier.Wide && controlsExpanded) {
    controlsStartOffsetDp + controlsCardWidthDp + gapDp
} else {
    0.dp
}

/** Target expanded cover size on Wide (`e`) Scrolling Lyrics so cover bottom clears card top by >= 24dp. */
fun computeWideCarExpandedCoverSizeDp(
    collapsedCoverDp: Dp,
    bodyHeightDp: Dp,
    controlsCardHeightDp: Dp,
    coverTopDp: Dp = 8.dp,
    controlsBottomExtraDp: Dp = 4.dp,
    minGapDp: Dp = 24.dp,
): Dp {
    val maxCoverBottom = bodyHeightDp - controlsBottomExtraDp - controlsCardHeightDp - minGapDp
    val available = (maxCoverBottom - coverTopDp).coerceAtLeast(140.dp)
    return collapsedCoverDp.coerceAtMost(available)
}

@Immutable
data class FolioFlowCoverLayoutSpec(
    val coverSizeDp: Dp,
    val showCover: Boolean,
)

/**
 * Computes square cover side length in Flow landscape layouts (`Short` phone landscape, `Wide`
 * tablet landscape, and `Wide` car 1920x1080 -> 1280x720 dp):
 * - Target side = `max(min(leftColumnWidth * 0.8, leftColumnHeight * 0.6), minBaselineCoverDp)`.
 * - Clamped to [availableHeightAboveControlsDp] so cover + controls/capsule never overlap.
 * - Hides the cover when remaining vertical clearance drops below [minVisibleCoverDp].
 */
fun computeFlowLandscapeCoverLayout(
    leftColumnWidthDp: Dp,
    leftColumnHeightDp: Dp,
    availableHeightAboveControlsDp: Dp,
    minBaselineCoverDp: Dp,
    minVisibleCoverDp: Dp = 72.dp,
): FolioFlowCoverLayoutSpec {
    if (leftColumnWidthDp <= 0.dp || leftColumnHeightDp <= 0.dp || availableHeightAboveControlsDp < minVisibleCoverDp) {
        return FolioFlowCoverLayoutSpec(coverSizeDp = 0.dp, showCover = false)
    }
    val preferred = androidx.compose.ui.unit.min(leftColumnWidthDp * 0.8f, leftColumnHeightDp * 0.6f)
    val atLeastBaseline = androidx.compose.ui.unit.max(preferred, minBaselineCoverDp)
    val fitted = atLeastBaseline.coerceAtMost(availableHeightAboveControlsDp)
    return FolioFlowCoverLayoutSpec(
        coverSizeDp = fitted,
        showCover = fitted >= minVisibleCoverDp,
    )
}

fun areControlsExpanded(
    manualOpen: Boolean,
    alwaysOpen: Boolean,
    playWhenReady: Boolean,
): Boolean = manualOpen || alwaysOpen || !playWhenReady

/** Header displays title/artist ONLY on Narrow (`c`) when controls are expanded and lyrics/queue exist. */
fun showHeaderTitle(
    tier: FolioTier,
    controlsExpanded: Boolean,
    displayState: FolioPlayerDisplayState,
): Boolean = tier == FolioTier.Narrow &&
    controlsExpanded &&
    displayState != FolioPlayerDisplayState.None

fun showCardMeta(tier: FolioTier): Boolean = tier != FolioTier.Narrow

enum class FolioTrackActionLocation {
    Header,
    ControlsCard,
}

/** Narrow (`c`) moves Add/Favorite out of the top bar into the expanded controls card. */
fun trackActionLocationForTier(tier: FolioTier): FolioTrackActionLocation =
    if (tier == FolioTier.Narrow) FolioTrackActionLocation.ControlsCard else FolioTrackActionLocation.Header

fun canSupportMiniWindow(
    hasFlymeWindowMode: Boolean,
    isUiPreview: Boolean,
): Boolean = hasFlymeWindowMode || isUiPreview

fun shouldShowMiniWindowButton(
    canOpenMini: Boolean,
    isMiniWindow: Boolean,
    tier: FolioTier,
): Boolean = canOpenMini && !isMiniWindow && (
    tier == FolioTier.Wide ||
        tier == FolioTier.Ultrawide ||
        tier == FolioTier.Short
    )

enum class FolioLyricsOptionStatus {
    Hint,
    Unavailable,
    Loading,
    LoadFailed,
}

enum class FolioLyricsSettingsPlacement {
    AnchoredPopover,
    BottomSheet,
}

val FolioTranslationLanguageTags: List<String> = listOf(
    "", "zh-TW", "zh-CN", "en-US", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE",
)

fun folioPronunciationLanguageTags(appLanguageTag: String): List<String> =
    listOf("en-US", appLanguageTag).distinct()

fun isLyricDisplayActive(
    translationEnabled: Boolean,
    pronunciationEnabled: Boolean,
): Boolean = translationEnabled || pronunciationEnabled

fun lyricTranslationAvailable(
    lines: List<LyricLine>,
    enhancementsReady: Boolean,
): Boolean = enhancementsReady && lines.any {
    !it.translation.isNullOrBlank() || !it.backgroundTranslation.isNullOrBlank()
}

fun lyricPronunciationAvailable(
    lines: List<LyricLine>,
    enhancementsReady: Boolean,
): Boolean = enhancementsReady && lines.any { line ->
    !line.pronunciationText.isNullOrBlank() || !line.backgroundPronunciationText.isNullOrBlank() ||
        line.words.any { !it.pronunciationText.isNullOrBlank() } ||
        line.backgroundWords.any { !it.pronunciationText.isNullOrBlank() }
}

fun resolveLyricsOptionStatus(
    optionEnabled: Boolean,
    optionAvailable: Boolean,
    enhancementsReady: Boolean,
    enhancementsFailed: Boolean,
): FolioLyricsOptionStatus = when {
    optionEnabled && enhancementsFailed -> FolioLyricsOptionStatus.LoadFailed
    optionEnabled && !enhancementsReady -> FolioLyricsOptionStatus.Loading
    optionEnabled && !optionAvailable -> FolioLyricsOptionStatus.Unavailable
    else -> FolioLyricsOptionStatus.Hint
}

fun lyricsSettingsPlacementForTier(tier: FolioTier): FolioLyricsSettingsPlacement =
    if (tier == FolioTier.Wide || tier == FolioTier.Ultrawide) {
        FolioLyricsSettingsPlacement.AnchoredPopover
    } else {
        FolioLyricsSettingsPlacement.BottomSheet
    }

fun folioLyricsTextScaleMultiplier(
    selected: LyricTextSize,
    defaultSize: LyricTextSize,
): Float = (selected.scale / defaultSize.scale).coerceIn(0.75f, 1.5f)

const val FOLIO_LYRIC_OFFSET_MIN_MS: Long = -3000L
const val FOLIO_LYRIC_OFFSET_MAX_MS: Long = 3000L

/**
 * Adjusts lyric timing offset clamped to `[-3000ms, +3000ms]`.
 * Positive values shift lyric evaluation ahead of audio playback (advancing lyrics earlier);
 * negative values delay lyrics later.
 */
fun adjustFolioLyricOffsetMs(
    currentOffsetMs: Long,
    deltaMs: Long,
): Long = (currentOffsetMs + deltaMs).coerceIn(FOLIO_LYRIC_OFFSET_MIN_MS, FOLIO_LYRIC_OFFSET_MAX_MS)

enum class FolioLyricOffsetDirection {
    Unadjusted,
    Advance,
    Delay,
}

data class FolioLyricOffsetState(
    val direction: FolioLyricOffsetDirection,
    val magnitudeMs: Long,
)

fun resolveFolioLyricOffsetState(offsetMs: Long): FolioLyricOffsetState = when {
    offsetMs > 0L -> FolioLyricOffsetState(FolioLyricOffsetDirection.Advance, offsetMs)
    offsetMs < 0L -> FolioLyricOffsetState(FolioLyricOffsetDirection.Delay, -offsetMs)
    else -> FolioLyricOffsetState(FolioLyricOffsetDirection.Unadjusted, 0L)
}

fun formatFolioLyricOffsetLabel(
    offsetMs: Long,
    advanceFormat: String,
    delayFormat: String,
    unadjustedLabel: String,
): String {
    val state = resolveFolioLyricOffsetState(offsetMs)
    return when (state.direction) {
        FolioLyricOffsetDirection.Advance -> String.format(advanceFormat, state.magnitudeMs)
        FolioLyricOffsetDirection.Delay -> String.format(delayFormat, state.magnitudeMs)
        FolioLyricOffsetDirection.Unadjusted -> unadjustedLabel
    }
}

@Immutable
data class FolioQueueControlAvailability(
    val canSetRepeat: Boolean,
    val canSetShuffle: Boolean,
    val canClearQueue: Boolean,
)

/** Mirrors `AtvApp` queue mode rules: stations disable all 3; Clear requires upcoming items. */
fun resolveQueueControlAvailability(
    isStation: Boolean,
    upNextCount: Int,
    autoplayCount: Int,
): FolioQueueControlAvailability = FolioQueueControlAvailability(
    canSetRepeat = !isStation,
    canSetShuffle = !isStation,
    canClearQueue = !isStation && (upNextCount > 0 || autoplayCount > 0),
)

/** Soak displays the left artwork column on `m/t/e/u` even when a track has no lyrics. */
fun showSoakArtworkStage(
    tier: FolioTier,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
): Boolean = style == LyricBackgroundStyle.Soak &&
    !(tier == FolioTier.Narrow && displayState == FolioPlayerDisplayState.None)

fun showUltrawideArtworkColumn(tier: FolioTier): Boolean = tier == FolioTier.Ultrawide

enum class FolioQueueSectionKind {
    Played,
    NowPlaying,
    UpNext,
    Autoplay,
}

@Immutable
data class FolioQueueRow(
    val key: String,
    val track: Track,
    val section: FolioQueueSectionKind,
    val timelineIndex: Int?,
    val isCurrent: Boolean,
)

@Immutable
data class FolioQueueSnapshot(
    val rows: List<FolioQueueRow>,
    val playedCount: Int,
    val upNextCount: Int,
    val autoplayCount: Int,
    val isStation: Boolean,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val shuffleEnabled: Boolean = false,
    val autoplayEnabled: Boolean,
    val autoplayStatus: AutoplayStatus,
)

/**
 * Groups the live playback queue into Played / Now Playing / Up Next / Autoplay while keeping
 * exact `timelineEntries` indices so tapping any row jumps via `PlaybackStore.selectQueueTrack`.
 */
fun buildFolioQueueSnapshot(
    queueState: QueueState,
    playbackState: PlaybackState,
): FolioQueueSnapshot {
    val currentTrack = queueState.current?.track
    if (currentTrack?.catalogKind == CatalogKind.Station) {
        val history = playbackState.stationHistory
        val rows = if (history.isEmpty()) {
            listOf(
                FolioQueueRow(
                    key = "station-current-${playbackState.track.id}",
                    track = playbackState.track,
                    section = FolioQueueSectionKind.NowPlaying,
                    timelineIndex = null,
                    isCurrent = true,
                ),
            )
        } else {
            history.mapIndexed { idx, track ->
                val isLast = idx == history.lastIndex
                FolioQueueRow(
                    key = "station-$idx-${track.id}",
                    track = track,
                    section = if (isLast) FolioQueueSectionKind.NowPlaying else FolioQueueSectionKind.Played,
                    timelineIndex = null,
                    isCurrent = isLast,
                )
            }
        }
        return FolioQueueSnapshot(
            rows = rows,
            playedCount = (history.size - 1).coerceAtLeast(0),
            upNextCount = 0,
            autoplayCount = 0,
            isStation = true,
            repeatMode = PlaybackRepeatMode.Off,
            shuffleEnabled = false,
            autoplayEnabled = false,
            autoplayStatus = AutoplayStatus.Idle,
        )
    }

    val entries: List<QueueEntry> = queueState.timelineEntries
    val historyEnd = queueState.history.size
    val currentEnd = historyEnd + if (queueState.current != null) 1 else 0
    val upNextEnd = currentEnd + queueState.upNext.size

    val rows = entries.mapIndexed { index, entry ->
        val section = when {
            index < historyEnd -> FolioQueueSectionKind.Played
            index < currentEnd -> FolioQueueSectionKind.NowPlaying
            index < upNextEnd -> FolioQueueSectionKind.UpNext
            else -> FolioQueueSectionKind.Autoplay
        }
        FolioQueueRow(
            key = "${section.name}-${entry.entryId}-$index",
            track = entry.track,
            section = section,
            timelineIndex = index,
            isCurrent = index == queueState.currentTimelineIndex,
        )
    }

    return FolioQueueSnapshot(
        rows = rows,
        playedCount = queueState.history.size,
        upNextCount = queueState.upNext.size,
        autoplayCount = queueState.autoplay.size,
        isStation = false,
        repeatMode = queueState.repeat,
        shuffleEnabled = queueState.shuffle,
        autoplayEnabled = queueState.infinite,
        autoplayStatus = queueState.autoplayStatus,
    )
}

fun posterLeadText(line: LyricLine): String =
    renderLyricWords(line.words.filterNot { it.isBackgroundVocal }, line.joinWithSpaces).trim()

fun posterPronunciationText(line: LyricLine): String {
    val linePron = line.pronunciationText?.trim().orEmpty()
    if (linePron.isNotEmpty()) return linePron
    return line.words.mapNotNull { it.pronunciationText?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(" ")
}

/**
 * Identifies whether a lyric line belongs to an opposite duet vocalist.
 *
 * Group chorus (`agentType == Group`) and unattributed lines (`agentId` blank) stay primary so
 * single-artist tracks and chorus refrains preserve standard foreground styling.
 */
fun isOppositeSingerLine(line: LyricLine): Boolean {
    if (line.agentId.isNullOrBlank() || line.agentType == LyricAgentType.Group) return false
    return line.isOppositeAgent
}

/** H5 + C1 + M1 player motion constants (`180..320ms` interactive transitions). */
object FolioPlayerMotionTokens {
    const val CTL_OPACITY_MS: Int = 280
    const val CTL_TRANSLATE_MS: Int = 320
    const val CTL_OFFSET_DP: Float = 14f
    const val PMETA_OPACITY_MS: Int = 250
    const val PMETA_TRANSLATE_MS: Int = 300
    const val MENU_OPEN_MS: Int = 240
    const val MENU_CLOSE_MS: Int = 180
    const val MENU_CLOSED_SCALE: Float = 0.92f
    const val MENU_BOTTOM_OFFSET_DP: Float = 24f
    const val QUEUE_POPOVER_OPEN_MS: Int = 240
    const val QUEUE_POPOVER_CLOSE_MS: Int = 180
    const val QUEUE_WIDE_SLIDE_DP: Float = 16f
    const val PLAY_ICON_SWITCH_MS: Int = 200
    const val PLAY_ICON_SWITCH_OUT_MS: Int = 140
    const val PLAY_ICON_SWITCH_SCALE: Float = 0.8f
    const val TOGGLE_PILL_MS: Int = 160
    const val POSTER_LIQ_IN_MAIN_MS: Int = 800
    const val POSTER_LIQ_IN_SUB_MS: Int = 700
    const val POSTER_LIQ_OUT_MS: Int = 600
    val EnterEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

fun isReducedMotionEnabled(animatorDurationScale: Float, animatorsEnabled: Boolean): Boolean =
    animatorDurationScale <= 0f || !animatorsEnabled

private fun srgbChannelToLinear(c: Float): Double {
    val d = c.toDouble().coerceIn(0.0, 1.0)
    return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
}

fun relativeLuminance(color: Color): Float {
    val r = srgbChannelToLinear(color.red)
    val g = srgbChannelToLinear(color.green)
    val b = srgbChannelToLinear(color.blue)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = max(la, lb)
    val darker = min(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun colorChroma(c: Color): Float =
    max(c.red, max(c.green, c.blue)) - min(c.red, min(c.green, c.blue))

private fun colorDistanceSq(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return dr * dr + dg * dg + db * db
}

/**
 * Selects a distinct, high-contrast duet vocalist color from [artworkPalette] against [baseColor].
 * Monochrome artworks with no saturated accents fall back to a soft cobalt/sky tint so the duet
 * line stays clearly distinguishable from the `#FFFFFF` / `#16141B` primary vocalist.
 */
fun selectDuetAccentColor(
    artworkPalette: List<Color>,
    baseColor: Color,
    isLight: Boolean,
): Color {
    val primaryBg = artworkPalette.firstOrNull() ?: baseColor
    val candidates = artworkPalette.drop(1).ifEmpty { artworkPalette }
    val chromatic = candidates
        .filter { colorChroma(it) >= 0.16f }
        .maxByOrNull { c -> colorChroma(c) * (0.35f + colorDistanceSq(c, primaryBg)) }
        ?: if (isLight) Color(0xFF285EB8) else Color(0xFF8EC5FF)

    val anchor = if (isLight) Color(0xFF16141B) else Color.White
    var best = chromatic
    for (step in 0..16) {
        val t = step / 20f // Mix up to 0.80 toward anchor while preserving hue
        val mixed = lerp(chromatic, anchor, t)
        val ratio = contrastRatio(mixed, baseColor)
        val luma = relativeLuminance(mixed)
        val passes = if (isLight) {
            ratio >= 4.8f && luma <= 0.26f
        } else {
            ratio >= 4.8f && luma >= 0.42f
        }
        if (passes) {
            best = mixed
            break
        }
        best = mixed
    }
    return best
}

@Immutable
data class HeaderColorScheme(
    val fg: Color,
    val fg2: Color,
    val glassBg: Color,
    val glassBorder: Color,
    val posterActiveBg: Color,
)

/**
 * Computes average relative luminance of the top artwork strip (`topFraction` = top 22%) sampled
 * once per track/artwork change for adaptive header icon ink contrast.
 */
fun computeTopStripLuminance(
    pixels: IntArray,
    width: Int,
    height: Int,
    topFraction: Float = 0.22f,
): Float {
    if (pixels.isEmpty() || width <= 0 || height <= 0) return 0f
    val rows = max(1, (height * topFraction).roundToInt()).coerceAtMost(height)
    val count = min(pixels.size, rows * width)
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) {
        val argb = pixels[i]
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        sum += 0.2126 * srgbChannelToLinear(r) + 0.7152 * srgbChannelToLinear(g) + 0.0722 * srgbChannelToLinear(b)
    }
    return (sum / count).toFloat().coerceIn(0f, 1f)
}

fun isTopBackdropLight(luminance: Float, threshold: Float = 0.50f): Boolean =
    luminance >= threshold

/**
 * Determines whether header controls use dark `#16141B` ink (`true`) or `#FFFFFF` ink (`false`).
 * When Soak paints full-width artwork behind the top bar (`Narrow` / `Medium`), the artwork top strip
 * luminance governs the ink so white-background photos use dark controls and dark scenes use white.
 */
fun resolveHeaderUseDarkInk(
    tier: FolioTier,
    style: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    themeIsLight: Boolean,
    topStripLuminance: Float?,
): Boolean {
    val topBleedBehindHeader = style == LyricBackgroundStyle.Soak &&
        showSoakArtworkStage(tier, style, displayState) &&
        (tier == FolioTier.Narrow || tier == FolioTier.Medium)
    if (topBleedBehindHeader && topStripLuminance != null) {
        return isTopBackdropLight(topStripLuminance)
    }
    return themeIsLight
}

fun resolveHeaderColorScheme(useDarkInk: Boolean): HeaderColorScheme = if (useDarkInk) {
    val fg = Color(0xFF16141B)
    HeaderColorScheme(
        fg = fg,
        fg2 = fg.copy(alpha = 0.62f),
        glassBg = fg.copy(alpha = 0.10f),
        glassBorder = fg.copy(alpha = 0.20f),
        posterActiveBg = fg.copy(alpha = 0.12f),
    )
} else {
    val fg = Color.White
    HeaderColorScheme(
        fg = fg,
        fg2 = fg.copy(alpha = 0.68f),
        glassBg = fg.copy(alpha = 0.14f),
        glassBorder = fg.copy(alpha = 0.22f),
        posterActiveBg = fg.copy(alpha = 0.16f),
    )
}

@Immutable
data class PosterTypographySpec(
    val mainSp: Float,
    val mainLineHeightSp: Float,
    val prevSp: Float,
    val prevLineHeightSp: Float,
    val backingSp: Float,
    val backingLineHeightSp: Float,
    val translationSp: Float,
    val translationLineHeightSp: Float,
    val nextSp: Float,
    val nextLineHeightSp: Float,
)

val posterUpcomingAlphas: List<Float> = listOf(0.50f, 0.32f, 0.18f)

const val POSTER_ROLL_DURATION_MS: Int = 550
const val BACKDROP_STYLE_CROSSFADE_MS: Int = 400

enum class PosterLyricRole {
    ExitingPrevious,
    Previous,
    Focus,
    Upcoming0,
    Upcoming1,
    Upcoming2,
    EnteringBelow,
}

fun posterRoleScale(
    role: PosterLyricRole,
    typo: PosterTypographySpec,
): Float {
    val safeMain = typo.mainSp.coerceAtLeast(1f)
    return when (role) {
        PosterLyricRole.Focus -> 1f
        PosterLyricRole.Previous -> typo.prevSp / safeMain
        PosterLyricRole.ExitingPrevious -> (typo.prevSp / safeMain) * 0.94f
        PosterLyricRole.Upcoming0,
        PosterLyricRole.Upcoming1,
        PosterLyricRole.Upcoming2,
        PosterLyricRole.EnteringBelow -> typo.nextSp / safeMain
    }
}

/**
 * Unscaled layout width for a Poster role so scaling by [scale] fits [panelWidthPx] without
 * wrapping early at the large Focus font size.
 */
fun computePosterRoleLayoutWidthPx(
    panelWidthPx: Int,
    scale: Float,
): Int {
    val safePanelWidth = panelWidthPx.coerceAtLeast(1)
    val safeScale = scale.coerceIn(0.1f, 4f)
    return (safePanelWidth / safeScale).roundToInt().coerceAtLeast(1)
}

fun posterRoleAlpha(
    role: PosterLyricRole,
    previousAlpha: Float,
): Float = when (role) {
    PosterLyricRole.ExitingPrevious,
    PosterLyricRole.EnteringBelow -> 0f
    PosterLyricRole.Previous -> previousAlpha
    PosterLyricRole.Focus -> 1f
    PosterLyricRole.Upcoming0 -> posterUpcomingAlphas[0]
    PosterLyricRole.Upcoming1 -> posterUpcomingAlphas[1]
    PosterLyricRole.Upcoming2 -> posterUpcomingAlphas[2]
}

/**
 * Computes Poster typography matching C4 + H5 `--pfs` ratios:
 * - `.pn`: [baseSp..1.3x] sized by longest lead line in song, `line-height: 1.16`, weight 800
 * - `.pv`: `baseSp * 0.42`, `.pbv`: `baseSp * 0.50`, `.pt`: `mainSp * 0.40`, `.px p`: `baseSp * 0.46`
 */
fun posterTypographyFor(
    lines: List<LyricLine>,
    availableWidthDp: Float,
    baseSp: Float,
): PosterTypographySpec {
    val mainSp = computeSongPosterFontSize(
        lines = lines,
        availableWidthDp = availableWidthDp,
        baseSp = baseSp,
    )
    val prevSp = baseSp * 0.42f
    val backingSp = baseSp * 0.50f
    val transSp = mainSp * 0.40f
    val nextSp = baseSp * 0.46f
    return PosterTypographySpec(
        mainSp = mainSp,
        mainLineHeightSp = mainSp * 1.16f,
        prevSp = prevSp,
        prevLineHeightSp = prevSp * 1.40f,
        backingSp = backingSp,
        backingLineHeightSp = backingSp * 1.30f,
        translationSp = transSp,
        translationLineHeightSp = transSp * 1.30f,
        nextSp = nextSp,
        nextLineHeightSp = nextSp * 1.35f,
    )
}

/**
 * Computes one unified poster font size across lead lines of the current song so short and long
 * lines share a consistent visual scale ("per-song longest line" rule), bounded to `[baseSp, 1.3x]`.
 */
fun computeSongPosterFontSize(
    lines: List<LyricLine>,
    availableWidthDp: Float,
    baseSp: Float,
): Float {
    if (lines.isEmpty() || availableWidthDp <= 0f) return baseSp
    var smallest = baseSp * 1.3f
    var measuredAny = false
    for (line in lines) {
        val text = posterLeadText(line)
        if (text.isEmpty()) continue
        measuredAny = true
        val candidate = posterFontSize(text = text, widthDp = availableWidthDp, base = baseSp)
        if (candidate < smallest) smallest = candidate
    }
    if (!measuredAny) return baseSp
    return smallest.coerceIn(baseSp, baseSp * 1.3f)
}

fun hasTimedLyrics(lines: List<LyricLine>): Boolean = lines.any { !it.isStatic }

/** Formats remaining playback duration as `-m:ss` matching H5 `#trem`. */
fun formatFolioRemainingTime(
    positionMs: Long,
    durationMs: Long,
): String {
    if (durationMs <= 0L) return ""
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return "-${formatFolioDuration(remaining)}"
}

/** Mixes artwork palette into `--base` for Flow & Soak in dark and light modes. */
fun folioPlayerBaseColor(
    artworkPalette: List<Color>,
    isLight: Boolean,
): Color {
    val dominant = artworkPalette.firstOrNull() ?: if (isLight) Color(0xFFF3EFF6) else Color(0xFF1F1D26)
    return if (isLight) {
        // Blend dominant artwork tone toward luminous paper white so dark ink stays high contrast.
        lerp(dominant, Color(0xFFF6F4F9), 0.84f)
    } else {
        // Deepen dominant color for Soak/Flow dark ambient backdrop, matching FlymeCarLyrics.
        lerp(dominant, Color.Black, 0.58f)
    }
}

/**
 * Resolves the normalized [TransformOrigin] inside a popover panel pointing back toward an anchor
 * button's center (used so the Lyrics menu and Queue sheet grow smoothly out of their button).
 */
fun computeAnchorTransformOrigin(
    anchorCenterX: Float,
    anchorCenterY: Float,
    panelWidth: Float,
    panelHeight: Float,
    defaultOrigin: TransformOrigin = TransformOrigin.Center,
): TransformOrigin {
    if (panelWidth <= 1f || panelHeight <= 1f) return defaultOrigin
    return TransformOrigin(
        pivotFractionX = (anchorCenterX / panelWidth).coerceIn(-0.5f, 1.5f),
        pivotFractionY = (anchorCenterY / panelHeight).coerceIn(-0.5f, 1.5f),
    )
}

/**
 * Selects the Poster header scrim color so Light mode never paints a dark scrim over the status
 * bar, using [lightBaseColor] over bright cover art and omitting milky haze over dark covers.
 */
fun resolvePosterTopScrimColor(
    isLight: Boolean,
    useDarkHeaderInk: Boolean,
    lightBaseColor: Color,
    darkBgColor: Color,
): Color = if (isLight) {
    if (useDarkHeaderInk) lightBaseColor else Color.Transparent
} else {
    darkBgColor
}
