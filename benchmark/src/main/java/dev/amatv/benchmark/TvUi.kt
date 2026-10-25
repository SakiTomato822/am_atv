package dev.amatv.benchmark

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.util.regex.Pattern

internal const val TARGET = "dev.amatv"
internal const val UI_TIMEOUT = 30_000L
internal val CARD_PATTERN = Pattern.compile("shelf-(\\d+)-item-(\\d+)-[a-z]+")

/** All user journeys use real key events. Wait for semantic destinations, not global animation idle. */
internal class TvUi(val device: UiDevice) {
    init {
        // Native/Compose animation does not imply input is unsafe. Implicit idle
        // waits would stretch a 110ms DPAD burst into seconds and hide the defect.
        Configurator.getInstance().waitForIdleTimeout = 0L
        Configurator.getInstance().keyInjectionDelay = 0L
    }
    fun requireObject(selector: BySelector, timeout: Long = UI_TIMEOUT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("Missing UI destination: $selector")
    fun id(value: String): UiObject2 = requireObject(By.res(value))
    fun has(value: String): Boolean = device.hasObject(By.res(value))
    fun settle(milliseconds: Long = 340L) = SystemClock.sleep(milliseconds)
    fun key(code: Int, pause: Long = 100L) {
        assertTrue("Key injection failed: $code", device.pressKeyCode(code))
        if (pause > 0) settle(pause)
    }
    fun down(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_DOWN, pause)
    fun up(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_UP, pause)
    fun left(pause: Long = 120L) = key(KeyEvent.KEYCODE_DPAD_LEFT, pause)
    fun right(pause: Long = 120L) = key(KeyEvent.KEYCODE_DPAD_RIGHT, pause)
    fun center(pause: Long = 340L) = key(KeyEvent.KEYCODE_DPAD_CENTER, pause)

    fun browseTab(tab: String) {
        repeat(5) {
            if (has("player-screen") || has("collection-screen")) {
                device.pressBack()
                settle()
            }
        }
        requireObject(By.res("nav-home"))
        // Screen setup uses a real UI click. Re-reading global By.focused()
        // selectors can return an earlier accessibility snapshot and queue
        // enough directional keys to overshoot the intended tab.
        id("nav-$tab")
        assertTrue("Navigation UI action failed: $tab",
            performUiAction("nav-$tab", AccessibilityNodeInfo.ACTION_CLICK))
        val destinationId = if (tab == "nowplaying") "player-screen" else "browse-$tab"
        id(destinationId)
        val focusedId = if (tab == "nowplaying") "player-screen" else "nav-$tab"
        performUiAction(focusedId, AccessibilityNodeInfo.ACTION_FOCUS)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            // UiObject2's property access refreshes the matched exact-ID node;
            // do not rely on By.focused() having matched a fresh snapshot.
            if (device.findObject(By.res(focusedId))?.isFocused == true) return
            settle(60)
        }
        throw AssertionError("Destination $destinationId is visible but focus did not reach $focusedId")
    }

