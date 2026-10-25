package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DeveloperTokenPolicyTest {
    private fun token(exp: Long, alg: String = "ES256"): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${encode("""{"alg":"$alg","kid":"test"}""")}.${encode("""{"exp":$exp}""")}.signature"
    }

    @Test fun standaloneConfigurationDoesNotNeedAnEndpointOrUserSession() {
        assertTrue(DeveloperTokenPolicy.isConfigured(token(10_000), ""))
        assertTrue(DeveloperTokenPolicy.isUsable(token(10_000), nowSeconds = 1_000))
        assertFalse(DeveloperTokenPolicy.isConfigured("", ""))
    }

    @Test fun expiredOrMalformedCredentialsCannotMasqueradeAsAWorkingStandaloneToken() {
        assertFalse(DeveloperTokenPolicy.isUsable(token(1_300), nowSeconds = 1_000))
        assertFalse(DeveloperTokenPolicy.isUsable(token(100_000, "none"), nowSeconds = 1_000))
        assertFalse(DeveloperTokenPolicy.isUsable("not.a.jwt", nowSeconds = 1_000))
        assertFalse(DeveloperTokenPolicy.isUsable("", nowSeconds = 1_000))
    }
}
