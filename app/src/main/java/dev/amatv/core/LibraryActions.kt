package dev.amatv.core

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryNotice {
    data object FavoriteRemoveUnsupported : LibraryNotice
    data object FavoriteFailed : LibraryNotice
    data object RemoveFromLibraryUnsupported : LibraryNotice
    data object AddToLibraryFailed : LibraryNotice
    data object AddedToLibrary : LibraryNotice
    data object AddedToFavorites : LibraryNotice
}

data class CurrentSongLibraryState(
    val catalogId: String? = null,
    val isFavorite: Boolean? = null,
    val isInLibrary: Boolean? = null,
    val isFavoriteInProgress: Boolean = false,
    val isLibraryInProgress: Boolean = false,
)

data class LibraryActionsState(
    val current: CurrentSongLibraryState = CurrentSongLibraryState(),
    val favoriteCache: Map<String, Boolean> = emptyMap(),
    val libraryCache: Map<String, Boolean> = emptyMap(),
    val notice: LibraryNotice? = null,
)

interface LibraryActionsApi {
    suspend fun isFavorite(catalogSongId: String): Boolean?
    suspend fun addFavorite(catalogSongId: String)
    suspend fun isSongInLibrary(catalogSongId: String): Boolean?
    suspend fun addToLibrary(catalogSongId: String)
}

class LibraryActions(
    private val api: LibraryActionsApi,
    private val coroutineScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(LibraryActionsState())
    val state: StateFlow<LibraryActionsState> = mutableState.asStateFlow()

    private var currentTrack: Track? = null
    private var isSignedIn: Boolean = false

    fun onCurrentTrackChanged(track: Track?, signedIn: Boolean) {
        currentTrack = track
        isSignedIn = signedIn

        val catalogId = track?.catalogId?.takeIf { it.matches(Regex("[0-9]+")) }
        val isLibraryTrack = track != null && (track.resourceType == "library-songs" || track.id.startsWith("library:"))

        val isFavoriteCached = catalogId?.let { mutableState.value.favoriteCache[it] }
        val isInLibraryCached = if (isLibraryTrack) true else catalogId?.let { mutableState.value.libraryCache[it] }

        mutableState.value = mutableState.value.copy(
            current = CurrentSongLibraryState(
                catalogId = catalogId,
                isFavorite = isFavoriteCached,
                isInLibrary = isInLibraryCached,
            )
        )

        if (track == null || catalogId == null || track.catalogKind != CatalogKind.Song || !signedIn) {
            return
        }

        val needsFavoriteFetch = isFavoriteCached == null
        val needsLibraryFetch = isInLibraryCached == null && !isLibraryTrack

        if (needsFavoriteFetch || needsLibraryFetch) {
            coroutineScope.launch {
                if (needsFavoriteFetch) {
                    val favorite = runCatching { withContext(ioDispatcher) { api.isFavorite(catalogId) } }.getOrNull()
                    if (favorite != null && currentTrack?.catalogId == catalogId) {
                        mutableState.value = mutableState.value.copy(
                            current = mutableState.value.current.copy(isFavorite = favorite),
                            favoriteCache = mutableState.value.favoriteCache + (catalogId to favorite),
                        )
                    }
                }
                if (needsLibraryFetch) {
                    val inLibrary = runCatching { withContext(ioDispatcher) { api.isSongInLibrary(catalogId) } }.getOrNull()
                    if (inLibrary != null && currentTrack?.catalogId == catalogId) {
                        mutableState.value = mutableState.value.copy(
                            current = mutableState.value.current.copy(isInLibrary = inLibrary),
                            libraryCache = mutableState.value.libraryCache + (catalogId to inLibrary),
                        )
                    }
                }
            }
        }
    }

    fun toggleFavorite() {
        val track = currentTrack ?: return
        val catalogId = track.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return
        if (mutableState.value.current.isFavorite == true || mutableState.value.favoriteCache[catalogId] == true) {
            mutableState.value = mutableState.value.copy(notice = LibraryNotice.FavoriteRemoveUnsupported)
            return
        }

        // Optimistic update
        mutableState.value = mutableState.value.copy(
            current = mutableState.value.current.copy(isFavorite = true, isFavoriteInProgress = true),
            favoriteCache = mutableState.value.favoriteCache + (catalogId to true),
        )

        coroutineScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { api.addFavorite(catalogId) }
            }
            if (result.isSuccess) {
                if (currentTrack?.catalogId == catalogId) {
                    mutableState.value = mutableState.value.copy(
                        current = mutableState.value.current.copy(isFavoriteInProgress = false),
                    )
                }
            } else {
                val error = result.exceptionOrNull()
                logWarning("AMATV-Favorite", "Favorite add failed: ${error?.javaClass?.simpleName}")
                val rollbackCache = mutableState.value.favoriteCache + (catalogId to false)
                mutableState.value = mutableState.value.copy(
                    current = if (currentTrack?.catalogId == catalogId) {
                        mutableState.value.current.copy(isFavorite = false, isFavoriteInProgress = false)
                    } else mutableState.value.current,
                    favoriteCache = rollbackCache,
                    notice = LibraryNotice.FavoriteFailed,
                )
            }
        }
    }

    fun addToLibrary() {
        val track = currentTrack ?: return
        val catalogId = track.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return
        if (mutableState.value.current.isInLibrary == true || mutableState.value.libraryCache[catalogId] == true) {
            mutableState.value = mutableState.value.copy(notice = LibraryNotice.RemoveFromLibraryUnsupported)
            return
        }

        // Optimistic update
        mutableState.value = mutableState.value.copy(
            current = mutableState.value.current.copy(isInLibrary = true, isLibraryInProgress = true),
            libraryCache = mutableState.value.libraryCache + (catalogId to true),
        )

        coroutineScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { api.addToLibrary(catalogId) }
            }
            if (result.isSuccess) {
                if (currentTrack?.catalogId == catalogId) {
                    mutableState.value = mutableState.value.copy(
                        current = mutableState.value.current.copy(isLibraryInProgress = false),
                        notice = LibraryNotice.AddedToLibrary,
                    )
                }
            } else {
                val rollbackCache = mutableState.value.libraryCache + (catalogId to false)
                mutableState.value = mutableState.value.copy(
                    current = if (currentTrack?.catalogId == catalogId) {
                        mutableState.value.current.copy(isInLibrary = false, isLibraryInProgress = false)
                    } else mutableState.value.current,
                    libraryCache = rollbackCache,
                    notice = LibraryNotice.AddToLibraryFailed,
                )
            }
        }
    }

    fun dismissNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }
}

private fun logWarning(tag: String, msg: String) {
    try {
        android.util.Log.w(tag, msg)
    } catch (_: Throwable) {
        System.err.println("$tag: $msg")
    }
}
