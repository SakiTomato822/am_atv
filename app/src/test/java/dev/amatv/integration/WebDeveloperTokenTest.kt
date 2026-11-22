package dev.amatv.integration

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDeveloperTokenTest {
    @Test
    fun findsMainBundleInDesktopHtml() {
        val html = """
            <link rel="modulepreload" href="/assets/index~preload.js">
            <script type="module" crossorigin
              src="/assets/index~B8RKPfmaCI.js"></script>
            <script src="/includes/musickit.js"></script>
        """.trimIndent()
        assertEquals("/assets/index~B8RKPfmaCI.js", WebDeveloperToken.bundlePath(html))
        assertEquals("/assets/index-Abc123.js", WebDeveloperToken.bundlePath("<script src='/assets/index-Abc123.js'>"))
        assertNull(WebDeveloperToken.bundlePath("<script src='https://other.example/assets/index-Abc123.js'>"))
    }

    @Test
    fun extractsUsableJwtFromJavascriptWithEitherHeaderOrder() {
        val expired = jwt("""{"alg":"ES256"}""", NOW)
        val unrelated = jwt("""{"alg":"HS256"}""")
        for (header in listOf("""{"alg":"ES256","typ":"JWT"}""", """{"typ":"JWT","alg":"ES256"}""")) {
            val candidate = jwt(header)
            val javascript = "const other = '$unrelated', old = '$expired', developer = '$candidate';"
            assertEquals(candidate, WebDeveloperToken.extractToken(javascript, NOW))
        }
        assertNull(WebDeveloperToken.extractToken("const old = '$expired';", NOW))
    }

    @Test
    fun readsExpiryAndTreatsTokenAsExpiredOneDayEarly() {
        val token = jwt("""{"alg":"ES256"}""")
        val exp = DeveloperTokenPolicy.expiresAt(token)
        assertEquals(EXP, exp)
        assertTrue(WebDeveloperToken.isUsable(exp, exp - 86_401))
        assertFalse(WebDeveloperToken.isUsable(exp, exp - 86_400))
        assertFalse(WebDeveloperToken.isUsable(exp, exp))
    }

    @Test
    fun tokenLogContainsOnlyMetadata() {
        val token = jwt("""{"alg":"ES256"}""")
        for (source in listOf("cache", "network")) {
            val log = WebDeveloperToken.tokenLog(token.length, DeveloperTokenPolicy.expiresAt(token), source)
            assertFalse(log.contains(token))
            assertEquals("channel=web-token source=$source length=${token.length} exp=$EXP", log)
        }
    }

    private fun jwt(header: String, exp: Long = EXP): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(header, """{"exp":$exp}""", "test-signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val NOW = 1_800_000_000L
        const val EXP = NOW + 172_800L
    }
}
