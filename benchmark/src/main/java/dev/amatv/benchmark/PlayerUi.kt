package dev.amatv.benchmark

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue

/** Reads actual player state through Android accessibility; no private playback command channel. */
internal class PlayerUi(private val ui: TvUi) {
    fun state(): String? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        @Suppress("DEPRECATION")
        fun read(node: AccessibilityNodeInfo): String? = try {
            if (node.viewIdResourceName == "player-screen") node.stateDescription?.toString()
            else {
                var found: String? = null
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    found = read(child)
                    if (found != null) break
                }
                found
            }
        } finally { node.recycle() }
        return read(root)
    }
    fun awaitState(expected: String, timeout: Long = UI_TIMEOUT) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (state() == expected) return
            ui.settle(150)
        }
        throw AssertionError("Player did not reach state=$expected; last state=${state()}")
    }
    fun wakeControls() {
        if (!ui.has("player-controls")) { ui.right(350) }
        ui.id("player-controls")
    }
    private fun isSelected(id: String): Boolean {
        val node = ui.device.findObject(By.res(id)) ?: return false
        // Compose exposes Selected as checked for non-tab controls.
        return node.isSelected || node.isChecked
    }

    fun focusControl(id: String) {
        wakeControls()
        // This helper is used from the controls zone; a freshly opened player
        // starts there and a hidden player returns there on its first key.
        repeat(7) {
            if (!isSelected(id)) ui.left(55)
        }
        repeat(7) {
            if (!isSelected(id)) ui.right(80)
        }
        assertTrue("Lost virtual player focus: $id", isSelected(id))
    }
    fun play() {
        focusControl("player-play-pause")
        if (state() != "playing") ui.center()
        awaitState("playing")
    }
    fun pause() {
        wakeControls()
        focusControl("player-play-pause")
        if (ui.device.hasObject(By.desc("暫停"))) ui.center()
        awaitState("paused")
    }
}
