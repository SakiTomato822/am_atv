package dev.amatv.integration

import dev.amatv.model.LyricLine
import kotlinx.coroutines.delay
import java.io.IOException

enum class LyricsFailureReason { Unavailable, Authorization, Transient }

class LyricsRequestException(
    val reason: LyricsFailureReason,
    message: String,
    val httpStatus: Int? = null,
) : IllegalStateException(message)

internal fun lyricsHttpFailure(status: Int): LyricsFailureReason = when (status) {
    404, 410 -> LyricsFailureReason.Unavailable
    401, 403 -> LyricsFailureReason.Authorization
    else -> LyricsFailureReason.Transient
}

internal sealed interface LyricsRequestResult {
    data class Available(val lines: List<LyricLine>) : LyricsRequestResult
    data object Unavailable : LyricsRequestResult
    data class RetryableFailure(val reason: LyricsFailureReason) : LyricsRequestResult
}

/** Retry transport failures without turning them into a permanent "no lyrics" cache entry. */
internal suspend fun requestLyricsWithRetry(
    fetch: suspend () -> List<LyricLine>,
    wait: suspend (Long) -> Unit = { delay(it) },
): LyricsRequestResult = try {
    val lines = retryReadRequest(fetch, wait) { causes ->
        val lyrics = causes.filterIsInstance<LyricsRequestException>().firstOrNull()
        val catalog = causes.filterIsInstance<CatalogRequestException>().firstOrNull()
        when {
            lyrics != null -> lyrics.reason == LyricsFailureReason.Transient && retryableReadStatus(lyrics.httpStatus)
            catalog != null -> catalog.reason == CatalogFailureReason.Transient && retryableReadStatus(catalog.httpStatus)
            else -> causes.any { it is IOException }
        }
    }
    if (lines.isEmpty()) LyricsRequestResult.Unavailable else LyricsRequestResult.Available(lines)
} catch (error: Exception) {
    val causes = error.requestCauses()
    val reason = causes.filterIsInstance<LyricsRequestException>().firstOrNull()?.reason ?: when (
        causes.filterIsInstance<CatalogRequestException>().firstOrNull()?.reason
    ) {
        CatalogFailureReason.Unavailable -> LyricsFailureReason.Unavailable
        CatalogFailureReason.Authorization -> LyricsFailureReason.Authorization
        CatalogFailureReason.Transient, null -> LyricsFailureReason.Transient
    }
    if (reason == LyricsFailureReason.Unavailable) LyricsRequestResult.Unavailable
    else LyricsRequestResult.RetryableFailure(reason)
}
