package dev.amatv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.onClick
import dev.amatv.integration.voice.VoiceLevel
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R

/** What the voice panel shows. */
internal sealed interface VoiceUiState {
    /** [ready]: the microphone is open; before that the panel says it is getting ready. */
    data class Listening(val heard: String, val ready: Boolean = true) : VoiceUiState
    /** Recording done; the engine (often a cloud API) is turning it into text. */
    data object Recognizing : VoiceUiState
    /** The chat model is reading [text]. */
    data class Understanding(val text: String) : VoiceUiState
    /** What was understood, shown while the app acts on it. */
    data class Heard(val text: String) : VoiceUiState
    data class Searching(val query: String) : VoiceUiState
    data class Choices(val items: List<dev.amatv.model.Track>, val page: Int, val pages: Int) : VoiceUiState
    data class Message(val text: String) : VoiceUiState
}

/** Round mic button for the top bar, next to the section tabs. */
@Composable
internal fun FlymeVoiceButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.voice_button)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(43.dp).automationId("nav-voice"),
        cornerRadius = 22.dp,
        focusedScale = 1.06f,
        background = if (active) FlymeInk else FlymeInk.copy(alpha = 0.09f),
        focusedBackground = if (active) FlymeInk else FlymeInk.copy(alpha = 0.2f),
    ) {
        Icon(Icons.Rounded.Mic, label, modifier = Modifier.align(Alignment.Center).size(20.dp),
            tint = if (active) FlymeOnInk else FlymeInk.copy(alpha = 0.85f))
    }
}

/**
 * Siri-style: a translucent bubble rises from the bottom with the words heard so far, updates as
 * they come, and floats up and away when done. Tapping outside cancels.
 */
