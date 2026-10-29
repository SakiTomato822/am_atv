package dev.amatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Keep entry on an actual card/control, rather than a lazy container. */
internal class ContentFocusEntry {
    var requester: FocusRequester? = null

    fun requestFocus(fallback: FocusRequester): Boolean {
        if (requester?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return true
        return runCatching { fallback.requestFocus() }.getOrDefault(false)
    }
}

internal val LocalContentFocusEntry = staticCompositionLocalOf<ContentFocusEntry?> { null }
/** A retained screen must remove its actual targets from remote navigation. */
internal val LocalFocusTargetsEnabled = staticCompositionLocalOf { true }

@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 18.dp,
    focusedScale: Float = 1.025f,
    focusedLift: Dp = 0.dp,
    restingElevation: Dp = 0.dp,
    focusedElevation: Dp = 0.dp,
    showBorder: Boolean = false,
    padding: PaddingValues = PaddingValues(0.dp),
    background: Color = Color.White.copy(alpha = 0.08f),
    focusedBackground: Color = Color.White.copy(alpha = 0.16f),
    deferIdleVisuals: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    var focused by remember { mutableStateOf(false) }
    val interaction = remember(deferIdleVisuals) {
        if (deferIdleVisuals) DeferredFocusInteractionSource() else MutableInteractionSource()
    }
    val deferredInteraction = interaction as? DeferredFocusInteractionSource
    val hovered = if (deferredInteraction != null) deferredInteraction.hovered
        else interaction.collectIsHoveredAsState().value
    val pressed = if (deferredInteraction != null) deferredInteraction.pressed
        else interaction.collectIsPressedAsState().value
    val visualsStarted = deferredInteraction == null || deferredInteraction.activated
    if (deferredInteraction != null) {
        DisposableEffect(deferredInteraction) {
            onDispose {
                focused = false
                deferredInteraction.reset()
            }
        }
    }
    val active = focused || hovered
    val density = LocalDensity.current
    val frameController = LocalFocusFrameController.current
    val frameEnabled = LocalFocusFrameEnabled.current
    val frameVisibility = LocalFocusFrameVisibility.current
    val scrollPlacement = LocalFocusScrollPlacement.current
    val frameKey = remember { Any() }
    val contentEntry = LocalContentFocusEntry.current
    val contentRequester = remember { FocusRequester() }
    val frameGeometry = remember { FocusFrameGeometry() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    // Idle Home cards need the native focus/click target, but no animation
    // coroutines or disposable overlay registrations before first interaction.
    if (visualsStarted) {
        DisposableEffect(frameController, frameKey) {
            onDispose { frameController?.clear(frameKey) }
        }
        DisposableEffect(contentEntry, contentRequester) {
            onDispose { if (contentEntry?.requester === contentRequester) contentEntry.requester = null }
        }
    }
    val targetScale = when {
        pressed && active -> focusedScale * 0.972f
        pressed -> 0.985f
        active -> focusedScale
        else -> 1f
    }
    val scale: State<Float>
    val focusProgress: State<Float>
    val pressTarget = if (pressed && interactive) 1f else 0f
    val pressProgress: State<Float>
    if (deferredInteraction != null) {
        if (visualsStarted) {
            // A newly focused card must spring from rest, never initialize at
            // its first active target. The epoch also resets reused lazy slots.
            val scaleAnimation = remember(deferredInteraction.activationEpoch) { Animatable(1f) }
            val progressAnimation = remember(deferredInteraction.activationEpoch) { Animatable(0f) }
            LaunchedEffect(scaleAnimation, targetScale) {
                scaleAnimation.animateTo(targetScale, spring(dampingRatio = 0.82f, stiffness = 430f))
            }
            LaunchedEffect(progressAnimation, active) {
                progressAnimation.animateTo(if (active) 1f else 0f,
                    spring(dampingRatio = 0.86f, stiffness = 380f))
            }
            scale = scaleAnimation.asState()
            focusProgress = progressAnimation.asState()
            pressProgress = animateFloatAsState(
                targetValue = pressTarget,
                animationSpec = tween(
                    durationMillis = if (pressTarget > 0f) MotionTokens.FocusPressEnterMillis
                    else MotionTokens.FocusPressExitMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
                label = "focus-press",
            )
        } else {
            scale = RestingFocusScale
            focusProgress = RestingFocusProgress
            pressProgress = RestingPressProgress
        }
    } else {
        scale = animateFloatAsState(
            targetValue = targetScale,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 430f),
            label = "focus-scale",
        )
        focusProgress = animateFloatAsState(
            targetValue = if (active) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f),
            label = "focus-progress",
        )
        pressProgress = animateFloatAsState(
            targetValue = pressTarget,
            animationSpec = tween(
                durationMillis = if (pressTarget > 0f) MotionTokens.FocusPressEnterMillis
                else MotionTokens.FocusPressExitMillis,
                easing = MotionTokens.BrowseContentEasing,
            ),
            label = "focus-press",
        )
    }
    val shape = RoundedCornerShape(cornerRadius)
    val restingElevationPx = with(density) { restingElevation.toPx() }
    val focusedElevationPx = with(density) { focusedElevation.toPx() }
    val liftPx = with(density) { focusedLift.toPx() }
    val frameTransform = remember(scale, focusProgress, liftPx, focusedScale) {
        FocusFrameTransform(scale, focusProgress, liftPx, focusedScale)
    }
    if (visualsStarted) SideEffect {
        if (focused && frameEnabled) frameGeometry.bounds?.let { bounds ->
            frameController?.report(
                frameKey,
                bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
            )
        } else frameController?.clear(frameKey)
    }