    /** Standard accessibility focus action for setup, including clicking the already-selected tab. */
    private fun performUiAction(id: String, action: Int): Boolean {
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo): Boolean = try {
            if (node.viewIdResourceName == id) node.performAction(action)
            else (0 until node.childCount).any { index -> node.getChild(index)?.let(::visit) == true }
        } finally { node.recycle() }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return visit(root)
    }

    fun homeCard(): UiObject2 = requireObject(By.res(CARD_PATTERN).focused(true))
    fun homeRowCount(): Int = requireObject(By.res(Pattern.compile("home-shelves-count-\\d+")))
        .resourceName.substringAfterLast('-').toInt()
    fun focusedCardPosition(): Pair<Int, Int> {
        val card = homeCard()
        val name = card.resourceName
        val match = CARD_PATTERN.matcher(name)
        assertTrue("Card has no stable coordinate: $name", match.matches())
        Log.i("AMATV-UI", "focus=${match.group(1)},${match.group(2)} bounds=${card.visibleBounds}")
        return match.group(1).toInt() to match.group(2).toInt()
    }
    fun homeItemCount(row: Int): Int = requireObject(By.res(Pattern.compile("home-row-$row-count-\\d+")))
        .resourceName.substringAfterLast('-').toInt()
    fun awaitCardPosition(row: Int, column: Int, timeout: Long = 5_000L): UiObject2 =
        requireObject(By.res(Pattern.compile("shelf-$row-item-$column-[a-z]+")).focused(true), timeout)

    fun enterHomeFirstCard() {
        // Setup only: a real tab round-trip disposes the old Home lazy state.
        // This is faster and independent of how many personalized shelves exist;
        // no process/data reset, synthetic coordinates or measured-key changes.
        browseTab("browse")
        browseTab("home")
        requireObject(By.res(Pattern.compile("home-shelves-count-\\d+")))
        assertTrue("Home needs at least one shelf", homeRowCount() > 0)
        requireObject(By.res(Pattern.compile("shelf-0-item-0-[a-z]+")))
        down(0)
        awaitCardPosition(0, 0)
        assertEquals(0 to 0, focusedCardPosition())
    }
    /** Pin the horizontal measurement to a public chart shelf, never a changing personalized first row. */
    fun enterHomeShelf(title: String, minimumCardCount: Int = 11) {
        enterHomeFirstCard()
        val rowCount = homeRowCount()
        repeat(rowCount) {
            val row = focusedCardPosition().first
            val namedRow = device.findObject(
                By.res(Pattern.compile("home-row-$row-count-\\d+")).hasDescendant(By.text(title)),
            )
            if (namedRow != null) {
                val count = homeItemCount(row)
                assertTrue("Public chart must contain at least $minimumCardCount cards", count >= minimumCardCount)
                awaitCardPosition(row, 0)
                Log.i("AMATV-UI", "benchmark shelf=$title items=$count bounds=${homeCard().visibleBounds}")
                return
            }
            if (row < rowCount - 1) {
                down(0)
                awaitCardPosition(row + 1, 0)
            }
        }
        throw AssertionError("Public Home shelf not available: $title")
    }

    fun awaitFocused(id: String, timeout: Long = 5_000L) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.res(id))?.isFocused == true) return
            settle(50)
        }
        throw AssertionError("Input focus did not reach $id")
    }

    fun focusUi(id: String) {
        this.id(id)
        assertTrue("UI focus action failed: $id",
            device.findObject(By.res(id))?.isFocused == true ||
                performUiAction(id, AccessibilityNodeInfo.ACTION_FOCUS))
        awaitFocused(id)
    }

    /** Seed through the public catalog UI only if a genuine saved player cannot be opened. */
    fun ensurePlayableSong(): Boolean {
        browseTab("home")
        assumeTrue("SKIPPED: Apple Music account is not signed in", device.hasObject(By.text("帳號")))
        if (has("nav-nowplaying")) {
            // Older builds may expose a ghost tab despite having no selected song.
            // Prove that it opens a real player before trusting its presence.
            performUiAction("nav-nowplaying", AccessibilityNodeInfo.ACTION_CLICK)
            if (device.wait(Until.hasObject(By.res("player-screen")), 2_000)) {
                focusUi("player-screen")
                return false
            }
            browseTab("home")
        }
        enterHomeShelf("熱門歌曲", minimumCardCount = 1)
        assertTrue("Public songs shelf must contain an actual song", homeCard().resourceName.endsWith("-song"))
        center()
        id("player-screen")
        val player = PlayerUi(this)
        player.awaitState("playing", 60_000)
        player.pause()
        return true
    }

    /** Read public accessibility state without waiting for artwork/text content to mount. */
    fun stateDescription(id: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo): String? = try {
            if (node.viewIdResourceName == id) node.stateDescription?.toString()
            else {
                var found: String? = null
                for (index in 0 until node.childCount) {
                    found = node.getChild(index)?.let(::visit)
                    if (found != null) break
                }
                found
            }
        } finally { node.recycle() }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        return visit(root)
    }

    fun stopPlaybackIfVisible() {
        if (!has("player-screen")) return
        if (!has("player-controls")) { center(); settle() }
        val pause = device.findObject(By.desc("暫停"))
        if (pause != null) {
            // Initial player control is Play/Pause. Return there through the shared remote root.
            repeat(6) { left(50) }
            right()
            center()
        }
    }
}
