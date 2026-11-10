package dev.amatv.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

/** A service-scoped LRU. Expired entries can render while one shared refresh is in flight. */
internal class DetailPageCache<K, V : Any>(
    private val scope: CoroutineScope,
    private val maxEntries: Int,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<V>(val value: V, val storedAt: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Deferred<V>>()
    private var generation = 0L

    init {
        require(maxEntries > 0 && ttlMillis > 0)
    }

    fun peek(key: K): V? = synchronized(lock) { entries[key]?.value }

    suspend fun getOrLoad(key: K, load: suspend () -> V): V {
        val flight = synchronized(lock) {
            entries[key]?.let { entry ->
                if (nowMillis() - entry.storedAt < ttlMillis) return entry.value
            }
            pending[key] ?: startLoad(key, load)
        }
        return flight.await()
    }

    // Called with the lock held. Install the deferred before it can run or complete.
    private fun startLoad(key: K, load: suspend () -> V): Deferred<V> {
        val requestGeneration = generation
        val flight = scope.async(start = CoroutineStart.LAZY) {
            val value = load()
            ensureActive()
            synchronized(lock) {
                if (generation == requestGeneration) {
                    entries[key] = Entry(value, nowMillis())
                    if (entries.size > maxEntries) entries.remove(entries.keys.first())
                }
            }
            value
        }
        pending[key] = flight
        flight.invokeOnCompletion {
            synchronized(lock) {
                if (pending[key] === flight) pending.remove(key)
            }
        }
        return flight
    }

    fun clear() {
        val obsolete = synchronized(lock) {
            generation++
            entries.clear()
            pending.values.toList().also { pending.clear() }
        }
        obsolete.forEach { it.cancel() }
    }
}
