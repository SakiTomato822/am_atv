package dev.amatv.integration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.amatv.core.AutoLowPerformanceReason
import dev.amatv.core.LowPerformanceMode
import dev.amatv.core.SettingsStore
import dev.amatv.core.SharedPreferencesSettingsStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPreferencesTest {
    @Test
    fun lowPerformanceModeMigratesAndKeepsExplicitChoice() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("performance_test_$name", mode)
        }
        val prefsName = "am_atv_playback_preferences"
        val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val storage = SharedPreferencesSettingsStorage(context)
        val key = "pref_playback_low_performance_mode"

        try {
            preferences.edit().clear().commit()
            assertEquals(LowPerformanceMode.Auto, storage.loadInitialState().lowPerformanceMode)

            preferences.edit().putBoolean(key, false).commit()
            assertEquals(LowPerformanceMode.Auto, storage.loadInitialState().lowPerformanceMode)

            preferences.edit().putBoolean(key, true).commit()
            assertEquals(LowPerformanceMode.On, storage.loadInitialState().lowPerformanceMode)

            storage.saveLowPerformanceMode(LowPerformanceMode.Off)
            assertEquals(LowPerformanceMode.Off, storage.loadInitialState().lowPerformanceMode)
            assertFalse(SettingsStore(storage).state.value.lowPerformance)

            storage.saveLowPerformanceMode(LowPerformanceMode.Auto)
            storage.savePlaybackJankDetected()
            assertEquals(AutoLowPerformanceReason.PlaybackJank, storage.loadInitialState().autoLowPerformanceReason)
            assertTrue(SettingsStore(storage).state.value.lowPerformance)
            storage.saveLowPerformanceMode(LowPerformanceMode.Auto)
            assertFalse(SettingsStore(storage).state.value.lowPerformance)
        } finally {
            targetContext.deleteSharedPreferences("performance_test_$prefsName")
        }
    }
}
