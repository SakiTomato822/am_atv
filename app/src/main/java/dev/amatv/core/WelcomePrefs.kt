package dev.amatv.core

import android.content.Context

const val WELCOME_PREFS_NAME = "am_atv_welcome"
const val CURRENT_WELCOME_VERSION = 1

interface WelcomePreferences {
    var welcomeVersion: Int
    var bgmMuted: Boolean
    var hintLowPerfShown: Boolean
    var hintPivotScrubShown: Boolean
    var hintStyleToggleShown: Boolean
}

class SharedPreferencesWelcomePreferences(context: Context) : WelcomePreferences {
    private val prefs = context.applicationContext.getSharedPreferences(
        WELCOME_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override var welcomeVersion: Int
        get() = prefs.getInt(KEY_WELCOME_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_WELCOME_VERSION, value).apply()
        }

    override var bgmMuted: Boolean
        get() = prefs.getBoolean(KEY_BGM_MUTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BGM_MUTED, value).apply()
        }

    override var hintLowPerfShown: Boolean
        get() = prefs.getBoolean(KEY_HINT_LOW_PERF, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HINT_LOW_PERF, value).apply()
        }

    override var hintPivotScrubShown: Boolean
        get() = prefs.getBoolean(KEY_HINT_PIVOT_SCRUB, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HINT_PIVOT_SCRUB, value).apply()
        }

    override var hintStyleToggleShown: Boolean
        get() = prefs.getBoolean(KEY_HINT_STYLE_TOGGLE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HINT_STYLE_TOGGLE, value).apply()
        }

    private companion object {
        const val KEY_WELCOME_VERSION = "welcome_version"
        const val KEY_BGM_MUTED = "bgm_muted"
        const val KEY_HINT_LOW_PERF = "hint_low_perf"
        const val KEY_HINT_PIVOT_SCRUB = "hint_pivot_scrub"
        const val KEY_HINT_STYLE_TOGGLE = "hint_style_toggle"
    }
}

fun shouldShowWelcome(storedVersion: Int): Boolean = storedVersion < CURRENT_WELCOME_VERSION
