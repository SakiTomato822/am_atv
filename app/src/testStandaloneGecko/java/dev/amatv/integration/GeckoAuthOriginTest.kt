package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeckoAuthOriginTest {
    private val origin = GeckoAuthOrigin(43210, "one-use-random-nonce")

    @Test fun tokenBridgeAcceptsOnlyTheExactAttemptOriginAndPath() {
        assertTrue(origin.ownsUrl(origin.url))
        assertFalse(origin.ownsUrl("http://127.0.0.1:43211${origin.path}"))
        assertFalse(origin.ownsUrl("http://127.0.0.1:43210/auth/old-attempt/"))
        assertFalse(origin.ownsUrl("http://127.0.0.1.evil.example:43210${origin.path}"))
        assertFalse(origin.ownsUrl("http://attacker@127.0.0.1:43210${origin.path}"))
        assertFalse(origin.ownsUrl(origin.url + "?token=untrusted"))
        assertFalse(origin.ownsUrl(origin.url + "#untrusted"))
        assertTrue(origin.ownsUrl(origin.url + "#callback", allowFragment = true))
    }

    @Test fun accountNavigationRequiresHttpsAndAnExactAppleDomain() {
        assertTrue(isGeckoAppleAuthUrl("https://idmsa.apple.com/appleauth/auth/authorize"))
        assertTrue(isGeckoAppleAuthUrl("https://appleid.apple.com.cn/"))
        assertTrue(isGeckoAppleAuthUrl("https://authorize.music.apple.com/woa"))
        assertFalse(isGeckoAppleAuthUrl("http://idmsa.apple.com/"))
        assertFalse(isGeckoAppleAuthUrl("https://apple.com.example.org/"))
        assertFalse(isGeckoAppleAuthUrl("https://notapple.com/"))
        assertFalse(isGeckoAppleAuthUrl("https://user@apple.com/"))
        assertFalse(isGeckoAppleAuthUrl("https://apple.com:444/"))
    }

    @Test fun callbackEnvelopeIsDecodedWithoutTreatingItsWrapperAsTheUserToken() {
        val token = "valid-music-user-token-for-the-new-account"
        val envelope = Base64.getEncoder().encodeToString("{\"musicUserToken\":\"$token\"}".toByteArray())
        assertEquals(token, geckoCallbackToken(origin.url + "#musicUserToken=" + envelope))
        assertEquals(token, geckoCallbackToken(origin.url + "#music-user-token=" + token))
        assertFalse(validGeckoMusicUserToken("token containing spaces is not accepted"))
        assertFalse(validGeckoMusicUserToken("short"))
        assertFalse(validGeckoMusicUserToken("x".repeat(16_385)))
    }
}
