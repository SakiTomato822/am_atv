package dev.amatv.integration

import dev.amatv.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class LyricsFailureReason { Unavailable, Authorization, Transient }

class LyricsRequestException(val reason: LyricsFailureReason, message: String) : IllegalStateException(message)

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
): LyricsRequestResult {
    val delays = longArrayOf(2_000L, 5_000L)
    for (attempt in 0..delays.size) {
        try {
            val lines = fetch()
            return if (lines.isEmpty()) LyricsRequestResult.Unavailable else LyricsRequestResult.Available(lines)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val catalogReason = error.catalogFailureReason()
            val reason = (error as? LyricsRequestException)?.reason ?: when (catalogReason) {
                CatalogFailureReason.Unavailable -> LyricsFailureReason.Unavailable
                CatalogFailureReason.Authorization -> LyricsFailureReason.Authorization
                CatalogFailureReason.Transient, null -> LyricsFailureReason.Transient
            }
            if (reason == LyricsFailureReason.Unavailable) return LyricsRequestResult.Unavailable
            if (reason == LyricsFailureReason.Authorization || attempt == delays.size) {
                return LyricsRequestResult.RetryableFailure(reason)
            }
            wait(delays[attempt])
        }
    }
    error("Lyrics retry loop did not complete")
}
