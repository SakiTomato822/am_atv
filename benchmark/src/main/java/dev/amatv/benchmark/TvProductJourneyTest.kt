package dev.amatv.benchmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class TvProductJourneyTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = TvUi(device)

    @Before fun coldLaunchPreservingAccount() {
        device.executeShellCommand("am force-stop $TARGET")
        launchTarget()
        if (ui.ensurePlayableSong()) {
            // Exercise a genuine restored start after the one-time public-song seed.
            // Home delivers ON_STOP so the normal cursor persistence path runs.
            device.pressHome()
            ui.settle(600)
            device.executeShellCommand("am force-stop $TARGET")
            launchTarget()
        }
    }

    private fun launchTarget() {
        val context = instrumentation.context
        val intent = context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw AssertionError("Target app not installed")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), UI_TIMEOUT))
    }

    @Test fun restoredStartupDoesNotAutomaticallyPlay() {
        ui.browseTab("home")
        assumeTrue("No saved song on this account", ui.has("nav-nowplaying"))
        ui.browseTab("nowplaying")
        val player = PlayerUi(ui)
        player.awaitState("paused")
        ui.settle(2_000)
        assertEquals("Startup must remain paused after services initialize", "paused", player.state())
    }

    @Test fun collectionSongsAndSingleBackReturnToBrowse() {
        ui.browseTab("library")
        ui.requireObject(By.res(Pattern.compile("home-shelves-count-\\d+")))
        ui.down(500)
        var collection = ui.homeCard().resourceName.endsWith("-playlist") || ui.homeCard().resourceName.endsWith("-album")
        repeat(20) {
            if (!collection) {
                ui.right(180)
                val focused = ui.homeCard().resourceName
                collection = focused.endsWith("-playlist") || focused.endsWith("-album")
            }
        }
        assumeTrue("Account has no visible album/playlist", collection)
        ui.center()
        ui.id("collection-screen")
        assertFalse("Collection is a detail page without top tabs", ui.has("nav-home"))
        ui.requireObject(By.res("collection-play").enabled(true), 60_000)
        // The play action deliberately keeps input focus while data is loading.
        // Real song content, rather than its focusable wrapper, signals readiness.
        ui.requireObject(By.res("collection-song-0"), 60_000)
        ui.focusUi("collection-play")
        ui.down(0)
        ui.awaitFocused("collection-song-0")
        val count = ui.requireObject(By.text(Pattern.compile("\\d+ 首歌曲.*")))
            .text.substringBefore(' ').toInt()
        val steps = (count - 1).coerceIn(0, 6)
        repeat(steps) { ui.down(100) }
        repeat(steps) { ui.up(100) }
        ui.settle(600)
        assertTrue("Song list must retain DPAD focus", ui.id("collection-song-0").isFocused)
        ui.left(350)
        device.pressBack()
        ui.id("nav-home")
        assertFalse("One Back must close collection", ui.has("collection-screen"))
    }

    @Test fun queueModesAndControllerDisappearTogether() {
        ui.browseTab("home")
        assumeTrue("No saved song on this account", ui.has("nav-nowplaying"))
        ui.browseTab("nowplaying")
        val player = PlayerUi(ui)
        player.focusControl("player-queue")
        ui.center()
        ui.id("queue-panel")
        ui.id("queue-mode-controls")
        assertFalse(ui.device.hasObject(By.text("Playing Next")))
        repeat(4) { ui.right(150) }
        repeat(4) { ui.left(150) }
        assertTrue(device.wait(Until.gone(By.res("player-controls")), 8_000))
        assertFalse("Top queue modes must disappear with controller", ui.has("queue-mode-controls"))
        assertTrue("Queue history stays visible while controls hide", ui.has("queue-panel"))
        ui.right(450)
        ui.id("player-controls")
        ui.id("queue-mode-controls")
    }

    @Test fun nativePlaybackCanStartAndPauseFromRemote() {
        ui.browseTab("home")
        assumeTrue("No saved song on this account", ui.has("nav-nowplaying"))
        ui.browseTab("nowplaying")
        val player = PlayerUi(ui)
        try {
            player.play()
            ui.settle(3_000)
            assertEquals("playing", player.state())
        } finally {
            player.pause()
        }
    }
}
