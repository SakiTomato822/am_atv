package dev.amatv.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import dev.amatv.integration.FlymePlaybackSettings
import dev.amatv.integration.FlymePlaybackOptions
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Scale the app's content, never change the head unit's global wm density. On the head unit the
 * car layout fills the window as before; on phones and tablets it fits the whole 1920x968 design
 * into the screen and stays clear of the status bar. The user's size setting applies on top.
 */
@Composable
fun CarLayout(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val window = remember { CarWindowSize() }
    val options = remember(context) { FlymePlaybackOptions.get(context) }
    val settings by options.settings.collectAsState()
    val headUnit = remember(context) { isFlymeHeadUnit(context) }
    val light = rememberFlymeLight()
    SideEffect {
        val activityWindow = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(activityWindow, view).apply {
            if (settings.hideSystemBars) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // The page colour also fills what the system bars cover, so the bars sit on the app's page.
    Box(Modifier.fillMaxSize().background((if (light) LightFlymePalette else DarkFlymePalette).page)) {
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (headUnit || settings.hideSystemBars) Modifier
            else Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ),
    ) {
        // adjustResize shrinks the window while the keyboard is up. Keep the full-window scale and
        // layout so the whole app does not shrink and reflow; the keyboard just covers the bottom.
        val keyboardVisible = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val fullHeight = window.fullHeight(maxWidth, maxHeight, keyboardVisible)
        // Match autoenergy-fuel-mod's 1920x968 design reference and fit both axes.
        // Insets can reduce this to 1920x896; use the actual content window.
        val fit = if (headUnit && !BuildConfig.FLYME_UI_PREVIEW) carContentScale(maxWidth.value, fullHeight.value)
            else deviceContentScale(maxWidth.value, fullHeight.value)
        val scale = fit * settings.uiScalePercent / 100f
        FlymeThemeFlip(light) { light ->
            CompositionLocalProvider(
                LocalFlymePalette provides if (light) LightFlymePalette else DarkFlymePalette,
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

/** Phones and tablets: the whole car design fits the screen, however small its dp size. */
internal fun deviceContentScale(widthDp: Float, heightDp: Float): Float =
    (1.5f * minOf(widthDp / 1920f, heightDp / 968f)).coerceIn(0.4f, 1.5f)

/** The Flyme Auto head unit (ECarX media center present, or the SE1000 board), not a phone or tablet. */
internal fun isFlymeHeadUnit(context: Context): Boolean =
    Build.BOARD.equals("se1000", ignoreCase = true) || Build.HARDWARE.contains("se1000", ignoreCase = true) ||
        runCatching { context.packageManager.getPackageInfo("ecarx.xsf.mediacenter", 0); true }.getOrDefault(false)

/** Light or dark, from Settings: fixed, the system night mode, or the chosen hours of the day. */
@Composable
internal fun rememberFlymeLight(): Boolean {
    val context = LocalContext.current
    val options = remember(context) { FlymePlaybackOptions.get(context) }
    val settings by options.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    var minuteOfDay by remember { mutableIntStateOf(currentMinuteOfDay()) }
    LaunchedEffect(settings.appearance) {
        while (settings.appearance == FlymePlaybackSettings.Appearance.Time) {
            minuteOfDay = currentMinuteOfDay()
            delay(30_000L)
        }
    }
    return settings.isLight(systemDark, minuteOfDay)
}

private fun currentMinuteOfDay(): Int = java.util.Calendar.getInstance().let {
    it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
}
