package dev.amatv.flyme

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
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberWelcomeAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    var animated by remember(resolver) { mutableStateOf(Settings.Global.getFloat(resolver, setting, 1f) > 0f) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // The window manager may deliver ValueAnimator's cached scale on a later callback.
                animated = Settings.Global.getFloat(resolver, setting, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
        observer.onChange(false)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return animated
}
