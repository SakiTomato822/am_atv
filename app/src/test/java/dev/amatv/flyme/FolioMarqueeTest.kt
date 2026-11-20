package dev.amatv.flyme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioMarqueeTest {
    @Test
    fun `marqueeOverflows returns false when content equals container`() {
        assertFalse(marqueeOverflows(contentPx = 120, containerPx = 120, tolerancePx = 2))
        assertFalse(marqueeOverflows(contentPx = 120, containerPx = 120, tolerancePx = 0))
    }

    @Test
    fun `marqueeOverflows returns false when content exceeds container by only 1px`() {
        assertFalse(marqueeOverflows(contentPx = 121, containerPx = 120, tolerancePx = 2))
        assertFalse(marqueeOverflows(contentPx = 121, containerPx = 120, tolerancePx = 1))
        assertFalse(marqueeOverflows(contentPx = 122, containerPx = 120, tolerancePx = 2))
    }

    @Test
    fun `marqueeOverflows returns true only when exceeding container beyond 1dp tolerance`() {
        assertTrue(marqueeOverflows(contentPx = 122, containerPx = 120, tolerancePx = 1))
        assertTrue(marqueeOverflows(contentPx = 123, containerPx = 120, tolerancePx = 2))
        assertFalse(marqueeOverflows(contentPx = 200, containerPx = 0, tolerancePx = 2))
    }
}
