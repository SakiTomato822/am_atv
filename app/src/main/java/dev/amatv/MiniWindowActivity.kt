package dev.amatv

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.doOnAttach
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The small window: the whole app in its own task. Declared only in the Flyme manifest.
 *
 * Flyme sizes a small window from the activity's requested orientation, so asking for portrait
 * once the window is open gives a portrait window. Full screen keeps whatever the display does.
 */
class MiniWindowActivity : MainActivity() {
    override val miniWindow: Boolean get() = true

    private var captionSetupAttempted = false
    private var captionListener: Any? = null
    private lateinit var setCaptionListener: Method

    override fun onCreate(savedInstanceState: Bundle?) {
        if (WindowHosts.hasFlymeWindowMode() && isInMultiWindowMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Flyme's small window is its own windowing mode (11), which isInMultiWindowMode does not count.
        if (captionSetupAttempted || !WindowHosts.hasFlymeWindowMode()) return
        captionSetupAttempted = true
        // Flyme registers the caption in setPhoneWindow, during decor creation.
        window.decorView.doOnAttach { decor ->
            installCaptionListener(decor)
        }
    }

    private fun installCaptionListener(decor: View) {
        try {
            val manager = Class.forName(CAPTION_MANAGER)
            val listenerType = Class.forName("$CAPTION_MANAGER\$MiniWindowActionListener")
            val fullButtonId = manager.getField("MINI_WINDOW_ACTION_BUTTON_FULL").getInt(null)
            val normalState = manager.getField("MINI_WINDOW_ACTION_STATE_NORMAL").getInt(null)
            val setActionState = manager.getMethod(
                "setMiniWindowActionState", Context::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            setCaptionListener = manager.getMethod(
                "setMiniWindowActionListener", Context::class.java, listenerType,
            )
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args.single()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MiniWindowActivity caption listener"
                    "onActionClick" -> {
                        val view = args[0] as View
                        // Flyme first asks for the action ID, then asks whether the click was handled.
                        if (args.size == 1) view.id else handleCaptionClick(view, decor, fullButtonId)
                    }
                    else -> throw UnsupportedOperationException(method.toString())
                }
            }
            setCaptionListener.invoke(null, this, listener)
            captionListener = listener
            activeCaptionListener = WeakReference(listener)
            Log.i(WINDOW_LOG_TAG, "Flyme caption listener registered")

            setActionState.invoke(null, this, fullButtonId, normalState)
            Log.i(WINDOW_LOG_TAG, "Flyme caption full-screen button enabled id=$fullButtonId state=$normalState")
        } catch (e: ReflectiveOperationException) {
            Log.w(WINDOW_LOG_TAG, "Could not set up Flyme caption: ${e.cause ?: e}")
        }
    }

    private fun handleCaptionClick(view: View, decor: View, fullButtonId: Int): Boolean {
        val intercepted = shouldInterceptCaptionClick(
            view.id, fullButtonId, view.rootView === decor,
        )
        Log.i(WINDOW_LOG_TAG, "Flyme caption click id=${view.id} intercepted=$intercepted")
        if (intercepted) {
            val launched = WindowHosts.switch(this, false)
            Log.i(WINDOW_LOG_TAG, "Flyme caption full-screen switch launched=$launched")
        }
        return intercepted
    }

    override fun onDestroy() {
        val listener = captionListener
        // The manager's listener is process-wide. A replacement window may already own it.
        if (listener != null && activeCaptionListener?.get() === listener) {
            try {
                setCaptionListener.invoke(null, this, null)
            } catch (e: ReflectiveOperationException) {
                Log.w(WINDOW_LOG_TAG, "Could not unregister Flyme caption listener: ${e.cause ?: e}")
            }
            activeCaptionListener = null
        }
        captionListener = null
        super.onDestroy()
    }

    private companion object {
        const val CAPTION_MANAGER = "flyme.view.minimenu.FlymeDecorCaptionManager"
        var activeCaptionListener: WeakReference<Any>? = null
    }
}

internal fun shouldInterceptCaptionClick(actionId: Int, fullButtonId: Int, isCurrentSmallWindow: Boolean): Boolean =
    isCurrentSmallWindow && actionId == fullButtonId
