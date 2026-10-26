package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogRetryTest {
    @Test fun metadataTransportFailureRetriesWithoutChangingItsResolvedValue() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = retryCatalogRequest(fetch = {
            if (++attempts < 3) throw CatalogRequestException(CatalogFailureReason.Transient, "busy", 503)
            "6790045614"
        }, wait = { waits += it })
        assertEquals("6790045614", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 5_000L), waits)
    }

    @Test fun unavailableAuthorizationAndCancellationDoNotWait() = runBlocking {
        for (error in listOf(
            CatalogRequestException(CatalogFailureReason.Unavailable, "unavailable"),
            CatalogRequestException(CatalogFailureReason.Authorization, "sign in"),
            CancellationException("changed song"),
        )) {
            var attempts = 0
            try {
                retryCatalogRequest<String>({ attempts++; throw error }, { fail("Must not retry $error") })
                fail("Failure must propagate")
            } catch (actual: Exception) { assertSame(error, actual) }
            assertEquals(1, attempts)
        }
    }
}
