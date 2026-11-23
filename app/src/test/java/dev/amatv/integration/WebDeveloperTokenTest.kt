package dev.amatv.integration

import java.net.URI
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
    fun followsRedirectsAndResolvesBundleAgainstFinalPage() {
        val start = URI("$APPLE_MUSIC_WEB_ORIGIN/tw/new")
        val next = URI("$APPLE_MUSIC_WEB_ORIGIN/tw/redirect?region=tw")
        val finalPage = URI("https://beta.music.apple.com/tw/new?region=tw")
        val requests = mutableListOf<URI>()
        val logs = mutableListOf<String>()
        val bundle = WebDeveloperToken.findBundle("tw") { uri ->
            downloadWebTokenResource(uri, "page", request = { requested ->
                requests += requested
                when (requested) {
                    start -> WebBrowseResponse(302, "", "redirect?region=tw")
                    next -> WebBrowseResponse(302, "", "//beta.music.apple.com/tw/new?region=tw")
                    finalPage -> WebBrowseResponse(200, "<script src='/assets/index-Abc123.js'>")
                    else -> error("Unexpected request: $requested")
                }
            }, log = { logs += it })
        }

        assertEquals(URI("https://beta.music.apple.com/assets/index-Abc123.js"), bundle)
        assertEquals(listOf(start, next, finalPage), requests)
        assertEquals(3, logs.size)
        assertTrue(logs[0].contains("host=music.apple.com path=/tw/new"))
        assertTrue(logs[1].contains("host=music.apple.com path=/tw/redirect"))
        assertTrue(logs[2].contains("host=beta.music.apple.com path=/tw/new"))
        assertTrue(logs.none { '?' in it || "region=" in it })
    }

    @Test
    fun triesUsThenRootWhenRedirectedPageHasNoBundle() {
        val visited = mutableListOf<URI>()
        val bundle = WebDeveloperToken.findBundle("tw") { uri ->
            downloadWebTokenResource(uri, "page", request = { requested ->
                visited += requested
                when (requested.path) {
                    "/tw/new" -> WebBrowseResponse(302, "", "/tw/landing")
                    "/tw/landing", "/us/new" -> WebBrowseResponse(200, "<html></html>")
                    "/" -> WebBrowseResponse(200, "<script src='/assets/index-Root.js'>")
                    else -> error("Unexpected request: $requested")
                }
            }, log = {})
        }

        assertEquals(listOf("/tw/new", "/tw/landing", "/us/new", "/"), visited.map(URI::getPath))
        assertEquals(URI("$APPLE_MUSIC_WEB_ORIGIN/assets/index-Root.js"), bundle)
    }

    @Test
    fun followsAtMostThreeRedirects() {
        val start = URI("$APPLE_MUSIC_WEB_ORIGIN/0")
        for (lastHop in listOf(3, 4)) {
            val visited = mutableListOf<Int>()
            val page = downloadWebTokenResource(start, "page", request = { uri ->
                val hop = uri.path.substring(1).toInt()
                visited += hop
                if (hop == lastHop) WebBrowseResponse(200, "ok")
                else WebBrowseResponse(302, "", "/${hop + 1}")
            }, log = {})

            assertEquals(listOf(0, 1, 2, 3), visited)
            if (lastHop == 3) assertEquals(URI("$APPLE_MUSIC_WEB_ORIGIN/3") to "ok", page)
            else assertNull(page)
        }
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
