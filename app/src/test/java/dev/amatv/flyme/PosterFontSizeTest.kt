package dev.amatv.flyme

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterFontSizeTest {
    @Test fun shortLineGrowsToFillTheWidth() {
        // Six CJK characters across 300 dp: 300 * 0.92 / 6 = 46, above the 40 base.
        assertEquals(46f, posterFontSize("我從不曾絕望", 300f, 40f), 0.01f)
    }

    @Test fun veryShortLineStopsAtOnePointSixTimes() {
        assertEquals(64f, posterFontSize("沒有", 300f, 40f), 0.01f)
    }

    @Test fun longLineKeepsTheBaseSize() {
        assertEquals(40f, posterFontSize("輕輕拭去滿佈全身的傷痕與過往", 300f, 40f), 0.01f)
    }

    @Test fun theLongestRowDecides() {
        assertEquals(40f, posterFontSize("短\n這一行比較長一點點點點", 300f, 40f), 0.01f)
    }
}
