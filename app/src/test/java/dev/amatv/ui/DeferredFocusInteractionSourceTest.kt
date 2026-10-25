package dev.amatv.ui

import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeferredFocusInteractionSourceTest {
    private class RecordingSource : MutableInteractionSource {
        override val interactions = emptyFlow<Interaction>()
        val forwarded = mutableListOf<Interaction>()
        var accepts = true
        override suspend fun emit(interaction: Interaction) { forwarded += interaction }
        override fun tryEmit(interaction: Interaction): Boolean {
            if (!accepts) return false
            forwarded += interaction
            return true
        }
    }

    @Test fun pairedPressesRemainPressedUntilEveryReleaseOrCancel() {
        val source = DeferredFocusInteractionSource(RecordingSource())
        val first = PressInteraction.Press(Offset.Zero)
        val second = PressInteraction.Press(Offset.Zero)
        source.tryEmit(first)
        source.tryEmit(second)
        source.tryEmit(PressInteraction.Release(first))
        assertTrue(source.pressed)
        source.tryEmit(PressInteraction.Cancel(second))
        assertFalse(source.pressed)
        assertTrue(source.activated)
    }

    @Test fun anUnpairedReleaseCannotClearAnotherLivePress() {
        val source = DeferredFocusInteractionSource(RecordingSource())
        val live = PressInteraction.Press(Offset.Zero)
        source.tryEmit(live)
        source.tryEmit(PressInteraction.Cancel(PressInteraction.Press(Offset.Zero)))
        assertTrue(source.pressed)
        source.tryEmit(PressInteraction.Release(live))
        assertFalse(source.pressed)
    }

    @Test fun pairedHoversRemainHoveredUntilEveryExit() {
        val source = DeferredFocusInteractionSource(RecordingSource())
        val first = HoverInteraction.Enter()
        val second = HoverInteraction.Enter()
        source.tryEmit(first)
        source.tryEmit(second)
        source.tryEmit(HoverInteraction.Exit(first))
        assertTrue(source.hovered)
        source.tryEmit(HoverInteraction.Exit(second))
        assertFalse(source.hovered)
    }

    @Test fun suspendAndImmediateEventsAreForwardedWithoutReplacement() = runBlocking {
        val delegate = RecordingSource()
        val source = DeferredFocusInteractionSource(delegate)
        val press = PressInteraction.Press(Offset.Zero)
        val release = PressInteraction.Release(press)
        source.emit(press)
        assertTrue(source.pressed)
        assertTrue(source.tryEmit(release))
        assertFalse(source.pressed)
        assertEquals(listOf(press, release), delegate.forwarded)
        assertSame(delegate.interactions, source.interactions)
    }

    @Test fun rejectedImmediateEventDoesNotAlterLocalVisualState() {
        val delegate = RecordingSource().also { it.accepts = false }
        val source = DeferredFocusInteractionSource(delegate)
        assertFalse(source.tryEmit(PressInteraction.Press(Offset.Zero)))
        assertFalse(source.pressed)
        assertFalse(source.activated)
        assertTrue(delegate.forwarded.isEmpty())
    }

    @Test fun recycledLazySlotResetsInputAndStartsANewRestAnimationEpoch() {
        val source = DeferredFocusInteractionSource(RecordingSource())
        source.activate() // Native D-pad focus starts visuals without a press.
        val epoch = source.activationEpoch
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        source.tryEmit(HoverInteraction.Enter())
        source.reset()
        assertFalse(source.activated)
        assertFalse(source.pressed)
        assertFalse(source.hovered)
        source.activate()
        assertTrue(source.activationEpoch > epoch)
    }
}
