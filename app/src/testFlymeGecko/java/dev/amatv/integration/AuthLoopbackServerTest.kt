package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class AuthLoopbackServerTest {
    @Test fun onlyTheRandomLocalBootstrapPathIsServedAndItCannotBeCached() {
        AuthLoopbackServer { "<html>one-attempt-only</html>" }.use { server ->
            assertEquals("127.0.0.1", URI(server.origin.url).host)
            assertTrue(server.origin.nonce.length >= 32)
            val good = URI(server.origin.url).toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(200, good.responseCode)
                assertEquals("no-store", good.getHeaderField("Cache-Control"))
                assertEquals("strict-origin-when-cross-origin", good.getHeaderField("Referrer-Policy"))
                assertEquals("<html>one-attempt-only</html>", good.inputStream.bufferedReader().use { it.readText() })
            } finally { good.disconnect() }
            val bad = URI("http://${server.origin.authority}/auth/previous-attempt/").toURL()
                .openConnection() as HttpURLConnection
            try {
                assertEquals(404, bad.responseCode)
                assertFalse(bad.errorStream.bufferedReader().use { it.readText() }.contains("one-attempt-only"))
            } finally { bad.disconnect() }
        }
    }
}
