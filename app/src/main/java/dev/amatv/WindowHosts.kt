package dev.amatv

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Which window this copy of the app is running in, and how to move to the other one.
 *
 * The small window runs the whole app in a portrait window. The player lives inside the activity, so moving between
 * the full-screen app and the small window hands the session over: the leaving host saves it
 * synchronously ([saveNow]), the arriving host restores it and, when [resume] is set, plays on.
 */
internal class HostWindow(
    val mini: Boolean,
    val resume: Boolean,
    /** Whether a small window can be opened on this device at all. */
    val canOpenMini: Boolean,
    private val switcher: (mini: Boolean, resume: Boolean) -> Unit,
) {
    /** Registered by the app root while it is composed; saves the playback session to disk now. */
    @Volatile var saveNow: (() -> Unit)? = null

    fun switchTo(mini: Boolean, resume: Boolean) {
        runCatching { saveNow?.invoke() }.onFailure { Log.w(TAG, "Could not save before switching window", it) }
        switcher(mini, resume)
    }
}

internal val LocalHostWindow = staticCompositionLocalOf<HostWindow?> { null }

/**
 * Keeps a single live host (full screen or small window). Two hosts would mean two players, so a
 * host that starts closes the previous one, remembering whether it was playing.
 */
internal object WindowHosts {
    private var current: WeakReference<Activity>? = null
    private var replacing: WeakReference<Activity>? = null

    /** The live host's play intent, so a host that replaces it can carry playback on. */
    @Volatile var playing: Boolean = false

    /**
     * False while a replaced host is still shutting down. MusicKit allows one player per process:
     * a new host must not create its player until the old one has released its own.
     */
    private val _previousReleased = MutableStateFlow(true)
    val previousReleased: StateFlow<Boolean> = _previousReleased

    /** Registers [activity] as the live host. Returns true if it replaced another host that was playing. */
    fun claim(activity: Activity): Boolean {
        val previous = current?.get()
        current = WeakReference(activity)
        if (previous == null || previous === activity || previous.isDestroyed) return false
        val wasPlaying = playing
        replacing = WeakReference(previous)
        _previousReleased.value = false
        if (!previous.isFinishing) {
            runCatching { previous.finishAndRemoveTask() }.onFailure {
                Log.w(TAG, "Could not close the previous window", it)
                stopWaiting()
            }
        }
        return wasPlaying
    }

    /** Call after the activity's own onDestroy, once its composition (and player) has been released. */
    fun release(activity: Activity) {
        if (current?.get() === activity) current = null
        if (replacing?.get() === activity) stopWaiting()
    }

    /** Proceeds without the old host; used if it never reports back. */
    fun stopWaiting() {
        replacing = null
        _previousReleased.value = true
    }

    /**
     * Flyme Auto opens a task in its small window only when the launch options carry
     * `start_windowmode`; `-2` forces full screen. Elsewhere, a device with free-form windows gets a
     * portrait window through launch bounds; anything else simply opens full screen.
     */
    fun switch(from: Activity, toMini: Boolean, resume: Boolean) {
        val app = from.applicationContext
        val target = if (toMini) MiniWindowActivity::class.java else MainActivity::class.java
        val intent = Intent(app, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_RESUME, resume)
        val options = ActivityOptions.makeBasic().apply {
            if (toMini && !hasFlymeWindowMode() && hasFreeform(app)) setLaunchBounds(portraitBounds(from))
        }.toBundle().apply {
            if (hasFlymeWindowMode()) {
                if (toMini) putBoolean(KEY_START_WINDOW_MODE, true) else putInt(KEY_START_WINDOW_MODE, FORCE_FULL_SCREEN)
            }
        }
        try {
            app.startActivity(intent, options)
        } catch (e: RuntimeException) {
            // The current window stays as it is; nothing has been closed yet.
            Log.w(TAG, "Could not open the ${if (toMini) "small" else "full-screen"} window", e)
        }
    }

    /** The UI preview build always offers it, so the hand-over can be checked on an emulator (it opens full screen there). */
    fun canOpenMini(context: Context): Boolean =
        hasFlymeWindowMode() || hasFreeform(context) || BuildConfig.FLYME_UI_PREVIEW

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

    const val EXTRA_RESUME = "dev.amatv.extra.RESUME_PLAYBACK"
    private const val KEY_START_WINDOW_MODE = "start_windowmode"
    private const val FORCE_FULL_SCREEN = -2
}

private const val TAG = "AmWindow"
