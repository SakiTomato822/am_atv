package dev.amatv.integration

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One account/storefront cache for library links and ordinary catalog recordings. */
internal class CatalogSongResolver(
    private val accountToken: () -> String?,
    private val storefront: suspend () -> String,
    private val libraryCatalogId: suspend (String) -> String,
    private val equivalentCatalogId: suspend (String, String) -> String,
    private val authorizationMessage: () -> String,
    private val maxEntries: Int = 64,
) {
    private data class Scope(val accountToken: String, val storefront: String)

    private class Flight {
        val mutex = Mutex()
        var users = 0
    }
    private val lock = Any()
    private val flights = mutableMapOf<Pair<Scope, String>, Flight>()
    private var scope: Scope? = null
    private val resolvedIds = LinkedHashMap<String, String>(64, 0.75f, true)

    init {
        require(maxEntries > 0)
    }

    suspend fun resolve(id: String): String {
        val account = accountToken()?.takeIf(String::isNotBlank)
            ?: throw CatalogRequestException(CatalogFailureReason.Authorization, authorizationMessage())
        val currentScope = Scope(account, storefront())
        ensureCurrentAccount(account)
        synchronized(lock) {
            if (scope != currentScope) {
                resolvedIds.clear()
                scope = currentScope
            }
        }
        return resolve(currentScope, id)
    }

    private suspend fun resolve(currentScope: Scope, id: String): String {
        val key = currentScope to id
        val flight = synchronized(lock) {
            flights.getOrPut(key, ::Flight).also { it.users++ }
        }
        try {
            return flight.mutex.withLock {
                ensureCurrentAccount(currentScope.accountToken)
                synchronized(lock) {
                    checkScope(currentScope)
                    resolvedIds[id]?.let { return@withLock it }
                }
                val catalogId = if (id.startsWith("i.")) libraryCatalogId(id) else id
                val playable = if (catalogId == id) equivalentCatalogId(currentScope.storefront, id)
                    else resolve(currentScope, catalogId)
                ensureCurrentAccount(currentScope.accountToken)
                synchronized(lock) {
                    checkScope(currentScope)
                    resolvedIds[id] = playable
                    resolvedIds[playable] = playable
                    while (resolvedIds.size > maxEntries) resolvedIds.remove(resolvedIds.keys.first())
                }
                playable
            }
        } finally {
            synchronized(lock) {
                if (--flight.users == 0) flights.remove(key)
            }
        }
    }

    private fun checkScope(expected: Scope) {
        if (scope != expected) throw CancellationException("Catalog storefront changed during song resolution")
    }

    private suspend fun ensureCurrentAccount(expected: String) {
        currentCoroutineContext().ensureActive()
        if (accountToken() != expected) {
            throw CancellationException("Catalog account changed during song resolution")
        }
    }
}

/** Preserve resource provenance while adopting Apple's current playback identity. */
internal suspend fun resolvePlayableSongTrack(
    track: Track,
    resolveId: suspend (String) -> String,
): Track {
    if (track.catalogKind != CatalogKind.Song) return track
    val original = checkNotNull(track.catalogId)
    val libraryId = track.resourceId?.takeIf { it.startsWith("i.") }
        ?: original.takeIf { it.startsWith("i.") }
    val resolved = resolveId(libraryId ?: original)
    if (resolved == original) return track
    return track.copy(
        id = "apple:$resolved",
        catalogId = resolved,
        resourceId = track.resourceId ?: libraryId ?: original,
        resourceType = track.resourceType ?: if (libraryId != null) "library-songs" else "songs",
    )
}
