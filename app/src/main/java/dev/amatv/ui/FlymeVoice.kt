package dev.amatv.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectDragGestures
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
 * A card that drops in below the top bar while voice is in use: a pulsing mic, the words heard so
 * far, then what is being searched or why nothing happened. Tapping outside cancels.
 */
@Composable
internal fun FlymeVoicePanel(state: VoiceUiState?, onDismiss: () -> Unit) {
    if (state != null) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ))
    }
    Box(Modifier.fillMaxSize().padding(top = 78.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = state != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 4 },
        ) {
            val shown = state ?: VoiceUiState.Message("")
            val palette = LocalFlymePalette.current
            Row(
                Modifier.width(620.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(palette.pageTop.copy(alpha = 0.97f))
                    .border(1.dp, FlymeInk.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 26.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                VoicePulse(listening = shown is VoiceUiState.Listening && shown.ready)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (title, body) = when (shown) {
                        is VoiceUiState.Listening -> stringResource(
                            if (shown.ready) R.string.voice_listening else R.string.voice_preparing,
                        ) to shown.heard.ifEmpty { stringResource(R.string.voice_hint) }
                        VoiceUiState.Recognizing -> stringResource(R.string.voice_recognizing) to ""
                        is VoiceUiState.Heard -> stringResource(R.string.voice_heard) to shown.text
                        is VoiceUiState.Searching -> stringResource(R.string.voice_searching_format, shown.query) to ""
                        is VoiceUiState.Message -> shown.text to ""
                    }
                    Text(title, color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (body.isNotEmpty()) {
                        val heard = (shown is VoiceUiState.Listening && shown.heard.isNotEmpty()) || shown is VoiceUiState.Heard
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
 * The mic on every page (browse, Now Playing, album and artist pages): a round button the user can
 * drag out of the way. [position] is its centre as a fraction of the screen, so it stays on screen
 * whatever the size; [onMoved] saves the new place.
 */
@Composable
internal fun FlymeFloatingVoiceButton(
    active: Boolean,
    position: androidx.compose.ui.geometry.Offset,
    onMoved: (androidx.compose.ui.geometry.Offset) -> Unit,
    onClick: () -> Unit,
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
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                .size(58.dp)
                .graphicsLayer { shadowElevation = 10f; shape = CircleShape; clip = true }
                .background(if (active) FlymeInk else LocalFlymePalette.current.pageTop.copy(alpha = 0.94f))
                .border(1.dp, FlymeInk.copy(alpha = 0.14f), CircleShape)
                .pointerInput(widthPx, heightPx) {
                    detectDragGestures(onDragEnd = { onMoved(fraction) }) { change, drag ->
                        change.consume()
                        fraction = androidx.compose.ui.geometry.Offset(
                            ((fraction.x * widthPx + drag.x) / widthPx).coerceIn(0.03f, 0.97f),
                            ((fraction.y * heightPx + drag.y) / heightPx).coerceIn(0.05f, 0.95f),
                        )
                    }
                }
                .clickable(onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(26.dp),
                tint = if (active) FlymeOnInk else FlymeInk.copy(alpha = 0.85f))
        }
    }
}
