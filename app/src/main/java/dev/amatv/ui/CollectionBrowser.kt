package dev.amatv.ui

import dev.amatv.R
import dev.amatv.integration.LibraryCollection
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CollectionBrowserState(
    val collection: Track? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null,
    /** Showing the catalog release instead of only the songs saved to the library. */
    val fullAlbum: Boolean = false,
) {
    /** Like Apple Music: a saved album lists saved songs and offers the complete release. */
    val canShowFullAlbum: Boolean
        get() = collection?.catalogKind == CatalogKind.Album &&
            collection.resourceType == LibraryCollection.Albums.resourceType && collection.catalogId != null

    /** The collection the visible tracks belong to; the catalog album once expanded. */
    val playbackCollection: Track?
        get() = if (fullAlbum) collection?.catalogAlbum() else collection
}

/** The catalog release behind a saved album: collection requests then read the catalog. */
internal fun Track.catalogAlbum(): Track = copy(resourceId = null, resourceType = "albums")

/** Browsing never touches the playback queue. A dismissed request cannot reopen the page. */
internal class CollectionBrowser(
    private val scope: CoroutineScope,
    private val peek: (Track) -> List<Track>? = { null },
    private val load: suspend (Track) -> List<Track>,
) {
    private val mutableState = MutableStateFlow(CollectionBrowserState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun open(collection: Track) {
        require(collection.openAction == CatalogOpenAction.Collection)
        load(collection, fullAlbum = false)
    }

    /** Switch a saved album between its saved songs and the complete catalog release. */
    fun setFullAlbum(full: Boolean) {
        val current = mutableState.value
        val collection = current.collection ?: return
        if (current.fullAlbum == full || (full && !current.canShowFullAlbum)) return
        load(collection, fullAlbum = full)
    }

    fun retry() {
        val current = mutableState.value
        current.collection?.let { load(it, current.fullAlbum) }
    }

    private fun load(collection: Track, fullAlbum: Boolean) {
        val request = ++generation
        job?.cancel()
        val target = if (fullAlbum) collection.catalogAlbum() else collection
        val cached = peek(target)
        mutableState.value = CollectionBrowserState(
            collection, cached.orEmpty(), loading = cached == null, fullAlbum = fullAlbum,
        )
        job = scope.launch {
            try {
                val tracks = load(target)
                if (request == generation) {
                    mutableState.value = CollectionBrowserState(collection, tracks, fullAlbum = fullAlbum)
                }
            } catch (_: Exception) {
                ensureActive()
                if (request == generation) {
                    mutableState.value = CollectionBrowserState(
                        collection, cached.orEmpty(),
                        error = if (cached == null) R.string.browse_tracks_error_detail else null,
                        fullAlbum = fullAlbum,
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
