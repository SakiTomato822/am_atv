package dev.amatv.flyme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal val folioSearchSession = FolioSearchSession(CoroutineScope(Dispatchers.Main.immediate))

internal data class FolioSearchSubmission(
    val term: String,
    val results: List<Track>? = null,
    val failed: Boolean = false,
)

internal class FolioSearchSession(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var submission by mutableStateOf<FolioSearchSubmission?>(null)
        private set
    private var searchJob: Job? = null

    fun edit(text: String) {
        query = text
        if (text.isBlank()) {
            searchJob?.cancel()
            submission = null
        }
    }

    fun submit(term: String, search: suspend (String) -> List<Track>) {
        searchJob?.cancel()
        query = term
        submission = FolioSearchSubmission(term)
        searchJob = scope.launch {
            val completed = try {
                FolioSearchSubmission(term, results = search(term))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FolioSearchSubmission(term, results = emptyList(), failed = true)
            }
            ensureActive()
            submission = completed
        }
    }
}

internal data class FolioSearchSections(
    val best: List<Track>,
    val songs: List<Track>,
    val albums: List<Track>,
    val artists: List<Track>,
    val playlists: List<Track>,
)

internal fun folioSearchSections(results: List<Track>): FolioSearchSections {
    // The shared catalog response places Apple's top results before type-specific matches.
    val remaining = results.drop(1).groupBy { it.catalogKind }
    return FolioSearchSections(
        best = results.take(1),
        songs = results.asSequence().drop(1)
            .filter { it.catalogKind == CatalogKind.Song || it.catalogKind == CatalogKind.MusicVideo }
            .take(6).toList(),
        albums = remaining[CatalogKind.Album].orEmpty(),
        artists = remaining[CatalogKind.Artist].orEmpty(),
        playlists = remaining[CatalogKind.Playlist].orEmpty(),
    )
}
