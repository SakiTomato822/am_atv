package dev.amatv.integration

import dev.amatv.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64

class DeveloperTokenClientTest {
    @Test fun bundledTokenWorksWithoutAnEndpointOrStoredAccountEvenDuringRefresh() = runBlocking {
        fun encode(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val value = "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":${Instant.now().epochSecond + 10_000}}""")}.test-signature"
        val client = DeveloperTokenClient(endpoint = "", bundledToken = value)
        assertEquals(value, client.token())
        assertEquals(value, client.token(forceRefresh = true))
    }

    @Test fun expiredStandaloneCredentialRequestsAnAppUpdateInsteadOfALanServer() = runBlocking {
        val client = DeveloperTokenClient(endpoint = "", bundledToken = "expired")
        val error = runCatching { client.token() }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("res:${R.string.services_credentials_expired_update}", error!!.message)
        assertFalse(error.message.orEmpty().contains("local.properties"))
    }
}
