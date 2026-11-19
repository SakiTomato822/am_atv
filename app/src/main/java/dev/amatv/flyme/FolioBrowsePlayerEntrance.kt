package dev.amatv.flyme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import dev.amatv.model.Track

@Composable
internal fun BoxScope.FolioBrowsePlayerEntrance(
    open: Boolean,
    hasCurrentTrack: Boolean,
    origin: Rect?,
    windowWidth: Int,
    windowHeight: Int,
    statusTop: Dp,
    navBottom: Dp,
    onClose: () -> Unit,
    onOpenDetail: (Track) -> Unit,
) {
    val motion = LocalFolioBrowseMotion.current
    val visible = open && hasCurrentTrack
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    val pivot = origin?.let {
        TransformOrigin(
            (it.center.x / windowWidth).coerceIn(0f, 1f),
            (it.center.y / windowHeight).coerceIn(0f, 1f),
        )
    }
    val spec = tween<Float>(320, easing = FolioMotion.PageEasing)
    val enter = when {
        !motion.animated -> EnterTransition.None
        !motion.spatial || pivot == null -> fadeIn(spec)
        else -> fadeIn(spec) + scaleIn(spec, initialScale = 0.76f, transformOrigin = pivot)
    }
    val exit = when {
        !motion.animated -> ExitTransition.None
        !motion.spatial || pivot == null -> fadeOut(spec)
        else -> fadeOut(spec) + scaleOut(spec, targetScale = 0.76f, transformOrigin = pivot)
    }
    if (state.isIdle && !state.currentState) {
        // The closed layer keeps lyrics warm without composing the player UI.
        FolioPlayerLayer(false, statusTop, navBottom, onClose, onOpenDetail)
    }
    AnimatedVisibility(state, Modifier.matchParentSize(), enter = enter, exit = exit) {
        Box(Modifier.fillMaxSize()) {
            // Mount already open so the sheet's internal slide does not run on top of the root animation.
            FolioPlayerLayer(true, statusTop, navBottom, onClose, onOpenDetail)
        }
    }
}
