package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.model.ContentShelf
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MusicConnectionState {
    Demo,
    ReadyToSignIn,
    SignedIn,
}

interface RecommendationProvider {
    suspend fun createAmStation(songId: String): Pair<String?, List<Track>>
    suspend fun amStationNextTracks(stationId: String): List<Track>
    suspend fun ytmRadio(
        seed: Track,
        continuation: String? = null,
        excludedCatalogIds: Set<String> = emptySet(),
        continuationSeedVideoId: String? = null,
    ): MusicServices.ResolvedRadioPage
}

class MusicServices(context: Context) : RecommendationProvider {
    private val appContext = context.applicationContext
    val contentCache = ContentCache.get(appContext)
    val playbackDiagnostics = PlaybackDiagnostics.get(appContext)
    val playbackOptions = FlymePlaybackOptions.get(appContext)
    /** Call off the main thread: whether the system Widevine lets the web player play stations. */
    val widevineReady: Boolean by lazy { checkWidevine().first }
    private val tokenStore = SecureTokenStore(appContext)
    private val webAuthenticationSession = WebAuthenticationSession(appContext)
    private val developerTokens = DeveloperTokenClient(
        endpoint = BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        tokenStore = tokenStore,
        bundledToken = BuildConfig.MUSICKIT_DEVELOPER_TOKEN,
    )
    private val bridge = MusicKitBridge(appContext)
    // Stations the native SDK cannot queue; null in variants without a second engine.
    private val stationEngine = StationPlaybackEngine.create(appContext)
    @Volatile private var stationEngineActive = false
    private val songSpectrum = SongAudioSpectrum(appContext, bridge::audioSessionId)
    private val mediaSession = PlaybackMediaSession(appContext)
    private val flymeMediaSource = if (BuildConfig.FLYME_NATIVE_MEDIA) FlymeMediaSource(appContext) else null
    val nativeMediaStatus get() = flymeMediaSource?.status
    private val playbackPreparation = Mutex()
    private val shelfSnapshots = ShelfSnapshots(appContext)
    private val detailPageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val collectionPages = DetailPageCache<DetailKey, List<Track>>(detailPageScope, maxEntries = 32)
    private val artistPages = DetailPageCache<DetailKey, CatalogArtistPage>(detailPageScope, maxEntries = 32)
    private val artistLibraryPages = DetailPageCache<Pair<DetailKey, String?>, LibraryPage>(detailPageScope, maxEntries = 64)
    private val artistViewPages = DetailPageCache<String, List<Track>>(detailPageScope, maxEntries = 16)

