package dev.amatv.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLaunchGateTest {
    @Test fun repeatedRemoteClicksCannotLaunchAnotherActivityWhilePreparingOrSigningIn() {
        val gate = AuthLaunchGate()
        assertTrue(gate.tryBegin())
        repeat(20) { assertFalse(gate.tryBegin()) }
        // Keep the same gate after token preparation, while waiting for ActivityResult.
        assertFalse(gate.tryBegin())
        gate.finish()
        assertTrue(gate.tryBegin())
    }

    @Test fun failureOrCancellationReleasesTheNextExplicitAttemptWithoutATimer() {
        val gate = AuthLaunchGate()
        assertTrue(gate.tryBegin())
        gate.finish()
        assertTrue(gate.tryBegin())
        gate.finish()
        gate.finish() // Cleanup is safe when the request never reached ActivityResult.
        assertTrue(gate.tryBegin())
    }
}
