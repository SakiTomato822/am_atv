package dev.amatv.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPreferencesTest {
    @Test
    fun lowPerformanceModeDefaultsOffAndKeepsExplicitChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "am_atv_playback_preferences",
            Context.MODE_PRIVATE,
        )
        val key = "pref_playback_low_performance_mode"
        val hadSavedValue = preferences.contains(key)
        val savedValue = preferences.getBoolean(key, false)

        try {
            preferences.edit().remove(key).commit()
            assertFalse(PlaybackPreferences(context).lowPerformanceMode)

            PlaybackPreferences(context).lowPerformanceMode = true
            assertTrue(PlaybackPreferences(context).lowPerformanceMode)

            PlaybackPreferences(context).lowPerformanceMode = false
            assertFalse(PlaybackPreferences(context).lowPerformanceMode)
        } finally {
            val editor = preferences.edit()
            if (hadSavedValue) editor.putBoolean(key, savedValue) else editor.remove(key)
            editor.commit()
        }
    }
}
