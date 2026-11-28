package dev.amatv.flyme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioWelcomeInkTest {
    private val first = WelcomeInkPoint(0f, 0.125f, 0f, 0f)
    private val second = WelcomeInkPoint(1f, 0.25f, 1f, 160f)
    private val timeline = WelcomeInkTimeline(listOf(listOf(first, second), listOf(first, second)))

    @Test
    fun parsesLittleEndianStrokesInWritingOrder() {
        val fixture = ByteBuffer.allocate(2 + 2 + 32 + 2 + 16).order(ByteOrder.LITTLE_ENDIAN)
        fixture.putShort(2).putShort(2)
        listOf(first, second).forEach { fixture.putFloat(it.x).putFloat(it.y).putFloat(it.pressure).putFloat(it.ms) }
        fixture.putShort(1).putFloat(0.5f).putFloat(0.125f).putFloat(0.75f).putFloat(0f)

        assertEquals(
            listOf(listOf(first, second), listOf(WelcomeInkPoint(0.5f, 0.125f, 0.75f, 0f))),
            parseWelcomeInk(fixture.array()),
        )
    }

    @Test
    fun playsInOrderAtRecordedSpeedAndInterpolatesPositionAndPressure() {
        assertEquals(287.5f, timeline.writingMs, 0f)
        assertEquals(1987.5f, timeline.durationMs, 0f)
        assertEquals(
            WelcomeInkFrame(0, WelcomeInkTip(0, WelcomeInkPoint(0.5f, 0.1875f, 0.5f, 80f))),
            timeline.frameAt(50f),
        )
        assertEquals(WelcomeInkFrame(1), timeline.frameAt(100f))
        assertNull(timeline.frameAt(187f).tip)
        assertEquals(WelcomeInkFrame(1, WelcomeInkTip(0, first)), timeline.frameAt(187.5f))
        assertEquals(WelcomeInkFrame(2), timeline.frameAt(787.5f))
        assertEquals(WelcomeInkFrame(2, expansion = 0.5f), timeline.frameAt(1387.5f))
        assertEquals(0.55f, first.widthScale, 0f)
        assertEquals(1.25f, second.widthScale, 0f)
    }

    @Test
    fun skipProducesTheSameEndStateFromWritingHoldAndExpansion() {
        val end = timeline.frameAt(timeline.durationMs)
        assertTrue(end.finished)
        listOf(50f, 400f, 1200f).forEach { elapsed ->
            assertFalse(timeline.frameAt(elapsed).finished)
            assertEquals(end, timeline.frameAt(elapsed, skip = true))
        }
    }

    @Test
    fun requiresSeventyPercentOfVisibleTilesAndKeepsRefreshingUntilAllAreLoaded() {
        val tiles = List(10) { WelcomeWallTile() }
        val empty = WelcomeWallArtwork(0, emptySet())
        val belowThreshold = WelcomeWallArtwork(10, tiles.take(6).toSet())
        val atThreshold = WelcomeWallArtwork(10, tiles.take(7).toSet())
        val complete = WelcomeWallArtwork(10, tiles.toSet())

        assertFalse(empty.readyToStart)
        assertFalse(empty.allLoaded)
        assertFalse(belowThreshold.readyToStart)
        assertTrue(atThreshold.readyToStart)
        assertFalse(atThreshold.allLoaded)
        assertTrue(complete.allLoaded)
    }
}
