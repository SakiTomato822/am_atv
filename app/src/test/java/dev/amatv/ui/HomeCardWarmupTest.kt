package dev.amatv.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeCardWarmupTest {
    @Test fun allRowsShareOneMountPermitPerDisplayFrame() {
        val queue = HomeCardWarmup()
        val mounted = mutableListOf<String>()
        queue.request({ 1 }) { mounted += "row-a" }
        queue.request({ 1 }) { mounted += "row-b" }
        queue.request({ 1 }) { mounted += "row-c" }
        assertTrue(queue.mountNext(100))
        assertFalse(queue.mountNext(100))
        assertEquals(listOf("row-a"), mounted)
        assertTrue(queue.mountNext(200))
        assertEquals(listOf("row-a", "row-b"), mounted)
    }

    @Test fun focusedThenVisibleCardsOvertakePrefetchedRowsWithoutReorderingTies() {
        val queue = HomeCardWarmup()
        val mounted = mutableListOf<String>()
        var offscreenPriority = 2
        queue.request({ offscreenPriority }) { mounted += "offscreen" }
        queue.request({ 1 }) { mounted += "visible-a" }
        queue.request({ 0 }) { mounted += "focused" }
        queue.request({ 1 }) { mounted += "visible-b" }
        repeat(3) { queue.mountNext(it.toLong()) }
        assertFalse(queue.mountNext(3))
        assertEquals(listOf("focused", "visible-a", "visible-b"), mounted)
        assertEquals(1, queue.pendingCount)
        offscreenPriority = 1
        queue.notifyPrioritiesChanged()
        assertTrue(queue.mountNext(4))
        assertEquals(listOf("focused", "visible-a", "visible-b", "offscreen"), mounted)
    }

    @Test fun rapidNavigationUsesTheLatestPriorityAtGrantTime() {
        val queue = HomeCardWarmup()
        val mounted = mutableListOf<String>()
        var firstPriority = 0
        var nextPriority = 2
        queue.request({ firstPriority }) { mounted += "old-target" }
        queue.request({ nextPriority }) { mounted += "new-target" }
        firstPriority = 2
        nextPriority = 0
        queue.mountNext(1)
        assertEquals(listOf("new-target"), mounted)
    }

    @Test fun disposingACellCancelsItsWorkAndAReusedCellRequestsAFreshPermit() {
        val queue = HomeCardWarmup()
        var mounted = 0
        val disposed = queue.request({ 0 }) { mounted++ }
        queue.cancel(disposed)
        assertEquals(0, queue.pendingCount)
        assertFalse(queue.mountNext(1))
        queue.request({ 0 }) { mounted++ }
        assertTrue(queue.mountNext(2))
        assertEquals(1, mounted)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun scrollingThroughManyDisposedCellsDoesNotRetainTheFeedHistory() {
        val queue = HomeCardWarmup()
        repeat(10_000) { queue.cancel(queue.request({ 2 }) { fail("Disposed card mounted") }) }
        assertEquals(0, queue.pendingCount)
        queue.request({ 0 }) {}
        assertTrue(queue.mountNext(1))
        assertEquals(0, queue.pendingCount)
    }

    @Test fun anIdleRunnerWakesForWorkAndClosingDropsPendingCallbacks() = runBlocking {
        val queue = HomeCardWarmup()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { queue.awaitPending() }
        assertFalse(waiting.isCompleted)
        queue.request({ 0 }) { fail("Closed feed mounted") }
        assertTrue(waiting.await())
        queue.close()
        assertEquals(0, queue.pendingCount)
        assertFalse(queue.mountNext(1))
        assertFalse(queue.awaitPending())
        queue.request({ 0 }) { fail("Request after close mounted") }
        assertEquals(0, queue.pendingCount)
    }
    @Test fun offscreenPermitsWaitUntilVisibilityOrFocusIsExplicitlyPromoted() = runBlocking {
        val queue = HomeCardWarmup()
        var priority = 2
        var mounts = 0
        queue.request({ priority }) { mounts++ }
        assertFalse(queue.mountNext(1))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { queue.awaitPending() }
        assertFalse(waiting.isCompleted)
        priority = 0
        queue.notifyPrioritiesChanged()
        assertTrue(waiting.await())
        assertTrue(queue.mountNext(2))
        assertFalse(queue.mountNext(2))
        assertEquals(1, mounts)
        queue.close()
    }

}
