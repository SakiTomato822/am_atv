package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException

/** HTTP catalog failures carry service meaning, unlike MusicKit's native component codes. */
internal enum class CatalogFailureReason { Unavailable, Authorization, Transient }

internal class CatalogRequestException(
    val reason: CatalogFailureReason,
    message: String,
    val httpStatus: Int? = null,
) : IllegalStateException(message)

internal fun catalogHttpFailure(status: Int): CatalogFailureReason = when (status) {
    404, 410 -> CatalogFailureReason.Unavailable
    401, 403 -> CatalogFailureReason.Authorization
    else -> CatalogFailureReason.Transient
}

/** A reflected/suspended request may wrap its original service error or cancellation. */
internal fun Throwable.requestCauses(): List<Throwable> {
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    return causes
}

internal fun Throwable.catalogFailureReason(): CatalogFailureReason? =
    requestCauses().filterIsInstance<CatalogRequestException>().firstOrNull()?.reason

internal fun retryableReadStatus(status: Int?): Boolean = status == null || status == 408 ||
    status == 429 || status in 500..599

/** Resolve metadata with bounded transport retries; auth and unavailable are final verdicts. */
internal suspend fun <T> retryCatalogRequest(
    fetch: suspend () -> T,
    wait: suspend (Long) -> Unit = { delay(it) },
): T = retryReadRequest(fetch, wait) { causes ->
    val catalog = causes.filterIsInstance<CatalogRequestException>().firstOrNull()
    if (catalog != null) catalog.reason == CatalogFailureReason.Transient && retryableReadStatus(catalog.httpStatus)
    else causes.any { it is IOException }
}

internal suspend fun <T> retryReadRequest(
    fetch: suspend () -> T,
    wait: suspend (Long) -> Unit,
    isTransient: (List<Throwable>) -> Boolean,
): T {
    val delays = longArrayOf(2_000L, 5_000L)
    var attempt = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return fetch().also { currentCoroutineContext().ensureActive() }
        } catch (error: Exception) {
            val causes = error.requestCauses()
            if (attempt == delays.size || !isTransient(causes)) throw error
        }
        wait(delays[attempt++])
    }
}

internal fun requireEquivalentCatalogSongId(
    root: JSONObject,
    originalId: String,
    unavailableMessage: String,
    transientMessage: String,
): String {
    equivalentCatalogSongId(root, originalId)?.let { return it }
    val mapping = root.optJSONObject("meta")?.optJSONObject("filters")
        ?.optJSONObject("equivalents")?.optJSONArray(originalId)
    // An explicit empty mapping is Apple's unavailable verdict. Missing or
    // inconsistent metadata is not proof that the recording should be skipped.
    val unavailable = mapping != null && mapping.length() == 0
    throw CatalogRequestException(
        if (unavailable) CatalogFailureReason.Unavailable else CatalogFailureReason.Transient,
        if (unavailable) unavailableMessage else transientMessage,
    )
}
