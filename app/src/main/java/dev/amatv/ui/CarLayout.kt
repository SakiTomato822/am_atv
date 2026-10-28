package dev.amatv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Scale the app's content, never change the head unit's global wm density. */
@Composable
fun CarLayout(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Match autoenergy-fuel-mod's 1920x968 design reference and fit both axes.
        // Insets can reduce this to 1920x896; use the actual content window.
        val scale = carContentScale(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(
            LocalDensity provides Density(systemDensity.density * scale, systemDensity.fontScale),
            content = content,
        )
    }
}

internal fun carContentScale(widthDp: Float, heightDp: Float): Float =
    (1.5f * minOf(widthDp / 1920f, heightDp / 968f)).coerceIn(1f, 1.5f)
