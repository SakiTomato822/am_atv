package dev.amatv.core

import android.os.SystemClock
import dev.amatv.R
import dev.amatv.data.MockCatalog
import dev.amatv.i18n.AppText
import dev.amatv.integration.BROWSE_LOG_TAG
import dev.amatv.integration.BROWSE_PERSONAL_RELEASES_ID
import dev.amatv.integration.CatalogRequestException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    suspend fun personalizedNewReleases(): ContentShelf?
    suspend fun editorialBrowseShelves(languageTag: String): List<ContentShelf>
    suspend fun recentlyPlayedTracks(): List<Track>
    suspend fun recentlyPlayed(): List<Track>
    suspend fun charts(types: String = "albums,songs,playlists", browseFallback: Boolean = false): List<Track>
    suspend fun liveRadio(browseFallback: Boolean = false): List<Track>
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

    private data class LoadInputs(
        val connectionState: MusicConnectionState,
        val reloadGeneration: Int,
        val languageTag: String,
    )

    private var loadInputs: LoadInputs? = null
    private var activeDestination = Destination.Home
    private var startupRefreshScheduled = false
    private val shelfJobs = mutableMapOf<Destination, Job>()
    private val librarySnapshotWrites = Mutex()
    private var lastLibraryRefreshMs: Long? = null
    private var libraryVisitedPages: Map<String, Set<String>> = emptyMap()
    private var libraryPagingGeneration: Long = 0L
    private val libraryPageJobs = mutableMapOf<String, Job>()
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

    private fun cancelShelfLoads() {
        shelfJobs.values.forEach(Job::cancel)
        shelfJobs.clear()
        lastLibraryRefreshMs = null
    }

    fun refreshAll(current: Destination) {
        operations.clearDetailPages()
        cancelShelfLoads()
        resetLibraryPagination()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            loadingDestinations = setOf(current),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
        _messages.tryEmit(null)
    }

    fun onSignedIn() {
        cancelShelfLoads()
        resetLibraryPagination()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            loadingDestinations = setOf(Destination.Home),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
    }

    fun onSignedOut() {
        cancelShelfLoads()
        _state.value = _state.value.copy(
            shelvesByDestination = emptyMap(),
            loadingDestinations = emptySet(),
            recentTracks = emptyList(),
            reloadGeneration = _state.value.reloadGeneration + 1,
        )
    }

    fun startSignedInContent(connectionState: MusicConnectionState, contentLanguageTag: String) {
        if (startupRefreshScheduled || connectionState != MusicConnectionState.SignedIn ||
            !operations.isCatalogConfigured
        ) return
        startupRefreshScheduled = true
        val inputs = LoadInputs(connectionState, _state.value.reloadGeneration, contentLanguageTag)
        prepareShelfLoads(inputs)
        loadShelvesFor(Destination.Home, inputs)
        loadShelvesFor(Destination.Library, inputs, after = shelfJobs[Destination.Home])
    }

    fun loadDestination(
        destination: Destination,
        connectionState: MusicConnectionState,
        reloadGeneration: Int,
        contentLanguageTag: String,
    ) {
        activeDestination = destination
        val inputs = LoadInputs(connectionState, reloadGeneration, contentLanguageTag)
        prepareShelfLoads(inputs)
        loadShelvesFor(destination, inputs)
    }

    private fun prepareShelfLoads(inputs: LoadInputs) {
        val previous = loadInputs
        if (previous == inputs) return
        loadInputs = inputs
        if (previous == null) return
        val loadingDestinations = _state.value.loadingDestinations - shelfJobs.keys
        cancelShelfLoads()
        if (previous.languageTag != inputs.languageTag) {
            resetLibraryPagination()
            // Section titles are presentation metadata. Invalidate only this
            // cache, retaining the selected song, queue, focus and account.
            _state.value = _state.value.copy(
                shelvesByDestination = if (operations.isCatalogConfigured) emptyMap() else demoShelves(),
                loadingDestinations = emptySet(),
            )
        } else if (previous.connectionState != inputs.connectionState) {
            _state.value = _state.value.copy(
                shelvesByDestination = _state.value.shelvesByDestination - Destination.Browse,
                savedShelfDestinations = _state.value.savedShelfDestinations - Destination.Browse,
                loadingDestinations = loadingDestinations,
            )
        } else {
            _state.value = _state.value.copy(
                loadingDestinations = loadingDestinations,
            )
        }
    }

    private fun saveLibrarySnapshot(inputs: LoadInputs, shelves: List<ContentShelf>) {
        coroutineScope.launch {
            // Keep the intermediate snapshot from overwriting the completed refresh.
            librarySnapshotWrites.withLock {
                if (loadInputs == inputs && _state.value.reloadGeneration == inputs.reloadGeneration) {
                    withContext(ioDispatcher) {
                        cancellableResult { operations.saveShelves(Destination.Library.name, inputs.languageTag, shelves) }
                    }
                }
            }
        }
    }

    private fun loadShelvesFor(target: Destination, inputs: LoadInputs, after: Job? = null) {
        if (!operations.isCatalogConfigured) return
        if (shelfJobs[target]?.isActive == true) return
        val (connectionState, _, contentLanguageTag) = inputs
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
            after?.join()
            // Show the last session's shelves at once; the network load below replaces them. The library
            // is kept whole too: its playlist index alone left an old, partial list on screen every launch.
            val snapshotName = target.name
            if (_state.value.shelvesByDestination[target].isNullOrEmpty() &&
                connectionState == MusicConnectionState.SignedIn
            ) {
                cancellableResult {
                    withContext(ioDispatcher) { operations.savedShelves(snapshotName, contentLanguageTag) }
                }.getOrNull()
                    ?.let { shelves ->
                        if (target == Destination.Browse) shelves.filterNot { it.id == "browse-videos" }
                        else shelves
                    }
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

                        Destination.Browse -> this@launch.loadBrowseShelves(contentLanguageTag, connectionState, errors)

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
                            if (recentAlbums.isNotEmpty()) {
                                publishRecentLibraryAlbums(recentAlbums)
                                saveLibrarySnapshot(inputs, _state.value.shelvesFor(Destination.Library))
                            }

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
            val (loadedShelves, errors) = loadShelves()
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
                if (target == Destination.Library) {
                    saveLibrarySnapshot(inputs, loadedShelves)
                } else if (loadedShelves.isNotEmpty() && connectionState == MusicConnectionState.SignedIn) {
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
            if (activeDestination == target) _messages.tryEmit(msg)
        }
        shelfJobs[target] = job
        job.start()
    }

    private suspend fun CoroutineScope.loadBrowseShelves(
        languageTag: String,
        connectionState: MusicConnectionState,
        errors: MutableList<Throwable>,
    ): List<ContentShelf> {
        val editorial = async(start = CoroutineStart.UNDISPATCHED) {
            withContext(ioDispatcher) { cancellableResult { operations.editorialBrowseShelves(languageTag) } }
        }
        val charts = if (!editorial.isCompleted && _state.value.shelvesFor(Destination.Browse).isEmpty()) async {
            val chartErrors = mutableListOf<Throwable>()
            loadBrowseCharts(chartErrors).also(::showBrowseCharts) to chartErrors
        } else null

        val result = editorial.await()
        val shelves = result.getOrDefault(emptyList())
        if (shelves.isNotEmpty()) {
            charts?.cancel()
            return shelves
        }
        val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "empty editorial"
        logWarning(BROWSE_LOG_TAG, "channel=charts Falling back to charts and live radio: $reason")
        val fallback = if (charts == null) loadBrowseCharts(errors).also(::showBrowseCharts) else {
            val (loaded, chartErrors) = charts.await()
            errors += chartErrors
            loaded
        }
        if (fallback.isEmpty() && Destination.Browse in _state.value.savedShelfDestinations) return emptyList()
        if (connectionState != MusicConnectionState.SignedIn) return fallback

        val personal = withContext(ioDispatcher) { cancellableResult { operations.personalizedNewReleases() } }
            .getOrElse { failure ->
                logWarning(BROWSE_LOG_TAG, "Personalized releases unavailable: ${failure.javaClass.simpleName}")
                _state.value.shelvesFor(Destination.Browse).firstOrNull { it.id == BROWSE_PERSONAL_RELEASES_ID }
            }
        return listOfNotNull(personal) + fallback
    }

    private fun showBrowseCharts(shelves: List<ContentShelf>) {
        if (shelves.isNotEmpty()) {
            val current = _state.value
            _state.value = current.copy(
                shelvesByDestination = current.shelvesByDestination + (Destination.Browse to shelves),
            )
        }
    }

    private suspend fun loadBrowseCharts(errors: MutableList<Throwable>): List<ContentShelf> = coroutineScope {
        val songs = async(ioDispatcher) { cancellableResult { operations.charts("songs", browseFallback = true) } }
        val albums = async(ioDispatcher) { cancellableResult { operations.charts("albums", browseFallback = true) } }
        val playlists = async(ioDispatcher) { cancellableResult { operations.charts("playlists", browseFallback = true) } }
        val radio = async(ioDispatcher) { cancellableResult { operations.liveRadio(browseFallback = true) } }
        buildList {
            fun addShelf(result: Result<List<Track>>, id: String, title: Int, featured: Boolean = false) {
                result.fold(
                    onSuccess = { items ->
                        if (items.isNotEmpty()) add(ContentShelf(id, AppText.text(title), items, featured = featured))
                    },
                    onFailure = { failure ->
                        errors.add(failure)
                        val status = (failure as? CatalogRequestException)?.httpStatus
                        logWarning(BROWSE_LOG_TAG, "channel=charts shelf=$id httpStatus=${status ?: "unknown"} " +
                            "error=${failure.javaClass.simpleName}")
                    },
                )
            }
            addShelf(songs.await(), "browse-songs", R.string.app_new_top_tracks, featured = true)
            addShelf(albums.await(), "browse-albums", R.string.app_top_albums)
            addShelf(playlists.await(), "browse-playlists", R.string.app_featured_playlists)
            addShelf(radio.await(), "browse-radio", R.string.app_apple_music_radio)
        }.also { logWarning(BROWSE_LOG_TAG, "channel=charts shelves=${it.size}") }
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
