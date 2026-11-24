package dev.amatv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniWindowCaptionClickTest {
    private val fullButtonId = 101

    @Test
    fun interceptsFullScreenButtonInCurrentSmallWindow() {
        assertTrue(shouldInterceptCaptionClick(fullButtonId, fullButtonId, isCurrentSmallWindow = true))
    }

    @Test
    fun leavesClosePinAndOtherActionsToFlyme() {
        assertFalse(shouldInterceptCaptionClick(102, fullButtonId, isCurrentSmallWindow = true))
        assertFalse(shouldInterceptCaptionClick(103, fullButtonId, isCurrentSmallWindow = true))
        assertFalse(shouldInterceptCaptionClick(104, fullButtonId, isCurrentSmallWindow = true))
    }

    @Test
    fun leavesFullScreenActionFromOtherWindowsToFlyme() {
        assertFalse(shouldInterceptCaptionClick(fullButtonId, fullButtonId, isCurrentSmallWindow = false))
    }
}
