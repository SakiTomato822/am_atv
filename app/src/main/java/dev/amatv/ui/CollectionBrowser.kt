package dev.amatv.ui

import dev.amatv.R
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CollectionBrowserState(
    val collection: Track? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null,
)

/** Browsing never touches the playback queue. A dismissed request cannot reopen the page. */
internal class CollectionBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (Track) -> List<Track>,
) {
    private val mutableState = MutableStateFlow(CollectionBrowserState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun open(collection: Track) {
        require(collection.openAction == CatalogOpenAction.Collection)
        val request = ++generation
        job?.cancel()
        mutableState.value = CollectionBrowserState(collection, loading = true)
        job = scope.launch {
            try {
                val tracks = load(collection)
                if (request == generation) {
                    mutableState.value = CollectionBrowserState(collection, tracks)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value = CollectionBrowserState(
                        collection, error = R.string.browse_tracks_error_detail,
                    )
                }
            }
        }
    }

    fun close() {
        generation++
        job?.cancel()
        mutableState.value = CollectionBrowserState()
    }
}
