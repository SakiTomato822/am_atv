package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricsRequestTest {
    private val lines = listOf(LyricLine(listOf(LyricWord("hello", 0L, 1_000L))))

    @Test fun temporaryTransportFailureCanRecoverWithoutCachingNoLyrics() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = requestLyricsWithRetry(
            fetch = { if (++calls < 3) throw java.io.IOException("offline") else lines },
            wait = { waits += it },
        )
        assertEquals(3, calls)
        assertEquals(listOf(2_000L, 5_000L), waits)
        assertEquals(LyricsRequestResult.Available(lines), result)
    }

    @Test fun exhaustedNetworkRetriesRemainRetryableForLaterReadinessOrRefresh() = runBlocking {
        var calls = 0
        val result = requestLyricsWithRetry(fetch = {
            calls++
            throw java.io.IOException("offline")
        }, wait = {})
        assertEquals(3, calls)
        assertEquals(LyricsRequestResult.RetryableFailure(LyricsFailureReason.Transient), result)
        // A later request is independent, rather than suppressed by a negative cache entry.
        assertEquals(LyricsRequestResult.Available(lines), requestLyricsWithRetry(fetch = { lines }, wait = {}))
    }

    @Test fun authorizationFailureWaitsForAuthenticationRatherThanBecomingNoLyrics() = runBlocking {
        var calls = 0
        val result = requestLyricsWithRetry(fetch = {
            calls++
            throw LyricsRequestException(LyricsFailureReason.Authorization, "sign in")
        }, wait = { fail("Authorization should not busy-retry") })
        assertEquals(1, calls)
        assertEquals(LyricsRequestResult.RetryableFailure(LyricsFailureReason.Authorization), result)
    }

    @Test fun onlyDefinitiveMissingLyricsBecomesUnavailable() = runBlocking {
        val missing = requestLyricsWithRetry(fetch = {
            throw LyricsRequestException(LyricsFailureReason.Unavailable, "not found")
        }, wait = { fail("Unavailable lyrics should not busy-retry") })
        assertEquals(LyricsRequestResult.Unavailable, missing)
        assertEquals(LyricsRequestResult.Unavailable, requestLyricsWithRetry(fetch = { emptyList() }, wait = {}))
        assertEquals(LyricsFailureReason.Unavailable, lyricsHttpFailure(404))
        assertEquals(LyricsFailureReason.Authorization, lyricsHttpFailure(401))
        assertEquals(LyricsFailureReason.Authorization, lyricsHttpFailure(403))
        assertEquals(LyricsFailureReason.Transient, lyricsHttpFailure(429))
        assertEquals(LyricsFailureReason.Transient, lyricsHttpFailure(503))
    }

    @Test fun navigatingAwayCancelsWithoutProducingAFailure() = runBlocking {
        var calls = 0
        try {
            requestLyricsWithRetry(fetch = { calls++; throw CancellationException("changed song") },
                wait = { fail("Cancellation must not retry") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
