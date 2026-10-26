package dev.amatv.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitWebAuthActivityTest {
    @Test fun acceptsOnlyAppleAndICloudAuthorizationHosts() {
        assertTrue(isAppleAuthorizationHost("idmsa.apple.com"))
        assertTrue(isAppleAuthorizationHost("apple.com"))
        assertTrue(isAppleAuthorizationHost("setup.icloud.com"))

        assertFalse(isAppleAuthorizationHost(null))
        assertFalse(isAppleAuthorizationHost("evilapple.com"))
        assertFalse(isAppleAuthorizationHost("apple.com.example.org"))
        assertFalse(isAppleAuthorizationHost("cdn-apple.com"))
    }
}
