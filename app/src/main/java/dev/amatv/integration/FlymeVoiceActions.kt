package dev.amatv.integration

import android.os.SystemClock
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
    flymeVoice?.startWithFeedback(scope) { command, announce ->
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
            } else return@startWithFeedback FlymeVoiceFeedback.notFound("当前歌曲")
            is Seek -> if (current.hasCurrentTrack) {
                if (command.relative) playback.seekPlaybackBy(command.milliseconds)
                else playback.seekPlaybackTo(command.milliseconds)
            } else return@startWithFeedback FlymeVoiceFeedback.notFound("当前歌曲")
            is Mode -> playback.dispatchQueueAction(StartupQueueAction.SetMode(command.repeat, command.shuffle))
            is Page -> navigate(FlymeVoiceDestination.NowPlaying(command == Page.Lyrics))
            is Info -> return@startWithFeedback FlymeVoiceFeedback.info(command, current.track.takeIf { current.hasCurrentTrack })
            is Search -> {
                if (command.searchOnly) {
                    Log.i(TAG, "path=search-page query=${command.query} fallback=selection-protocol-unverified")
                    navigate(FlymeVoiceDestination.Search(command.query))
                } else {
                    val name = command.targetTitle.ifBlank { command.query }
                    val target = if (command.kind.catalogKind == CatalogKind.Album) "专辑 $name" else name
                    return@startWithFeedback playVoiceSelection(playback, target, announce) { matched ->
                        lookupVoiceSearch(command, matched)
                    }
                }
            }
            is ListSource -> return@startWithFeedback playVoiceSelection(playback, command.label, announce) { matched ->
                when (command) {
                    ListSource.Favorites -> {
                        val playlist = findVoiceFavorites(content)
                        Log.i(TAG, "path=favorites selected=${playlist?.title}")
                        playlist?.let { voiceCollection(it, matched) }
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
                        item?.let { voiceCollection(it, matched) }
                    }
                    ListSource.Chart -> {
                        val tracks = charts("songs", browseFallback = false).filter { it.catalogKind == CatalogKind.Song }
                        Log.i(TAG, "path=storefront-chart queueSize=${tracks.size}")
                        FlymeVoiceQueue(tracks, spokenName = command.label)
                    }
                }
            }
            is Style -> return@startWithFeedback playVoiceSelection(
                playback, command.name.ifBlank { "音乐风格" }, announce,
            ) { matched ->
                val library = findVoiceStylePlaylist(command)
                val item = library ?: matchVoiceStylePlaylist(command, api.searchVoiceCollections(
                    command.name.ifBlank { command.originInfo },
                ))
                Log.i(TAG, "path=style source=${if (library != null) "library" else "catalog"} " +
                    "style=${command.name} selected=${item?.title} kind=${item?.catalogKind}")
                item?.let { voiceCollection(it, matched) }
            }
            is Ignored -> Unit
        }
        null
    }
}