@Composable
internal fun FlymeVoicePanel(state: VoiceUiState?, onDismiss: () -> Unit, onChoice: (Int) -> Unit) {
    if (state != null) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ))
    }
    // The last thing shown stays during the exit, so the bubble floats away with its words.
    var lastShown by remember { androidx.compose.runtime.mutableStateOf<VoiceUiState>(VoiceUiState.Message("")) }
    if (state != null) lastShown = state
    Box(Modifier.fillMaxSize().padding(bottom = 104.dp, start = 40.dp, end = 40.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state != null,
            enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.62f, stiffness = 380f), initialScale = 0.9f) +
                slideInVertically(spring(dampingRatio = 0.7f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 2 },
            exit = fadeOut(tween(420)) + slideOutVertically(tween(520)) { -it * 2 },
        ) {
            val shown = lastShown
            val palette = LocalFlymePalette.current
            Row(
                Modifier.widthIn(min = 320.dp, max = 860.dp)
                    .graphicsLayer { shadowElevation = 18f; shape = RoundedCornerShape(36.dp); clip = true }
                    .background(palette.pageTop.copy(alpha = 0.9f))
                    .border(1.dp, FlymeInk.copy(alpha = 0.12f), RoundedCornerShape(36.dp))
                    .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold))
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VoicePulse(listening = shown is VoiceUiState.Listening && shown.ready)
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val (title, body) = when (shown) {
                        is VoiceUiState.Listening -> stringResource(
                            if (shown.ready) R.string.voice_listening else R.string.voice_preparing,
                        ) to shown.heard.ifEmpty { stringResource(R.string.voice_hint) }
                        VoiceUiState.Recognizing -> stringResource(R.string.voice_recognizing) to ""
                        is VoiceUiState.Heard -> stringResource(R.string.voice_heard) to shown.text
                        is VoiceUiState.Understanding -> stringResource(R.string.voice_understanding) to shown.text
                        is VoiceUiState.Searching -> stringResource(R.string.voice_searching_format, shown.query) to ""
                        is VoiceUiState.Choices -> stringResource(R.string.voice_choices_format, shown.page, shown.pages) to ""
                        is VoiceUiState.Message -> shown.text to ""
                    }
                    Text(title, color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (shown is VoiceUiState.Choices) {
                        shown.items.forEachIndexed { index, item ->
                            val kind = stringResource(when (item.catalogKind) {
                                dev.amatv.model.CatalogKind.Song -> R.string.services_kind_song
                                dev.amatv.model.CatalogKind.Album -> R.string.services_kind_album
                                dev.amatv.model.CatalogKind.Artist -> R.string.services_kind_artist
                                dev.amatv.model.CatalogKind.Playlist -> R.string.services_kind_playlist
                                dev.amatv.model.CatalogKind.Station -> R.string.services_kind_station
                                dev.amatv.model.CatalogKind.MusicVideo -> R.string.services_kind_music_video
                            })
                            val details = listOf(kind, item.artist.takeUnless { item.catalogKind == dev.amatv.model.CatalogKind.Artist }.orEmpty(),item.album.takeIf { item.catalogKind == dev.amatv.model.CatalogKind.Song && it != item.title }.orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            // Rows drop in one after another with a soft spring; only layer
                            // properties animate, so the list never re-measures while it moves.
                            val appear = remember(shown.page, item.id) { Animatable(0f) }
                            LaunchedEffect(appear) {
                                delay(45L * index)
                                appear.animateTo(1f, spring(dampingRatio = 0.66f, stiffness = 340f))
                            }
                            Row(
                                Modifier.graphicsLayer {
                                    alpha = appear.value.coerceIn(0f, 1f)
                                    translationY = (1f - appear.value) * 18.dp.toPx()
                                }
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = state is VoiceUiState.Choices) { onChoice(index + 1) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.size(28.dp).background(FlymeInk.copy(alpha = 0.1f), CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", color = FlymeInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Column {
                                    Text(item.title, color = FlymeInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(details, color = FlymeInk.copy(alpha = 0.55f), fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (body.isNotEmpty()) {
                        val heard = (shown is VoiceUiState.Listening && shown.heard.isNotEmpty()) || shown is VoiceUiState.Heard || shown is VoiceUiState.Understanding
                        Text(body, color = if (heard) FlymeInk else FlymeInk.copy(alpha = 0.45f),
                            fontSize = if (heard) 24.sp else 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePulse(listening: Boolean) {
    val pulse by rememberInfiniteTransition(label = "voice-pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "voice-pulse-value",
    )
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(Modifier.size(56.dp).graphicsLayer {
                val scale = 0.8f + 0.2f * pulse
                scaleX = scale; scaleY = scale
                alpha = 0.35f * (1f - pulse) + 0.1f
            }.clip(CircleShape).background(FlymeInk))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (listening) FlymeInk else FlymeInk.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(22.dp),
                tint = if (listening) FlymeOnInk else FlymeInk.copy(alpha = 0.7f))
        }
    }
}

/**
 * The mic on every page (browse, Now Playing, album and artist pages). Tap: a session that keeps
 * listening until tapped again. Hold: walkie-talkie, records until released. Drag (move before
 * the hold starts): put it somewhere else. [position] is its centre as a fraction of the screen.
 */
@Composable
internal fun FlymeFloatingVoiceButton(
    active: Boolean,
    position: androidx.compose.ui.geometry.Offset,
    onMoved: (androidx.compose.ui.geometry.Offset) -> Unit,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val sizePx = with(density) { 58.dp.toPx() }
        var fraction by remember(position) { androidx.compose.runtime.mutableStateOf(position) }
        val x = (fraction.x * widthPx - sizePx / 2).coerceIn(0f, (widthPx - sizePx).coerceAtLeast(0f))
        val y = (fraction.y * heightPx - sizePx / 2).coerceIn(0f, (heightPx - sizePx).coerceAtLeast(0f))
        val label = stringResource(R.string.voice_button)
        val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
        val holdStart by androidx.compose.runtime.rememberUpdatedState(onHoldStart)
        val holdEnd by androidx.compose.runtime.rememberUpdatedState(onHoldEnd)
        val moved by androidx.compose.runtime.rememberUpdatedState(onMoved)
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                .size(58.dp)
                .graphicsLayer { shadowElevation = 10f; shape = CircleShape; clip = true }
                .background(if (active) FlymeInk else LocalFlymePalette.current.pageTop.copy(alpha = 0.94f))
                .border(1.dp, FlymeInk.copy(alpha = 0.14f), CircleShape)
                .pointerInput(widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragging = false
                        // Until the long-press time: a release is a tap, a move is a drag.
                        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var up = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    up = true
                                    break
                                }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    break
                                }
                            }
                            up
                        }
                        when {
                            released == true -> tap()
                            dragging -> {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val delta = change.positionChange()
                                    change.consume()
                                    fraction = androidx.compose.ui.geometry.Offset(
                                        ((fraction.x * widthPx + delta.x) / widthPx).coerceIn(0.03f, 0.97f),
                                        ((fraction.y * heightPx + delta.y) / heightPx).coerceIn(0.05f, 0.95f),
                                    )
                                }
                                moved(fraction)
                            }
                            else -> {
                                holdStart()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                holdEnd()
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    onClick(label) { tap(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(26.dp),
                tint = if (active) FlymeOnInk else FlymeInk.copy(alpha = 0.85f))
        }
    }
}

/**
 * Siri-style glow around the screen edge while voice is on: a band of blue, violet, pink and
 * orange that runs around the edge, thicker and brighter as the user speaks. Android 11 has no
 * blur, so the glow is a few strokes from wide and faint to narrow and bright.
 */
@Composable
internal fun FlymeVoiceGlow(visible: Boolean) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f, tween(if (visible) 280 else 420), label = "voice-glow",
    )
    if (shown <= 0.01f) return
    // Cost matters more than smoothness here: music plays through the same CPU, and a full-screen
    // redraw every vsync starved the audio thread (crackling), like the old spectrum view did.
    // So the glow ticks at ~30 fps, skips frames when nothing moved, and draws on its own layer
    // (graphicsLayer below) so a tick repaints only the glow, never the app underneath.
    var spin by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var level by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val start = android.os.SystemClock.uptimeMillis()
        while (true) {
            kotlinx.coroutines.delay(33L)
            spin = ((android.os.SystemClock.uptimeMillis() - start) % 3_200L) * (360f / 3_200f)
            val next = level + (VoiceLevel.value - level) * 0.5f
            if (kotlin.math.abs(next - level) > 0.01f) level = next
        }
    }
    val colors = remember {
        listOf(0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF97316, 0xFFFACC15, 0xFF22D3EE, 0xFF3B82F6)
            .map { androidx.compose.ui.graphics.Color(it) }
    }
    val matrix = remember { android.graphics.Matrix() }
    // Plain holder, not state: filled during draw, which must not invalidate itself.
    val cache = remember { arrayOfNulls<Any>(2) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val shader = (cache[1] as? androidx.compose.ui.graphics.Shader)?.takeIf { cache[0] == size }
            ?: androidx.compose.ui.graphics.SweepGradientShader(center, colors).also { cache[0] = size; cache[1] = it }
        matrix.setRotate(spin, center.x, center.y)
        shader.setLocalMatrix(matrix)
        val brush = androidx.compose.ui.graphics.ShaderBrush(shader)
        val base = 6.dp.toPx() + level * 12.dp.toPx()
        val corner = 26.dp.toPx()
        // Two strokes (a wide soft one and a bright core) read as a glow at a fraction of the fill.
        for (layer in 1 downTo 0) {
            val width = base * (1f + layer * 2.2f)
            val alpha = shown * (0.9f - layer * 0.35f) * (0.65f + 0.35f * level)
            drawRoundRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(width / 2, width / 2),
                size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width),
                alpha = alpha.coerceIn(0f, 1f) * (if (layer == 0) 1f else 0.45f),
            )
        }
    }
}

/** The list "第三个" counts in on the current page, and how that page opens one of its items. */
internal class VoiceList(val items: List<dev.amatv.model.Track>, val open: (dev.amatv.model.Track) -> Unit)

/** Pages with their own lists (the library grid) report them here; null when they go away. */
internal val LocalVoiceListSink = androidx.compose.runtime.staticCompositionLocalOf<(VoiceList?) -> Unit> { {} }
