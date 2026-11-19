package dev.amatv.core

import android.os.SystemClock
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.LibraryCollection
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.mergeLibraryPage
import dev.amatv.integration.retryCatalogRequest
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIBRARY_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

internal fun demoShelves(): Map<Destination, List<ContentShelf>> = mapOf(
    Destination.Home to listOf(
        ContentShelf("demo-picks", AppText.text(R.string.app_demo_top_picks), MockCatalog.tracks, featured = true),
        ContentShelf("demo-recent", AppText.text(R.string.app_recently_played), MockCatalog.tracks.asReversed()),
    ),
    Destination.Browse to listOf(
        ContentShelf("demo-new", AppText.text(R.string.app_demo_new_this_week), MockCatalog.tracks, featured = true),
    ),
    Destination.Library to listOf(
        ContentShelf("demo-library", AppText.text(R.string.app_recently_added), MockCatalog.tracks, featured = true),
    ),
)

/** The shelf retains its existing cards while one continuation page is requested. */
data class LibraryShelfPagination(
    val hasMore: Boolean,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val showAction: Boolean get() = hasMore || loading || failed
}

data class ContentState(
    val shelvesByDestination: Map<Destination, List<ContentShelf>> = emptyMap(),
    val loadingDestinations: Set<Destination> = emptySet(),
    val savedShelfDestinations: Set<Destination> = emptySet(),
    val recentTracks: List<Track> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val searchLoading: Boolean = false,
    val reloadGeneration: Int = 0,
    val libraryNextPages: Map<String, String> = emptyMap(),
    val libraryLoadingShelves: Set<String> = emptySet(),
    val libraryFailedShelves: Set<String> = emptySet(),
) {
    fun shelvesFor(destination: Destination): List<ContentShelf> = shelvesByDestination[destination].orEmpty()
    fun isLoading(destination: Destination): Boolean = destination in loadingDestinations

    val shelfPagination: Map<String, LibraryShelfPagination>
        get() = (libraryNextPages.keys + libraryLoadingShelves + libraryFailedShelves)
            .associateWith { id ->
                LibraryShelfPagination(
                    hasMore = id in libraryNextPages,
                    loading = id in libraryLoadingShelves,
                    failed = id in libraryFailedShelves,
                )
            }
}

interface ContentOperations {
    val isCatalogConfigured: Boolean
    suspend fun savedShelves(name: String, languageTag: String): List<ContentShelf>?
    suspend fun saveShelves(name: String, languageTag: String, shelves: List<ContentShelf>)
    suspend fun savedPlaylistIndex(): List<Track>?
    suspend fun recommendationShelves(): List<ContentShelf>
    suspend fun recentlyPlayedTracks(): List<Track>
    suspend fun recentlyPlayed(): List<Track>
    suspend fun charts(types: String = "albums,songs,playlists"): List<Track>
    suspend fun liveRadio(): List<Track>
    suspend fun recentlyAdded(): List<Track>
    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage
    suspend fun search(term: String): List<Track>
    fun clearDetailPages(dropSnapshots: Boolean = false)
}

