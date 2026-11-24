package dev.amatv.integration

import android.util.Log
import dev.amatv.core.ContentStore
import dev.amatv.core.PlayHistoryStore
import dev.amatv.core.PlaybackStore
import dev.amatv.core.StartupQueueAction
import dev.amatv.integration.FlymeVoiceBridge.Companion.TAG
import dev.amatv.integration.FlymeVoiceCommand.Control
import dev.amatv.integration.FlymeVoiceCommand.Ignored
import dev.amatv.integration.FlymeVoiceCommand.Info
import dev.amatv.integration.FlymeVoiceCommand.ListSource
import dev.amatv.integration.FlymeVoiceCommand.Mode
import dev.amatv.integration.FlymeVoiceCommand.Page
import dev.amatv.integration.FlymeVoiceCommand.Search
import dev.amatv.integration.FlymeVoiceCommand.Seek
import dev.amatv.integration.FlymeVoiceCommand.Style
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface FlymeVoiceDestination {
    data class NowPlaying(val lyrics: Boolean) : FlymeVoiceDestination
    data class Search(val query: String) : FlymeVoiceDestination
}

internal data class FlymeVoiceFeedback(val text: String, val failure: Boolean = false) {
    companion object {
        fun notFound(name: String) = FlymeVoiceFeedback("没找到 $name", failure = true)

        fun track(track: Track) = playing(listOf(track.title, track.artist)
            .filter(String::isNotBlank).joinToString(" – "))

        fun playing(name: String) = FlymeVoiceFeedback("正在播放 $name")

        fun info(info: Info, track: Track?): FlymeVoiceFeedback {
            val field = when (info) {
                Info.Title -> track?.title
                Info.Artist -> track?.artist
                Info.Album -> track?.album
            }
            return if (field.isNullOrBlank()) notFound(when (info) {
                Info.Title -> "当前歌曲"
                Info.Artist -> "当前歌手"
                Info.Album -> "当前专辑"
            }) else FlymeVoiceFeedback(field)
        }
    }
}

internal fun MusicServices.startFlymeVoice(
    scope: CoroutineScope,
    playback: PlaybackStore,
    history: PlayHistoryStore,
    content: ContentStore,
    navigate: (FlymeVoiceDestination) -> Unit,
    favorite: () -> Unit,
) {
    flymeVoice?.start(scope) { command ->
        val current = playback.state.value
        when (command) {
            Control.Play -> playback.requestPlayback()
            Control.Pause -> playback.pausePlayback()
            Control.Previous -> playback.dispatchQueueAction(StartupQueueAction.Previous)
            Control.Next -> playback.dispatchQueueAction(StartupQueueAction.Next)
            Control.Favorite -> favorite()
            Control.Replay -> if (current.hasCurrentTrack) {
                playback.seekPlaybackTo(0)
                playback.requestPlayback()
            } else return@start FlymeVoiceFeedback.notFound("当前歌曲")
            is Seek -> if (current.hasCurrentTrack) {
                if (command.relative) playback.seekPlaybackBy(command.milliseconds)
                else playback.seekPlaybackTo(command.milliseconds)
            } else return@start FlymeVoiceFeedback.notFound("当前歌曲")
            is Mode -> playback.dispatchQueueAction(StartupQueueAction.SetMode(command.repeat, command.shuffle))
            is Page -> navigate(FlymeVoiceDestination.NowPlaying(command == Page.Lyrics))
            is Info -> return@start FlymeVoiceFeedback.info(command, current.track.takeIf { current.hasCurrentTrack })
            is Search -> {
                if (command.searchOnly) {
                    Log.i(TAG, "path=search-page query=${command.query} fallback=selection-protocol-unverified")
                    navigate(FlymeVoiceDestination.Search(command.query))
                } else {
                    val name = command.targetTitle.ifBlank { command.query }
                    val target = if (command.kind.catalogKind == CatalogKind.Album) "专辑 $name" else name
                    return@start playVoiceSelection(playback, target) { lookupVoiceSearch(command) }
                }
            }
            is ListSource -> return@start playVoiceSelection(playback, command.label) {
                when (command) {
                    ListSource.Favorites -> {
                        val playlist = findVoiceFavorites(content)
                        Log.i(TAG, "path=favorites selected=${playlist?.title}")
                        playlist?.let { voiceCollection(it) }
                    }
                    ListSource.History -> {
                        val tracks = history.entries.value.map { it.toTrack() }.filter { it.catalogId != null }
                        Log.i(TAG, "path=history queueSize=${tracks.size}")
                        FlymeVoiceQueue(tracks, spokenName = command.label)
                    }
                    ListSource.Recommendations -> {
                        val home = content.state.value.shelvesFor(Destination.Home)
                        val item = voiceRecommendation(home) ?: voiceRecommendation(recommendationShelves()).also {
                            Log.i(TAG, "path=recommendation fallback=fresh-home-shelves selected=${it?.title}")
                        }
                        Log.i(TAG, "path=recommendation selected=${item?.title} kind=${item?.catalogKind}")
                        item?.let { voiceCollection(it) }
                    }
                    ListSource.Chart -> {
                        val tracks = charts("songs", browseFallback = false).filter { it.catalogKind == CatalogKind.Song }
                        Log.i(TAG, "path=storefront-chart queueSize=${tracks.size}")
                        FlymeVoiceQueue(tracks, spokenName = command.label)
                    }
                }
            }
            is Style -> return@start playVoiceSelection(playback, command.name.ifBlank { "音乐风格" }) {
                val playlist = findVoiceStylePlaylist(command)
                if (playlist != null) {
                    Log.i(TAG, "path=library-playlist style=${command.name} origin=${command.originInfo} selected=${playlist.title}")
                    voiceCollection(playlist)
                } else {
                    val item = search(command.name).firstOrNull(::isVoiceCollection)
                    Log.i(TAG, "path=style fallback=library-playlist-not-found style=${command.name} selected=${item?.title} kind=${item?.catalogKind}")
                    item?.let { voiceCollection(it) }
                }
            }
            is Ignored -> Unit
        }
        null
    }
}

