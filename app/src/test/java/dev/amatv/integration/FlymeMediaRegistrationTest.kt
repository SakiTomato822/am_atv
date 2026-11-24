package dev.amatv.integration

import dev.amatv.integration.FlymeMediaSource.RegistrationPath
import dev.amatv.integration.FlymeMediaSource.V3State
import dev.amatv.integration.FlymeMediaSource.Companion.voiceRegistrationPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlymeMediaRegistrationTest {
    @Test fun reusesSuccessfulV3Registration() {
        assertEquals(RegistrationPath.V3, voiceRegistrationPath(true, V3State.Registered))
    }

    @Test fun fallsBackToV4WhenV3ReturnsNoBinderAndVoiceIsEnabled() {
        assertEquals(RegistrationPath.V4, voiceRegistrationPath(true, V3State.NoBinder))
    }

    @Test fun selectsNoVoiceRegistrationWhenVoiceIsDisabled() {
        for (state in V3State.entries) assertNull(voiceRegistrationPath(false, state))
    }

    @Test fun waitsForV3AndDoesNotFallbackAfterProtocolOrRegistrationFailure() {
        assertNull(voiceRegistrationPath(true, V3State.Pending))
        assertNull(voiceRegistrationPath(true, V3State.Failed))
    }
}
