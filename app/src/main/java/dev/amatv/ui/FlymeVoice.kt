package dev.amatv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
internal fun FlymeVoicePanel(state: VoiceUiState?, onDismiss: () -> Unit) {
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
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(420)) + slideOutVertically(tween(520)) { -it * 2 },
        ) {
            val shown = lastShown
            val palette = LocalFlymePalette.current
            Row(
                Modifier.widthIn(min = 320.dp, max = 860.dp)
                    .graphicsLayer { shadowElevation = 18f; shape = RoundedCornerShape(36.dp); clip = true }
                    .background(palette.pageTop.copy(alpha = 0.9f))
                    .border(1.dp, FlymeInk.copy(alpha = 0.12f), RoundedCornerShape(36.dp))
                    .animateContentSize()
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
                        is VoiceUiState.Message -> shown.text to ""
                    }
                    Text(title, color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val spin by rememberInfiniteTransition(label = "voice-glow-spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "voice-glow-angle",
    )
    var level by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { level += (VoiceLevel.value - level) * 0.35f }
    }
    val colors = remember {
        listOf(0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF97316, 0xFFFACC15, 0xFF22D3EE, 0xFF3B82F6)
            .map { androidx.compose.ui.graphics.Color(it) }
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val shader = androidx.compose.ui.graphics.SweepGradientShader(center, colors)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setRotate(spin, center.x, center.y) })
        val brush = androidx.compose.ui.graphics.ShaderBrush(shader)
        val base = 5.dp.toPx() + level * 12.dp.toPx()
        val corner = 26.dp.toPx()
        for (layer in 3 downTo 0) {
            val width = base * (1f + layer * 1.1f)
            val alpha = shown * (0.9f - layer * 0.2f) * (0.65f + 0.35f * level)
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
