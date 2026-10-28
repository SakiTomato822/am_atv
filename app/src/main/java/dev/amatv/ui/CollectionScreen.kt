package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import dev.amatv.BuildConfig
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

@Composable
internal fun CollectionScreen(
    state: CollectionBrowserState,
    currentTrack: Track,
    isPlaying: Boolean,
    firstFocus: FocusRequester,
    onRetry: () -> Unit,
    onPlay: (index: Int, shuffle: Boolean) -> Unit,
    onBack: () -> Unit = {},
) {
    val collection = state.collection ?: return
    val listState = rememberLazyListState()
    val songFocus = remember(state.tracks) { List(state.tracks.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<Job?>(null) }
    var requestedIndex by remember(state.tracks) { mutableIntStateOf(0) }
    fun moveToSong(index: Int) {
        if (index !in songFocus.indices) return
        requestedIndex = index
        focusJob?.cancel()
        focusJob = scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val anchor = if (index < listState.firstVisibleItemIndex) index
                    else (index - visibleCount + 2).coerceAtLeast(0)
                listState.animateScrollToItem(anchor)
            }
            withFrameNanos { }
            runCatching { songFocus[index].requestFocus() }
        }
    }
    val playFocus = firstFocus
    val shuffleFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val ready = !state.loading && state.error == null && state.tracks.isNotEmpty()
    val duration = remember(state.tracks) { state.tracks.sumOf { it.durationMs }.coerceAtLeast(0L) }

    Column(Modifier.fillMaxSize().automationId("collection-screen").padding(bottom = 20.dp)) {
        if (BuildConfig.FLYME_AUTO) {
            CollectionAction(stringResource(R.string.browse_back), null, onBack,
                modifier = Modifier.width(112.dp).height(56.dp).padding(bottom = 8.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(
                Modifier.width(270.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Reserve text and controls first; the cover adapts to the TV's available height.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MotionArtwork(
                        collection, Modifier.fillMaxHeight().aspectRatio(1f),
                        animate = false, cornerRadius = 10.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    collection.title, fontSize = 25.sp, lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    collection.artist, color = Color(0xFFFF788B), fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    when {
                        state.loading -> stringResource(R.string.browse_tracks_loading)
                        state.error != null -> stringResource(R.string.browse_tracks_unavailable)
                        else -> stringResource(
                            R.string.browse_collection_summary,
                            pluralStringResource(R.plurals.browse_song_count, state.tracks.size, state.tracks.size),
                            collectionDuration(duration),
                        )
                    },
                    color = Color.White.copy(alpha = 0.48f), fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CollectionAction(
                        stringResource(R.string.browse_play), Icons.Rounded.PlayArrow, { onPlay(0, false) }, enabled = ready, retainFocus = true,
                        modifier = Modifier.automationId("collection-play").weight(1f).height(42.dp).focusRequester(playFocus)
                            .onPreviewKeyEvent { event ->
                                if (ready && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    moveToSong(requestedIndex)
                                    true
                                } else false
                            }
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = when {
                                    ready -> shuffleFocus
                                    state.error != null -> retryFocus
                                    else -> FocusRequester.Cancel
                                }
                                down = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                            },
                    )
                    CollectionAction(
                        stringResource(R.string.browse_shuffle), Icons.Rounded.Shuffle,
                        { onPlay(state.tracks.indices.random(), true) }, enabled = ready,
                        modifier = Modifier.automationId("collection-shuffle").weight(1f).height(42.dp).focusRequester(shuffleFocus)
                            .onPreviewKeyEvent { event ->
                                if (ready && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                    moveToSong(requestedIndex)
                                    true
                                } else false
                            }
                            .focusProperties {
                                left = playFocus
                                up = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    state.loading -> Column(
                        Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp,
                            modifier = Modifier.size(26.dp))
                        Text(stringResource(R.string.browse_tracks_loading), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                    }
                    state.error != null -> Column(
                        Modifier.align(Alignment.Center).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(stringResource(state.error), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                        CollectionAction(stringResource(R.string.browse_reload), null, onRetry, modifier = Modifier.width(150.dp).height(42.dp).focusRequester(retryFocus)
                            .focusProperties {
                                left = playFocus
                                up = playFocus
                                right = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            })
                    }
                    state.tracks.isEmpty() -> Text(
                        stringResource(R.string.browse_tracks_empty), modifier = Modifier.align(Alignment.Center),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp,
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 28.dp),
                    ) {
                        itemsIndexed(state.tracks, key = { index, song -> "$index:${song.id}" },
                            contentType = { _, _ -> "song" }) { index, song ->
                            val selected = song.catalogId == currentTrack.catalogId && song.catalogId != null
                            FocusSurface(
                                onClick = { onPlay(index, false) },
                                modifier = Modifier.automationId("collection-song-$index").fillMaxWidth().height(64.dp)
                                    .focusRequester(songFocus[index])
                                    .onFocusChanged { if (it.isFocused) requestedIndex = index }
                                    .onPreviewKeyEvent { event ->
                                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                                        else when (event.nativeKeyEvent.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                moveToSong((requestedIndex + 1).coerceAtMost(state.tracks.lastIndex))
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_UP -> {
                                                if (requestedIndex > 0) moveToSong(requestedIndex - 1)
                                                else runCatching { playFocus.requestFocus() }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .focusProperties {
                                        right = FocusRequester.Cancel
                                        left = if (ready) shuffleFocus else firstFocus
                                        if (index == 0) up = playFocus
                                        if (index == state.tracks.lastIndex) down = FocusRequester.Cancel
                                    },
                                cornerRadius = 8.dp, focusedScale = 1f,
                                background = Color.Transparent,
                                focusedBackground = Color.White.copy(alpha = 0.12f),
                                padding = PaddingValues(horizontal = 12.dp),
                            ) { focused ->
                                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (selected && isPlaying) "♫" else stringResource(R.string.browse_track_index, index + 1),
                                        color = if (selected) Color(0xFFFF788B) else Color.White.copy(alpha = 0.4f),
                                        fontSize = 13.sp, modifier = Modifier.width(30.dp),
                                    )
                                    if (collection.catalogKind == CatalogKind.Playlist) {
                                        MotionArtwork(song, Modifier.size(42.dp), animate = false, cornerRadius = 5.dp)
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(song.title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                            color = if (selected) Color(0xFFFF91A0) else Color.White,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, fontSize = 12.sp,
                                            color = Color.White.copy(alpha = if (focused) 0.72f else 0.48f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(songDuration(song.durationMs), fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.45f), modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                            HorizontalDivider(Modifier.padding(start = 42.dp, end = 12.dp),
                                color = Color.White.copy(alpha = 0.07f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionAction(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    retainFocus: Boolean = false,
) {
    FocusSurface(
        onClick = { if (enabled) onClick() }, enabled = enabled || retainFocus, modifier = modifier,
        cornerRadius = 8.dp, focusedScale = 1.025f,
        background = Color.White.copy(alpha = if (enabled) 0.12f else 0.04f),
        focusedBackground = Color.White.copy(alpha = 0.24f),
    ) { _ ->
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            val color = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f)
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
internal fun StationUnavailableDialog(station: Track, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    CompositionLocalProvider(LocalFocusFrameEnabled provides false) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(station.title) },
            text = { Text(stringResource(R.string.browse_station_unavailable)) },
            confirmButton = {
                CollectionAction(stringResource(R.string.browse_back), null, onDismiss,
                    modifier = Modifier.width(110.dp).height(42.dp).focusRequester(focus))
            },
            containerColor = Color(0xFF252529),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun songDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return stringResource(R.string.browse_song_duration, seconds / 60, seconds % 60)
}

@Composable
private fun collectionDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) stringResource(R.string.browse_duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.browse_duration_minutes, minutes)
}
