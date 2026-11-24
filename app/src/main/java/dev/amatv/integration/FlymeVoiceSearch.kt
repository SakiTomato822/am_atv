package dev.amatv.integration

import dev.amatv.integration.FlymeVoiceCommand.Search
import dev.amatv.integration.FlymeVoiceCommand.Search.Kind
import dev.amatv.integration.FlymeVoiceCommand.Style
import dev.amatv.integration.voice.NameEncoder
import dev.amatv.integration.voice.NameMatcher
import dev.amatv.integration.voice.NameRoute
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
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
    onMatch: (FlymeVoiceFeedback) -> Unit = {},
): FlymeVoiceQueue = coroutineScope {
    suspend fun catalogSearch(term: String): Result<List<Track>> = try {
        Result.success(search(term))
    } catch (error: CatalogRequestException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    }
    val catalogRequest = async(start = CoroutineStart.LAZY) { catalogSearch(command.query) }
    val libraryRequest = async(start = CoroutineStart.UNDISPATCHED) {
        librarySearch(index.libraryTerm, command.kind.libraryCollection)
    }
    val titleRequest = async(start = CoroutineStart.LAZY) { catalogSearch(command.targetTitle) }
    if (!libraryRequest.isCompleted) catalogRequest.start()
    val titleRetry = index.requiresExactArtistSong && command.targetTitle != command.query
    if (!libraryRequest.isCompleted && titleRetry) titleRequest.start()
    val library = libraryRequest.await()
    if (index.requiresExactArtistSong) {
        val libraryExact = index.exactArtistSong(library)
        val exact = libraryExact
            ?: catalogRequest.await().getOrNull()?.let(index::exactArtistSong)
            ?: if (titleRetry) titleRequest.await().getOrNull()?.let(index::exactArtistSong) else null
        if (exact != null) {
            catalogRequest.cancel()
            titleRequest.cancel()
            onMatch(FlymeVoiceFeedback.track(exact))
            log("kind=${command.kind} path=matched match=exact-credit id=${exact.catalogId ?: exact.id} " +
                "name=${exact.title} queueSize=1")
            return@coroutineScope FlymeVoiceQueue(listOf(exact))
        }
        log("kind=${command.kind} exact-credit=none, ranking phonetically")
    }
    val titleMatches = if (titleRetry) titleRequest.await().getOrElse { error ->
        log("kind=${command.kind} fallback=title-recall-${error.javaClass.simpleName}")
        emptyList()
    } else emptyList()
    titleRequest.cancel()
    var matched = index.rank(library).exact
    var catalog = emptyList<Track>()
    if (matched == null) {
        catalog = catalogRequest.await().getOrElse { error ->
            if (titleMatches.isEmpty()) throw error
            log("kind=${command.kind} fallback=catalog-search-${error.javaClass.simpleName} using=title-recall")
            emptyList()
        }
        var ranked = index.rank(library, catalog + titleMatches)
        if (ranked.exact == null) {
            val recalled = coroutineScope {
                index.recallTerms().map { term -> async { search(term) } }.awaitAll().flatten()
            }
            ranked = index.rank(library, catalog + titleMatches + recalled)
        }
        matched = ranked.exact ?: ranked.phonetic
    } else {
        catalogRequest.cancel()
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
        CatalogKind.Artist -> {
            val name = "${selected.title} 的歌"
            onMatch(FlymeVoiceFeedback.playing(name))
            FlymeVoiceQueue(artistTopSongs(selected), spokenName = name)
        }
        CatalogKind.Album -> {
            onMatch(FlymeVoiceFeedback.playing("专辑 ${selected.title}"))
            val album = if (selected.resourceType == LibraryCollection.Albums.resourceType) {
                catalogAlbum(selected) ?: selected.also {
                    log("kind=${command.kind} fallback=library-album-no-catalog using=library-tracks")
                }
            } else selected
            FlymeVoiceQueue(collectionTracks(album), collection = album, spokenName = "专辑 ${album.title}")
        }
        CatalogKind.Song -> {
            onMatch(FlymeVoiceFeedback.track(selected))
            FlymeVoiceQueue(listOf(selected))
        }
        else -> if (command.kind == Kind.Artist) {
            val song = catalog.firstOrNull { it.catalogKind == CatalogKind.Song }
            song?.let { onMatch(FlymeVoiceFeedback.track(it)) }
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
    queue
}

internal fun matchVoiceStylePlaylist(
    command: Style,
    playlists: List<Track>,
    encoder: NameEncoder = flymeVoiceNameEncoder(),
): Track? {
    val queries = listOf(command.name, command.originInfo).map(encoder::prepare)
        .filter { it.key.isNotEmpty() }.distinctBy { it.key }
    val matcher = NameMatcher()
    return playlists.asSequence().filter {
        it.catalogKind == CatalogKind.Playlist || it.catalogKind == CatalogKind.Station
    }.mapNotNull { playlist ->
        val names = if (playlist.isVoiceFavorites()) favoritePlaylistNames + playlist.title else setOf(playlist.title)
        val score = names.asSequence().map(encoder::prepare).filter { it.key.isNotEmpty() }.maxOfOrNull { name ->
            queries.maxOfOrNull { query ->
                val evidence = matcher.compare(query, name)
                when {
                    evidence?.literalExact == true -> 3.0
                    minOf(query.key.length, name.key.length) >= 2 &&
                        (query.key.contains(name.key) || name.key.contains(query.key)) ->
                        1.0 + minOf(query.key.length, name.key.length).toDouble() / maxOf(query.key.length, name.key.length)
                    evidence?.route == NameRoute.Mandarin -> evidence.score
                    else -> 0.0
                }
            } ?: 0.0
        } ?: 0.0
        if (score > 0.0) playlist to score else null
    }.maxByOrNull { it.second }?.first
}