private suspend fun MusicServices.lookupVoiceSearch(
    command: Search, onMatch: (FlymeVoiceFeedback) -> Unit,
): FlymeVoiceQueue =
    resolveFlymeVoiceSearch(
        command,
        search = ::search,
        artistTopSongs = api::artistTopSongsForVoice,
        collectionTracks = ::collectionTracks,
        catalogAlbum = api::catalogAlbumForVoice,
        log = { Log.i(TAG, it) },
        onMatch = onMatch,
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
    announce: (FlymeVoiceFeedback) -> Unit,
    resolve: suspend ((FlymeVoiceFeedback) -> Unit) -> FlymeVoiceQueue?,
): FlymeVoiceFeedback? {
    val missing = FlymeVoiceFeedback.notFound(target)
    if (connectionState != MusicConnectionState.SignedIn) {
        Log.i(TAG, "path=not-signed-in target=$target")
        return missing
    }
    val receivedAt = SystemClock.elapsedRealtime()
    var matchedAt: Long? = null
    val matched: (FlymeVoiceFeedback) -> Unit = { feedback ->
        val now = SystemClock.elapsedRealtime()
        matchedAt = now
        Log.i(TAG, "stage=matched elapsedMs=${now - receivedAt} target=$target")
        announce(feedback)
    }
    val failure = FlymeVoiceFeedback("无法播放 $target", failure = true)
    val result = try {
        withContext(Dispatchers.IO) { api.withAccount { resolve(matched) } }
    } catch (error: CatalogRequestException) {
        Log.w(TAG, "path=lookup-failed target=$target reason=${error.reason}")
        return if (matchedAt == null) missing else failure
    } catch (error: IOException) {
        Log.w(TAG, "path=lookup-failed target=$target", error)
        return if (matchedAt == null) missing else failure
    }
    val first = result?.tracks?.firstOrNull()
    if (result == null || first == null) return if (matchedAt == null) missing else failure
    if (matchedAt == null) matched(result.spokenName?.let(FlymeVoiceFeedback::playing) ?: FlymeVoiceFeedback.track(first))
    val firstBatchAt = SystemClock.elapsedRealtime()
    Log.i(TAG, "stage=first-batch elapsedMs=${firstBatchAt - receivedAt} " +
        "sinceMatchMs=${firstBatchAt - checkNotNull(matchedAt)} count=${result.tracks.size} target=$target")
    val oldSession = playback.queueController.state.value.sessionId
    if (result.collection != null) playback.playCollection(result.collection, result.tracks, index = 0, shuffle = false)
    else playback.playTrack(first, if (result.tracks.size > 1) result.tracks else emptyList())
    val requestedAt = SystemClock.elapsedRealtime()
    Log.i(TAG, "stage=play-requested elapsedMs=${requestedAt - receivedAt} target=$target")
    val started = withTimeoutOrNull(15_000L) {
        playback.state.first { state ->
            val queue = playback.queueController.state.value
            queue.sessionId != oldSession && state.isPlaying &&
                (state.snapshot?.positionMs ?: 0L) > 0L && if (first.catalogKind == CatalogKind.Station) {
                queue.context?.id == first.catalogId
            } else {
                queue.current?.track?.matchesPlaybackQueueEntry(state.track) == true &&
                    (queue.contextEntries.any { it.track.matchesPlaybackQueueEntry(state.track) } ||
                        result.tracks.any { state.track.matchesPlaybackQueueEntry(it) })
            }
        }
    }
    if (started == null) {
        Log.w(TAG, "path=playback-unconfirmed target=$target")
        return failure
    }
    val confirmedAt = SystemClock.elapsedRealtime()
    Log.i(TAG, "path=playback-confirmed stage=audible elapsedMs=${confirmedAt - receivedAt} " +
        "sinceRequestMs=${confirmedAt - requestedAt} id=${started.track.catalogId} target=$target")
    return started.track.takeIf { result.collection?.catalogKind == CatalogKind.Album }
        ?.let(FlymeVoiceFeedback::track)
}

private fun isVoiceCollection(track: Track): Boolean =
    track.catalogKind == CatalogKind.Playlist || track.catalogKind == CatalogKind.Station

private fun voiceRecommendation(shelves: List<ContentShelf>): Track? =
    (shelves.firstOrNull { it.id == "top-picks" }?.items.orEmpty().asSequence() +
        shelves.asSequence().filterNot { it.id == "top-picks" || it.id == "recently-played" }
            .flatMap { it.items.asSequence() })
        .firstOrNull(::isVoiceCollection)

private suspend fun MusicServices.voiceCollection(
    item: Track, matched: (FlymeVoiceFeedback) -> Unit,
): FlymeVoiceQueue {
    matched(FlymeVoiceFeedback.playing(item.title))
    return if (item.catalogKind == CatalogKind.Station) FlymeVoiceQueue(listOf(item), spokenName = item.title)
    else FlymeVoiceQueue(collectionTracks(item), collection = item, spokenName = item.title)
}

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

internal val favoritePlaylistNames = setOf("favorite songs", "favourite songs", "喜好歌曲",
    "喜爱歌曲", "喜愛歌曲", "喜爱的歌曲", "喜愛的歌曲", "最爱的歌曲", "最愛的歌曲", "お気に入りの曲",
    "我喜欢的歌", "我喜歡的歌", "收藏的歌")
