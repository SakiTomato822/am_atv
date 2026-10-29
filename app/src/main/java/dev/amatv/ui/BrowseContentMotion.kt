package dev.amatv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Small Material-style replacement transition for browsing content. Outgoing
 * content remains visible for its fade but cannot receive input, advertise a
 * focus frame, or stay registered as the navigation entry point.
 */
@Composable
internal fun <T> BrowseContentMotion(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val density = LocalDensity.current
    val focusEntry = LocalContentFocusEntry.current
    val parentFocusTargetsEnabled = LocalFocusTargetsEnabled.current
    val parentFocusFrameEnabled = LocalFocusFrameEnabled.current

    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(
                animationSpec = tween(
                    MotionTokens.BrowseContentEnterMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
            ) + slideInVertically(
                animationSpec = tween(
                    MotionTokens.BrowseContentEnterMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
                initialOffsetY = {
                    with(density) { MotionTokens.BrowseContentTravelDp.dp.roundToPx() }
                },
            )).togetherWith(
                fadeOut(
                    animationSpec = tween(
                        MotionTokens.BrowseContentExitMillis,
                        easing = MotionTokens.BrowseContentExitEasing,
                    ),
                ) + slideOutVertically(
                    animationSpec = tween(
                        MotionTokens.BrowseContentExitMillis,
                        easing = MotionTokens.BrowseContentExitEasing,
                    ),
                    targetOffsetY = {
                        with(density) { -MotionTokens.BrowseContentExitTravelDp.dp.roundToPx() }
                    },
                ),
            )
        },
        label = label,
    ) { item ->
        val isCurrentTarget = item == targetState
        CompositionLocalProvider(
            LocalFocusTargetsEnabled provides (parentFocusTargetsEnabled && isCurrentTarget),
            LocalFocusFrameEnabled provides (parentFocusFrameEnabled && isCurrentTarget),
            LocalContentFocusEntry provides if (isCurrentTarget) focusEntry else null,
        ) {
            content(item)
        }
    }
}
