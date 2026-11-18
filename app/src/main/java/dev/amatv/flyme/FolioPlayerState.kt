package dev.amatv.flyme

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
import dev.amatv.core.PlaybackState
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.renderLyricWords
import dev.amatv.playback.AutoplayStatus
import dev.amatv.playback.QueueEntry
import dev.amatv.playback.QueueState
import dev.amatv.ui.posterFontSize

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
        controlCardWidth = 340.dp,
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

/** Resolves the primary content state from H5 `sync()` / `s-lyrics|s-poster|s-queue|s-none`. */
fun resolvePlayerDisplayState(
    tier: FolioTier,
    queueOpen: Boolean,
    posterPreferred: Boolean,
    hasLyrics: Boolean,
    hasTimedLyrics: Boolean,
): FolioPlayerDisplayState = when {
    queueOpen && tier != FolioTier.Ultrawide -> FolioPlayerDisplayState.Queue
    !hasLyrics -> FolioPlayerDisplayState.None
    posterPreferred && hasTimedLyrics -> FolioPlayerDisplayState.Poster
    else -> FolioPlayerDisplayState.Lyrics
}

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

/** Mirrors H5 `alwaysOpen` controls rule. */
fun alwaysOpenControls(
    tier: FolioTier,
    style: LyricBackgroundStyle,
    hasLyrics: Boolean,
    displayState: FolioPlayerDisplayState,
): Boolean {
    val flowMultiCol = style == LyricBackgroundStyle.Flow &&
        (tier == FolioTier.Wide || tier == FolioTier.Ultrawide || tier == FolioTier.Medium)
    val narrowNoLyrics = tier == FolioTier.Narrow &&
        !hasLyrics &&
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

@Immutable
data class PosterLyricsProjection(
    val focusLeadIndex: Int,
    val hasStartedLead: Boolean,
    val previousLeadText: String,
    val activeBackingText: String,
    val upcomingLeadTexts: List<String>,
)

/**
 * Projects lyrics for Poster mode following H5 `snapshot()` / `renderPoster()` / `.pbv` rules:
 * - Only lead vocal lines appear as `.pn` large lines, `.pv` previous line, and `.px` upcoming preview.
 * - Backing-only lines (`words` empty, `backgroundWords` non-empty) never become `.pn` or `.px`; when
 *   active in [activeLineIndices] they appear in [activeBackingText] beneath the focused lead line.
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
            hasStartedLead = false,
            previousLeadText = "",
            activeBackingText = "",
            upcomingLeadTexts = emptyList(),
        )
    }

    val leadIndices = lines.indices.filter { idx ->
        !isPosterBackingOnlyLine(lines[idx]) && posterLeadText(lines[idx]).isNotEmpty()
    }
    if (leadIndices.isEmpty()) {
        return PosterLyricsProjection(
            focusLeadIndex = 0,
            hasStartedLead = false,
            previousLeadText = "",
            activeBackingText = "",
            upcomingLeadTexts = emptyList(),
        )
    }

    val activeLead = leadIndices.lastOrNull { it in activeLineIndices }
    val lastStartedLead = leadIndices.lastOrNull { lines[it].startMs <= positionMs }
    val focusLeadIndex = activeLead ?: lastStartedLead ?: leadIndices.first()
    val hasStartedLead = (activeLead ?: lastStartedLead) != null
    val focusLeadPos = leadIndices.indexOf(focusLeadIndex)

    val previousLeadText = if (!isInstrumentalGap && hasStartedLead && focusLeadPos > 0) {
        posterLeadText(lines[leadIndices[focusLeadPos - 1]])
    } else {
        ""
    }

    val activeBackingIndex = if (!isInstrumentalGap) {
        activeLineIndices
            .filter { idx -> idx in lines.indices && isPosterBackingOnlyLine(lines[idx]) }
            .maxOrNull()
    } else {
        null
    }
    val inlineBackingText = if (!isInstrumentalGap && activeBackingIndex == null && hasStartedLead) {
        val lead = lines[focusLeadIndex]
        if (lead.backgroundWords.isNotEmpty()) {
            val bgStart = lead.backgroundWords.minOf { it.startMs }
            val bgEnd = lead.backgroundWords.maxOf { it.endMs }
            if (positionMs in bgStart until bgEnd) posterBackingText(lead) else ""
        } else {
            ""
        }
    } else {
        ""
    }
    val activeBackingText = activeBackingIndex?.let { posterBackingText(lines[it]) } ?: inlineBackingText

    val upcomingIndices = if (isInstrumentalGap) {
        leadIndices.filter { it >= instrumentalBeforeLineIndex }.take(3)
    } else if (hasStartedLead && focusLeadPos >= 0) {
        leadIndices.drop(focusLeadPos + 1).take(3)
    } else {
        leadIndices.drop(1).take(3)
    }
    val upcomingLeadTexts = upcomingIndices.map { posterLeadText(lines[it]) }

    return PosterLyricsProjection(
        focusLeadIndex = focusLeadIndex,
        hasStartedLead = hasStartedLead,
        previousLeadText = previousLeadText,
        activeBackingText = activeBackingText,
        upcomingLeadTexts = upcomingLeadTexts,
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
