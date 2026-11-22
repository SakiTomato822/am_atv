package dev.amatv.flyme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.core.PlayHistoryDateGroup
import dev.amatv.core.PlayHistoryEntry
import dev.amatv.core.groupPlayHistoryByDate
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry

private fun playHistoryGroupLabelRes(group: PlayHistoryDateGroup): Int = when (group) {
    PlayHistoryDateGroup.Today -> R.string.play_history_group_today
    PlayHistoryDateGroup.Yesterday -> R.string.play_history_group_yesterday
    PlayHistoryDateGroup.ThisWeek -> R.string.play_history_group_this_week
    PlayHistoryDateGroup.Earlier -> R.string.play_history_group_earlier
}

@Composable
internal fun FolioPlayHistoryLayer(
    open: Boolean,
    handleBack: Boolean,
    entries: List<PlayHistoryEntry>,
    currentTrack: Track,
    isPlaying: Boolean,
    statusTop: Dp,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onClearHistory: () -> Unit,
) {
    BackHandler(enabled = open && handleBack, onBack = onBack)
    val motion = LocalFolioBrowseMotion.current
    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)
    val fadeSpec = tween<Float>(FOLIO_DETAIL_MOTION_MS, easing = FolioMotion.PageEasing)

    AnimatedVisibility(
        visible = open,
        enter = when {
            !motion.animated -> EnterTransition.None
            motion.lowPerformance -> fadeIn(fadeSpec)
            else -> slideInHorizontally(slideSpec) { it }
        },
        exit = when {
            !motion.animated -> ExitTransition.None
            motion.lowPerformance -> fadeOut(fadeSpec)
            else -> slideOutHorizontally(slideSpec) { it }
        },
    ) {
        FolioPlayHistoryScreen(
            entries = entries,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            statusTop = statusTop,
            bottomPadding = bottomPadding,
            onBack = onBack,
            onPlayTrack = onPlayTrack,
            onClearHistory = onClearHistory,
        )
    }
}

@Composable
private fun FolioPlayHistoryScreen(
    entries: List<PlayHistoryEntry>,
    currentTrack: Track,
    isPlaying: Boolean,
    statusTop: Dp,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onClearHistory: () -> Unit,
) {
    val palette = LocalFolioPalette.current
    val spec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    var confirmingClear by remember { mutableStateOf(false) }

    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) confirmingClear = false
    }

    val sections = remember(entries) { groupPlayHistoryByDate(entries) }
    val historyTracks = remember(entries) { entries.map { it.toTrack() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .folioAmbientBackground(palette)
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(top = statusTop + folioDetailTopMargin)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spec.ib)
                    .padding(start = spec.px, end = spec.pr),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FolioCircleButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.browse_back),
                        onClick = onBack,
                    )
                    Text(
                        text = stringResource(R.string.play_history_title),
                        color = palette.fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (entries.isNotEmpty()) {
                    if (confirmingClear) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HistoryHeaderActionPill(
                                label = stringResource(R.string.play_history_cancel),
                                destructive = false,
                                onClick = { confirmingClear = false },
                            )
                            HistoryHeaderActionPill(
                                label = stringResource(R.string.folio_player_queue_clear_confirm),
                                destructive = true,
                                onClick = {
                                    onClearHistory()
                                    confirmingClear = false
                                },
                            )
                        }
                    } else {
                        HistoryHeaderActionPill(
                            label = stringResource(R.string.folio_player_queue_clear),
                            destructive = false,
                            onClick = { confirmingClear = true },
                        )
                    }
                }
            }

            Spacer(Modifier.height(folioDetailBodyGap))

            if (sections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = spec.px)
                        .padding(bottom = bottomPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.play_history_empty),
                        color = palette.fg2,
                        fontFamily = fontFamily,
                        fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spec.px,
                        end = spec.pr,
                        top = 4.dp,
                        bottom = bottomPadding,
                    ),
                ) {
                    var flatIndex = 0
                    sections.forEach { section ->
                        val sectionStartIndex = flatIndex
                        flatIndex += section.entries.size
                        item(
                            key = "history-group-${section.group.name}",
                            contentType = "section-title",
                        ) {
                            FolioSectionTitle(stringResource(playHistoryGroupLabelRes(section.group)))
                        }
                        itemsIndexed(
                            items = section.entries,
                            key = { index, item ->
                                "history-${section.group.name}-${item.songKey}-${item.playedAtMs}-$index"
                            },
                            contentType = { _, _ -> "song-row" },
                        ) { index, entry ->
                            val track = historyTracks[sectionStartIndex + index]
                            val queueFromClicked = remember(historyTracks, sectionStartIndex, index) {
                                historyTracks.subList(sectionStartIndex + index, historyTracks.size)
                            }
                            FolioSongRow(
                                track = track,
                                index = sectionStartIndex + index,
                                showCover = true,
                                showArtist = true,
                                isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                                isPlaying = isPlaying,
                                onClick = {
                                    onPlayTrack(track, queueFromClicked)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeaderActionPill(
    label: String,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    val shape = RoundedCornerShape(FolioRadii.Chip)
    val bg = if (destructive) palette.accent else palette.rb
    val fg = if (destructive) Color.White else palette.fg
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .then(if (!destructive) Modifier.border(1.dp, palette.glassb, shape) else Modifier)
            .folioBrowsePressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}
