package dev.amatv.ui

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LibraryArtistState(
    val artist: Track? = null,
    val albums: List<Track> = emptyList(),
    val nextPage: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** A late artist request may not reopen a dismissed page or replace a different artist. */
internal class LibraryArtistBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (Track, String?) -> LibraryPage,
) {
    private val mutableState = MutableStateFlow(LibraryArtistState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private val visited = mutableSetOf<String>()

    fun open(artist: Track) {
        require(artist.openAction == CatalogOpenAction.OpenArtist)
        close()
        mutableState.value = LibraryArtistState(artist = artist)
        request(null)
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
                mutableState.value = LibraryArtistState(artist,
                    mergeLibraryPage(before.albums, page.items), page.nextPage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) mutableState.value = before.copy(failed = true)
            }
        }
    }

    fun close() {
        generation++
        job?.cancel()
        visited.clear()
        mutableState.value = LibraryArtistState()
    }
}
