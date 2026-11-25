package dev.amatv.flyme

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class FolioLavaBackdropTest {
    @Test
    fun `motion requires playback and a visible home backdrop`() {
        val visibleStates = setOf(Lifecycle.State.STARTED, Lifecycle.State.RESUMED)
        for (lifecycle in Lifecycle.State.entries) {
            for (playerOpen in listOf(false, true)) {
                for (isPlaying in listOf(false, true)) {
                    assertEquals(
                        "lifecycle=$lifecycle, playerOpen=$playerOpen, isPlaying=$isPlaying",
                        lifecycle in visibleStates && !playerOpen && isPlaying,
                        isLavaBackdropRunning(lifecycle, playerOpen, isPlaying),
                    )
                }
            }
        }
    }
}
