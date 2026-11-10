package dev.amatv.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPageCacheTest {
    @Test fun freshPageReopensWithoutLoadingAndExpiredPageStaysVisibleUntilRefreshed() = runBlocking {
        var now = 0L
        val cache = DetailPageCache<String, String>(this, maxEntries = 2, ttlMillis = 10) { now }
        assertEquals("old", cache.getOrLoad("playlist") { "old" })
        now = 9
        assertEquals("old", cache.getOrLoad("playlist") { error("fresh page reloaded") })

        now = 10
        val fresh = CompletableDeferred<String>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("playlist") { fresh.await() } }
        assertEquals("old", cache.peek("playlist"))
        fresh.complete("new")
        assertEquals("new", refresh.await())
        assertEquals("new", cache.peek("playlist"))
    }

    @Test fun clearRemovesPagesAndCancelsSharedRequestBeforeItCanRefillCache() = runBlocking {
        val cache = DetailPageCache<String, String>(this, maxEntries = 2)
        cache.getOrLoad("loaded") { "old" }
        val pending = CompletableDeferred<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("loading") { pending.await() } }
        var duplicateLoad = false
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("loading") {
                duplicateLoad = true
                "duplicate"
            }
        }

        cache.clear()
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(duplicateLoad)
        assertNull(cache.peek("loaded"))
        assertNull(cache.peek("loading"))
        assertEquals("new", cache.getOrLoad("loading") { "new" })
    }

    @Test fun leastRecentlyUsedPageIsEvicted() = runBlocking {
        val cache = DetailPageCache<String, String>(this, maxEntries = 2)
        cache.getOrLoad("one") { "first" }
        cache.getOrLoad("two") { "second" }
        assertEquals("first", cache.peek("one"))
        cache.getOrLoad("three") { "third" }
        assertNull(cache.peek("two"))
        assertEquals("first", cache.peek("one"))
        assertEquals("third", cache.peek("three"))
    }
}
