package dev.amatv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small, quiet activity indicator; animation invalidates drawing, not the surrounding layout. */
@Composable
internal fun AppleLoadingIndicator(modifier: Modifier = Modifier) {
    val phase = if (LocalFocusTargetsEnabled.current) rememberInfiniteTransition(label = "activity-indicator").animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "activity-spokes",
    ) else null
    Canvas(modifier.size(28.dp).semantics { contentDescription = "載入中" }) {
        val radius = size.minDimension / 2f
        val head = (phase?.value?.toInt() ?: 0) % 12
        repeat(12) { index ->
            val angle = (index * PI / 6 - PI / 2).toFloat()
            val distance = (head - index + 12) % 12
            val direction = Offset(cos(angle), sin(angle))
            drawLine(
                color = Color.White.copy(alpha = 0.18f + (1f - distance / 12f) * 0.72f),
                start = center + direction * (radius * 0.50f),
                end = center + direction * (radius * 0.86f),
                strokeWidth = radius * 0.14f,
                cap = StrokeCap.Round,
            )
        }
    }
}