    private data class DetailKey(val type: String, val id: String)
    private fun Track.detailKey() = DetailKey(resourceType ?: catalogKind.name, resourceId ?: catalogId ?: id)
    private val api = AppleMusicApi(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        developerTokens = developerTokens,
        userToken = tokenStore::readUserToken,
        storefrontPreferences = appContext.getSharedPreferences("am_atv_storefront", android.content.Context.MODE_PRIVATE),
    )
    private val appleLyrics = AppleMusicLyricsClient(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        storefrontProvider = api::userStorefront,
        userToken = tokenStore::readUserToken,
        debugDumpDir = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "lyrics-ttml") else null,
        contentCache = contentCache,
    )
    private val google = GoogleTvAuthorization(tokenStore)
    private val ytm = YtmInnertubeClient(google::accessToken)
    private val resolver = CrossCatalogResolver(api)

    val ytmPairingConfigured get() = google.configured
    val ytmSignedIn get() = google.signedIn
    suspend fun startYtmPairing(): GoogleTvPairing = google.startPairing()
    suspend fun awaitYtmPairing(pairing: GoogleTvPairing) = google.awaitPairing(pairing)
    fun signOutYtm() = google.signOut()

    data class ResolvedRadioPage(
        val tracks: List<Track>,
        val continuation: String?,
        val seedVideoId: String?,
    )

    private suspend fun resolveRecommendation(song: YtmSong): Track? = try {
        resolver.resolve(song)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w("AMATV-YTM", "Skipping recommendation ${song.videoId}", error)
        null
    }

    override suspend fun ytmRadio(
        seed: Track,
        continuation: String?,
        excludedCatalogIds: Set<String>,
        continuationSeedVideoId: String?,
    ): ResolvedRadioPage {
        val saved = YtmResolutionCursor.decode(continuation)
        val videoId = saved?.seedVideoId
            ?: continuationSeedVideoId?.takeIf { continuation != null }
            ?: seed.ytmVideoId ?: ytm.searchSong(seed.title, seed.artist, seed.durationMs)?.videoId
            ?: return ResolvedRadioPage(emptyList(), null, null)
        val candidates: List<YtmSong>
        val providerNext: String?
        if (saved != null && saved.pending.isNotEmpty()) {
            candidates = saved.pending
            providerNext = saved.providerContinuation
        } else {
            // A corrupt local cursor restarts the original seed instead of sending
            // application metadata as an undocumented provider token.
            val page = ytm.radio(videoId, continuation.takeUnless { YtmResolutionCursor.isLocal(it) })
            candidates = page.songs
            providerNext = page.continuation
        }
        // Resolve a bounded batch in provider order. Preserve every remaining song
        // in the persisted cursor rather than dropping everything after take(15).
        val batch = candidates.take(12)
        val resolved = coroutineScope {
            batch.map { song -> async { resolveRecommendation(song) } }.awaitAll()
                .filterNotNull().filter { it.catalogId !in excludedCatalogIds }
                .distinctBy { it.catalogId }
        }
        val next = YtmResolutionCursor(videoId, candidates.drop(batch.size), providerNext).encode()
        return ResolvedRadioPage(resolved, next, videoId)
    }

    suspend fun ytmHomeShelves(): List<ContentShelf> = coroutineScope {
        ytm.home().sortedByDescending { shelf ->
            when {
                shelf.title.contains("supermix", ignoreCase = true) -> 3
                shelf.title.contains("listen again", ignoreCase = true) -> 2
                else -> 1
            }
        }.take(3).mapIndexed { index, shelf ->
            async {
                val songs = if (shelf.playlistId != null && shelf.songs.isEmpty()) {
                    ytm.playlist(shelf.playlistId)
                } else shelf.songs
                val tracks = songs.take(6).map { song ->
                    async { resolveRecommendation(song) }
                }.awaitAll().filterNotNull()
                ContentShelf("ytm:$index", "YTM · ${shelf.title}", tracks)
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    val connectionState: MusicConnectionState
        get() = when {
            tokenStore.readUserToken() != null -> MusicConnectionState.SignedIn
            isCatalogConfigured ->
                MusicConnectionState.ReadyToSignIn
            else -> MusicConnectionState.Demo
        }

    val setupMessage: String?
        get() = when {
            !isCatalogConfigured -> AppText.text(R.string.services_catalog_not_configured)
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN.isNotBlank() &&
                !DeveloperTokenPolicy.isUsable(BuildConfig.MUSICKIT_DEVELOPER_TOKEN) &&
                BuildConfig.MUSICKIT_TOKEN_ENDPOINT.isBlank() -> AppText.text(R.string.services_credentials_expired_update)
            else -> null
        }

    val isCatalogConfigured: Boolean
        get() = DeveloperTokenPolicy.isConfigured(
            BuildConfig.MUSICKIT_DEVELOPER_TOKEN, BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        )

    suspend fun webAuthenticationIntent(): Intent {
        // Gecko owns a fresh private profile. Do not initialize the system
        // WebView provider on a compatibility build whose provider may be broken.
        if (!BuildConfig.AUTH_BROWSER_IS_GECKO) webAuthenticationSession.prepareForSignIn()
        val developerToken = developerTokens.token()
        return Intent().setClassName(appContext, BuildConfig.AUTH_BROWSER_ACTIVITY).putExtra(
            MusicKitWebAuthActivity.EXTRA_DEVELOPER_TOKEN,
            developerToken,
        )
    }

    fun completeWebAuthentication(data: Intent): String {
        val userToken = checkNotNull(
            data.getStringExtra(MusicKitWebAuthActivity.EXTRA_USER_TOKEN),
        ) { AppText.text(R.string.services_auth_invalid_user_token) }
        tokenStore.writeUserToken(userToken)
        clearDetailPages()
        mediaSession.clear()
        flymeMediaSource?.clear()
        bridge.release()
        releaseStationEngine()
        return userToken
    }

    /** Last saved shelves of a destination for this account and language, or null. */
    suspend fun savedShelves(name: String, languageTag: String): List<ContentShelf>? {
        val userToken = tokenStore.readUserToken()?.takeIf(String::isNotBlank) ?: return null
        return shelfSnapshots.read(name, userToken, languageTag)
    }

    suspend fun saveShelves(name: String, languageTag: String, shelves: List<ContentShelf>) {
        val userToken = tokenStore.readUserToken()?.takeIf(String::isNotBlank) ?: return
        shelfSnapshots.write(name, userToken, languageTag, shelves)
    }

    fun signOut() {
        shelfSnapshots.clear()
        webAuthenticationSession.markLocalWebSessionForReset()
        songSpectrum.setActive(false)
        mediaSession.clear()
        flymeMediaSource?.clear()
        bridge.release()
        releaseStationEngine()
        tokenStore.clear()
        clearDetailPages()
    }

    suspend fun recommendations(): List<Track> = api.recommendations()
    suspend fun recommendationShelves(): List<ContentShelf> = api.recommendationShelves()
    suspend fun recentlyPlayed(): List<Track> = api.recentlyPlayed()
    suspend fun recentlyPlayedTracks(): List<Track> = api.recentlyPlayedTracks()
    suspend fun recentlyAdded(): List<Track> = api.recentlyAdded()
    suspend fun libraryPlaylists(): List<Track> = api.libraryPlaylists()
    suspend fun libraryAlbums(): List<Track> = api.libraryAlbums()
    suspend fun libraryArtists(): List<Track> = api.libraryArtists()
    suspend fun librarySongs(): List<Track> = api.librarySongs()
    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage = api.libraryAlbumsPage(nextPage)
    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage = api.libraryArtistsPage(nextPage)
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage = api.libraryPlaylistsPage(nextPage)
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage = api.librarySongsPage(nextPage)
    suspend fun libraryArtistAlbumsPage(artist: Track, nextPage: String? = null): LibraryPage =
        artistLibraryPages.getOrLoad(artist.detailKey() to nextPage) { api.libraryArtistAlbumsPage(artist, nextPage) }
    fun cachedArtistAlbums(artist: Track): LibraryPage? = artistLibraryPages.peek(artist.detailKey() to null)
    suspend fun charts(types: String = "albums,songs,playlists"): List<Track> =
        api.charts(types)
    suspend fun liveRadio(): List<Track> = api.liveRadio()
    suspend fun search(term: String): List<Track> = api.search(term)
    override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> =
        api.createContinuousStation(songId)
    override suspend fun amStationNextTracks(stationId: String): List<Track> =
        api.stationNextTracks(stationId)
    suspend fun collectionTracks(collection: Track): List<Track> =
        collectionPages.getOrLoad(collection.detailKey()) { api.collectionTracks(collection) }
    fun cachedCollectionTracks(collection: Track): List<Track>? = collectionPages.peek(collection.detailKey())

    suspend fun catalogArtistPage(artist: Track): CatalogArtistPage =
        artistPages.getOrLoad(artist.detailKey()) { api.catalogArtistPage(artist) }
    fun cachedArtistPage(artist: Track): CatalogArtistPage? = artistPages.peek(artist.detailKey())

    suspend fun catalogArtistViewMore(moreHref: String): List<Track> =
        artistViewPages.getOrLoad(moreHref) { api.catalogArtistViewMore(moreHref) }

    fun clearDetailPages() {
        collectionPages.clear()
        artistPages.clear()
        artistLibraryPages.clear()
        artistViewPages.clear()
    }

    suspend fun catalogArtistOf(item: Track): Track? = api.catalogArtistOf(item)

    suspend fun isFavorite(catalogSongId: String): Boolean? = api.isFavorite(catalogSongId)

    suspend fun addFavorite(catalogSongId: String) {
        api.addFavorite(catalogSongId)
        collectionPages.clear()
    }

    suspend fun catalogAlbumOf(song: Track): Track? = api.catalogAlbumOf(song)

    suspend fun lyricsFor(
        track: Track,
        localization: LyricsLocalizationRequest = LyricsLocalizationRequest.Disabled,
    ): List<LyricLine> {
        val playable = resolvePlayableTrack(track)
        return appleLyrics.lyricsFor(checkNotNull(playable.catalogId), localization)
    }

    /** Resolve all songs through account-storefront equivalence, retaining source provenance. */
    suspend fun resolvePlayableTrack(track: Track): Track {
        if (track.catalogKind != dev.amatv.model.CatalogKind.Song) return track
        checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
        return resolvePlayableSongTrack(track, api::resolveCatalogSongId)
    }

    suspend fun prepareAndPlay(track: Track): Long = preparePlayback(track)

    suspend fun preparePlayback(
        track: Track,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Long {
        val userToken = checkNotNull(tokenStore.readUserToken()) { AppText.text(R.string.services_sign_in_apple_music) }
        val developerToken = developerTokens.token()
        return playbackPreparation.withLock {
            withContext(Dispatchers.Default) {
                if (playWhenReady) {
                    mediaSession.playbackRequested()
                    flymeMediaSource?.playbackRequested()
                }
                val station = stationEngine?.takeIf { track.catalogKind == dev.amatv.model.CatalogKind.Station }
                if (station != null) {
                    // One audible engine at a time: silence the native queue before the web one starts.
                    if (!stationEngineActive) bridge.pause()
                    stationEngineActive = true
                    station.prepare(track, developerToken, userToken, playWhenReady)
                } else {
                    if (stationEngineActive) stationEngine?.stop()
                    stationEngineActive = false
                    bridge.preparePlayback(track, developerToken, userToken, positionMs, playWhenReady)
                }
            }
        }
    }

    fun audioSpectrum(): AudioSpectrum = songSpectrum.sample()
    fun setSpectrumActive(enabled: Boolean) = songSpectrum.setActive(enabled)

    fun play() {
        mediaSession.playbackRequested()
        flymeMediaSource?.playbackRequested()
        if (stationEngineActive) stationEngine?.play() else bridge.play()
    }
    fun pause() {
        mediaSession.playbackPaused()
        flymeMediaSource?.playbackPaused()
        if (stationEngineActive) stationEngine?.pause() else bridge.pause()
    }
    // Station streams are never seekable; MusicKitBridge also ignores live seeks.
    fun seekTo(positionMs: Long) { if (!stationEngineActive) bridge.seekTo(positionMs) }
    fun skipToNext() { if (stationEngineActive) stationEngine?.skipToNext() else bridge.skipToNext() }
    fun skipToPrevious() { if (!stationEngineActive) bridge.skipToPrevious() }
    fun skipToQueueItem(queueId: Long) = bridge.skipToQueueItem(queueId)
    /** Gapless preloading of the app's next song; stations manage their own queue. */
    fun setLookahead(next: Track?) { if (!stationEngineActive) bridge.setLookahead(next) }
    fun setFadeEnabled(enabled: Boolean) { bridge.fadeEnabled = enabled }
    /** A station's next songs, to play it on the native player without Widevine. */
    suspend fun stationSongs(stationId: String): List<Track> = api.stationNextTracks(stationId)
    fun skipToQueuedLookahead(catalogId: String): Boolean = !stationEngineActive && bridge.skipToQueuedLookahead(catalogId)
    // The app queue owns repeat, shuffle and autoplay. Each native queue contains
    // one selected track, so enabling a second queue engine would race the app.
    fun playbackSnapshot(): PlaybackSnapshot? =
        if (stationEngineActive) stationEngine?.playbackSnapshot() else bridge.playbackSnapshot()
    // Both engines report to the same listener; the app drops stale generations.
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        bridge.setPlaybackListener(listener)
        stationEngine?.setPlaybackListener(listener)
    }
    fun setSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?) {
        mediaSession.setCallbacks(callbacks)
        flymeMediaSource?.setCallbacks(callbacks)
    }
    fun publishSystemPlayback(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean) {
        mediaSession.publish(snapshot, canSkipNext, canSkipPrevious)
        flymeMediaSource?.publish(snapshot.copy(canSkipNext = canSkipNext, canSkipPrevious = canSkipPrevious))
    }
    fun release() {
        clearDetailPages()
        detailPageScope.cancel()
        songSpectrum.release()
        mediaSession.release()
        flymeMediaSource?.release()
        bridge.release()
        releaseStationEngine()
    }

    private fun releaseStationEngine() {
        stationEngineActive = false
        stationEngine?.release()
    }
}
