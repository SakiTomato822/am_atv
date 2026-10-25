package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class YtmResolutionCursorTest {
    private val seed = "dQw4w9WgXcQ"
    private fun songs(count: Int) = (0 until count).map {
        YtmSong("v%010d".format(it), "Song $it", "Artist $it", if (it % 2 == 0) null else 180_000L)
    }

    @Test fun pageRemainderSurvivesEveryBatchWithoutAdvancingProviderEarly() {
        val original = songs(70)
        val delivered = mutableListOf<YtmSong>()
        var pending = original
        var cursor: String? = null
        while (pending.isNotEmpty()) {
            val batch = pending.take(12)
            delivered += batch
            cursor = YtmResolutionCursor(seed, pending.drop(batch.size), "real-provider-next").encode()
            val restored = YtmResolutionCursor.decode(cursor)
            if (restored != null) {
                assertEquals(seed, restored.seedVideoId)
                assertEquals("real-provider-next", restored.providerContinuation)
                pending = restored.pending
            } else pending = emptyList()
        }
        assertEquals(original, delivered)
        assertEquals("real-provider-next", cursor)
    }

    @Test fun completedFinalPageHasNoContinuation() {
        assertNull(YtmResolutionCursor(seed, emptyList(), null).encode())
    }

    @Test fun malformedLocalCursorIsRecognizedAndCannotLeakToTheProvider() {
        val malformed = "amatv-radio-v1:not-json"
        assertTrue(YtmResolutionCursor.isLocal(malformed))
        assertNull(YtmResolutionCursor.decode(malformed))
        assertFalse(YtmResolutionCursor.isLocal("external-provider-token"))
        assertNull(YtmResolutionCursor.decode("external-provider-token"))
    }
}
