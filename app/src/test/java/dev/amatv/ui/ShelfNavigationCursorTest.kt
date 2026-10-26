package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class ShelfNavigationCursorTest {
    private fun row(vararg cards: ShelfCardBounds) = ShelfRowBounds(1_000f, cards.toList())
    private fun card(index: Int, center: Float, width: Float = 180f) = ShelfCardBounds(index, center - width / 2f, width)

    @Test fun appendingAPageKeepsFocusAndAllowsNavigationIntoTheNewItems() {
        val cursor = ShelfNavigationCursor(listOf(101, 101))
        cursor.onFocused(0, 100)
        cursor.updateShelfSizes(listOf(201, 201))
        assertEquals(ShelfFocusPosition(0, 100), cursor.focused)
        assertEquals(ShelfFocusPosition(1, 150), cursor.request(1) { _, _ -> 150 }!!.position)
    }

    @Test fun removingAnExhaustedLoadMoreCardClampsTheCursorToExistingContent() {
        val cursor = ShelfNavigationCursor(listOf(101, 101))
        cursor.onFocused(0, 100)
        cursor.updateShelfSizes(listOf(100, 101))
        assertEquals(ShelfFocusPosition(0, 99), cursor.focused)
    }

    @Test fun aScrolledSourceDoesNotForceTheSameIndexIntoAnUnscrolledRow() {
        val cursor = ShelfNavigationCursor(listOf(14, 14))
        cursor.onFocused(0, 10)
        val destination = row(card(0, 100f), card(1, 300f), card(2, 500f), card(3, 700f), card(4, 900f))
        val request = cursor.request(1, 860f) { _, anchor -> nearestVisibleShelfCard(anchor, destination) }!!
        assertEquals(ShelfFocusPosition(1, 4), request.position)
        assertEquals(860f, request.anchorX!!, 0f)
    }

    @Test fun clippedEdgeCardDoesNotPullTheDestinationRowSideways() {
        val destination = row(ShelfCardBounds(7, -70f, 180f), card(8, 280f), card(9, 480f))
        assertEquals(8, nearestVisibleShelfCard(20f, destination))
        assertEquals(-70f, destination.cards.first().left, 0f)
    }

    @Test fun aDestinationWithItsOwnScrollKeepsThatViewport() {
        val destination = row(card(8, 100f), card(9, 300f), card(10, 500f), card(11, 700f))
        assertEquals(9, nearestVisibleShelfCard(320f, destination))
    }

    @Test fun unmeasuredRowUsesItsPersistedScrollAndRegularCardGeometry() {
        val destination = estimateShelfRowBounds(20, 7, 60, 168f, 20f, 14f, 1_000f)
        assertEquals(11, nearestVisibleShelfCard(790f, destination))
        assertEquals(142f, destination.cards.first { it.index == 8 }.left, 0f)
        assertEquals(7, destination.cards.first { it.left < 0f && it.right > 0f }.index)
    }

    @Test fun missingSourceAnchorStillChoosesAVisibleDestinationInsteadOfIndexZero() {
        assertEquals(8, nearestVisibleShelfCard(null, row(card(8, 100f), card(9, 300f))))
        assertEquals(0, nearestVisibleShelfCard(null, ShelfRowBounds(0f, emptyList())))
    }

    @Test fun oversizedCardCanBeChosenWhenThereIsNoFullyVisibleAlternative() {
        assertEquals(12, nearestVisibleShelfCard(400f, row(ShelfCardBounds(12, -100f, 1_200f))))
    }

    @Test fun repeatedDownAdvancesBeforeTheFirstScrollHasFinished() {
        val cursor = ShelfNavigationCursor(listOf(14, 14, 14, 14))
        cursor.onFocused(0, 10)
        assertEquals(ShelfFocusPosition(1, 4), cursor.request(1, 860f) { _, _ -> 4 }!!.position)
        assertEquals(ShelfFocusPosition(2, 8), cursor.request(1, 100f) { _, anchor ->
            assertEquals(860f, anchor!!, 0f); 8
        }!!.position)
        assertEquals(ShelfFocusPosition(3, 2), cursor.request(1) { _, _ -> 2 }!!.position)
        assertEquals(ShelfFocusPosition(0, 10), cursor.focused)
    }

    @Test fun incomingFocusDoesNotDriftTheAnchorDuringAQuickSecondDown() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8))
        val first = cursor.request(1, 260f) { _, _ -> 0 }!!
        cursor.onFocused(1, 0) // This card's center is x=200, but the user's anchor was x=260.
        val next = row(card(0, 100f), card(1, 300f))
        assertEquals(1, cursor.request(1, 200f) { _, anchor -> nearestVisibleShelfCard(anchor, next) }!!.position.item)
        cursor.complete(first)
        assertEquals(2, cursor.requestedPosition.shelf)
    }

    @Test fun intermediateFocusAndCancelledCompletionCannotEraseTheLatestIntent() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8, 8))
        val first = cursor.request(1, 500f) { _, _ -> 2 }!!
        val second = cursor.request(1) { _, _ -> 3 }!!
        cursor.onFocused(1, 2)
        cursor.complete(first)
        assertEquals(second.position, cursor.requestedPosition)
        assertEquals(ShelfFocusPosition(3, 1), cursor.request(1) { _, _ -> 1 }!!.position)
    }

    @Test fun measuredRefinementCannotRewriteAReplacementRequest() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8))
        val first = cursor.request(1, 500f) { _, _ -> 2 }!!
        val next = cursor.request(1) { _, _ -> 3 }!!
        assertFalse(cursor.refine(first, 0))
        assertEquals(ShelfFocusPosition(2, 3), cursor.requestedPosition)
        assertTrue(cursor.refine(next, 4))
        assertEquals(ShelfFocusPosition(2, 4), cursor.requestedPosition)
    }

    @Test fun upReversesPendingRowAndUsesThatRowsVisibleGeometry() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8))
        cursor.request(1, 500f) { _, _ -> 2 }
        cursor.request(1) { _, _ -> 6 }
        assertEquals(ShelfFocusPosition(1, 1), cursor.request(-1) { _, anchor ->
            assertEquals(500f, anchor!!, 0f); 1
        }!!.position)
    }

    @Test fun emptyShelvesAreSkippedAndResolverCannotChooseOutsideTheCount() {
        val cursor = ShelfNavigationCursor(listOf(8, 0, 2, 8))
        assertEquals(ShelfFocusPosition(2, 1), cursor.request(1, 700f) { _, _ -> 6 }!!.position)
        assertEquals(ShelfFocusPosition(3, 4), cursor.request(1) { _, _ -> 4 }!!.position)
    }

    @Test fun horizontalOrPageNavigationCancelsTheOldAnchor() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8))
        val old = cursor.request(1, 700f)!!
        cursor.cancel()
        cursor.onFocused(0, 4)
        cursor.complete(old)
        cursor.request(1, 200f) { _, anchor -> assertEquals(200f, anchor!!, 0f); 1 }
    }

    @Test fun boundaryPressDoesNotCancelAStillValidScroll() {
        val cursor = ShelfNavigationCursor(listOf(8, 8))
        assertNull(cursor.request(-1))
        val last = cursor.request(1)!!
        assertNull(cursor.request(1))
        assertEquals(last.position, cursor.requestedPosition)
        cursor.onFocused(1, 0)
        cursor.complete(last)
        assertEquals(ShelfFocusPosition(1, 0), cursor.requestedPosition)
    }

    @Test fun anOlderRequestForTheSameDestinationCannotClearItsReplacement() {
        val cursor = ShelfNavigationCursor(listOf(8, 8, 8))
        val first = cursor.request(1)!!
        cursor.request(-1)
        val replacement = cursor.request(1)!!
        assertEquals(first.position, replacement.position)
        cursor.complete(first)
        assertSame(replacement.position, cursor.requestedPosition)
    }
}