    Box(
        modifier = modifier
            // Focused cards draw above their siblings, so their enlarged edge
            // and shadow are not cut by an adjacent card.
            .zIndex(if (active) 1f else 0f)
            // Measure the layout box before the animated layer. Its visual scale
            // must never feed back into the overlay's spring target.
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.focusFrameBounds()
                frameGeometry.bounds = bounds
                if (focused && frameEnabled) {
                    frameController?.report(
                        frameKey, bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
                    )
                }
            }
            .then(if (visualsStarted || restingElevationPx != 0f) Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationY = -liftPx * focusProgress.value
                shadowElevation = (
                    restingElevationPx +
                        (focusedElevationPx - restingElevationPx) * focusProgress.value
                    ) * if (pressed) 0.68f else 1f
                this.shape = shape
                clip = false
                ambientShadowColor = Color.Black.copy(alpha = 0.58f)
                spotShadowColor = Color.Black.copy(alpha = 0.72f)
            } else Modifier)
            .then(if (!deferIdleVisuals || background.alpha > 0f || focusedBackground.alpha > 0f || showBorder) Modifier.drawBehind {
                val radius = cornerRadius.toPx()
                drawRoundRect(
                    color = lerp(background, focusedBackground, focusProgress.value),
                    cornerRadius = CornerRadius(radius, radius),
                )
                if (showBorder) {
                    drawRoundRect(
                        color = lerp(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.92f),
                            focusProgress.value,
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = (1.dp.toPx() * (1f + focusProgress.value)),
                        ),
                    )
                }
            } else Modifier)
            .then(if (visualsStarted) Modifier.drawWithContent {
                drawContent()
                val pressAlpha = MotionTokens.FocusPressOverlayAlpha * pressProgress.value
                if (pressAlpha > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = pressAlpha),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                }
            } else Modifier)
            .focusRequester(contentRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (focused) {
                    deferredInteraction?.activate()
                    contentEntry?.requester = contentRequester
                }
                if (focused && frameEnabled) {
                    frameGeometry.bounds?.let { bounds ->
                        frameController?.report(
                            frameKey, bounds, cornerRadiusPx, frameVisibility, frameTransform, scrollPlacement,
                        )
                    }
                } else {
                    frameController?.clear(frameKey)
                }
            }
            .hoverable(interactionSource = interaction)
            .clickable(
                enabled = interactive,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .then(if (deferIdleVisuals && padding == ZeroFocusPadding) Modifier else Modifier.padding(padding)),
        content = { content(active) },
    )
}


private val RestingFocusScale: State<Float> = object : State<Float> { override val value = 1f }
private val RestingFocusProgress: State<Float> = object : State<Float> { override val value = 0f }
private val RestingPressProgress: State<Float> = object : State<Float> { override val value = 0f }
private val ZeroFocusPadding = PaddingValues(0.dp)

/** Records paired input directly, avoiding a collector coroutine for every idle Home card. */
internal class DeferredFocusInteractionSource(
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    var activated by mutableStateOf(false)
        private set
    var hovered by mutableStateOf(false)
        private set
    var pressed by mutableStateOf(false)
        private set
    var activationEpoch = 0L
        private set
    private var hovers: MutableSet<HoverInteraction.Enter>? = null
    private var presses: MutableSet<PressInteraction.Press>? = null

    fun activate() {
        if (!activated) {
            activationEpoch++
            activated = true
        }
    }

    override suspend fun emit(interaction: Interaction) {
        delegate.emit(interaction)
        record(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        if (!delegate.tryEmit(interaction)) return false
        record(interaction)
        return true
    }

    private fun record(interaction: Interaction) {
        when (interaction) {
            is HoverInteraction.Enter -> {
                activate()
                (hovers ?: mutableSetOf<HoverInteraction.Enter>().also { hovers = it }).add(interaction)
                hovered = true
            }
            is HoverInteraction.Exit -> {
                hovers?.remove(interaction.enter)
                hovered = hovers?.isNotEmpty() == true
            }
            is PressInteraction.Press -> {
                activate()
                (presses ?: mutableSetOf<PressInteraction.Press>().also { presses = it }).add(interaction)
                pressed = true
            }
            is PressInteraction.Release -> {
                presses?.remove(interaction.press)
                pressed = presses?.isNotEmpty() == true
            }
            is PressInteraction.Cancel -> {
                presses?.remove(interaction.press)
                pressed = presses?.isNotEmpty() == true
            }
        }
    }

    fun reset() {
        hovers?.clear()
        presses?.clear()
        hovered = false
        pressed = false
        activated = false
    }
}
