package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class FocusFrameMotionTest {
    private fun FocusFrameMotion.step(x: Float, dt: Long = 16_000_000, visible: Boolean = true, follow: Boolean = false) =
        advance(dt, x, 80f, 210f, 256f, 14f, visible, follow)

    @Test fun retargetKeepsMomentumInsteadOfRestartingFromRest() {
        val a = FocusFrameMotion().apply { step(0f); repeat(6) { step(200f) } }
        val before = a.left
        a.step(400f)
        val movingStep = a.left - before
        val stopped = FocusFrameMotion().apply { step(before); step(400f) }
        assertTrue(movingStep > stopped.left - before)
        assertTrue(a.left > before && a.left < 400f)
    }

    @Test fun continuousSpringMatchesAcrossDisplayFrameRates() {
        val slow = FocusFrameMotion().apply { step(0f); repeat(20) { step(400f, 16_000_000) } }
        val fast = FocusFrameMotion().apply { step(0f); repeat(40) { step(400f, 8_000_000) } }
        assertEquals(slow.left, fast.left, 0.001f)
        assertEquals(slow.width, fast.width, 0.001f)
    }

    @Test fun oneFrameFocusHandoffDoesNotResetPositionOrBlink() {
        val motion = FocusFrameMotion().apply { step(200f) }
        motion.step(200f, visible = false)
        assertTrue(motion.opacity > 0.7f)
        motion.step(400f)
        assertTrue(motion.left > 200f && motion.left < 230f)
        assertTrue(motion.opacity > 0.7f)
    }

    @Test fun controlsFollowTheirExitDirectlyAndIdleClockCanStop() {
        val motion = FocusFrameMotion().apply { step(0f); step(200f, follow = true) }
        assertEquals(200f, motion.left, 0f)
        assertFalse(motion.isMoving)
        repeat(100) { motion.step(200f, visible = false) }
        assertEquals(0f, motion.opacity, 0f)
        assertFalse(motion.isMoving)
        motion.step(600f)
        assertEquals(600f, motion.left, 0f)
        assertEquals(1f, motion.opacity, 0f)
    }

    @Test fun scrollingSameCardDoesNotStartAnotherFocusAnimation() {
        val motion = FocusFrameMotion()
        motion.advance(0, 400f, 80f, 210f, 256f, 14f, true, key = "card")
        motion.advance(16_000_000, 310f, 60f, 210f, 256f, 14f, true, key = "card")
        assertEquals(310f, motion.left, 0f)
        assertEquals(60f, motion.top, 0f)
        assertFalse(motion.isMoving)
    }

    @Test fun viewportTransportPreservesTheFocusGlideAndItsMomentum() {
        val stationary = FocusFrameMotion()
        val scrolling = FocusFrameMotion()
        listOf(stationary, scrolling).forEach {
            it.advance(0, 0f, 80f, 210f, 256f, 14f, true, key = "a")
            it.advance(16_000_000, 400f, 80f, 210f, 256f, 14f, true, key = "b")
        }
        repeat(30) { frame ->
            val scroll = minOf(frame * 15f, 240f)
            stationary.advance(16_000_000, 400f, 80f, 210f, 256f, 14f, true, key = "b")
            scrolling.advance(16_000_000, 400f - scroll, 80f, 210f, 256f, 14f, true, key = "b")
            assertEquals(stationary.left - scroll, scrolling.left, 0.001f)
        }
    }

    @Test fun actualTargetResizeIsNotMistakenForAnotherFocusHandoff() {
        val motion = FocusFrameMotion()
        motion.advance(0, 100f, 80f, 210f, 256f, 14f, true, key = "card")
        motion.advance(16_000_000, 100f, 80f, 280f, 300f, 18f, true, key = "card")
        assertEquals(280f, motion.width, 0f)
        assertEquals(300f, motion.height, 0f)
        assertEquals(18f, motion.radius, 0f)
        assertFalse(motion.isMoving)
    }
}