private suspend fun MusicServices.lookupVoiceSearch(command: Search): FlymeVoiceQueue =
    resolveFlymeVoiceSearch(
        command,
        search = ::search,
        artistTopSongs = { catalogArtistPage(it).topSongs },
        collectionTracks = ::collectionTracks,
        catalogAlbum = api::catalogAlbumForVoice,
        log = { Log.i(TAG, it) },
        librarySearch = { term, collection ->
            try {
                api.searchLibraryForVoice(term, collection)
            } catch (error: CatalogRequestException) {
                Log.w(TAG, "kind=${command.kind} fallback=library-search-${error.reason} using=catalog")
                emptyList()
            } catch (error: IOException) {
                Log.w(TAG, "kind=${command.kind} fallback=library-search-io using=catalog", error)
                emptyList()
            }
        },
    )

private suspend fun MusicServices.playVoiceSelection(
    playback: PlaybackStore,
    target: String,
    resolve: suspend () -> FlymeVoiceQueue?,
): FlymeVoiceFeedback {
    val missing = FlymeVoiceFeedback.notFound(target)
    if (connectionState != MusicConnectionState.SignedIn) {
        Log.i(TAG, "path=not-signed-in target=$target")
        return missing
    }
    val result = try {
        withContext(Dispatchers.IO) { api.withAccount { resolve() } }
    } catch (error: CatalogRequestException) {
        Log.w(TAG, "path=lookup-failed target=$target reason=${error.reason}")
        return missing
    } catch (error: IOException) {
        Log.w(TAG, "path=lookup-failed target=$target", error)
        return missing
    }
    val first = result?.tracks?.firstOrNull()
    if (result == null || first == null) return missing
    if (result.collection != null) playback.playCollection(result.collection, result.tracks, index = 0, shuffle = false)
    else playback.playTrack(first, if (result.tracks.size > 1) result.tracks else emptyList())
    val started = withTimeoutOrNull(15_000L) {
        playback.state.first { state ->
            state.isPlaying && if (first.catalogKind == CatalogKind.Station) {
                playback.queueController.state.value.context?.id == first.catalogId
            } else state.track.matchesPlaybackQueueEntry(first)
        }
    }
    if (started == null) {
        Log.w(TAG, "path=playback-unconfirmed target=$target")
        return FlymeVoiceFeedback("无法播放 $target", failure = true)
    }
    Log.i(TAG, "path=playback-confirmed target=$target")
    return result.spokenName?.let(FlymeVoiceFeedback::playing) ?: FlymeVoiceFeedback.track(first)
}

private fun isVoiceCollection(track: Track): Boolean =
    track.catalogKind == CatalogKind.Playlist || track.catalogKind == CatalogKind.Station

private fun voiceRecommendation(shelves: List<ContentShelf>): Track? =
    (shelves.firstOrNull { it.id == "top-picks" }?.items.orEmpty().asSequence() +
        shelves.asSequence().filterNot { it.id == "top-picks" || it.id == "recently-played" }
            .flatMap { it.items.asSequence() })
        .firstOrNull(::isVoiceCollection)

private suspend fun MusicServices.voiceCollection(item: Track): FlymeVoiceQueue =
    if (item.catalogKind == CatalogKind.Station) FlymeVoiceQueue(listOf(item), spokenName = item.title)
    else FlymeVoiceQueue(collectionTracks(item), collection = item, spokenName = item.title)

private suspend fun MusicServices.findVoiceStylePlaylist(command: Style): Track? {
    if (command.name.isBlank() && command.originInfo.isBlank()) return null
    val playlists = mutableListOf<Track>()
    var next: String? = null
    do {
        val page = libraryPlaylistsPage(next)
        playlists += page.items
        next = page.nextPage
    } while (next != null)
    return matchVoiceStylePlaylist(command, playlists)
}

private suspend fun MusicServices.findVoiceFavorites(content: ContentStore): Track? {
    content.state.value.shelvesFor(Destination.Library).asSequence().flatMap { it.items.asSequence() }
        .firstOrNull(Track::isVoiceFavorites)?.let { return it }
    var next: String? = null
    repeat(8) {
        val page = libraryPlaylistsPage(next)
        page.items.firstOrNull(Track::isVoiceFavorites)?.let { return it }
        next = page.nextPage ?: return null
    }
    Log.i(TAG, "path=favorites fallback=playlist-page-limit")
    return null
}

internal fun Track.isVoiceFavorites(): Boolean =
    catalogKind == CatalogKind.Playlist && title.trim().lowercase() in favoritePlaylistNames

private val favoritePlaylistNames = setOf("favorite songs", "favourite songs", "喜好歌曲",
    "喜爱歌曲", "喜愛歌曲", "喜爱的歌曲", "喜愛的歌曲", "最爱的歌曲", "最愛的歌曲", "お気に入りの曲")
