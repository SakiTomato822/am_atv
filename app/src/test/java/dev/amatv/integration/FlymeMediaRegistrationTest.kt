package dev.amatv.integration

import dev.amatv.integration.FlymeMediaSource.RegistrationPath
import dev.amatv.integration.FlymeMediaSource.Companion.registrationPath
import org.junit.Assert.assertEquals
import org.junit.Test

class FlymeMediaRegistrationTest {
    @Test fun reusesSuccessfulV3Registration() {
        assertEquals(RegistrationPath.V3, registrationPath(v3HasBinder = true))
    }

    @Test fun fallsBackToV4WhenV3ReturnsNoBinder() {
        assertEquals(RegistrationPath.V4, registrationPath(v3HasBinder = false))
    }
}
