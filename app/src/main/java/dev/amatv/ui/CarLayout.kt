package dev.amatv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.amatv.BuildConfig

/** How much of the bottom of the car layout the on-screen keyboard covers, in layout dp. */
val LocalCarKeyboardOverlap = staticCompositionLocalOf { 0.dp }

/** Scale the app's content, never change the head unit's global wm density. */
@Composable
fun CarLayout(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val view = LocalView.current
    val window = remember { CarWindowSize() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // adjustResize shrinks the window while the keyboard is up. Keep the full-window scale and
        // layout so the whole app does not shrink and reflow; the keyboard just covers the bottom.
        val keyboardVisible = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val fullHeight = window.fullHeight(maxWidth, maxHeight, keyboardVisible)
        // Match autoenergy-fuel-mod's 1920x968 design reference and fit both axes.
        // Insets can reduce this to 1920x896; use the actual content window.
        val scale = if (BuildConfig.FLYME_UI_PREVIEW) {
            // Preview the car viewport on a high-density Xiaomi phone as well.
            (1.5f * minOf(maxWidth.value / 1920f, fullHeight.value / 968f)).coerceIn(0.4f, 1.5f)
        } else carContentScale(maxWidth.value, fullHeight.value)
        CompositionLocalProvider(
            LocalDensity provides Density(systemDensity.density * scale, systemDensity.fontScale),
            LocalCarKeyboardOverlap provides (fullHeight - maxHeight) / scale,
        ) {
            Box(
                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                    .requiredHeight(fullHeight / scale),
            ) { content() }
        }
    }
}

/** Remembers the window height without the keyboard, for the current window width. */
private class CarWindowSize {
    private var width = Dp.Unspecified
    private var height = Dp.Unspecified

    fun fullHeight(currentWidth: Dp, currentHeight: Dp, keyboardVisible: Boolean): Dp {
        if (!keyboardVisible || currentWidth != width || height == Dp.Unspecified || currentHeight > height) {
            width = currentWidth
            height = currentHeight
        }
        return height
    }
}

internal fun carContentScale(widthDp: Float, heightDp: Float): Float =
    (1.5f * minOf(widthDp / 1920f, heightDp / 968f)).coerceIn(1f, 1.5f)
