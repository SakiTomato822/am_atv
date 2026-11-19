package dev.amatv.flyme

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal data class FolioBrowseMotion(val animated: Boolean, val lowPerformance: Boolean) {
    val spatial: Boolean get() = animated && !lowPerformance
}

internal val LocalFolioBrowseMotion = staticCompositionLocalOf { FolioBrowseMotion(true, false) }

@Composable
internal fun rememberFolioBrowseMotion(lowPerformance: Boolean): FolioBrowseMotion {
    val resolver = LocalContext.current.contentResolver
    var animated by remember(resolver) { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animated = ValueAnimator.areAnimatorsEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        animated = ValueAnimator.areAnimatorsEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return FolioBrowseMotion(animated, lowPerformance)
}
