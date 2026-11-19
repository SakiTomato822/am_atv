package dev.amatv.flyme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException

internal object FolioDetailSession {
    val stack = mutableStateOf(emptyList<FolioDetailEntry>())
}

internal fun <T> popFolioDetail(stack: List<T>): List<T> = stack.dropLast(1)

internal data class FolioDetailData<T>(
    val value: T? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

// The service decides when its memory entry is stale. A restored snapshot can render during refresh.
internal suspend fun <T : Any> loadFolioDetail(
    cached: T?,
    saved: suspend () -> T?,
    fetch: suspend () -> T,
    complete: (T) -> Boolean,
    publish: (FolioDetailData<T>) -> Unit,
) {
    var visible = cached
    publish(FolioDetailData(visible, loading = true))
    try {
        if (visible == null || !complete(visible)) {
            saved()?.let {
                visible = it
                publish(FolioDetailData(it, loading = true))
            }
        }
        val result = fetch()
        if (complete(result) || visible?.let(complete) != true) visible = result
        publish(FolioDetailData(visible, loading = false, failed = !complete(visible)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        publish(FolioDetailData(visible, loading = false, failed = true))
    }
}

@Composable
internal fun <T : Any> rememberFolioDetailData(
    cached: () -> T?,
    saved: suspend () -> T? = { null },
    complete: (T) -> Boolean = { true },
    fetch: suspend () -> T,
): Pair<FolioDetailData<T>, () -> Unit> {
    var data by remember { mutableStateOf(FolioDetailData(cached())) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loadFolioDetail(data.value ?: cached(), saved, fetch, complete) { data = it }
    }
    return data to { attempt++ }
}
