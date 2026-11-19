package dev.amatv.flyme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRollGeometryTest {
    private val panelWidth = 400f
    private val panelHeight = 500f
    private val gap = 14f

    private data class Part(val width: Float, val height: Float, val pose: PosterInkPose)

    private fun row(vararg parts: Part): PosterRollBounds =
        PosterRollBounds(parts.first().pose.top, parts.last().let { it.pose.top + it.height * it.pose.scale })

    @Test
    fun changingRolesKeepBothLayoutsInsideDisjointRowsEveryFivePercent() {
        val previous = Part(926f, 90f, PosterInkPose(34f, 0.42f, 0.68f))
        val oldFocus = listOf(
            Part(371f, 26f, PosterInkPose(92f, 1f, 1f)), // Pronunciation
            Part(399f, 155f, PosterInkPose(122f, 1f, 1f)),
            Part(353f, 32f, PosterInkPose(281f, 1f, 1f)), // Translation
            Part(350f, 40f, PosterInkPose(317f, 1f, 1f)), // Overlapping vocal
        )
        val newPrevious = Part(952f, 60f, PosterInkPose(76f, 0.42f, 0.68f))
        val oldPreview = Part(869f, 110f, PosterInkPose(385f, 0.46f, 0.5f))
        val newFocus = listOf(
            Part(399f, 210f, PosterInkPose(121.2f, 1f, 1f)),
            Part(338f, 31f, PosterInkPose(335.2f, 1f, 1f)),
            Part(373f, 56f, PosterInkPose(370.2f, 1f, 1f)),
        )
        val oldNext = Part(860f, 70f, PosterInkPose(449.6f, 0.46f, 0.32f))
        val newNext = oldNext.copy(pose = PosterInkPose(454.2f, 0.46f, 0.5f))
        val entrant = Part(865f, 50f, PosterInkPose(500.4f, 0.46f, 0.32f))
        val start = mapOf(0 to listOf(previous), 1 to oldFocus, 2 to listOf(oldPreview), 3 to listOf(oldNext))
        val end = mapOf(1 to listOf(newPrevious), 2 to newFocus, 3 to listOf(newNext), 4 to listOf(entrant))

        assertRoll(start, end)
        assertRoll(end, start)
        // The interlude is an interior row when it appears between two existing lyric rows.
        assertRoll(
            mapOf(0 to listOf(previous), 2 to listOf(oldPreview)),
            mapOf(0 to listOf(previous), 1 to listOf(Part(90f, 64f, PosterInkPose(100f, 1.65f, 1f))), 2 to listOf(oldPreview)),
        )
    }

    private fun assertRoll(start: Map<Int, List<Part>>, end: Map<Int, List<Part>>) {
        val orders = (start.keys + end.keys).sorted()
        val visibleStart = start.mapValues { (_, parts) -> row(*parts.toTypedArray()) }
        val visibleEnd = end.mapValues { (_, parts) -> row(*parts.toTypedArray()) }
        val starts = posterRollBounds(orders, visibleStart, visibleEnd, panelHeight, gap)
        val ends = posterRollBounds(orders, visibleEnd, visibleStart, panelHeight, gap)
        val last = orders.last()
        if (last !in start) assertTrue(starts.getValue(last).top >= panelHeight)
        for (step in 0..20) {
            val time = step / 20f
            val movement = posterRollMovement(time)
            var previousBottom = Float.NEGATIVE_INFINITY
            for (order in orders) {
                val a = starts.getValue(order)
                val b = ends.getValue(order)
                val bounds = a.interpolate(b, movement)
                assertTrue("Row $order overlaps at $time", bounds.top >= previousBottom - 0.001f)
                previousBottom = bounds.bottom
                for (part in start[order].orEmpty()) {
                    val hidden = part.pose.hiddenIn(a, b, part.width, panelWidth)
                    assertInside(part, PosterInkTrack(part.pose, hidden).poseAt(time, movement), bounds, time)
                }
                for (part in end[order].orEmpty()) {
                    val hidden = part.pose.hiddenIn(b, a, part.width, panelWidth)
                    assertInside(part, PosterInkTrack(hidden, part.pose).poseAt(time, movement), bounds, time)
                }
            }
        }
    }

    private fun assertInside(part: Part, pose: PosterInkPose, bounds: PosterRollBounds, time: Float) {
        assertTrue("Width at $time", part.width * pose.scale <= panelWidth + 0.001f)
        assertTrue("Top at $time", pose.top >= bounds.top - 0.001f)
        assertTrue("Bottom at $time", pose.top + part.height * pose.scale <= bounds.bottom + 0.001f)
    }

    @Test
    fun otherRoleFadesStillOverlapBetweenTwentyFiveAndFortyFivePercent() {
        val shown = PosterInkPose(0f, 1f, 0.8f)
        val hidden = shown.copy(opacity = 0f)
        val appearing = PosterInkTrack(hidden, shown)
        val disappearing = PosterInkTrack(shown, hidden)
        assertEquals(0f, appearing.poseAt(0.25f, 0.8f).opacity, 0f)
        assertTrue(appearing.poseAt(0.35f, 0.9f).opacity > 0f)
        assertTrue(disappearing.poseAt(0.35f, 0.9f).opacity > 0f)
        assertEquals(0f, disappearing.poseAt(0.45f, 0.95f).opacity, 0f)
        assertEquals(0.8f, appearing.poseAt(1f, 1f).opacity, 0f)
    }

    @Test
    fun focusRegionKeepsBrightInkEveryFivePercentDuringHandoff() {
        assertFocusHandoff(
            oldLead = Part(290f, 36f, PosterInkPose(202f, 1f, 1f)),
            newLead = Part(396f, 40f, PosterInkPose(200f, 1f, 1f)),
            oldSlot = PosterRollBounds(202f, 238f),
            previousSlot = PosterRollBounds(164f, 180f),
            previewSlot = PosterRollBounds(266f, 286f),
            newSlot = PosterRollBounds(200f, 240f),
        )
        assertFocusHandoff(
            oldLead = Part(399f, 155f, PosterInkPose(122f, 1f, 1f)),
            newLead = Part(399f, 210f, PosterInkPose(94.5f, 1f, 1f)),
            oldSlot = PosterRollBounds(92f, 357f), // Pronunciation, translation and backing vocals
            previousSlot = PosterRollBounds(49.3f, 74.5f),
            previewSlot = PosterRollBounds(385f, 435.6f),
            newSlot = PosterRollBounds(94.5f, 364.5f),
        )
    }

    private fun assertFocusHandoff(
        oldLead: Part,
        newLead: Part,
        oldSlot: PosterRollBounds,
        previousSlot: PosterRollBounds,
        previewSlot: PosterRollBounds,
        newSlot: PosterRollBounds,
    ) {
        // The focus area is the envelope of the two settled main lyric rectangles.
        val focusRegion = PosterRollBounds(
            minOf(oldLead.pose.top, newLead.pose.top),
            maxOf(row(oldLead).bottom, row(newLead).bottom),
        )
        val outgoing = PosterInkTrack(
            oldLead.pose,
            oldLead.pose.hiddenIn(oldSlot, previousSlot, oldLead.width, panelWidth),
            PosterInkFade.OutgoingFocus,
        )
        val incoming = PosterInkTrack(
            newLead.pose.hiddenIn(newSlot, previewSlot, newLead.width, panelWidth),
            newLead.pose,
            PosterInkFade.IncomingFocus,
        )

        fun bounds(part: Part, pose: PosterInkPose) = PosterRollBounds(pose.top, pose.top + part.height * pose.scale)
        fun inFocus(ink: PosterRollBounds) = ink.top < focusRegion.bottom && ink.bottom > focusRegion.top
        fun focusOpacity(pose: PosterInkPose, ink: PosterRollBounds): Float =
            if (inFocus(ink)) pose.opacity * posterEdgeOpacity((ink.top + ink.bottom) / 2f, panelHeight) else 0f

        for (step in 0..20) {
            val time = step / 20f
            val movement = posterRollMovement(time)
            val old = outgoing.poseAt(time, movement)
            val new = incoming.poseAt(time, movement)
            val oldInk = bounds(oldLead, old)
            val newInk = bounds(newLead, new)
            assertTrue("Focus layouts overlap at $time", oldInk.bottom < newInk.top)
            assertTrue("Focus goes dim at $time", maxOf(focusOpacity(old, oldInk), focusOpacity(new, newInk)) >= 0.7f)
            if (step == 7) {
                assertTrue("Old focus still in the focus region at 35%", !inFocus(oldInk))
                assertTrue("New focus alpha at 35%", focusOpacity(new, newInk) >= 0.75f)
            }
            if (step == 10) assertTrue("New focus scale at 50%", new.scale >= 0.9f * newLead.pose.scale)
        }
    }

    @Test
    fun centerFadesSymmetricallyThroughTheOuterTwelvePercent() {
        for ((center, opacity) in listOf(-10f to 0f, 0f to 0f, 30f to 0.5f, 60f to 1f, 250f to 1f)) {
            assertEquals(opacity, posterEdgeOpacity(center, panelHeight), 0.001f)
            assertEquals(opacity, posterEdgeOpacity(panelHeight - center, panelHeight), 0.001f)
        }
    }
}
