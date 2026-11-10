package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.CatalogArtistPage
import dev.amatv.model.ContentShelf
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Saved artists also list the albums in the library; catalog artists show Apple Music's page only. */
internal val Track.isLibraryArtist: Boolean
    get() = resourceType == dev.amatv.integration.LibraryCollection.Artists.resourceType

internal data class LibraryArtistState(
    val artist: Track? = null,
    val albums: List<Track> = emptyList(),
    val nextPage: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** Apple Music's own artist page; empty if the artist has no catalog match. */
    val catalog: CatalogArtistPage = CatalogArtistPage(),
    val catalogLoading: Boolean = false,
) {
    val catalogShelves: List<ContentShelf> get() = catalog.shelves
}

/** A late artist request may not reopen a dismissed page or replace a different artist. */
internal class LibraryArtistBrowser(
    private val scope: CoroutineScope,
    private val loadCatalog: suspend (Track) -> CatalogArtistPage = { CatalogArtistPage() },
    private val peekCatalog: (Track) -> CatalogArtistPage? = { null },
    private val peekAlbums: (Track) -> LibraryPage? = { null },
    private val load: suspend (Track, String?) -> LibraryPage,
) {
    private val mutableState = MutableStateFlow(LibraryArtistState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var catalogJob: Job? = null
    private var generation = 0L
    private val visited = mutableSetOf<String>()

    fun open(artist: Track) {
        require(artist.openAction == CatalogOpenAction.OpenArtist)
        close()
        val catalog = peekCatalog(artist)
        val savedAlbums = if (artist.isLibraryArtist) peekAlbums(artist) else null
        mutableState.value = LibraryArtistState(
            artist = artist,
            albums = savedAlbums?.items.orEmpty(),
            catalog = catalog ?: CatalogArtistPage(),
            catalogLoading = catalog == null,
        )
        if (artist.isLibraryArtist) request(null)
        val requestGeneration = generation
        catalogJob = scope.launch {
            val page = try {
                loadCatalog(artist)
            } catch (_: Exception) {
                ensureActive()
                // A network error must not erase a previously displayed artist page.
                catalog ?: CatalogArtistPage()
            }
            if (requestGeneration == generation) {
                mutableState.value = mutableState.value.copy(catalog = page, catalogLoading = false)
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (!current.loading) current.nextPage?.let(::request)
    }

    fun retry() {
        val current = mutableState.value
        if (current.failed && !current.loading) request(current.nextPage)
    }

    private fun request(next: String?) {
        val before = mutableState.value
        val artist = before.artist ?: return
        val requestGeneration = generation
        mutableState.value = before.copy(loading = true, failed = false)
        job = scope.launch {
            try {
                val page = load(artist, next)
                if (requestGeneration != generation) return@launch
                check(page.requestedPage !in visited) { "Repeated artist albums page" }
                page.nextPage?.let {
                    check(it !in visited && it != page.requestedPage) { "Repeated artist albums continuation" }
                }
                visited += page.requestedPage
                mutableState.value = mutableState.value.copy(
                    albums = mergeLibraryPage(if (next == null) emptyList() else before.albums, page.items),
                    nextPage = page.nextPage,
                    loading = false, failed = false)
            } catch (_: Exception) {
                ensureActive()
                if (requestGeneration == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, failed = true)
                }
            }
        }
    }

    fun close() {
        generation++
        job?.cancel()
        catalogJob?.cancel()
        visited.clear()
        mutableState.value = LibraryArtistState()
    }
}
