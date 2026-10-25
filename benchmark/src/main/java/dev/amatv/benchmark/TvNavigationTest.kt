package dev.amatv.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.content.Intent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule val failures = UiFailureArtifacts()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ui = TvUi(device)
    @Before fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(TARGET)
            ?: throw AssertionError("Target app is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), UI_TIMEOUT))
    }

    private fun visibleRowCenters(row: Int): Map<String, Float> {
        val cards = device.findObjects(By.res(java.util.regex.Pattern.compile("shelf-$row-item-\\d+-[a-z]+")))
            .map { it.resourceName to it.visibleBounds }
            .filter { it.second.width() > 0 && it.second.height() > 0 }
        val widest = cards.maxOfOrNull { it.second.width() } ?: 0
        // A partially clipped edge card is not the target for vertical entry.
        // Allow the focused card's small draw-scale difference from its neighbors.
        val fullyVisible = cards.filter { it.second.width() >= widest - 12 }
        return fullyVisible.ifEmpty { cards }.associate { it.first to it.second.exactCenterX() }
    }

    private fun assertRowDidNotMoveHorizontally(before: Map<String, Float>, after: Map<String, Float>) {
        val common = before.keys.intersect(after.keys)
        assertTrue("Destination row must keep its visible cards", common.isNotEmpty())
        common.forEach { id ->
            assertTrue("Vertical focus shifted $id horizontally", kotlin.math.abs(before.getValue(id) - after.getValue(id)) <= 8f)
        }
    }

    @Test fun homeHorizontalAndVerticalFocusRemainAddressable() {
        // Public chart rows are stable; only their on-screen geometry determines
        // the target. Personalized shelf order is discovered through the UI.
        ui.enterHomeShelf("熱門專輯")
        val sourceRow = ui.focusedCardPosition().first
        val destinationRow = sourceRow + 1
        assumeTrue("Need a lower shelf for screen-position navigation", destinationRow < ui.homeRowCount())
        ui.down(0)
        ui.requireObject(By.res(java.util.regex.Pattern.compile("shelf-$destinationRow-item-\\d+-[a-z]+")).focused(true), 5_000L)
        val lowerBefore = visibleRowCenters(destinationRow)
        assertTrue("Lower row needs visible card geometry", lowerBefore.isNotEmpty())
        ui.up(0)
        ui.awaitCardPosition(sourceRow, 0)
        val sourceColumn = minOf(10, ui.homeItemCount(sourceRow) - 1)
        repeat(sourceColumn) { ui.right(100) }
        val sourceId = ui.awaitCardPosition(sourceRow, sourceColumn).resourceName
        val sourceX = ui.id(sourceId).visibleBounds.exactCenterX()
        val upperBefore = visibleRowCenters(sourceRow)
        val firstUpper = upperBefore.keys.minOf { it.split('-')[3].toInt() }
        val firstLower = lowerBefore.keys.minOf { it.split('-')[3].toInt() }
        assertTrue("Setup must give the two rows different horizontal offsets", firstUpper > firstLower)
        val expectedLower = lowerBefore.minByOrNull { kotlin.math.abs(it.value - sourceX) }!!.key
        ui.down(0)
        ui.awaitFocused(expectedLower)
        // This is a normal single-step oracle: finish the 260ms reveal before
        // computing the independent Up anchor. Rapid input has its own test.
        ui.settle(340)
        assertTrue("Down must keep exact destination input focus", ui.id(expectedLower).isFocused)
        assertFalse("Source must release input focus", ui.id(sourceId).isFocused)
        val lowerAfter = visibleRowCenters(destinationRow)
        assertRowDidNotMoveHorizontally(lowerBefore, lowerAfter)
        val incomingX = ui.id(expectedLower).visibleBounds.exactCenterX()
        val expectedUpper = upperBefore.minByOrNull { kotlin.math.abs(it.value - incomingX) }!!.key
        ui.up(0)
        ui.awaitFocused(expectedUpper)
        ui.settle(340)
        assertTrue("Up must keep exact destination input focus", ui.id(expectedUpper).isFocused)
        assertFalse("Lower source must release input focus", ui.id(expectedLower).isFocused)
        assertRowDidNotMoveHorizontally(upperBefore, visibleRowCenters(sourceRow))
    }

    @Test fun rapidDownDoesNotCancelTheRequestedDestination() {
        // Recreate Home through a real tab change. Keep catalog/account data; do
        // not reset the process, disk cache, recommendations or remote service.
        ui.browseTab("browse")
        ui.enterHomeFirstCard()
        val targetRow = ui.homeRowCount() - 1
        assumeTrue("Need at least three shelves for rapid cold-row navigation", targetRow >= 2)
        // Home's public playlist chart is last, regardless of personalized rows.
        // Confirm the public heading after arrival instead of assuming a feed.
        repeat(targetRow - 2) { row ->
            ui.down(0)
            ui.awaitCardPosition(row + 1, 0)
        }
        ui.down(110)
        ui.down(0)
        val target = ui.awaitCardPosition(targetRow, 0)
        val targetId = target.resourceName
        ui.awaitFocused(targetId)
        assertEquals("Two Down presses must advance two rows", targetRow to 0, ui.focusedCardPosition())
        val chart = device.findObject(By.res(java.util.regex.Pattern.compile("home-row-$targetRow-count-\\d+"))
            .hasDescendant(By.text("熱門播放列表")))
        assumeTrue("Public playlist chart is not available in this storefront response", chart != null)
        assertTrue("Chart first card must open a collection", targetId.endsWith("-playlist"))
        // Only the fixed focus/click wrapper is required. Never wait for title,
        // cover, decoder completion or the scheduler's mounted-content state.
        android.util.Log.i("AMATV-UI", "cold-row=$targetRow wrapperState=${ui.stateDescription(targetId)}")
        ui.center(0)
        ui.id("collection-screen")
        assertFalse("The selected card must open its collection", ui.has("player-screen"))
        device.pressBack()
        ui.id("nav-home")
        assertFalse("A single Back must leave the collection", ui.has("collection-screen"))
        ui.awaitFocused(targetId)
        assertEquals("Back must restore the same Home card", targetRow to 0, ui.focusedCardPosition())
    }

    @Test fun searchFieldCanAlwaysMoveDownToResults() {
        ui.browseTab("search")
        ui.down(400)
        assertTrue("Navigation Down must focus search input", ui.id("search-field").isFocused)
        ui.up()
        assertTrue(ui.id("search-term-0").isFocused)
        ui.center()
        ui.down()
        assertTrue(ui.id("search-field").isFocused)
        ui.requireObject(By.res("search-result-0"))
        ui.down(700)
        assertTrue("Search field Down must reach first result", ui.id("search-result-0").isFocused)
        ui.up(400)
        assertTrue("Result Up must return to field", ui.id("search-field").isFocused)
    }
}
