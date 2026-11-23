package dev.amatv.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLaunchGateTest {
    @Test fun repeatedRemoteClicksCannotLaunchAnotherActivityWhilePreparingOrSigningIn() {
        val gate = AuthLaunchGate()
        val attempt = checkNotNull(gate.tryBegin())
        repeat(20) { assertNull(gate.tryBegin()) }
        assertTrue(gate.isCurrent(attempt))
        gate.finish(attempt) { Unit }
        assertFalse(gate.isCurrent(attempt))
        checkNotNull(gate.tryBegin())
    }

    @Test fun failureOrCancellationReleasesTheNextExplicitAttemptWithoutATimer() {
        val gate = AuthLaunchGate()
        val first = checkNotNull(gate.tryBegin())
        gate.finish(first) { Unit }
        val second = checkNotNull(gate.tryBegin())
        assertNull(gate.finish(first) { error("Stale attempt ran") })
        assertTrue(gate.isCurrent(second))
        gate.finish(second) { Unit }
        checkNotNull(gate.tryBegin())
    }
}
