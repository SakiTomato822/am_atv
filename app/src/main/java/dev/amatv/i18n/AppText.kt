package dev.amatv.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** Resolves service messages using the same per-app locale as the visible activity. */
object AppText {
    @Volatile private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun text(@StringRes id: Int, vararg args: Any): String {
        // Pure JVM domain tests carry a resource identity without needing Android
        // resources or a second, separately maintained set of fallback translations.
        val context = applicationContext ?: return "res:$id"
        return ContextCompat.getContextForLanguage(context).getString(id, *args)
    }
}
