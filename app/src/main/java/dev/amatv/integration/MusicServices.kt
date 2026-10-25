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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val tokenStore = SecureTokenStore(appContext)
    private val developerTokens = DeveloperTokenClient(
        endpoint = BuildConfig.MUSICKIT_TOKEN_ENDPOINT,
        tokenStore = tokenStore,
        bundledToken = BuildConfig.MUSICKIT_DEVELOPER_TOKEN,
    )
    private val bridge = MusicKitBridge(appContext)
    private val songSpectrum = SongAudioSpectrum(appContext, bridge::audioSessionId)
    private val mediaSession = PlaybackMediaSession(appContext)
    private val playbackPreparation = Mutex()
    private val appleLyrics = AppleMusicLyricsClient(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        userToken = tokenStore::readUserToken,
        debugDumpDir = if (BuildConfig.DEBUG) java.io.File(appContext.cacheDir, "lyrics-ttml") else null,
        contentCache = contentCache,
    )
    private val api = AppleMusicApi(
        storefront = BuildConfig.APPLE_MUSIC_STOREFRONT,
        developerTokens = developerTokens,
        userToken = tokenStore::readUserToken,
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
        val developerToken = developerTokens.token()
        return Intent(appContext, MusicKitWebAuthActivity::class.java).putExtra(
            MusicKitWebAuthActivity.EXTRA_DEVELOPER_TOKEN,
            developerToken,
        )
    }

    fun completeWebAuthentication(data: Intent): String {
        val userToken = checkNotNull(
            data.getStringExtra(MusicKitWebAuthActivity.EXTRA_USER_TOKEN),
        ) { AppText.text(R.string.services_auth_invalid_user_token) }
        tokenStore.writeUserToken(userToken)
        mediaSession.clear()
        bridge.release()
        return userToken
    }

    fun signOut() {
        songSpectrum.setActive(false)
        mediaSession.clear()
        bridge.release()
        tokenStore.clear()
    }

    suspend fun recommendations(): List<Track> = api.recommendations()
    suspend fun recommendationShelves(): List<ContentShelf> = api.recommendationShelves()
    suspend fun recentlyPlayed(): List<Track> = api.recentlyPlayed()
    suspend fun recentlyPlayedTracks(): List<Track> = api.recentlyPlayedTracks()
    suspend fun recentlyAdded(): List<Track> = api.recentlyAdded()
    suspend fun libraryPlaylists(): List<Track> = api.libraryPlaylists()
    suspend fun librarySongs(): List<Track> = api.librarySongs()
    suspend fun charts(types: String = "albums,songs,playlists"): List<Track> =
        api.charts(types)
    suspend fun liveRadio(): List<Track> = api.liveRadio()
    suspend fun search(term: String): List<Track> = api.search(term)
    override suspend fun createAmStation(songId: String): Pair<String?, List<Track>> =
        api.createContinuousStation(songId)
    override suspend fun amStationNextTracks(stationId: String): List<Track> =
        api.stationNextTracks(stationId)
    suspend fun collectionTracks(collection: Track): List<Track> = api.collectionTracks(collection)

    suspend fun lyricsFor(track: Track): List<LyricLine> {
        val rawCatalogId = checkNotNull(track.catalogId) { AppText.text(R.string.services_catalog_id_missing) }
        val resolvedCatalogId = if (rawCatalogId.startsWith("i.")) {
            api.resolveCatalogSongId(rawCatalogId)
        } else {
            rawCatalogId
        }
        return appleLyrics.lyricsFor(resolvedCatalogId)
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
                if (playWhenReady) mediaSession.playbackRequested()
                bridge.preparePlayback(track, developerToken, userToken, positionMs, playWhenReady)
            }
        }
    }

    fun audioSpectrum(): AudioSpectrum = songSpectrum.sample()
    fun setSpectrumActive(enabled: Boolean) = songSpectrum.setActive(enabled)

    fun play() {
        mediaSession.playbackRequested()
        bridge.play()
    }
    fun pause() = bridge.pause()
    fun seekTo(positionMs: Long) = bridge.seekTo(positionMs)
    fun skipToNext() = bridge.skipToNext()
    fun skipToPrevious() = bridge.skipToPrevious()
    fun skipToQueueItem(queueId: Long) = bridge.skipToQueueItem(queueId)
    // The app queue owns repeat, shuffle and autoplay. Each native queue contains
    // one selected track, so enabling a second queue engine would race the app.
    fun playbackSnapshot(): PlaybackSnapshot? = bridge.playbackSnapshot()
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?) =
        bridge.setPlaybackListener(listener)
    fun setSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?) =
        mediaSession.setCallbacks(callbacks)
    fun publishSystemPlayback(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean) =
        mediaSession.publish(snapshot, canSkipNext, canSkipPrevious)
    fun release() {
        songSpectrum.release()
        mediaSession.release()
        bridge.release()
    }
}
