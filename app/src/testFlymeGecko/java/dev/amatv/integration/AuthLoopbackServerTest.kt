package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.Socket

class AuthLoopbackServerTest {
    @Test fun brandedOriginServesTheIconOnlyAtTheExactAttemptHostAndPath() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        AuthLoopbackServer(host = "amfolio.localhost", iconPng = png) { "<html></html>" }.use { server ->
            val origin = server.origin
            fun get(path: String, host: String = origin.authority): String =
                Socket("127.0.0.1", origin.port).use { socket ->
                    socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                }

            assertEquals("amfolio.localhost", URI(origin.url).host)
            assertTrue(get(origin.path).startsWith("HTTP/1.1 200 OK"))
            val response = get(origin.iconPath)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Type: image/png\r\n"))
            assertEquals(png.toString(Charsets.ISO_8859_1), response.substringAfter("\r\n\r\n"))
            assertTrue(get(origin.iconPath, "127.0.0.1:${origin.port}").startsWith("HTTP/1.1 404"))
            assertTrue(get("/auth/previous-attempt/icon.png").startsWith("HTTP/1.1 404"))
        }
    }

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
