package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSongQueueTest {
    private val queue = NativeSongQueue().apply {
        record(0, "A", 100L)
        record(1, "B", 101L)
        record(2, "C", null)
    }

    @Test
    fun choosesPreviousOrVisitedSelectionByIdAndAdjacentLookaheadByPosition() {
        assertEquals(NativeSongQueue.Command.ById(0, 100L), queue.command("A", 1, 3, true, true, true))
        assertEquals(NativeSongQueue.Command.Next(2), queue.command("C", 1, 3, true, true, true))
        assertEquals(NativeSongQueue.Command.Restart(1), queue.command("B", 1, 3, true, true, true))
        assertEquals(NativeSongQueue.Command.ById(1, 101L), queue.command("B", 0, 3, true, true, true))
    }

    @Test
    fun onlyIssuesRelativeNextOnceItIsVisibleFromTheCurrentSlot() {
        assertNull(queue.command("C", 1, 2, true, true, true))
        assertNull(queue.command("C", 1, 3, false, false, true))
        assertEquals(NativeSongQueue.Command.ById(0, 100L), queue.command("A", 1, 3, false, false, true))
        queue.forgetFrom(2)
        assertNull(queue.command("C", 1, 3, true, true, true))
    }
}
