package dev.amatv.flyme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

@Composable
internal fun Modifier.folioBrowsePressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.97f,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
): Modifier = folioBrowsePressEffect(interaction, pressedScale)
    .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)

@Composable
internal fun Modifier.folioBrowsePressEffect(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalFolioBrowseMotion.current
    val strength by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (motion.animated) tween(180, easing = FolioMotion.PageEasing) else snap(),
        label = "Folio browse press",
    )
    return graphicsLayer {
        val scale = if (motion.spatial) 1f - (1f - pressedScale) * strength else 1f
        scaleX = scale
        scaleY = scale
    }.drawWithContent {
        drawContent()
        if (strength > 0f) drawRect(Color.White.copy(alpha = strength * 0.10f))
    }
}
