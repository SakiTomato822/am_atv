package dev.amatv.ui

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.amatv.MainActivity
import dev.amatv.data.MockCatalog
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryCategoriesTest {
    @Test fun fourCategoriesSelectTheirOwnSavedItems() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val kinds = listOf(CatalogKind.Playlist, CatalogKind.Artist, CatalogKind.Album, CatalogKind.Song)
        val categories = LibraryCategory.entries
        val seed = MockCatalog.tracks.first()
        val shelves = categories.mapIndexed { i, category ->
            ContentShelf(category.shelfId, "Preview ${category.name}",
                listOf(seed.copy(id = category.name, title = "Demo ${category.name}", catalogKind = kinds[i])))
        }
        var previousDescription: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AmAtvTheme {
                        CarLayout {
                            HomeScreen(destination = Destination.Library, currentTrack = seed,
                                connectionState = MusicConnectionState.SignedIn, homeShelves = shelves)
                        }
                    }
                }
            }
            categories.forEach { category ->
                val button = device.wait(Until.findObject(By.res("library-category-${category.name.lowercase()}")), 5000)
                if (button == null) saveDiagnostics(device, context, category.name)
                assertNotNull(category.name, button)
                button!!.click()
                val currentDescription = "Demo ${category.name}"
                val currentItem = device.wait(Until.findObject(By.descContains(currentDescription)), 5000)
                if (currentItem == null) saveDiagnostics(device, context, category.name)
                assertNotNull("Saved ${category.name} should appear", currentItem)
                previousDescription?.let { previous ->
                    val previousItemGone = device.wait(Until.gone(By.descContains(previous)), 5000)
                    if (!previousItemGone) saveDiagnostics(device, context, category.name)
                    assertTrue("Previous category item '$previous' should disappear", previousItemGone)
                }
                previousDescription = currentDescription
            }
            device.waitForIdle()
            Thread.sleep(800L) // Let staged cover/text rendering finish before saving the visual record.
            assertTrue(device.takeScreenshot(File(context.filesDir, "library-four-categories.png")))
        }
    }

    private fun saveDiagnostics(device: UiDevice, context: android.content.Context, category: String) {
        device.takeScreenshot(File(context.filesDir, "library-$category-failure.png"))
        device.dumpWindowHierarchy(File(context.filesDir, "library-hierarchy.xml"))
    }
}
