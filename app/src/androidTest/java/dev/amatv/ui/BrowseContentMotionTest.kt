package dev.amatv.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.amatv.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowseContentMotionTest {
    @Test
    fun outgoingPageLosesFocusTargetsAndEntryDuringTransition() {
        val page = mutableStateOf("old")
        val focusEntry = ContentFocusEntry()
        val observations = ConcurrentHashMap<String, FocusLocals>()
        val initialPageComposed = CountDownLatch(1)
        val outgoingPageDisabled = CountDownLatch(1)
        val incomingPageEnabled = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AmAtvTheme {
                        CompositionLocalProvider(
                            LocalContentFocusEntry provides focusEntry,
                            LocalFocusTargetsEnabled provides true,
                            LocalFocusFrameEnabled provides true,
                        ) {
                            BrowseContentMotion(
                                targetState = page.value,
                                modifier = Modifier.fillMaxSize(),
                                label = "browse-focus-safety-test",
                            ) { target ->
                                val focusTargetsEnabled = LocalFocusTargetsEnabled.current
                                val focusFrameEnabled = LocalFocusFrameEnabled.current
                                val targetFocusEntry = LocalContentFocusEntry.current
                                SideEffect {
                                    observations[target] = FocusLocals(
                                        targetsEnabled = focusTargetsEnabled,
                                        frameEnabled = focusFrameEnabled,
                                        focusEntry = targetFocusEntry,
                                    )
                                    when {
                                        target == "old" && focusTargetsEnabled &&
                                            focusFrameEnabled && targetFocusEntry === focusEntry ->
                                            initialPageComposed.countDown()

                                        target == "old" && !focusTargetsEnabled &&
                                            !focusFrameEnabled && targetFocusEntry == null ->
                                            outgoingPageDisabled.countDown()

                                        target == "new" && focusTargetsEnabled &&
                                            focusFrameEnabled && targetFocusEntry === focusEntry ->
                                            incomingPageEnabled.countDown()
                                    }
                                }
                                Text(target)
                            }
                        }
                    }
                }
            }

            assertTrue("Initial browse page should compose", initialPageComposed.await(5, TimeUnit.SECONDS))
            scenario.onActivity { page.value = "new" }

            assertTrue(
                "Outgoing page should remain composed briefly with focus disabled",
                outgoingPageDisabled.await(5, TimeUnit.SECONDS),
            )
            assertTrue(
                "Incoming page should retain its focus targets and entry",
                incomingPageEnabled.await(5, TimeUnit.SECONDS),
            )

            val outgoing = observations["old"]
            val incoming = observations["new"]
            assertNotNull(outgoing)
            assertNotNull(incoming)
            assertFalse(outgoing!!.targetsEnabled)
            assertFalse(outgoing.frameEnabled)
            assertNull(outgoing.focusEntry)
            assertTrue(incoming!!.targetsEnabled)
            assertTrue(incoming.frameEnabled)
            assertSame(focusEntry, incoming.focusEntry)
        }
    }

    private data class FocusLocals(
        val targetsEnabled: Boolean,
        val frameEnabled: Boolean,
        val focusEntry: ContentFocusEntry?,
    )
}
