package dev.amatv

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import java.lang.ref.WeakReference

/**
 * Which window this copy of the app is running in, and how to move to the other one.
 *
 * The small window runs the whole app in a portrait window. The player belongs to the process
 * ([AmAtvApplication.playbackStore]), so moving between the full-screen app and the small window only
 * changes the window around it: playback carries on untouched.
 */
internal class HostWindow(
    val mini: Boolean,
    /** Whether a small window can be opened on this device at all. */
    val canOpenMini: Boolean,
    val switchTo: (mini: Boolean) -> Unit,
)

internal val LocalHostWindow = staticCompositionLocalOf<HostWindow?> { null }

/** Keeps a single window (full screen or small window): one that opens closes the other. */
internal object WindowHosts {
    private var current: WeakReference<Activity>? = null

    /** Registers [activity] as the live window and closes the previous one. */
    fun claim(activity: Activity) {
        val previous = current?.get()
        current = WeakReference(activity)
        if (previous != null && previous !== activity && !previous.isFinishing) previous.finishAndRemoveTask()
    }

    fun release(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    /**
     * Flyme Auto opens a task in its small window only when the launch options carry
     * `start_windowmode`; `-2` forces full screen. Elsewhere, a device with free-form windows gets a
     * portrait window through launch bounds; anything else simply opens full screen.
     */
    fun switch(from: Activity, toMini: Boolean): Boolean {
        val app = from.applicationContext
        val target = if (toMini) MiniWindowActivity::class.java else MainActivity::class.java
        val intent = Intent(app, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply {
            if (toMini && !hasFlymeWindowMode() && hasFreeform(app)) setLaunchBounds(portraitBounds(from))
        }.toBundle().apply {
            if (hasFlymeWindowMode()) {
                if (toMini) putBoolean(KEY_START_WINDOW_MODE, true) else putInt(KEY_START_WINDOW_MODE, FORCE_FULL_SCREEN)
            }
        }
        return try {
            app.startActivity(intent, options)
            true
        } catch (e: RuntimeException) {
            // The current window stays as it is; nothing has been closed yet.
            Log.w(WINDOW_LOG_TAG, "Could not open the ${if (toMini) "small" else "full-screen"} window", e)
            false
        }
    }

    /** The UI preview build always offers it, so the hand-over can be checked on an emulator (it opens full screen there). */
    fun canOpenMini(context: Context): Boolean =
        dev.amatv.flyme.canSupportMiniWindow(
            hasFlymeWindowMode = hasFlymeWindowMode(),
            isUiPreview = BuildConfig.FLYME_UI_PREVIEW,
        )

    /** Flyme's window-mode options class ships in its framework; its presence means the small window exists. */
    fun hasFlymeWindowMode(): Boolean = flymeWindowMode

    private val flymeWindowMode: Boolean by lazy {
        runCatching { Class.forName("flyme.app.FlymeActivityOptions") }.isSuccess
    }

    private fun hasFreeform(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    private fun portraitBounds(activity: Activity): Rect {
        val screen = activity.resources.displayMetrics
        val height = (screen.heightPixels * 0.8f).toInt().coerceAtLeast(1)
        val width = (height * 0.5f).toInt().coerceAtMost(screen.widthPixels).coerceAtLeast(1)
        val left = ((screen.widthPixels - width) / 2).coerceAtLeast(0)
        val top = ((screen.heightPixels - height) / 2).coerceAtLeast(0)
        return Rect(left, top, left + width, top + height)
    }

    private const val KEY_START_WINDOW_MODE = "start_windowmode"
    private const val FORCE_FULL_SCREEN = -2
}

internal const val WINDOW_LOG_TAG = "AmWindow"