class ContentStore(
    private val operations: ContentOperations,
    private val coroutineScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow(
        ContentState(
            shelvesByDestination = if (operations.isCatalogConfigured) emptyMap() else demoShelves(),
            loadingDestinations = if (operations.isCatalogConfigured) setOf(Destination.Home) else emptySet(),
            recentTracks = if (operations.isCatalogConfigured) emptyList() else MockCatalog.tracks.asReversed(),
        ),
    )
    val state: StateFlow<ContentState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    val messages: SharedFlow<String?> = _messages.asSharedFlow()

    private val shelfLoads = ShelfLoads(coroutineScope)
    private var shelvesLanguageTag: String? = null
    private var lastLibraryRefreshMs: Long? = null
    private var libraryVisitedPages: Map<String, Set<String>> = emptyMap()
    private var libraryPagingGeneration: Long = 0L
    private val libraryPageJobs = mutableMapOf<String, Job>()
    private var loadJob: Job? = null
    private var loadKey: List<Any?>? = null
    private var searchJob: Job? = null

    fun resetLibraryPagination() {
        libraryPagingGeneration++
        libraryPageJobs.values.forEach(Job::cancel)
        libraryPageJobs.clear()
        libraryVisitedPages = emptyMap()
        _state.value = _state.value.copy(
            libraryNextPages = emptyMap(),
            libraryLoadingShelves = emptySet(),
            libraryFailedShelves = emptySet(),
        )
    }

    private fun markLibraryPageVisited(shelfId: String, page: LibraryPage) {
        val visited = libraryVisitedPages[shelfId].orEmpty() + page.requestedPage
        check(page.nextPage == null || page.nextPage !in visited) {
            AppText.text(R.string.services_playlist_page_repeated)
        }
        libraryVisitedPages = libraryVisitedPages + (shelfId to visited)
    }

    private fun rememberLibraryPage(shelfId: String, page: LibraryPage) {
        markLibraryPageVisited(shelfId, page)
        val currentNextPages = _state.value.libraryNextPages
        val updatedNextPages = page.nextPage?.let { currentNextPages + (shelfId to it) }
            ?: (currentNextPages - shelfId)
        _state.value = _state.value.copy(libraryNextPages = updatedNextPages)
    }

    fun loadMoreLibraryShelf(shelfId: String, connectionState: MusicConnectionState) {
        val current = _state.value
        val nextPage = current.libraryNextPages[shelfId]
        if (nextPage == null && shelfId !in current.libraryFailedShelves) return
        if (connectionState != MusicConnectionState.SignedIn || current.isLoading(Destination.Library) ||
            shelfId in current.libraryLoadingShelves
        ) return
        val generation = libraryPagingGeneration
        _state.value = current.copy(
            libraryLoadingShelves = current.libraryLoadingShelves + shelfId,
            libraryFailedShelves = current.libraryFailedShelves - shelfId,
        )
        val pageJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = withContext(ioDispatcher) {
                    retryCatalogRequest({
                        when (shelfId) {
                            "lib-artists" -> operations.libraryArtistsPage(nextPage)
                            "lib-albums" -> operations.libraryAlbumsPage(nextPage)
                            "lib-playlists" -> loadLibraryPlaylistsPage(nextPage)
                            "lib-songs" -> operations.librarySongsPage(nextPage)
                            else -> error("Unknown library shelf")
                        }
                    })
                }
                if (generation != libraryPagingGeneration) return@launch
                check(page.requestedPage !in libraryVisitedPages[shelfId].orEmpty()) {
                    AppText.text(R.string.services_playlist_page_repeated)
                }
                rememberLibraryPage(shelfId, page)
                val latest = _state.value
                val shelves = latest.shelvesByDestination[Destination.Library].orEmpty().map { shelf ->
                    if (shelf.id != shelfId) shelf
                    else shelf.copy(items = when {
                        nextPage != null -> mergeLibraryPage(shelf.items, page.items)
                        shelfId == "lib-albums" -> mergeLibraryPage(
                            latest.shelvesFor(Destination.Library).firstOrNull { it.id == "lib-recently-added" }
                                ?.items.orEmpty().recentLibraryAlbums(),
                            page.items,
                        )
                        else -> page.items
                    })
                }
                _state.value = latest.copy(
                    shelvesByDestination = latest.shelvesByDestination + (Destination.Library to shelves),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == libraryPagingGeneration) {
                    _state.value = _state.value.copy(
                        libraryFailedShelves = _state.value.libraryFailedShelves + shelfId,
                    )
                    logWarning("AMATV-Library", "Library page failed (${error.javaClass.simpleName})")
                }
            } finally {
                if (generation == libraryPagingGeneration) {
                    _state.value = _state.value.copy(
                        libraryLoadingShelves = _state.value.libraryLoadingShelves - shelfId,
                    )
                    libraryPageJobs.remove(shelfId)
                }
            }
        }
        libraryPageJobs[shelfId] = pageJob
        pageJob.start()
    }

    // An explicit first-page URL bypasses the in-memory playlist index used by detail pages.
    private suspend fun loadLibraryPlaylistsPage(nextPage: String? = null): LibraryPage =
        operations.libraryPlaylistsPage(nextPage ?: LibraryCollection.Playlists.request.firstPage)

    private fun List<Track>.recentLibraryAlbums(): List<Track> =
        filter { it.resourceType == LibraryCollection.Albums.resourceType }

    private fun publishRecentLibraryAlbums(albums: List<Track>) {
        val current = _state.value
        val shelves = current.shelvesFor(Destination.Library)
        val existing = shelves.firstOrNull { it.id == "lib-albums" }
        val updated = existing?.copy(items = mergeLibraryPage(albums, existing.items))
            ?: ContentShelf("lib-albums", AppText.text(R.string.app_library_albums), albums)
        _state.value = current.copy(
            shelvesByDestination = current.shelvesByDestination + (Destination.Library to
                if (existing == null) shelves + updated else shelves.map { if (it.id == updated.id) updated else it }),
        )
    }

    private fun invalidateLibraryRefresh() {
        lastLibraryRefreshMs = null
        if (loadKey?.first() == Destination.Library) loadJob?.cancel()
    }

    fun refreshAll(current: Destination) {
        operations.clearDetailPages()
        invalidateLibraryRefresh()
        resetLibraryPagination()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            loadingDestinations = setOf(current),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
        _messages.tryEmit(null)
    }

    fun onSignedIn() {
        invalidateLibraryRefresh()
        resetLibraryPagination()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            loadingDestinations = setOf(Destination.Home),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
    }

    fun onSignedOut() {
        invalidateLibraryRefresh()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            recentTracks = emptyList(),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
    }

    fun loadDestination(
        destination: Destination,
        connectionState: MusicConnectionState,
        reloadGeneration: Int,
        contentLanguageTag: String,
    ) {
        val key = listOf(destination, connectionState, reloadGeneration, contentLanguageTag)
        if (key == loadKey && loadJob?.isActive == true) return
        loadJob?.cancel()
        if (loadKey?.first() == Destination.Library) {
            _state.value = _state.value.copy(
                loadingDestinations = _state.value.loadingDestinations - Destination.Library,
            )
        }
        loadKey = key
        if (shelvesLanguageTag == null) {
            shelvesLanguageTag = contentLanguageTag
        } else if (shelvesLanguageTag != contentLanguageTag) {
            lastLibraryRefreshMs = null
            resetLibraryPagination()
            // Section titles are presentation metadata. Invalidate only this
            // cache, retaining the selected song, queue, focus and account.
            _state.value = _state.value.copy(
                shelvesByDestination = if (operations.isCatalogConfigured) emptyMap() else demoShelves(),
                loadingDestinations = emptySet(),
            )
            shelvesLanguageTag = contentLanguageTag
        }
        if (!operations.isCatalogConfigured) return
        val target = destination
        if (
            target != Destination.Home &&
            target != Destination.Browse &&
            target != Destination.Library
        ) {
            return
        }
        if (target != Destination.Library &&
            _state.value.shelvesFor(target).isNotEmpty() && target !in _state.value.savedShelfDestinations
        ) return
        if (target == Destination.Library && connectionState != MusicConnectionState.SignedIn) {
            _state.value = _state.value.copy(
                loadingDestinations = _state.value.loadingDestinations - target,
            )
            return
        }
        val refreshedAt = lastLibraryRefreshMs
        if (target == Destination.Library && refreshedAt != null &&
            elapsedRealtimeMs() - refreshedAt < LIBRARY_REFRESH_INTERVAL_MS
        ) return

        _state.value = _state.value.copy(
            loadingDestinations = _state.value.loadingDestinations + target,
        )
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            // Show the last session's shelves at once; the network load below replaces them. The library
            // is kept whole too: its playlist index alone left an old, partial list on screen every launch.
            val snapshotName = target.name
            if (_state.value.shelvesByDestination[target].isNullOrEmpty() &&
                connectionState == MusicConnectionState.SignedIn
            ) {
                cancellableResult {
                    withContext(ioDispatcher) { operations.savedShelves(snapshotName, contentLanguageTag) }
                }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && _state.value.shelvesByDestination[target].isNullOrEmpty() }
                    ?.let { saved ->
                        _state.value = _state.value.copy(
                            shelvesByDestination = _state.value.shelvesByDestination + (target to saved),
                            savedShelfDestinations = _state.value.savedShelfDestinations + target,
                        )
                    }
            }
            if (target == Destination.Library && _state.value.shelvesByDestination[target].isNullOrEmpty()) {
                withContext(ioDispatcher) { operations.savedPlaylistIndex() }?.takeIf { it.isNotEmpty() }?.let { playlists ->
                    _state.value = _state.value.copy(
                        shelvesByDestination = _state.value.shelvesByDestination + (target to listOf(
                            ContentShelf("lib-playlists", AppText.text(R.string.app_library_playlists), playlists),
                        )),
                        savedShelfDestinations = _state.value.savedShelfDestinations + target,
                    )
                }
            }
            val libraryFirstPages = mutableMapOf<String, LibraryPage>()
            val libraryFailures = mutableSetOf<String>()
            val loadShelves: suspend () -> Pair<List<ContentShelf>, List<Throwable>> = {
                val errors = mutableListOf<Throwable>()
                coroutineScope {
                    when (target) {
                        Destination.Home -> {
                            val recommendations = async(ioDispatcher) {
                                if (connectionState == MusicConnectionState.SignedIn) {
                                    cancellableResult { operations.recommendationShelves() }
                                } else Result.success(emptyList())
                            }
                            val recentPlayedTracks = async(ioDispatcher) {
                                if (connectionState == MusicConnectionState.SignedIn) {
                                    cancellableResult { operations.recentlyPlayedTracks() }
                                } else Result.success(emptyList())
                            }
                            val recentContainers = async(ioDispatcher) {
                                if (connectionState == MusicConnectionState.SignedIn) {
                                    cancellableResult { operations.recentlyPlayed() }
                                } else Result.success(emptyList())
                            }
                            val songs = async(ioDispatcher) { cancellableResult { operations.charts("songs") } }
                            val albums = async(ioDispatcher) { cancellableResult { operations.charts("albums") } }
                            val playlists = async(ioDispatcher) { cancellableResult { operations.charts("playlists") } }

                            buildList {
                                recommendations.await().fold(
                                    onSuccess = { addAll(it) },
                                    onFailure = errors::add,
                                )
                                recentPlayedTracks.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            _state.value = _state.value.copy(recentTracks = items)
                                            add(
                                                ContentShelf(
                                                    id = "recent-tracks",
                                                    title = AppText.text(R.string.app_recent_tracks),
                                                    items = items,
                                                    featured = isEmpty(),
                                                ),
                                            )
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                recentContainers.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("recent", AppText.text(R.string.app_recent_albums_and_playlists), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                songs.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(
                                                ContentShelf(
                                                    id = "top-songs",
                                                    title = AppText.text(R.string.app_top_songs),
                                                    items = items,
                                                    featured = isEmpty(),
                                                ),
                                            )
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                albums.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("top-albums", AppText.text(R.string.app_top_albums), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                playlists.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("top-playlists", AppText.text(R.string.app_top_playlists), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                            }
                        }

                        Destination.Browse -> {
                            val songs = async(ioDispatcher) { cancellableResult { operations.charts("songs") } }
                            val albums = async(ioDispatcher) { cancellableResult { operations.charts("albums") } }
                            val playlists = async(ioDispatcher) { cancellableResult { operations.charts("playlists") } }
                            val videos = async(ioDispatcher) { cancellableResult { operations.charts("music-videos") } }
                            val radio = async(ioDispatcher) { cancellableResult { operations.liveRadio() } }

                            buildList {
                                songs.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(
                                                ContentShelf(
                                                    id = "browse-songs",
                                                    title = AppText.text(R.string.app_new_top_tracks),
                                                    items = items,
                                                    featured = true,
                                                ),
                                            )
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                albums.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("browse-albums", AppText.text(R.string.app_top_albums), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                playlists.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("browse-playlists", AppText.text(R.string.app_featured_playlists), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                videos.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("browse-videos", AppText.text(R.string.app_top_music_videos), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                                radio.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            add(ContentShelf("browse-radio", AppText.text(R.string.app_apple_music_radio), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                            }
                        }

                        Destination.Library -> {
                            resetLibraryPagination()
                            val savedShelves = _state.value.shelvesFor(Destination.Library).associateBy { it.id }
                            val recentlyAdded = async(ioDispatcher) { cancellableResult { operations.recentlyAdded() } }
                            val artists = async(ioDispatcher) { cancellableResult { operations.libraryArtistsPage() } }
                            val albums = async(ioDispatcher) { cancellableResult { operations.libraryAlbumsPage() } }
                            val playlists = async(ioDispatcher) { cancellableResult { loadLibraryPlaylistsPage() } }
                            val songs = async(ioDispatcher) { cancellableResult { operations.librarySongsPage() } }
                            val recentPlayed = async(ioDispatcher) { cancellableResult { operations.recentlyPlayedTracks() } }

                            val recentItems = recentlyAdded.await().getOrElse { error ->
                                errors += error
                                emptyList()
                            }
                            val recentAlbums = recentItems.recentLibraryAlbums()
                            if (recentAlbums.isNotEmpty()) publishRecentLibraryAlbums(recentAlbums)

                            buildList {
                                if (recentItems.isNotEmpty()) {
                                    add(ContentShelf("lib-recently-added", AppText.text(R.string.app_recently_added),
                                        recentItems, featured = true))
                                }
                                artists.await().fold(
                                    onSuccess = { page ->
                                        libraryFirstPages["lib-artists"] = page
                                        add(ContentShelf("lib-artists", AppText.text(R.string.app_library_artists), page.items))
                                    },
                                    onFailure = { error ->
                                        errors += error
                                        libraryFailures += "lib-artists"
                                        add(savedShelves["lib-artists"]
                                            ?: ContentShelf("lib-artists", AppText.text(R.string.app_library_artists), emptyList()))
                                    },
                                )
                                albums.await().fold(
                                    onSuccess = { page ->
                                        libraryFirstPages["lib-albums"] = page
                                        val items = mergeLibraryPage(recentAlbums, page.items)
                                        if (items.isNotEmpty() || page.nextPage != null) {
                                            add(ContentShelf("lib-albums", AppText.text(R.string.app_library_albums),
                                                items, featured = isEmpty()))
                                        }
                                    },
                                    onFailure = { error ->
                                        errors += error
                                        libraryFailures += "lib-albums"
                                        add(_state.value.shelvesFor(Destination.Library).firstOrNull { it.id == "lib-albums" }
                                            ?: ContentShelf("lib-albums", AppText.text(R.string.app_library_albums), emptyList()))
                                    },
                                )
                                playlists.await().fold(
                                    onSuccess = { page ->
                                        libraryFirstPages["lib-playlists"] = page
                                        if (page.items.isNotEmpty() || page.nextPage != null) {
                                            add(
                                                ContentShelf(
                                                    id = "lib-playlists",
                                                    title = AppText.text(R.string.app_library_playlists),
                                                    items = page.items,
                                                    featured = isEmpty(),
                                                ),
                                            )
                                        }
                                    },
                                    onFailure = { error ->
                                        errors += error
                                        libraryFailures += "lib-playlists"
                                        add(savedShelves["lib-playlists"]
                                            ?: ContentShelf("lib-playlists", AppText.text(R.string.app_library_playlists), emptyList()))
                                    },
                                )
                                songs.await().fold(
                                    onSuccess = { page ->
                                        libraryFirstPages["lib-songs"] = page
                                        if (page.items.isNotEmpty() || page.nextPage != null) {
                                            add(ContentShelf("lib-songs", AppText.text(R.string.app_library_songs), page.items))
                                        }
                                    },
                                    onFailure = { error ->
                                        errors += error
                                        libraryFailures += "lib-songs"
                                        add(savedShelves["lib-songs"]
                                            ?: ContentShelf("lib-songs", AppText.text(R.string.app_library_songs), emptyList()))
                                    },
                                )
                                recentPlayed.await().fold(
                                    onSuccess = { items ->
                                        if (items.isNotEmpty()) {
                                            _state.value = _state.value.copy(recentTracks = items)
                                            add(ContentShelf("lib-recent", AppText.text(R.string.app_recently_played), items))
                                        }
                                    },
                                    onFailure = errors::add,
                                )
                            }
                        }

                        Destination.NowPlaying,
                        Destination.Sing,
                        Destination.Videos,
                        Destination.Radio,
                        Destination.Account,
                        Destination.Search,
                        -> emptyList()
                    }
                } to errors
            }
            // Library pagination belongs to this refresh. Cancel it with the destination load.
            val (loadedShelves, errors) = if (target == Destination.Library) loadShelves() else shelfLoads.load(key, loadShelves)
            val keepSaved = loadedShelves.isEmpty() && target in _state.value.savedShelfDestinations
            if (!keepSaved) {
                var updated = _state.value.copy(
                    shelvesByDestination = _state.value.shelvesByDestination + (target to loadedShelves),
                    savedShelfDestinations = _state.value.savedShelfDestinations - target,
                    loadingDestinations = _state.value.loadingDestinations - target,
                )
                if (target == Destination.Library) {
                    libraryFirstPages.forEach { (id, page) -> markLibraryPageVisited(id, page) }
                    updated = updated.copy(
                        libraryNextPages = libraryFirstPages.mapNotNull { (id, page) ->
                            page.nextPage?.let { id to it }
                        }.toMap(),
                        libraryFailedShelves = libraryFailures,
                    )
                    if (errors.isEmpty()) lastLibraryRefreshMs = elapsedRealtimeMs()
                }
                _state.value = updated
                if (loadedShelves.isNotEmpty() && connectionState == MusicConnectionState.SignedIn) {
                    coroutineScope.launch(ioDispatcher) {
                        cancellableResult { operations.saveShelves(snapshotName, contentLanguageTag, loadedShelves) }
                    }
                }
            } else {
                _state.value = _state.value.copy(
                    loadingDestinations = _state.value.loadingDestinations - target,
                )
            }
            val msg = if (loadedShelves.isEmpty() && !keepSaved) {
                errors.firstOrNull()?.message
            } else {
                null
            }
            _messages.tryEmit(msg)
        }
        loadJob = job
        job.start()
    }

    fun ensureDefaultSearch() {
        if (_state.value.searchResults.isEmpty() && operations.isCatalogConfigured) {
            coroutineScope.launch {
                _state.value = _state.value.copy(searchLoading = true)
                cancellableResult { withContext(ioDispatcher) { operations.charts("songs") } }
                    .onSuccess { _state.value = _state.value.copy(searchResults = it) }
                    .onFailure { _messages.tryEmit(it.message) }
                _state.value = _state.value.copy(searchLoading = false)
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        lateinit var job: Job
        job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            _state.value = _state.value.copy(searchLoading = true)
            try {
                cancellableResult { withContext(ioDispatcher) { operations.search(query) } }
                    .onSuccess { items ->
                        _state.value = _state.value.copy(searchResults = items)
                        val msg = if (items.isEmpty()) {
                            AppText.text(R.string.app_search_no_results_format, query)
                        } else null
                        _messages.tryEmit(msg)
                    }
                    .onFailure {
                        _state.value = _state.value.copy(searchResults = emptyList())
                        _messages.tryEmit(it.message ?: AppText.text(R.string.app_search_failed))
                    }
            } finally {
                if (searchJob === job) {
                    _state.value = _state.value.copy(searchLoading = false)
                }
            }
        }
        searchJob = job
        job.start()
    }
}

/**
 * One shelf load per destination and inputs. Startup recomposition can restart the loading effect
 * with unchanged inputs; the restarted effect awaits the running load instead of requesting every
 * Apple Music endpoint again. A load for the same destination with other inputs is superseded.
 */
private class ShelfLoads(private val scope: CoroutineScope) {
    private val running = mutableMapOf<List<Any?>, Deferred<Pair<List<ContentShelf>, List<Throwable>>>>()

    suspend fun load(
        key: List<Any?>,
        block: suspend () -> Pair<List<ContentShelf>, List<Throwable>>,
    ): Pair<List<ContentShelf>, List<Throwable>> {
        val deferred = running[key]?.takeIf { it.isActive } ?: run {
            running.keys.filter { it.first() == key.first() && it != key }.forEach { running.remove(it)?.cancel() }
            scope.async { block() }.also { running[key] = it }
        }
        return try {
            deferred.await()
        } finally {
            if (!deferred.isActive && running[key] === deferred) running.remove(key)
        }
    }
}

private fun logWarning(tag: String, msg: String) {
    try {
        android.util.Log.w(tag, msg)
    } catch (_: Throwable) {
        System.err.println("$tag: $msg")
    }
}

private suspend inline fun <T> cancellableResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
