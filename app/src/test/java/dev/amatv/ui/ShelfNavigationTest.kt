package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfNavigationTest {
    @Test fun fullyVisibleShelfDoesNotMoveDuringHorizontalFocus() {
        assertEquals(0f, shelfRevealScroll(20, 640, 0, 900), 0f)
    }
    @Test fun partiallyVisibleShelfIsRevealedWithOneBoundedVerticalMove() {
        assertEquals(410f, shelfRevealScroll(700, 610, 0, 900), 0f)
        assertEquals(-120f, shelfRevealScroll(-120, 610, 0, 900), 0f)
    }
    @Test fun oversizedShelfAlignsItsTopRatherThanOscillatingBetweenEdges() {
        assertEquals(100f, shelfRevealScroll(100, 1100, 0, 900), 0f)
        assertEquals(0f, shelfRevealScroll(0, 1100, 0, 900), 0f)
    }
    @Test fun incomingShelfUsesOneDestinationThroughoutTheColumnScroll() {
        val plan = ShelfFocusScroll(1, 290f)
        for (consumed in 0..410 step 10) {
            val rowOffset = 700 - consumed
            assertEquals(290f, rowOffset - plan.remaining(1, rowOffset), 0f)
        }
        assertEquals(0f, plan.remaining(0, -410), 0f)
    }
}
