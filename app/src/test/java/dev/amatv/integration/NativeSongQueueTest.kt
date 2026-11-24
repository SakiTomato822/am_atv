package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun appendWithCarCallbackContainerTypeZeroStillNeedsExactlyOneSuccessor() {
        for (callbackFirst in listOf(false, true)) {
            val append = NativeSongQueue.Append(7L, 1, "B", null)
            if (!callbackFirst) assertFalse(append.isVisible(7L, 0, 2, "A"))
            assertTrue(append.acknowledge(7L, insertionType = 2, containerType = 0))
            assertFalse(append.isVisible(7L, 0, 1, "A"))
            assertFalse(append.isVisible(7L, 0, 3, "A"))
            assertTrue(append.isVisible(7L, 0, 2, "A"))
        }
    }

    @Test
    fun missingOrObsoleteCallbackCannotConfirmQueue() {
        val append = NativeSongQueue.Append(7L, 1, "B", null)
        assertFalse(append.acknowledge(6L, insertionType = 2, containerType = 0))
        assertFalse(append.acknowledge(7L, insertionType = 3, containerType = 0))
        assertFalse(append.acknowledge(7L, insertionType = 2, containerType = 1))
        assertFalse(append.isVisible(7L, 0, 2, "A"))
        assertTrue(append.acknowledge(7L, insertionType = 2, containerType = 0))
        assertFalse(append.acknowledge(7L, insertionType = 2, containerType = 0))
        assertFalse(append.isVisible(8L, 0, 2, "A"))
    }

    @Test
    fun queueEditAndRepeatedRecordingDoNotChangeOccurrenceOwningAppend() {
        val append = NativeSongQueue.Append(7L, 1, "B", GaplessTransition(7L, 20L, "test", 0L))
        assertTrue(append.acknowledge(7L, insertionType = 2, containerType = 0))
        assertFalse(append.matchesPlan("C", 30L))
        assertFalse(append.matchesPlan("B", 30L))
        assertTrue(append.matchesPlan("B", 20L))
        assertTrue(append.isVisible(7L, 1, 2, "B"))
        assertFalse(append.isVisible(7L, 1, 2, "C"))
        assertFalse(append.isVisible(7L, 2, 3, "B"))
    }

    @Test
    fun nativeIndexCanMoveBeforeTheAppAdoptsTheNextOccurrence() {
        queue.activeIndex = 0
        assertFalse(queue.hasAdvanced(0))
        assertTrue(queue.hasAdvanced(1))
        queue.activeIndex = 1
        assertFalse(queue.hasAdvanced(1))
        assertTrue(queue.hasAdvanced(2))
    }

    @Test
    fun onePendingAppendOwnsTheQueueAcrossRepeatedSyncAndPlanChanges() {
        val rail = NativeSongQueue()
        rail.activeIndex = 0
        val first = checkNotNull(rail.beginAppend(7L, 0, "B", null))
        assertNull(rail.beginAppend(7L, 0, "B", null))
        assertNull(rail.beginAppend(7L, 0, "C", null))
        assertNull(rail.confirmAppend(7L, 0, 2, "A"))
        assertTrue(first.acknowledge(7L, insertionType = 2, containerType = 0))
        assertNull(rail.confirmAppend(7L, 0, 1, "A"))
        assertNull(rail.beginAppend(7L, 0, "C", null))
        assertEquals(first, rail.confirmAppend(7L, 1, 2, "B"))
        assertNull(rail.beginAppend(7L, 1, "C", null))
        rail.activeIndex = 1
        assertEquals(2, rail.beginAppend(7L, 1, "C", null)?.index)
    }
}
