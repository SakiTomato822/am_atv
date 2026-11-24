package dev.amatv.integration

import dev.amatv.integration.FlymeVoiceCommand.Search
import dev.amatv.integration.FlymeVoiceCommand.Search.Kind
import dev.amatv.integration.voice.NameRoute
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class FlymeVoiceQueue(
    val tracks: List<Track>,
    val collection: Track? = null,
    val spokenName: String? = null,
)

internal suspend fun resolveFlymeVoiceSearch(
    command: Search,
    search: suspend (String) -> List<Track>,
    artistTopSongs: suspend (Track) -> List<Track>,
    collectionTracks: suspend (Track) -> List<Track>,
    log: (String) -> Unit,
    librarySearch: suspend (String, LibraryCollection) -> List<Track> = { _, _ -> emptyList() },
    catalogAlbum: suspend (Track) -> Track? = { null },
    index: VoiceCatalogIndex = VoiceCatalogIndex(command),
): FlymeVoiceQueue {
    val library = librarySearch(index.libraryTerm, command.kind.libraryCollection)
    var matched = index.rank(library).exact
    var catalog = emptyList<Track>()
    if (matched == null) {
        catalog = search(command.query)
        var ranked = index.rank(library, catalog)
        if (ranked.exact == null) {
            val recalled = coroutineScope {
                index.recallTerms().map { term -> async { search(term) } }.awaitAll().flatten()
            }
            ranked = index.rank(library, catalog + recalled)
        }
        matched = ranked.exact ?: ranked.phonetic
    }
    val selected = matched?.track ?: catalog.firstOrNull(index::accepts)
    val matchType = when {
        matched != null -> when (matched.evidence.route) {
            NameRoute.Literal -> "exact"
            NameRoute.Alias -> "alias"
            else -> "phonetic"
        }
        selected != null -> "fallback-first-result"
        else -> "none"
    }
    val queue = when (selected?.catalogKind) {
        CatalogKind.Artist -> FlymeVoiceQueue(artistTopSongs(selected), spokenName = "${selected.title} 的歌")
        CatalogKind.Album -> {
            val album = if (selected.resourceType == LibraryCollection.Albums.resourceType) {
                catalogAlbum(selected) ?: selected.also {
                    log("kind=${command.kind} fallback=library-album-no-catalog using=library-tracks")
                }
            } else selected
            FlymeVoiceQueue(collectionTracks(album), collection = album, spokenName = "专辑 ${album.title}")
        }
        CatalogKind.Song -> FlymeVoiceQueue(listOf(selected))
        else -> if (command.kind == Kind.Artist) {
            val song = catalog.firstOrNull { it.catalogKind == CatalogKind.Song }
            log("kind=${command.kind} fallback=artist-not-found using=first-song songId=${song?.catalogId ?: song?.id}")
            FlymeVoiceQueue(listOfNotNull(song))
        } else {
            FlymeVoiceQueue(emptyList())
        }
    }
    val identified = queue.collection ?: selected
    val path = when {
        queue.tracks.isEmpty() -> "not-found"
        matchType == "exact" || matchType == "alias" -> "matched"
        else -> "best-match-fallback(selection-protocol-unverified)"
    }
    log("kind=${command.kind} path=$path match=$matchType id=${identified?.catalogId ?: identified?.resourceId ?: identified?.id} " +
        "name=${selected?.title} queueSize=${queue.tracks.size}")
    return queue
}
