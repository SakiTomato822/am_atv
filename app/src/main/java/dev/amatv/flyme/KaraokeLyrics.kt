package dev.amatv.flyme

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.flyme.rendering.TextureAlphaMask
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTimeline
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

private val LyricPrimaryWeight = FontWeight.ExtraBold

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
internal fun KaraokeLyricLine(
    line: LyricLine,
    isActive: Boolean,
    isStaticMode: Boolean,
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
            isStaticMode = true,
            compactMainLocalization = compactMainLocalization,
            compactBackgroundLocalization = compactBackgroundLocalization,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 84.dp, top = 2.dp, bottom = 10.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Column {
                if (!staticPronunciation.isNullOrBlank()) {
                    Text(
                        text = staticPronunciation,
                        fontSize = staticPronunciationFontSize,
                        lineHeight = staticPronunciationLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = FlymeInk.copy(alpha = 0.58f),
                        modifier = Modifier.padding(bottom = staticLocalizationGap),
                    )
                }
                Text(
                    text = line.text,
                    fontSize = staticMainFontSize,
                    lineHeight = staticMainLineHeight,
                    fontWeight = LyricPrimaryWeight,
                    color = FlymeInk.copy(alpha = MotionTokens.KaraokePrimaryAlpha),
                )
                if (!line.translation.isNullOrBlank()) {
                    Text(
                        text = line.translation,
                        fontSize = staticTranslationFontSize,
                        lineHeight = staticTranslationLineHeight,
                        fontWeight = FontWeight.Medium,
                        color = FlymeInk.copy(alpha = 0.62f),
                        modifier = Modifier.padding(top = staticLocalizationGap),
                    )
                }
                if (!staticBackgroundPronunciation.isNullOrBlank()) {
                    Text(
                        text = staticBackgroundPronunciation,
                        fontSize = staticBackgroundTypography.pronunciationSizeSp.sp,
                        lineHeight = staticBackgroundTypography.pronunciationLineHeightSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FlymeInk.copy(alpha = 0.48f),
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
                        color = FlymeInk.copy(alpha = 0.52f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!line.backgroundTranslation.isNullOrBlank()) {
                    Text(
                        text = line.backgroundTranslation,
                        fontSize = staticBackgroundTypography.translationSizeSp.sp,
                        lineHeight = staticBackgroundTypography.translationLineHeightSp.sp,
                        fontWeight = FontWeight.Medium,
                        color = FlymeInk.copy(alpha = 0.48f),
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
    // Exact scale animation from A.java (0ms/800ms activate, 150ms/800ms deactivate)
    // and U0.java (150ms/500ms activate, 150ms/600ms deactivate) with E.f43134h (LineScaleEasing)
    val activeScale = animateFloatAsState(
        targetValue = if (isActive) {
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
    val activePresence = rememberContinuousVocalPresence(timedPresence)
    val backgroundPresence = rememberContinuousVocalPresence(timedBackgroundPresence)

    val unlitAlpha = flymeLyricAlpha(MotionTokens.KaraokeSecondaryAlpha)
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
                        color = FlymeInk,
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
                        color = FlymeInk,
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
                            color = FlymeInk,
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
                        color = FlymeInk,
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

/** Limit how quickly the painted vocal highlight fades. */
@Composable
private fun rememberContinuousVocalPresence(target: State<Float>): State<Float> {
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
            color = FlymeInk,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().then(subAlphaModifier).padding(bottom = mainGap),
        )
    }
    Text(
        text = line.text,
        fontSize = mainFontSize,
        lineHeight = mainLineHeight,
        fontWeight = LyricPrimaryWeight,
        color = FlymeInk,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth().then(mainAlphaModifier),
    )
    if (!line.translation.isNullOrBlank()) {
        Text(
            text = line.translation,
            fontSize = translationFontSize,
            lineHeight = translationLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = FlymeInk,
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
                color = FlymeInk,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().then(backgroundAlphaModifier).padding(top = backgroundGap),
            )
        }
        Text(
            text = "(${line.backgroundText})",
            fontSize = backgroundFontSize,
            lineHeight = backgroundLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = FlymeInk,
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
            color = FlymeInk,
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
    val litAlpha = flymeLyricAlpha(if (isBackgroundVocal) {
        MotionTokens.KaraokeBgVocalLitAlpha
    } else {
        MotionTokens.KaraokePrimaryAlpha
    })
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
    val ink = FlymeInk
    val shadowStyle = remember(shadowRadiusPx, baseTextStyle) {
        baseTextStyle.copy(
            shadow = Shadow(
                color = ink,
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
                color = FlymeInk.copy(
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
                fontWeight = if (isBackgroundVocal) {
                    FontWeight.SemiBold
                } else LyricPrimaryWeight,
                color = FlymeInk,
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
                            fontWeight = if (isBackgroundVocal) {
                                FontWeight.SemiBold
                            } else LyricPrimaryWeight,
                            color = FlymeInk,
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
                                    fontWeight = if (isBackgroundVocal) {
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
                                fontWeight = if (isBackgroundVocal) {
                                    FontWeight.SemiBold
                                } else LyricPrimaryWeight,
                                color = FlymeInk,
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
 * - Uses `litColor = FlymeInk.copy(alpha = effectiveLitAlpha)` and
 *   `unlitColor = FlymeInk.copy(alpha = unlitAlpha)` so both lit and unlit portions are
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
