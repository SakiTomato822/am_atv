package dev.amatv.ui

import dev.amatv.integration.AutoplaySource
import org.junit.Assert.*
import org.junit.Test

class StartupQueueActionsTest {
    @Test fun queuedPlayReplaysNavigationWithoutPreparingIntermediateSongs() {
        val pending = StartupQueueActions()
        pending.enqueue(StartupQueueAction.Next)
        pending.enqueue(StartupQueueAction.Next)
        var playIntent = true
        var index = 500 // The fully hydrated timeline, not its small checkpoint tail.
        var intermediateAudioPrepares = 0
        pending.replayHydrated(true, { playIntent = it }) { action ->
            if (playIntent) intermediateAudioPrepares++
            if (action == StartupQueueAction.Next) index++
            true
        }
        assertEquals(502, index)
        assertEquals(0, intermediateAudioPrepares)
        assertTrue(playIntent)
        assertTrue(pending.drain().isEmpty())
    }

    @Test fun latestPauseChoiceWinsEvenIfQueueSelectionHadRequestedPlayEarlier() {
        val pending = StartupQueueActions()
        pending.enqueue(StartupQueueAction.Select(9001L))
        pending.enqueue(StartupQueueAction.Previous)
        var playIntent = false
        val observed = mutableListOf<StartupQueueAction>()
        pending.replayHydrated(false, { playIntent = it }) {
            assertFalse(playIntent)
            observed += it
            true
        }
        assertEquals(listOf(StartupQueueAction.Select(9001L), StartupQueueAction.Previous), observed)
        assertFalse(playIntent)
    }

    @Test fun catalogContextReplacementKeepsModeChoicesButDiscardsOldTimelineActions() {
        val pending = StartupQueueActions()
        pending.enqueue(StartupQueueAction.Next)
        pending.enqueue(StartupQueueAction.SetSource(AutoplaySource.AppleMusic))
        pending.enqueue(StartupQueueAction.ClearUpcoming)
        pending.enqueue(StartupQueueAction.ToggleShuffle)
        pending.enqueue(StartupQueueAction.Select(9001L))
        assertEquals(listOf(
            StartupQueueAction.SetSource(AutoplaySource.AppleMusic), StartupQueueAction.ToggleShuffle,
        ), pending.preferencesForNewContext())
        assertTrue(pending.drain().isEmpty())
    }

    @Test fun missingStableEntryCannotAutoplayAnotherRestoredEntry() {
        val pending = StartupQueueActions()
        pending.enqueue(StartupQueueAction.Select(9001L))
        var playIntent = true
        pending.replayHydrated(true, { playIntent = it }) { false }
        assertFalse(playIntent)
    }

    @Test fun unavailableStorageCannotAccumulateUnboundedRemoteInput() {
        val pending = StartupQueueActions(capacity = 2)
        assertTrue(pending.enqueue(StartupQueueAction.Next))
        assertTrue(pending.enqueue(StartupQueueAction.Previous))
        assertFalse(pending.enqueue(StartupQueueAction.Next))
        assertEquals(listOf(StartupQueueAction.Next, StartupQueueAction.Previous), pending.drain())
        assertTrue(pending.enqueue(StartupQueueAction.Next))
    }

    @Test fun onlyTheExactWaitingEntryConsumesARecommendationFailure() {
        assertEquals("timeout", waitingRecommendationError(42L, 42L, false, "timeout"))
        // Ordinary reserve prefetch errors must not pause a playing/preparing song.
        assertNull(waitingRecommendationError(null, 42L, false, "timeout"))
        assertNull(waitingRecommendationError(41L, 42L, false, "old timeout"))
        assertNull(waitingRecommendationError(42L, 42L, true, "previous error"))
        assertNull(waitingRecommendationError(42L, 42L, false, null))
    }
}
