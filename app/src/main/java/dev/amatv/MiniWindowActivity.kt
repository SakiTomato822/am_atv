package dev.amatv

import android.content.pm.ActivityInfo
import android.os.Bundle

/**
 * The small window: the whole app in its own task. Declared only in the Flyme manifest.
 *
 * Flyme sizes a small window from the activity's requested orientation, so asking for portrait
 * once the window is open gives a portrait window. Full screen keeps whatever the display does.
 */
class MiniWindowActivity : MainActivity() {
    override val miniWindow: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        if (WindowHosts.hasFlymeWindowMode() && isInMultiWindowMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
    }
}
