package dev.amatv.integration.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSkipDeduplicatorTest {
    @Test fun onlyConsumesOneMatchingSkipFromThisListeningTurn() {
        val gate = VoiceSkipDeduplicator()
        gate.record(1, 150)
        assertFalse(gate.consume(1, null))
        assertFalse(gate.consume(1, 151))
        assertFalse(gate.consume(-1, 100))
        assertTrue(gate.consume(1, 100))
        assertFalse(gate.consume(1, 100))
    }
}
