package dev.amatv.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.amatv.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlymeTouchSmokeTest {
    @Test fun demoPlayerControlsStayVisibleAndQueueAndBackRespondToTouch() {
        assumeTrue(BuildConfig.FLYME_AUTO)
        // Only run with a fresh, unconfigured demo installation on a test device.
        assumeTrue(BuildConfig.MUSICKIT_DEVELOPER_TOKEN.isBlank() && BuildConfig.MUSICKIT_TOKEN_ENDPOINT.isBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val theme = context.resources.newTheme().apply { applyStyle(dev.amatv.R.style.Theme_AmAtv, true) }
        val flags = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowFullscreen))
        try { assertFalse("Car theme must preserve the status bar", flags.getBoolean(0, true)) }
        finally { flags.recycle() }
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val nav = device.wait(Until.findObject(By.res("nav-nowplaying")), 15000)
        assertNotNull("Now Playing navigation", nav)
        nav.click()
        val play = device.wait(Until.findObject(By.res("player-play-pause")), 10000)
        assertNotNull("Touch playback control", play)
        assertTrue(play.isClickable)
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single()
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
            assertTrue("Status bar stays visible", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            assertTrue("Bottom bar stays visible", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
            File(context.filesDir, "flyme-viewport.txt").writeText(
                "content=${content.width}x${content.height}\n" +
                    "bars=${insets.getInsets(WindowInsetsCompat.Type.systemBars())}\n",
            )
        }
        assertNotNull(device.findObject(By.res("player-touch-progress")))
        val lyricsBounds = device.findObject(By.res("lyrics-panel"))?.visibleBounds
        val progressBounds = device.findObject(By.res("player-touch-progress")).visibleBounds
        if (lyricsBounds != null) assertTrue("Lyrics stay above playback controls", lyricsBounds.bottom <= progressBounds.top)
        val prefs = dev.amatv.integration.PlaybackPreferences(context)
        val initialTextSize = prefs.lyricTextSize
        device.findObject(By.res("player-lyrics-display")).click()
        val textSizeRow = device.wait(Until.findObject(By.res("lyrics-text-size")), 5000)
        assertNotNull(textSizeRow)
        var expectedTextSize = initialTextSize
        // Wait for the new rendered value before clicking again: UIAutomator's
        // idle signal alone does not guarantee a Compose recomposition finished.
        repeat(3) {
            device.findObject(By.res("lyrics-text-size")).click()
            expectedTextSize = expectedTextSize.next()
            val label = context.getString(when (expectedTextSize) {
                dev.amatv.integration.LyricTextSize.Standard -> dev.amatv.R.string.flyme_text_standard
                dev.amatv.integration.LyricTextSize.Large -> dev.amatv.R.string.flyme_text_large
                dev.amatv.integration.LyricTextSize.ExtraLarge -> dev.amatv.R.string.flyme_text_extra_large
            })
            assertTrue("Updated lyric size is rendered", device.wait(Until.hasObject(By.text(label)), 5000))
            assertEquals(expectedTextSize, prefs.lyricTextSize)
        }
        assertEquals(initialTextSize, prefs.lyricTextSize)
        device.findObject(By.text(context.getString(dev.amatv.R.string.player_lyrics_display_done))).click()
        device.waitForIdle()
        device.findObject(By.res("player-play-pause")).click()
        assertTrue("Touch play changes the playback control",
            device.wait(Until.hasObject(By.desc(context.getString(dev.amatv.R.string.player_pause))), 5000))
        device.findObject(By.res("player-play-pause")).click()
        assertTrue("Touch pause changes the playback control",
            device.wait(Until.hasObject(By.desc(context.getString(dev.amatv.R.string.player_play))), 5000))
        Thread.sleep(5500) // TV hides its controls after five seconds; the car must retain them.
        assertNotNull(device.findObject(By.res("player-play-pause")))
        assertTrue(device.takeScreenshot(File(context.filesDir, "flyme-player.png")))
        device.findObject(By.res("player-queue")).click()
        assertTrue(device.wait(Until.hasObject(By.res("queue-panel")), 5000))
        assertTrue(device.takeScreenshot(File(context.filesDir, "flyme-queue.png")))
        device.findObject(By.res("player-back")).click()
        assertTrue(device.wait(Until.hasObject(By.res("nav-home")), 5000))
    }
}
