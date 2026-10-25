package dev.amatv.integration

import dev.amatv.model.Destination
import dev.amatv.playback.QueueState
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplaySourceDefaultsTest {
    @Test fun missingOrInvalidStoredPreferencesCannotOptInToYtm() {
        for (raw in listOf(null, "", "unknown", "YouTube Music", "null")) {
            assertEquals(AutoplaySource.AppleMusic, autoplaySourceFromStoredValue(raw))
        }
    }

    @Test fun recognizedExplicitChoicesSurvivePreferenceReads() {
        assertEquals(AutoplaySource.YouTubeMusic, autoplaySourceFromStoredValue("YouTubeMusic"))
        assertEquals(AutoplaySource.AppleMusic, autoplaySourceFromStoredValue("AppleMusic"))
    }

    @Test fun newSessionAndQueueUseAppleMusicWithoutASavedSelection() {
        assertEquals(AutoplaySource.AppleMusic, QueueState().source)
        val session = PlaybackSession(
            queue = emptyList(), currentIndex = 0, positionMs = 0, wasPlaying = false,
            destination = Destination.Home, previousDestination = Destination.Home,
            radioActive = true, radioSeed = null, radioContinuation = null,
        )
        assertEquals(AutoplaySource.AppleMusic, session.autoplaySource)
    }
}
