package dev.amatv.flyme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.LyricsLoadPhase
import dev.amatv.core.LyricsState
import dev.amatv.core.PlaybackState
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.renderLyricWords
import dev.amatv.playback.AutoplayStatus
import dev.amatv.playback.QueueEntry
import dev.amatv.playback.QueueState
import dev.amatv.ui.posterFontSize
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
    val posterPreferredState: MutableState<Boolean> = mutableStateOf(false)

    fun reset() {
        openState.value = false
        queueOpenState.value = false
        posterPreferredState.value = false
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

/**
 * Collapsed capsule `.pmeta .pc` shows thumbnail ONLY on portrait tiers (`Narrow` / `Medium`) when
 * not in Poster mode, matching H5 `.player.s-poster .pmeta .pc,.t-m/.t-e/.t-u .player .pmeta .pc{display:none}`.
 */
fun showCollapsedCapsuleArtwork(
    tier: FolioTier,
    displayState: FolioPlayerDisplayState,
): Boolean = (tier == FolioTier.Narrow || tier == FolioTier.Medium) &&
    displayState != FolioPlayerDisplayState.Poster

/** Mirrors H5 `alwaysOpen` controls rule. */
fun alwaysOpenControls(
    tier: FolioTier,
    style: LyricBackgroundStyle,
    hasLyricsOrPending: Boolean,
    displayState: FolioPlayerDisplayState,
): Boolean {
    val flowMultiCol = style == LyricBackgroundStyle.Flow &&
        (tier == FolioTier.Wide || tier == FolioTier.Ultrawide || tier == FolioTier.Medium)
    val narrowNoLyrics = tier == FolioTier.Narrow &&
        !hasLyricsOrPending &&
        displayState != FolioPlayerDisplayState.Queue
    return flowMultiCol || narrowNoLyrics
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
        autoplayEnabled = queueState.infinite,
        autoplayStatus = queueState.autoplayStatus,
    )
}

fun posterLeadText(line: LyricLine): String =
    renderLyricWords(line.words, line.joinWithSpaces).trim()

fun posterBackingText(line: LyricLine): String {
    val raw = renderLyricWords(line.backgroundWords, line.joinWithSpaces).trim()
    if (raw.isEmpty()) return ""
    return if (raw.startsWith("(") || raw.startsWith("（")) raw else "($raw)"
}

/** True when a line carries only backing vocals (`backgroundWords`) with no lead vocal text. */
fun isPosterBackingOnlyLine(line: LyricLine): Boolean =
    posterLeadText(line).isEmpty() &&
        renderLyricWords(line.backgroundWords, line.joinWithSpaces).isNotBlank()

/**
 * Identifies whether a lyric line belongs to an opposite duet vocalist.
 *
 * Chorus/group or unattributed lines (`agentId` blank, `"v1000"`, or `"group"`) stay primary so
 * single-artist tracks and chorus refrains preserve standard foreground styling.
 */
fun isOppositeSingerLine(line: LyricLine): Boolean {
    val id = line.agentId?.trim().orEmpty()
    if (!line.isOppositeAgent || id.isEmpty()) return false
    if (id.equals("v1000", ignoreCase = true) || id.contains("group", ignoreCase = true)) return false
    return true
}

@Immutable
data class PosterTextLine(
    val text: String,
    val isOppositeSinger: Boolean,
)

@Immutable
data class PosterLyricsProjection(
    val focusLeadIndex: Int,
    val isFocusOppositeSinger: Boolean,
    val hasStartedLead: Boolean,
    val previousLine: PosterTextLine?,
    val smallLines: List<PosterTextLine>,
    val upcomingLines: List<PosterTextLine>,
) {
    val previousLeadText: String get() = previousLine?.text.orEmpty()
    val activeBackingText: String get() = smallLines.firstOrNull()?.text.orEmpty()
    val upcomingLeadTexts: List<String> get() = upcomingLines.map { it.text }
}

/**
 * Projects lyrics for Poster mode following H5 + duet rules:
 * - Only non-backing lead vocal lines appear as `.pn` large lines, `.pv` previous line, and `.px` preview.
 * - Backing-only lines never become `.pn` or `.px`; when active they sit beneath `.pn` in [smallLines].
 * - When two lead lines overlap in [activeLineIndices] (duet simultaneous singing), the earlier-starting
 *   line holds `.pn` while the later-starting overlapping line joins [smallLines] and is skipped from `.px`.
 */
