package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRecorderRedactTest {
    @Test
    fun masksTokensButKeepsOrdinaryLines() {
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJBQkNERUZHSElKIn0.c2lnbmF0dXJlc2lnbmF0dXJl"
        assertFalse(LogRecorder.redact("Authorization: Bearer $jwt").contains("eyJhbGci"))
        val userToken = "A".repeat(40) + "b".repeat(60)
        assertFalse(LogRecorder.redact("Music-User-Token=$userToken").contains(userToken))
        assertFalse(LogRecorder.redact("media token: \"abcdefghijklmnop\"").contains("abcdefghijklmnop"))
        val ordinary = "11-03 20:45:14.123  3861  3900 I AMATV-MusicKit: Gapless advance generation=1 catalog=1534006011"
        assertEquals(ordinary, LogRecorder.redact(ordinary))
    }
}
