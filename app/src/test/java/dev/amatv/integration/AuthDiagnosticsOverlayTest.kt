package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class AuthDiagnosticsOverlayTest {
    @Test fun removesAuthCredentialsFromConsoleMessagesAndStructuredValues() {
        val secrets = listOf("plain-password", "one time value", "opaque-token", "email-secret@example.com",
            "short-scnt", "oauth-secret", "session-secret", "account-secret", "snake-account-secret")
        val input = """
            password="plain-password"
            verificationCode='one time value'
            musicUserToken=opaque-token
            email-secret@example.com
            {"scnt":"short-scnt","oauth_token":"oauth-secret","sessionId":"session-secret",
             "accountName":"account-secret","account_name":"snake-account-secret"}
            HTTP 503 while hydrating form
        """.trimIndent()
        val safe = AuthDiagnosticsSanitizer.sanitize(input)
        for (secret in secrets) assertFalse("Leaked $secret", safe.contains(secret))
        assertTrue(safe.contains("HTTP 503"))
        assertTrue(safe.contains("[redacted]"))
    }

    @Test fun stripsUrlQueriesFragmentsAndUserInfoButPreservesResourcePaths() {
        val safe = AuthDiagnosticsSanitizer.sanitize("""
            https://idmsa.apple.com/appleauth/auth?code=oauth-code&state=state-secret#fragment-secret
            https://private-user:private-password@example.com/script.js?cache=private-cache
            /appleauth/auth?code=relative-secret#private-fragment
        """.trimIndent())
        for (secret in listOf("oauth-code", "state-secret", "fragment-secret", "private-user",
                "private-password", "private-cache", "relative-secret", "private-fragment")) {
            assertFalse("Leaked $secret", safe.contains(secret))
        }
        assertTrue(safe.contains("https://idmsa.apple.com/appleauth/auth"))
        assertTrue(safe.contains("example.com/script.js"))
        assertFalse(safe.contains('?'))
        assertFalse(safe.contains('#'))
    }

    @Test fun removesJwtBearerBasicHeadersAndLongOpaqueCredentials() {
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJwcml2YXRlLXVzZXIifQ.privateSignature123"
        val safe = AuthDiagnosticsSanitizer.sanitize("""
            jwt=$jwt
            Bearer short-bearer-secret
            Basic dXNlcjpwYXNzd29yZA==
            Authorization: Digest username="private-account", response="private-response"
            Cookie: session=private-cookie; other=private-other-cookie
            SCNT: private-header-scnt
            X-Apple-ID-Session-Id: private-header-session
            opaque ${"A".repeat(120)}
            resource returned HTTP 404
        """.trimIndent())
        for (secret in listOf(jwt, "short-bearer-secret", "dXNlcjpwYXNzd29yZA==", "private-account",
                "private-response", "private-cookie", "private-other-cookie", "private-header-scnt",
                "private-header-session", "A".repeat(80))) {
            assertFalse("Leaked credential", safe.contains(secret))
        }
        assertTrue(safe.contains("HTTP 404"))
    }

    @Test fun boundedHistoryKeepsTheNewestMessages() {
        val buffer = AuthDiagnosticsBuffer()
        for (index in 0 until 40) buffer.append("[INFO] event-$index")
        assertEquals(24, buffer.size())
        val lines = buffer.snapshot().lines()
        assertEquals("[INFO] event-16", lines.first())
        assertEquals("[INFO] event-39", lines.last())
    }

    @Test fun longMessagesCannotExceedTheTextBudgetOrRetainSensitiveValues() {
        val buffer = AuthDiagnosticsBuffer(maxMessages = 24, maxChars = 500)
        repeat(50) { buffer.append("$it " + "failure ".repeat(150) + " password=private-password") }
        assertTrue(buffer.snapshot().length <= 500)
        assertTrue(buffer.size() in 1..24)
        assertFalse(buffer.snapshot().contains("private-password"))
        assertTrue(AuthDiagnosticsSanitizer.sanitize("a ".repeat(10_000)).length <= 700)
        assertEquals("…", AuthDiagnosticsSanitizer.sanitize("long", maxChars = 1))
    }
}