fun projectPosterLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    activeLineIndices: Collection<Int>,
    isInstrumentalGap: Boolean = false,
    instrumentalBeforeLineIndex: Int = 0,
): PosterLyricsProjection {
    if (lines.isEmpty()) {
        return PosterLyricsProjection(
            focusLeadIndex = 0,
            isFocusOppositeSinger = false,
            hasStartedLead = false,
            previousLine = null,
            smallLines = emptyList(),
            upcomingLines = emptyList(),
        )
    }

    val leadIndices = lines.indices.filter { idx ->
        !isPosterBackingOnlyLine(lines[idx]) && posterLeadText(lines[idx]).isNotEmpty()
    }
    if (leadIndices.isEmpty()) {
        return PosterLyricsProjection(
            focusLeadIndex = 0,
            isFocusOppositeSinger = false,
            hasStartedLead = false,
            previousLine = null,
            smallLines = emptyList(),
            upcomingLines = emptyList(),
        )
    }

    val activeLeads = leadIndices
        .filter { it in activeLineIndices }
        .sortedWith(compareBy<Int>({ lines[it].startMs }, { it }))
    val lastStartedLead = leadIndices.lastOrNull { lines[it].startMs <= positionMs }
    val focusLeadIndex = activeLeads.firstOrNull() ?: lastStartedLead ?: leadIndices.first()
    val hasStartedLead = activeLeads.isNotEmpty() || lastStartedLead != null
    val focusLeadPos = leadIndices.indexOf(focusLeadIndex)

    val previousLine = if (!isInstrumentalGap && hasStartedLead && focusLeadPos > 0) {
        val prevIdx = leadIndices[focusLeadPos - 1]
        PosterTextLine(posterLeadText(lines[prevIdx]), isOppositeSingerLine(lines[prevIdx]))
    } else {
        null
    }

    val smallLines = buildList {
        if (!isInstrumentalGap) {
            if (activeLeads.size > 1) {
                for (overlapIdx in activeLeads.drop(1)) {
                    add(
                        PosterTextLine(
                            text = posterLeadText(lines[overlapIdx]),
                            isOppositeSinger = isOppositeSingerLine(lines[overlapIdx]),
                        ),
                    )
                }
            }
            val activeBackingIndices = activeLineIndices
                .filter { idx -> idx in lines.indices && isPosterBackingOnlyLine(lines[idx]) }
                .sorted()
            if (activeBackingIndices.isNotEmpty()) {
                for (bgIdx in activeBackingIndices) {
                    add(
                        PosterTextLine(
                            text = posterBackingText(lines[bgIdx]),
                            isOppositeSinger = isOppositeSingerLine(lines[bgIdx]),
                        ),
                    )
                }
            } else if (hasStartedLead) {
                val lead = lines[focusLeadIndex]
                if (lead.backgroundWords.isNotEmpty()) {
                    val bgStart = lead.backgroundWords.minOf { it.startMs }
                    val bgEnd = lead.backgroundWords.maxOf { it.endMs }
                    if (positionMs in bgStart until bgEnd) {
                        add(PosterTextLine(posterBackingText(lead), isOppositeSingerLine(lead)))
                    }
                }
            }
        }
    }

    val maxConsumedActiveLead = activeLeads.maxOrNull()
    val upcomingIndices = when {
        isInstrumentalGap -> leadIndices.filter { it >= instrumentalBeforeLineIndex }.take(3)
        maxConsumedActiveLead != null && activeLeads.size > 1 ->
            leadIndices.filter { it > maxConsumedActiveLead }.take(3)
        hasStartedLead && focusLeadPos >= 0 -> leadIndices.drop(focusLeadPos + 1).take(3)
        else -> leadIndices.drop(1).take(3)
    }
    val upcomingLines = upcomingIndices.map { idx ->
        PosterTextLine(posterLeadText(lines[idx]), isOppositeSingerLine(lines[idx]))
    }

    return PosterLyricsProjection(
        focusLeadIndex = focusLeadIndex,
        isFocusOppositeSinger = isOppositeSingerLine(lines[focusLeadIndex]),
        hasStartedLead = hasStartedLead,
        previousLine = previousLine,
        smallLines = smallLines,
        upcomingLines = upcomingLines,
    )
}

/** H5 + C1 player motion constants matching CSS durations and cubic-bezier curves. */
object FolioPlayerMotionTokens {
    const val CTL_OPACITY_MS: Int = 280
    const val CTL_TRANSLATE_MS: Int = 350
    const val CTL_OFFSET_DP: Float = 14f
    const val PMETA_OPACITY_MS: Int = 250
    const val PMETA_TRANSLATE_MS: Int = 300
    const val POSTER_LIQ_IN_MAIN_MS: Int = 800
    const val POSTER_LIQ_IN_SUB_MS: Int = 700
    const val POSTER_LIQ_OUT_MS: Int = 600
    const val POSTER_CHAR_LIGHT_MS: Long = 200L
    val EnterEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

fun isReducedMotionEnabled(animatorDurationScale: Float, animatorsEnabled: Boolean): Boolean =
    animatorDurationScale <= 0f || !animatorsEnabled

/**
 * Computes per-character/word opacity for Poster sweep (`.pn .c { opacity: .4; transition: opacity .2s }`),
 * starting illumination at [charStartMs] so timing never lags behind audio.
 */
fun posterCharAlpha(
    posMs: Long,
    charStartMs: Long,
    litAlpha: Float = 1f,
    unlitAlpha: Float = 0.40f,
    reducedMotion: Boolean = false,
): Float {
    if (posMs < charStartMs) return unlitAlpha
    if (reducedMotion) return litAlpha
    val progress = ((posMs - charStartMs).toFloat() / FolioPlayerMotionTokens.POSTER_CHAR_LIGHT_MS.toFloat())
        .coerceIn(0f, 1f)
    return unlitAlpha + (litAlpha - unlitAlpha) * progress
}

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

/**
 * Computes Poster typography matching H5 `--pfs` ratios:
 * - `.pn`: [baseSp..1.3x] sized by longest lead line in song, `line-height: 1.16`, weight 800
 * - `.pv`: `baseSp * 0.42`, `.pbv`: `baseSp * 0.50`, `.pt`: `baseSp * 0.38`, `.px p`: `baseSp * 0.46`
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
    val transSp = baseSp * 0.38f
    val nextSp = baseSp * 0.46f
    return PosterTypographySpec(
        mainSp = mainSp,
        mainLineHeightSp = mainSp * 1.16f,
        prevSp = prevSp,
        prevLineHeightSp = prevSp * 1.40f,
        backingSp = backingSp,
        backingLineHeightSp = backingSp * 1.30f,
        translationSp = transSp,
        translationLineHeightSp = transSp * 1.35f,
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
        if (isPosterBackingOnlyLine(line)) continue
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
