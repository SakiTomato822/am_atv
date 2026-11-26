package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.util.Log
import android.os.SystemClock
import dev.amatv.BuildConfig
import dev.amatv.model.AppleArtworkColors
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import dev.amatv.model.fallbackTrackPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

internal const val SEARCH_LOG_TAG = "AMATV-Search"
private const val SEARCH_RESOURCE_TYPES = "albums,artists,music-videos,playlists,songs,stations"
internal const val BROWSE_LOG_TAG = "AMATV-Browse"
internal const val BROWSE_PERSONAL_RELEASES_ID = "browse-personal-new-releases"

internal data class CatalogSearchTerm(val displayTerm: String, val searchTerm: String)

internal data class CatalogSearchSuggestions(
    val terms: List<CatalogSearchTerm>,
    val topResults: List<Track>,
)

class DeveloperTokenClient(
    private val endpoint: String,
    private val tokenStore: SecureTokenStore? = null,
    private val bundledToken: String = "",
) {
    @Volatile
    private var cachedToken: String? = null

    suspend fun token(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        // A distributable APK carries a signed, expiring developer token, never its signing key
        // or a user's token. Prefer it over a short-lived cache from an earlier LAN build.
        if (DeveloperTokenPolicy.isUsable(bundledToken)) return@withContext bundledToken
        if (!forceRefresh) {
            cachedToken?.takeIf(::isJwtValid)?.let { return@withContext it }
            tokenStore?.readDeveloperToken()?.takeIf(::isJwtValid)?.let { stored ->
                cachedToken = stored
                return@withContext stored
            }
        } else {
            cachedToken = null
            tokenStore?.clearDeveloperToken()
        }

        check(endpoint.isNotBlank()) {
            if (bundledToken.isNotBlank()) AppText.text(R.string.services_credentials_expired_update)
            else AppText.text(R.string.services_full_version_required)
        }
        val secureEndpoint = endpoint.startsWith("https://")
        val debugLanEndpoint = BuildConfig.DEBUG && endpoint.startsWith("http://")
        check(secureEndpoint || debugLanEndpoint) {
            AppText.text(R.string.services_token_https_required)
        }
        val value = runCatching {
            val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 6_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json, text/plain")
                val status = connection.responseCode
                val body = (if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
                check(status in 200..299) { AppText.text(R.string.services_token_http_error, status) }
                val parsed = if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    json.optString("developerToken").ifBlank { json.optString("token") }
                } else {
                    body
                }
                check(isJwtValid(parsed)) { AppText.text(R.string.services_token_invalid) }
                parsed
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            throw IllegalStateException(
                AppText.text(R.string.services_token_connection_failed, endpoint, error.message ?: error.javaClass.simpleName),
                error,
            )
        }

        cachedToken = value
        tokenStore?.writeDeveloperToken(value)
        value
    }

    private fun isJwtValid(token: String): Boolean = DeveloperTokenPolicy.isUsable(token)
}

class AppleMusicApi(
    private val storefront: String,
    private val developerTokens: DeveloperTokenClient,
    /** Keeps the account storefront across launches, so cold-start catalog requests need not wait for it. */
    private val storefrontPreferences: android.content.SharedPreferences? = null,
    private val libraryMappingPreferences: android.content.SharedPreferences? = null,
    private val userToken: () -> String?,
) : dev.amatv.core.LibraryActionsApi {
    private class Account(val token: String?, val revision: Long) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Account>
    }

    private val accountLock = Any()
    private val accountRevision = java.util.concurrent.atomic.AtomicLong()

    private fun captureAccount(): Account = synchronized(accountLock) {
        Account(userToken()?.takeIf(String::isNotBlank), accountRevision.get())
    }

    private fun requireCurrent(account: Account) {
        if (account.revision != accountRevision.get()) {
            throw kotlinx.coroutines.CancellationException("Apple Music account changed")
        }
    }

    private suspend fun currentAccount(): Account = currentCoroutineContext()[Account] ?: captureAccount()

    internal suspend fun <T> withAccount(block: suspend () -> T): T {
        val account = currentAccount()
        requireCurrent(account)
        return withContext(account) {
            try {
                block()
            } finally {
                requireCurrent(account)
            }
        }
    }

    internal suspend fun <T> useCurrentUserToken(block: (String?) -> T): T {
        val account = currentAccount()
        currentCoroutineContext().ensureActive()
        return synchronized(accountLock) {
            requireCurrent(account)
            block(account.token)
        }
    }

    internal fun updateAccount(changeToken: () -> Unit) = synchronized(accountLock) {
        changeToken()
        accountRevision.incrementAndGet()
        userStorefrontCache = null
        catalogSongResolver.invalidate()
    }

    private data class UserStorefrontCache(val userToken: String, val storefront: String)

    private val parseDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val userStorefrontMutex = Mutex()
    private val catalogSongResolver = CatalogSongResolver(
        account = {
            val account = currentAccount()
            CatalogSongResolver.AccountSnapshot(account.token) { account.revision == accountRevision.get() }
        },
        storefront = ::userStorefront,
        libraryCatalogId = ::libraryCatalogSongId,
        equivalentCatalogId = ::equivalentSongId,
        authorizationMessage = { AppText.text(R.string.services_sign_in_apple_music) },
        readLibraryMapping = { token, storefront, id ->
            libraryMappingPreferences?.getString(libraryMappingKey(token, storefront, id), null)
        },
        saveLibraryMapping = { token, storefront, id, catalogId ->
            libraryMappingPreferences?.edit()?.putString(libraryMappingKey(token, storefront, id), catalogId)?.apply()
        },
    )

    private fun libraryMappingKey(token: String, storefront: String, id: String): String =
        "${storefrontTokenDigest(token)}:$storefront:$id"

    @Volatile
    private var userStorefrontCache: UserStorefrontCache? = null

    internal fun cachedUserStorefront(currentUserToken: String): String? {
        userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.let { return it.storefront }
        val digest = storefrontTokenDigest(currentUserToken)
        return storefrontPreferences?.takeIf { it.getString(KEY_STOREFRONT_TOKEN, null) == digest }
            ?.getString(KEY_STOREFRONT, null)?.takeIf { it.matches(Regex("[a-z]{2}")) }
    }

    suspend fun userStorefront(): String = withAccount {
        val account = currentAccount()
        val token = account.token ?: throw CatalogRequestException(CatalogFailureReason.Authorization,
            AppText.text(R.string.services_sign_in_apple_music))
        userStorefrontCache?.takeIf { it.userToken == token }?.let { return@withAccount it.storefront }
        userStorefrontMutex.withLock {
            requireCurrent(account)
            cachedUserStorefront(token)?.let { saved ->
                synchronized(accountLock) {
                    requireCurrent(account)
                    userStorefrontCache = UserStorefrontCache(token, saved)
                }
                return@withLock saved
            }
            val root = requestRoot(path = "/v1/me/storefront", needsUserToken = true)
            val resolved = parseUserStorefrontId(root)
                ?: error(AppText.text(R.string.services_user_storefront_missing))
            synchronized(accountLock) {
                requireCurrent(account)
                userStorefrontCache = UserStorefrontCache(token, resolved)
                storefrontPreferences?.edit()?.putString(KEY_STOREFRONT_TOKEN, storefrontTokenDigest(token))
                    ?.putString(KEY_STOREFRONT, resolved)?.apply()
            }
            Log.i(TAG, "Apple user storefront=$resolved")
            resolved
        }
    }

    private fun storefrontTokenDigest(token: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun effectiveStorefront(): String = withAccount {
        if (currentAccount().token == null) storefront else userStorefront()
    }

    suspend fun recommendations(): List<Track> = request(
        path = "/v1/me/recommendations?limit=10&extend=editorialArtwork,editorialVideo",
        needsUserToken = true,
    )

    private data class RecommendationSnapshot(val revision: Long, val loadedAtMs: Long, val root: JSONObject)
    private val recommendationMutex = Mutex()
    private var recommendationSnapshot: RecommendationSnapshot? = null

    private suspend fun recommendationRoot(): JSONObject = withAccount {
        val account = currentAccount()
        recommendationMutex.withLock {
            requireCurrent(account)
            recommendationSnapshot?.takeIf {
                it.revision == account.revision && SystemClock.elapsedRealtime() - it.loadedAtMs < 120_000L
            }?.root ?: requestRoot(
                // The web home shows about twenty groups; ten cut off genres, stations and artists.
                path = "/v1/me/recommendations?limit=${if (BuildConfig.FLYME_AUTO) 20 else 10}" +
                    "&extend=editorialArtwork,editorialVideo",
                needsUserToken = true,
            ).also { root ->
                requireCurrent(account)
                recommendationSnapshot = RecommendationSnapshot(account.revision, SystemClock.elapsedRealtime(), root)
            }
        }
    }

    suspend fun recommendationShelves(): List<ContentShelf> {
        val root = recommendationRoot()
        return withContext(parseDispatcher) {
            val groups = parseRecommendationGroups(root)
            val shelves = groups.map { it.shelf }.let { shelves ->
                if (!BuildConfig.FLYME_AUTO) shelves
                else listOfNotNull(topPicks(groups)) + shelves
            }
            val motionCount = shelves.sumOf { shelf ->
                shelf.items.count { it.motionArtworkUrl != null }
            }
            Log.i(TAG, "Home motion artwork: $motionCount/${shelves.sumOf { it.items.size }}")
            if (shelves.isNotEmpty()) return@withContext shelves
            val fallback = parseResources(root)
            if (fallback.isEmpty()) emptyList() else listOf(
                ContentShelf("recommendations", AppText.text(R.string.services_top_picks_for_you), fallback, featured = true),
            )
        }
    }

    private data class RecommendationGroup(val shelf: ContentShelf, val kind: String?)

    private fun parseRecommendationGroups(root: JSONObject): List<RecommendationGroup> = buildList {
        val recommendations = root.optJSONArray("data") ?: return@buildList
        for (index in 0 until recommendations.length()) {
            val recommendation = recommendations.optJSONObject(index) ?: continue
            val attributes = recommendation.optJSONObject("attributes")
            val contents = recommendation.optJSONObject("relationships")?.optJSONObject("contents") ?: continue
            val tracks = parseResources(contents)
            if (tracks.isNotEmpty()) {
                add(
                    RecommendationGroup(
                        shelf = ContentShelf(
                            id = "recommendation:${recommendation.optString("id", index.toString())}",
                            title = attributes?.displayText("title") ?: attributes?.displayText("name")
                                ?: AppText.text(R.string.services_recommended_for_you),
                            items = tracks,
                            featured = isEmpty() && !BuildConfig.FLYME_AUTO,
                        ),
                        kind = attributes?.optString("kind"),
                    ),
                )
            }
        }
    }

    private fun newReleaseGroupIndex(groups: List<RecommendationGroup>): Int =
        groups.indices.drop(1).firstOrNull { index ->
            val group = groups[index]
            group.kind != "recently-played" && group.shelf.items.all { it.catalogKind == CatalogKind.Album }
        } ?: -1

    suspend fun personalizedNewReleases(): ContentShelf? {
        val root = recommendationRoot()
        return withContext(parseDispatcher) { parsePersonalizedNewReleases(root) }
    }

    internal fun parsePersonalizedNewReleases(root: JSONObject): ContentShelf? {
        val groups = parseRecommendationGroups(root)
        return groups.getOrNull(newReleaseGroupIndex(groups))?.shelf?.copy(
            id = BROWSE_PERSONAL_RELEASES_ID,
            title = AppText.text(R.string.browse_new_releases_for_you),
            featured = false,
        )
    }

    /**
     * Apple Music Web's "Top Picks for You" is assembled by Apple's own web service. Rebuild it
     * from the public groups: the lead item of each, labelled with why it is there.
     */
    private fun topPicks(groups: List<RecommendationGroup>): ContentShelf? {
        val newReleaseIndex = newReleaseGroupIndex(groups)
        val picks = groups.mapIndexedNotNull { index, group ->
            if (group.kind == "recently-played") return@mapIndexedNotNull null
            val item = group.shelf.items.first()
            val reason = when {
                index == 0 -> R.string.services_pick_made_for_you
                group.shelf.items.all { it.catalogKind == CatalogKind.Station } -> R.string.services_pick_station
                index == newReleaseIndex -> R.string.services_pick_new_release
                else -> R.string.services_pick_for_you
            }
            item.copy(pickReason = AppText.text(reason))
        }.distinctBy { it.id }.take(8)
        return picks.takeIf { it.size >= 3 }?.let {
            ContentShelf("top-picks", AppText.text(R.string.services_top_picks_for_you), it, featured = true)
        }
    }

    internal suspend fun editorialBrowseShelves(languageTag: String, webTokens: WebDeveloperToken): List<ContentShelf> =
        withAccount {
            val storefront = effectiveStorefront()
            val path = editorialBrowsePath(storefront, languageTag)
            val musicUserToken = currentAccount().token
            repeat(2) {
                currentCoroutineContext().ensureActive()
                val token = webTokens.token(storefront)
                var rejected = false
                for (host in listOf("amp-api.music.apple.com", "amp-api-edge.music.apple.com")) {
                    requireCurrent(currentAccount())
                    val (status, shelves) = withContext(Dispatchers.IO) {
                        requestWebEditorial(host, path, token, musicUserToken)
                    }
                    if (shelves.isNotEmpty()) return@withAccount shelves
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        if (musicUserToken == null) {
                            rejected = true
                        } else {
                            requireCurrent(currentAccount())
                            val (publicStatus, publicShelves) = withContext(Dispatchers.IO) {
                                requestWebEditorial(host, path, token, null)
                            }
                            if (publicShelves.isNotEmpty()) return@withAccount publicShelves
                            rejected = rejected || publicStatus == HttpURLConnection.HTTP_UNAUTHORIZED
                        }
                    }
                }
                if (!rejected) return@withAccount emptyList()
                webTokens.invalidate(token)
            }
            emptyList()
        }

    private suspend fun requestWebEditorial(
        host: String,
        path: String,
        token: String,
        musicUserToken: String?,
    ): Pair<Int?, List<ContentShelf>> {
        currentCoroutineContext().ensureActive()
        val account = currentAccount()
        requireCurrent(account)
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Origin", APPLE_MUSIC_WEB_ORIGIN)
            put("Referer", "$APPLE_MUSIC_WEB_ORIGIN/")
            put("Accept", "application/json")
            if (musicUserToken != null) put("Music-User-Token", musicUserToken)
        }
        val log = "channel=web-editorial host=$host hasUserToken=${musicUserToken != null}"
        val (status, body) = try {
            getWebBrowseResource(URI("https://$host$path"), headers)
        } catch (error: IOException) {
            Log.w(BROWSE_LOG_TAG, "$log httpStatus=unavailable shelves=0 error=${error.javaClass.simpleName}")
            return null to emptyList()
        }
        requireCurrent(account)
        val shelves = if (status in 200..299) {
            try {
                withContext(parseDispatcher) { parseEditorialBrowseShelves(JSONObject(body)) }
            } catch (_: JSONException) {
                Log.w(BROWSE_LOG_TAG, "$log httpStatus=$status shelves=0 error=InvalidJson")
                return status to emptyList()
            }
        } else emptyList()
        Log.i(BROWSE_LOG_TAG, "$log httpStatus=$status shelves=${shelves.size}")
        return status to shelves
    }

    internal fun parseEditorialBrowseShelves(root: JSONObject): List<ContentShelf> {
        val grouping = root.optJSONArray("data")?.optJSONObject(0) ?: return emptyList()
        val tab = grouping.editorialRelationship("tabs").firstOrNull() ?: return emptyList()
        return tab.editorialRelationship("children").mapIndexedNotNull { index, element ->
            val attributes = element.optJSONObject("attributes") ?: return@mapIndexedNotNull null
            val resources = when (attributes.optString("editorialElementKind")) {
                "326", "327", "345", "387" -> element.editorialRelationship("contents")
                "316", "385", "488" -> element.editorialRelationship("children").flatMap { child ->
                    child.editorialRelationship("contents").take(1)
                }
                else -> return@mapIndexedNotNull null
            }
            val items = resources.mapNotNull { resource ->
                val type = resource.optString("type")
                val kind = when (type) {
                    "albums" -> CatalogKind.Album
                    "playlists" -> CatalogKind.Playlist
                    "songs" -> CatalogKind.Song
                    "stations" -> CatalogKind.Station
                    else -> return@mapNotNull null
                }
                val id = (resource.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                resource.optJSONObject("attributes")?.toTrack(id, kind, resourceType = type)
            }.toList()
            if (items.isEmpty()) return@mapIndexedNotNull null
            val id = element.optString("id").ifBlank { index.toString() }
            ContentShelf(
                id = "browse-editorial:$index:$id",
                title = attributes.displayText("name") ?: attributes.displayText("title").orEmpty(),
                items = items,
            )
        }.toList()
    }

    private fun JSONObject.editorialRelationship(name: String): Sequence<JSONObject> {
        val data = optJSONObject("relationships")?.optJSONObject(name)?.optJSONArray("data")
            ?: return emptySequence()
        return (0 until data.length()).asSequence().mapNotNull(data::optJSONObject)
    }

    internal fun editorialBrowsePath(storefront: String, languageTags: String): String {
        val language = editorialBrowseLanguage(languageTags)
        return "/v1/editorial/$storefront/groupings?name=music&platform=web&tabs=nonsubscriber" +
            "&l=${URLEncoder.encode(language, "UTF-8")}&extend=editorialArtwork"
    }

    internal fun editorialBrowseLanguage(languageTags: String): String {
        val firstTag = languageTags.substringBefore(',')
        return when (firstTag) {
            "zh-CN" -> "zh-Hans-CN"
            "zh-TW" -> "zh-Hant-TW"
            else -> firstTag
        }
    }

    suspend fun recentlyPlayed(): List<Track> = request(
        path = "/v1/me/recent/played?limit=16&extend=editorialArtwork,editorialVideo",
        needsUserToken = true,
    )

    suspend fun recentlyPlayedTracks(): List<Track> = request(
        path = "/v1/me/recent/played/tracks?limit=20&extend=editorialArtwork,editorialVideo",
        needsUserToken = true,
    )

    suspend fun recentlyAdded(): List<Track> = request(
        path = "/v1/me/library/recently-added?limit=24&include=catalog",
        needsUserToken = true,
    )

    suspend fun libraryPlaylists(): List<Track> = libraryPlaylistsPage().items

    suspend fun libraryAlbums(): List<Track> = libraryAlbumsPage().items

    suspend fun libraryArtists(): List<Track> = libraryArtistsPage().items

    suspend fun librarySongs(): List<Track> = librarySongsPage().items

    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Albums, nextPage) { requestRoot(it, needsUserToken = true) }

    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Artists, nextPage) { requestRoot(it, needsUserToken = true) }

    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Playlists, nextPage) { requestRoot(it, needsUserToken = true) }

    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage =
        libraryPage(LibraryCollection.Songs, nextPage) { requestRoot(it, needsUserToken = true) }

    suspend fun libraryArtistAlbumsPage(artist: Track, nextPage: String? = null): LibraryPage =
        libraryArtistAlbumsPage(artist, nextPage) { requestRoot(it, needsUserToken = true) }

    internal suspend fun libraryArtistAlbumsPage(
        artist: Track,
        nextPage: String? = null,
        fetchPage: suspend (String) -> JSONObject,
    ): LibraryPage = withContext(parseDispatcher) {
        val request = libraryArtistAlbumsRequest(artist)
        val path = nextPage?.let(request::nextPage) ?: request.firstPage
        currentCoroutineContext().ensureActive()
        val root = fetchPage(path)
        val data = checkNotNull(root.optJSONArray("data")) {
            AppText.text(R.string.services_playlist_tracks_unavailable)
        }
        // This relationship is specifically LibraryAlbums. Ignore catalog album
        // resources so an artist's catalog discography cannot masquerade as saved.
        val albums = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type != LibraryCollection.Albums.resourceType) continue
                val attributes = item.optJSONObject("attributes") ?: JSONObject()
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), CatalogKind.Album, linked, type)?.let(::add)
            }
        }
        val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
            ?.let(request::nextPage)
        check(next != path) { AppText.text(R.string.services_playlist_page_repeated) }
        LibraryPage(items = mergeLibraryPage(emptyList(), albums), nextPage = next, requestedPage = path)
    }

    internal suspend fun libraryPage(
        collection: LibraryCollection,
        nextPage: String? = null,
        fetchPage: suspend (String) -> JSONObject,
    ): LibraryPage = withContext(parseDispatcher) {
        val request = collection.request
        val path = nextPage?.let(request::nextPage) ?: request.firstPage
        currentCoroutineContext().ensureActive()
        val root = fetchPage(path)
        val data = checkNotNull(root.optJSONArray("data")) {
            AppText.text(R.string.services_playlist_tracks_unavailable)
        }
        // Only root resources belong to this page. Catalog relationships enrich
        // those resources; they must not become additional library shelf cards.
        val tracks = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type != collection.resourceType) continue
                // The API's resource id and type identify the library item. Keep
                // it visible even when Apple omits optional display attributes.
                val attributes = item.optJSONObject("attributes") ?: JSONObject()
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), checkNotNull(catalogKind(type)), linked, type)?.let(::add)
            }
        }
        val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
            ?.let(request::nextPage)
        check(next != path) { AppText.text(R.string.services_playlist_page_repeated) }
        LibraryPage(items = mergeLibraryPage(emptyList(), tracks), nextPage = next, requestedPage = path)
    }

    /**
     * The catalog side of a saved artist: top songs, albums and singles, so the
     * artist page is not limited to what the user happened to save.
     */
    suspend fun catalogArtistPage(artist: Track): CatalogArtistPage = withAccount {
        val catalogId = catalogArtistId(artist) ?: return@withAccount CatalogArtistPage()
        val storefront = effectiveStorefront()
        val root = requestRoot(
            "/v1/catalog/$storefront/artists/$catalogId?views=${ARTIST_VIEWS.joinToString(",") { it.first }}" +
                "&limit[views]=20",
            needsUserToken = false,
        )
        val page = withContext(parseDispatcher) { parseArtistPage(root, catalogId) }
        return@withAccount expandArtistTopSongs(root, page)
    }

    internal suspend fun artistTopSongsForVoice(artist: Track): List<Track> = withAccount {
        val catalogId = catalogArtistId(artist) ?: return@withAccount emptyList()
        request("/v1/catalog/${effectiveStorefront()}/artists/$catalogId/view/top-songs?limit=20", false)
    }

    private suspend fun catalogArtistId(artist: Track): String? {
        val id = if (artist.resourceType == LibraryCollection.Artists.resourceType) {
            val libraryId = checkNotNull(artist.resourceId) { "Library artist ID is missing" }
            require(libraryId.matches(Regex("[A-Za-z0-9._-]+"))) { "Library artist ID is invalid" }
            val linked = requestRoot("/v1/me/library/artists/$libraryId/catalog", needsUserToken = true)
            linked.optJSONArray("data")?.optJSONObject(0)?.optString("id")
        } else artist.catalogId
        return id?.takeIf { it.matches(Regex("[0-9]+")) }
    }

    private suspend fun expandArtistTopSongs(root: JSONObject, page: CatalogArtistPage): CatalogArtistPage {
        // Top songs come ten at a time; Apple Music Web lists far more.
        var next = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("views")
            ?.optJSONObject("top-songs")?.optString("next")?.takeIf { it.startsWith("/v1/") }
        val more = mutableListOf<Track>()
        var complete = true
        for (index in 0 until 2) {
            val href = next ?: break
            val pageRoot = try {
                requestRoot(href, needsUserToken = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                complete = false
                break
            }
            more += withContext(parseDispatcher) { parseResources(pageRoot) }
            next = pageRoot.optString("next").takeIf { it.startsWith("/v1/") }
        }
        return page.copy(topSongs = (page.topSongs + more).distinctBy { it.id }, complete = complete)
    }

    /**
     * Whether a catalog song is in Favorite Songs; null when unknown. Favorites are their own list
     * since iOS 17.1 (not the old "love" rating), read through the song's inFavorites attribute.
     */
    override suspend fun isFavorite(catalogSongId: String): Boolean? = withAccount {
        require(catalogSongId.matches(Regex("[0-9]+"))) { "Catalog song ID is invalid" }
        val root = runCatching {
            requestRoot("/v1/catalog/${effectiveStorefront()}/songs/$catalogSongId?extend=inFavorites",
                needsUserToken = true)
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            return@withAccount null
        }
        val attributes = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: return@withAccount null
        return@withAccount if (attributes.has("inFavorites")) attributes.optBoolean("inFavorites") else null
    }

    /**
     * Adds the song to Favorite Songs, as the star in Apple Music does. Removing is not offered:
     * the public API answers DELETE /v1/me/favorites with 40012 "Insufficient Permissions"
     * ('Favorites:DELETE:IdsQuery'); only Apple's own apps may unfavorite.
     */
    override suspend fun addFavorite(catalogSongId: String): Unit = withAccount {
        require(catalogSongId.matches(Regex("[0-9]+"))) { "Catalog song ID is invalid" }
        requestRoot("/v1/me/favorites?ids%5Bsongs%5D=$catalogSongId", needsUserToken = true,
            method = "POST", postBody = "", refreshAfterUnauthorized = false)
    }

    /**
     * Adds the catalog song to the user's personal iCloud Music Library.
     * POST /v1/me/library?ids[songs]=<id>
     */
    override suspend fun addToLibrary(catalogSongId: String): Unit = withAccount {
        require(catalogSongId.matches(Regex("[0-9]+"))) { "Catalog song ID is invalid" }
        requestRoot("/v1/me/library?ids%5Bsongs%5D=$catalogSongId", needsUserToken = true,
            method = "POST", postBody = "", refreshAfterUnauthorized = false)
    }

    /**
     * Checks if a catalog song is present in the user's library using include=library.
     * Logs response status and library-related keys for verification.
     * Returns true if in library, false if definitively absent, or null if unknown.
     */
    override suspend fun isSongInLibrary(catalogSongId: String): Boolean? = withAccount {
        require(catalogSongId.matches(Regex("[0-9]+"))) { "Catalog song ID is invalid" }
        val root = requestRoot("/v1/catalog/${effectiveStorefront()}/songs/$catalogSongId?include=library",
            needsUserToken = true)

        val data0 = root.getJSONArray("data").getJSONObject(0)
        val relationships = data0.optJSONObject("relationships")

        if (relationships == null || !relationships.has("library")) {
            return@withAccount null
        }
        val libraryObj = relationships.optJSONObject("library") ?: return@withAccount null
        val libraryData = libraryObj.optJSONArray("data") ?: return@withAccount null
        return@withAccount libraryData.length() > 0
    }

    internal fun parseArtistPage(root: JSONObject, catalogId: String): CatalogArtistPage {
        val resource = root.optJSONArray("data")?.optJSONObject(0) ?: return CatalogArtistPage()
        val hero = (resource.optJSONObject("attributes") ?: JSONObject())
            .toTrack(resource.optString("id", catalogId), CatalogKind.Artist, resourceType = "artists")
        val shelves = parseArtistViews(root, catalogId)
        val topSongs = shelves.firstOrNull { it.id.endsWith("-top-songs") }?.items.orEmpty()
        return CatalogArtistPage(hero, topSongs, shelves.filterNot { it.id.endsWith("-top-songs") })
    }

    /**
     * The rest of an artist page shelf (Apple Music's "See All"), following [moreHref] page by page.
     * Only catalog artist views are followed; at most [ARTIST_VIEW_MAX_PAGES] pages.
     */
    suspend fun catalogArtistViewMore(moreHref: String): List<Track> = withAccount {
        require(moreHref.startsWith("/v1/catalog/") && "/artists/" in moreHref && "/view/" in moreHref) {
            "Not an artist view page"
        }
        val items = mutableListOf<Track>()
        var next: String? = moreHref
        repeat(ARTIST_VIEW_MAX_PAGES) {
            val href = next ?: return@withAccount items
            val root = requestRoot(href, needsUserToken = false)
            items += withContext(parseDispatcher) { parseResources(root) }
            next = root.optString("next").takeIf { it.startsWith("/v1/catalog/") }
        }
        return@withAccount items
    }

    /** The catalog artist of a song or album, so the player and album pages can open it. */
    suspend fun catalogArtistOf(item: Track): Track? = catalogRelation(item, "artists", CatalogKind.Artist)

    /** The catalog album a song belongs to. */
    suspend fun catalogAlbumOf(song: Track): Track? =
        if (song.catalogKind == CatalogKind.Song) catalogRelation(song, "albums", CatalogKind.Album) else null

    private suspend fun catalogRelation(item: Track, relation: String, kind: CatalogKind): Track? = withAccount {
        val type = when (item.catalogKind) {
            CatalogKind.Song -> "songs"
            CatalogKind.Album -> "albums"
            else -> return@withAccount null
        }
        val id = item.catalogId?.takeIf { it.matches(Regex("[0-9]+")) } ?: return@withAccount null
        val root = requestRoot("/v1/catalog/${effectiveStorefront()}/$type/$id/$relation", needsUserToken = false)
        return@withAccount withContext(parseDispatcher) {
            val resource = root.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
            (resource.optJSONObject("attributes") ?: JSONObject())
                .toTrack(resource.getString("id"), kind, resourceType = relation)
        }
    }

    internal fun parseArtistViews(root: JSONObject, catalogId: String): List<ContentShelf> {
        val views = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("views") ?: return emptyList()
        return ARTIST_VIEWS.mapNotNull { (view, fallbackTitle) ->
            val viewObject = views.optJSONObject(view) ?: return@mapNotNull null
            val data = viewObject.optJSONArray("data") ?: return@mapNotNull null
            val items = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val type = item.optString("type")
                    val kind = (if (type == "artists") CatalogKind.Artist else catalogKind(type)) ?: continue
                    val attributes = item.optJSONObject("attributes") ?: continue
                    attributes.toTrack(item.getString("id"), kind, resourceType = type)?.let(::add)
                }
            }
            // Apple names each view in the storefront's language (e.g. 參與作品, 相似藝人).
            val title = viewObject.optJSONObject("attributes")?.optString("title")?.takeIf { it.isNotBlank() }
                ?: AppText.text(fallbackTitle)
            val more = viewObject.optString("next").takeIf { it.startsWith("/v1/catalog/") }
            items.takeIf { it.isNotEmpty() }?.let { ContentShelf("artist-$catalogId-$view", title, it, moreHref = more) }
        }
    }

    suspend fun resolveCatalogSongId(id: String): String = withContext(Dispatchers.IO) {
        withAccount { catalogSongResolver.resolve(id) }
    }

    internal suspend fun songPreviewUrl(catalogId: String): String? =
        withAccount {
            requestRoot("/v1/catalog/${effectiveStorefront()}/songs/$catalogId", needsUserToken = false)
                .optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.songPreviewUrl()
        }

    private suspend fun libraryCatalogSongId(id: String): String {
        val root = requestRoot(
            path = "/v1/me/library/songs/${URLEncoder.encode(id, Charsets.UTF_8.name())}?include=catalog",
            needsUserToken = true,
        )
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw CatalogRequestException(CatalogFailureReason.Unavailable,
                AppText.text(R.string.services_library_song_catalog_missing, id))
        val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
            ?.optJSONArray("data")?.optJSONObject(0)
        val resolved = catalogPlaybackId(id, "library-songs",
            item.optJSONObject("attributes")?.optJSONObject("playParams"), linked)
        if (resolved == id || resolved.startsWith("i.")) {
            throw CatalogRequestException(CatalogFailureReason.Unavailable,
                AppText.text(R.string.services_uploaded_song_lyrics_unavailable))
        }
        Log.i(TAG, "Library source catalog: $id -> $resolved")
        return resolved
    }

    private suspend fun equivalentSongId(storefront: String, id: String): String {
        // Catalog queues as well as library links can outlive a release. Apple
        // owns the recording equivalence; the account storefront owns availability.
        val equivalents = requestRoot(
            path = "/v1/catalog/$storefront/songs?filter%5Bequivalents%5D=${URLEncoder.encode(id, Charsets.UTF_8.name())}",
            needsUserToken = false,
        )
        val playable = requireEquivalentCatalogSongId(
            equivalents, id,
            unavailableMessage = AppText.text(R.string.services_track_unavailable),
            transientMessage = AppText.text(R.string.services_song_connection_failed),
        )
        Log.i(TAG, "Catalog equivalence: $id -> $playable (storefront=$storefront)")
        return playable
    }

    suspend fun charts(types: String = "albums,songs,playlists", browseFallback: Boolean = false): List<Track> = withAccount {
        val sf = effectiveStorefront()
        return@withAccount request(
            path = "/v1/catalog/$sf/charts?types=$types&limit=14&" +
                "extend=editorialArtwork,editorialVideo",
            needsUserToken = false,
            browseChannel = "charts".takeIf { browseFallback },
        )
    }

    suspend fun liveRadio(browseFallback: Boolean = false): List<Track> = withAccount {
        val sf = effectiveStorefront()
        return@withAccount request(
            path = "/v1/catalog/$sf/stations?filter%5Bfeatured%5D=apple-music-live-radio",
            needsUserToken = false,
            browseChannel = "charts".takeIf { browseFallback },
        )
    }

    suspend fun search(term: String): List<Track> = withAccount {
        if (term.isBlank()) return@withAccount emptyList()
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        if (BuildConfig.FLYME_AUTO) {
            // The car page groups results like Apple Music: top results, songs, artists, albums...
            val root = requestRoot(
                "/v1/catalog/$sf/search?term=$encoded&types=$SEARCH_RESOURCE_TYPES" +
                    "&limit=21&with=topResults",
                needsUserToken = false,
            )
            logSearchShape("search", sf, root)
            return@withAccount withContext(parseDispatcher) { parseGroupedSearch(root, term) }
        }
        return@withAccount request(
            path = "/v1/catalog/$sf/search?term=$encoded&types=songs,albums,playlists,music-videos,stations&limit=15",
            needsUserToken = false,
        )
    }

    internal suspend fun searchLibraryForVoice(term: String, collection: LibraryCollection): List<Track> = withAccount {
        if (useCurrentUserToken { it.isNullOrBlank() }) return@withAccount emptyList()
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val root = requestRoot(
            "/v1/me/library/search?term=$encoded&types=${collection.resourceType}&limit=24",
            needsUserToken = true,
        )
        withContext(parseDispatcher) { parseResources(root) }
    }

    internal suspend fun searchVoiceCollections(term: String): List<Track> = withAccount {
        if (term.isBlank()) return@withAccount emptyList()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        request("/v1/catalog/${effectiveStorefront()}/search?term=$encoded&types=playlists,stations&limit=15", false)
    }

    internal suspend fun catalogAlbumForVoice(album: Track): Track? = withAccount {
        album.catalogId?.let { return@withAccount album.copy(resourceId = it, resourceType = "albums") }
        val libraryId = checkNotNull(album.resourceId)
        require(libraryId.matches(Regex("[A-Za-z0-9._-]+")))
        val linked = try {
            requestRoot("/v1/me/library/albums/$libraryId/catalog", needsUserToken = true)
        } catch (error: CatalogRequestException) {
            if (error.reason != CatalogFailureReason.Unavailable) throw error
            return@withAccount null
        }
        withContext(parseDispatcher) { parseResources(linked).firstOrNull { it.catalogKind == CatalogKind.Album } }
    }

    internal suspend fun searchSuggestions(term: String): CatalogSearchSuggestions = withAccount {
        val termLength = term.length
        Log.d(SEARCH_LOG_TAG, "suggestions request termLength=$termLength")
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val root = requestRoot(
            path = "/v1/catalog/$sf/search/suggestions?term=$encoded" +
                "&kinds=terms,topResults&types=$SEARCH_RESOURCE_TYPES&limit=10",
            needsUserToken = false,
            suggestionTermLength = termLength,
        )
        logSearchShape("suggestions", sf, root)
        val suggestions = withContext(parseDispatcher) { parseSearchSuggestions(root) }
        Log.d(SEARCH_LOG_TAG, "suggestions parsed termLength=$termLength " +
            "terms=${suggestions.terms.size} topResults=${suggestions.topResults.size}")
        if (!BuildConfig.FLYME_AUTO || suggestions.topResults.isNotEmpty()) return@withAccount suggestions
        val prefix = searchKey(term)
        if (prefix.isEmpty()) return@withAccount suggestions
        val matches = search(term).asSequence().filter { searchKey(it.title).startsWith(prefix) }.take(3).toList()
        Log.d(SEARCH_LOG_TAG, "suggestions fallback=grouped-search termLength=$termLength count=${matches.size}")
        suggestions.copy(topResults = matches)
    }

    internal fun parseSearchSuggestions(root: JSONObject): CatalogSearchSuggestions {
        val suggestions = checkNotNull(root.optJSONObject("results")?.optJSONArray("suggestions")) {
            "Apple Music response is missing the results.suggestions array"
        }
        val terms = mutableListOf<CatalogSearchTerm>()
        val topResults = mutableListOf<Track>()
        for (index in 0 until suggestions.length()) {
            val suggestion = suggestions.optJSONObject(index) ?: continue
            when (suggestion.optString("kind")) {
                "terms" -> if (terms.size < 5) {
                    val searchTerm = suggestion.optString("searchTerm").takeIf(String::isNotBlank) ?: continue
                    val displayTerm = suggestion.optString("displayTerm").ifBlank { searchTerm }
                    terms.add(CatalogSearchTerm(displayTerm, searchTerm))
                }
                "topResults" -> if (topResults.size < 3) {
                    val content = suggestion.optJSONObject("content") ?: continue
                    parseSearchResource(root, content)?.let(topResults::add)
                }
            }
        }
        return CatalogSearchSuggestions(terms, topResults)
    }

    private fun logSearchShape(operation: String, storefront: String, root: JSONObject) {
        val results = root.optJSONObject("results")
        fun topTypes(key: String): List<String?> {
            val data = results?.let { searchResultData(it, key) }
            return List(data?.length() ?: 0) { data?.optJSONObject(it)?.optString("type") }
        }
        val suggestions = results?.optJSONArray("suggestions")
        val kinds = List(suggestions?.length() ?: 0) { index ->
            val suggestion = suggestions?.optJSONObject(index)
            "${suggestion?.optString("kind")}:${suggestion?.optJSONObject("content")?.optString("type")}"
        }
        Log.d(SEARCH_LOG_TAG, "$operation storefront=$storefront keys=${results?.keys()?.asSequence()?.toList()} " +
            "topTypes=${topTypes("top")} topResultsTypes=${topTypes("topResults")} suggestionKinds=$kinds")
    }

    private fun searchResultData(results: JSONObject, key: String): JSONArray? =
        results.optJSONObject(key)?.optJSONArray("data") ?: results.optJSONArray(key)

    private fun parseSearchResource(root: JSONObject, item: JSONObject): Track? {
        val type = item.optString("type")
        val kind = (if (type == "artists") CatalogKind.Artist else catalogKind(type)) ?: return null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return null
        val attributes = item.optJSONObject("attributes")
            ?: root.optJSONObject("resources")?.optJSONObject(type)?.optJSONObject(id)?.optJSONObject("attributes")
            ?: return null
        return attributes.toTrack(id, kind, resourceType = type)
    }

    private fun searchKey(value: String): String =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Keep Apple's order within each section and prefer an exact title for the first result. */
    internal fun parseGroupedSearch(root: JSONObject, term: String = ""): List<Track> {
        val results = root.optJSONObject("results") ?: return emptyList()
        val output = LinkedHashMap<String, Track>()
        for (key in listOf("topResults", "top", "songs", "artists", "albums", "playlists", "stations", "music-videos")) {
            val data = searchResultData(results, key) ?: continue
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val track = parseSearchResource(root, item) ?: continue
                output.putIfAbsent(track.id, track)
            }
        }
        val resultsInOrder = output.values.toList()
        val exact = searchKey(term).takeIf(String::isNotEmpty)?.let { key ->
            resultsInOrder.firstOrNull { searchKey(it.title) == key }
        } ?: return resultsInOrder
        return listOf(exact) + resultsInOrder.filterNot { it.id == exact.id }
    }

    /** Search only playable songs for cross-catalog resolution. */
    suspend fun searchSongs(term: String): List<Track> = withAccount {
        if (term.isBlank()) return@withAccount emptyList()
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        return@withAccount request(
            path = "/v1/catalog/$sf/search?term=$encoded&types=songs&limit=25",
            needsUserToken = false,
        ).filter { it.catalogKind == CatalogKind.Song && it.catalogId != null }
    }

    suspend fun createContinuousStation(songId: String): Pair<String?, List<Track>> = withAccount {
        val cleanSongId = songId.removePrefix("apple:")
        val type = if (cleanSongId.startsWith("i.")) "library-songs" else "songs"
        val bodyJson = JSONObject().put("data", JSONArray().put(JSONObject().put("id", cleanSongId).put("type", type)))
        val root = requestRoot(
            path = "/v1/me/stations/continuous?limit[results:tracks]=10&with=tracks",
            needsUserToken = true,
            method = "POST",
            postBody = bodyJson.toString(),
        )
        return@withAccount continuousStationFromResponse(root, ::stationNextTracks)
    }

    suspend fun stationNextTracks(stationId: String): List<Track> {
        val root = requestRoot(
            path = "/v1/me/stations/next-tracks/$stationId?limit=10",
            needsUserToken = true,
            method = "POST",
            postBody = "{}",
        )
        return withContext(parseDispatcher) { parseStationTracks(root) }
    }

    internal suspend fun continuousStationFromResponse(
        root: JSONObject,
        loadNextTracks: suspend (String) -> List<Track>,
    ): Pair<String?, List<Track>> {
        val (stationId, tracks) = withContext(parseDispatcher) {
            val data = root.optJSONArray("data")
            val station = (0 until (data?.length() ?: 0)).firstNotNullOfOrNull { index ->
                data?.optJSONObject(index)?.takeIf { it.optString("type") == "stations" }
            } ?: root.optJSONObject("results")?.optJSONObject("station")
            val id = station?.optString("id")?.takeIf(String::isNotBlank)
            id to parseStationTracks(root)
        }
        // Station creation can return only its resource metadata. That resource
        // is a continuation handle, never an item for the native song queue.
        val songs = if (tracks.isEmpty() && stationId != null) loadNextTracks(stationId) else tracks
        return stationId to songs.filter { it.catalogKind == CatalogKind.Song }
    }

    internal fun parseStationTracks(root: JSONObject): List<Track> = parseAllTracks(root, songsOnly = true)

    internal suspend fun collectionTracks(
        collection: Track,
        onPartial: (List<Track>) -> Unit = {},
        initialPage: CollectionTrackPage? = null,
    ): List<Track> = withAccount {
        collectionTracks(collection, onPartial, initialPage) { path, needsUserToken ->
            requestRoot(path, needsUserToken)
        }
    }

    internal suspend fun firstCollectionTrackPage(collection: Track): CollectionTrackPage =
        withAccount {
            val request = collection.collectionRequest(effectiveStorefront())
            readCollectionTrackPage(request, request.firstPage) { path, needsUserToken ->
                requestRoot(path, needsUserToken)
            }
        }

    internal suspend fun collectionTracks(
        collection: Track,
        onPartial: (List<Track>) -> Unit = {},
        initialPage: CollectionTrackPage? = null,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): List<Track> = withContext(parseDispatcher) {
        val request = collection.collectionRequest(effectiveStorefront())
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        var path: String? = request.firstPage
        while (path != null) {
            currentCoroutineContext().ensureActive()
            check(visited.add(path)) { AppText.text(R.string.services_playlist_page_repeated) }
            val page = initialPage?.takeIf { path == request.firstPage }
                ?: readCollectionTrackPage(request, path, fetchPage)
            currentCoroutineContext().ensureActive()
            tracks += page.items
            path = page.next
            if (path != null && tracks.isNotEmpty()) onPartial(tracks.toList())
        }
        tracks
    }

    private suspend fun readCollectionTrackPage(
        request: CollectionRequest,
        path: String,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): CollectionTrackPage {
        val root = fetchPage(path, request.needsUserToken)
        val data = root.optJSONArray("data") ?: error(AppText.text(R.string.services_playlist_tracks_unavailable))
        // Nested catalog resources are metadata. Keep the relationship's order and repeats.
        val tracks = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (catalogKind(type) != CatalogKind.Song) continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), CatalogKind.Song, linked, type)?.let(::add)
            }
        }
        val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let(request::nextPage)
        return CollectionTrackPage(tracks, next)
    }

    private suspend fun request(path: String, needsUserToken: Boolean, browseChannel: String? = null): List<Track> {
        val root = requestRoot(path, needsUserToken, browseChannel = browseChannel)
        return withContext(parseDispatcher) { parseResources(root) }
    }

    private suspend fun requestRoot(
        path: String,
        needsUserToken: Boolean,
        method: String = "GET",
        postBody: String? = null,
        suggestionTermLength: Int? = null,
        browseChannel: String? = null,
        refreshAfterUnauthorized: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val account = currentAccount()
        requireCurrent(account)
        val musicUserToken = account.token
        if (path.startsWith("/v1/catalog/")) {
            val pathStorefront = path.removePrefix("/v1/catalog/").substringBefore('/')
            if (pathStorefront != effectiveStorefront()) {
                throw kotlinx.coroutines.CancellationException("Apple Music storefront changed")
            }
        }
        if (needsUserToken) {
            if (musicUserToken.isNullOrBlank()) {
                throw CatalogRequestException(CatalogFailureReason.Authorization,
                    AppText.text(R.string.services_sign_in_apple_music))
            }
        }

        suspend fun execute(forceTokenRefresh: Boolean): Pair<Int, String> {
            val developerToken = developerTokens.token(forceRefresh = forceTokenRefresh)
            currentCoroutineContext().ensureActive()
            requireCurrent(account)
            val url = "https://api.music.apple.com$path"
            val headers = buildMap {
                put("Authorization", "Bearer $developerToken")
                put("Accept", "application/json")
                if (!musicUserToken.isNullOrBlank()) put("Music-User-Token", musicUserToken)
            }
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (postBody != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(postBody) }
                }
                val status = connection.responseCode
                if (browseChannel != null) Log.i(BROWSE_LOG_TAG, "channel=$browseChannel GET $path httpStatus=$status")
                if (suggestionTermLength != null) {
                    Log.d(SEARCH_LOG_TAG, "suggestions response termLength=$suggestionTermLength httpStatus=$status")
                }
                val body = (if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader()?.use { it.readText() }.orEmpty()
                status to body
            } finally {
                connection.disconnect()
            }
        }

        var (status, body) = execute(forceTokenRefresh = false)
        requireCurrent(account)
        if (refreshAfterUnauthorized && status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            currentCoroutineContext().ensureActive()
            val retried = execute(forceTokenRefresh = true)
            requireCurrent(account)
            status = retried.first
            body = retried.second
        }
        if (status !in 200..299) {
            val message = when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> AppText.text(R.string.services_apple_auth_http_error, status)
                else -> AppText.text(R.string.services_apple_api_http_error, status)
            }
            throw CatalogRequestException(catalogHttpFailure(status), message, status)
        }
        // PUT/DELETE (ratings) answer 204 with no body.
        try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (error: JSONException) {
            if (suggestionTermLength == null) throw error
            // Android's JSON exception can include the response body and the search term.
            throw JSONException("Apple Music suggestions response is not a valid JSON object")
        }
    }

    private fun parseAllTracks(root: JSONObject, songsOnly: Boolean = false): List<Track> {
        val output = LinkedHashMap<String, Track>()

        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val id = value.optString("id")
                    val type = value.optString("type")
                    val attributes = value.optJSONObject("attributes")
                    val kind = catalogKind(type)
                    if (id.isNotBlank() && attributes != null && kind != null &&
                        (!songsOnly || kind == CatalogKind.Song)
                    ) {
                        val linkedCatalog = value.optJSONObject("relationships")
                            ?.optJSONObject("catalog")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                        val track = attributes.toTrack(id, kind, linkedCatalog, type)
                        if (track != null && !output.containsKey(track.id)) {
                            output[track.id] = track
                        }
                        return
                    }
                    value.keys().forEachRemaining { key ->
                        if (key != "attributes") visit(value.opt(key))
                    }
                }
            }
        }

        visit(root)
        return output.values.toList()
    }

    private fun parseResources(root: JSONObject): List<Track> {
        val output = LinkedHashMap<String, Track>()

        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val id = value.optString("id")
                    val type = value.optString("type")
                    val attributes = value.optJSONObject("attributes")
                    val kind = catalogKind(type)
                    if (id.isNotBlank() && attributes != null && kind != null) {
                        val linkedCatalog = value.optJSONObject("relationships")
                            ?.optJSONObject("catalog")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                        val track = attributes.toTrack(id, kind, linkedCatalog, type)
                        if (track != null && !output.containsKey(track.id)) {
                            output[track.id] = track
                        }
                        return
                    }
                    value.keys().forEachRemaining { key ->
                        if (key != "attributes") visit(value.opt(key))
                    }
                }
            }
        }

        visit(root)
        return output.values.take(24)
    }

    private fun JSONObject.toTrack(
        id: String,
        kind: CatalogKind,
        linkedCatalog: JSONObject? = null,
        resourceType: String? = null,
    ): Track? {
        val catalogAttrs = linkedCatalog?.optJSONObject("attributes")
        val playParams = optJSONObject("playParams")
        val isLibraryArtist = resourceType == LibraryCollection.Artists.resourceType && kind == CatalogKind.Artist
        val isLibraryContainer = resourceType?.startsWith("library-") == true &&
            kind in setOf(CatalogKind.Album, CatalogKind.Playlist)
        val resolvedCatalogId = if (isLibraryArtist) {
            null
        } else if (isLibraryContainer) {
            libraryContainerCatalogId(id, kind, playParams, linkedCatalog)
        } else {
            catalogPlaybackId(id, resourceType, playParams, linkedCatalog)
        }

        val rawName = optString("name").ifBlank {
            catalogAttrs?.optString("name").orEmpty()
        }
        val rawArtist = optString("artistName").ifBlank {
            optString("curatorName").ifBlank {
                catalogAttrs?.optString("artistName").orEmpty().ifBlank {
                    catalogAttrs?.optString("curatorName").orEmpty()
                }
            }
        }
        val localArtwork = optJSONObject("artwork")
        val catalogArtwork = catalogAttrs?.optJSONObject("artwork")
        val artwork = (localArtwork?.optString("url") ?: catalogArtwork?.optString("url"))
            ?.asArtworkUrl()

        // Skip unplayable unmatched iCloud Music Library local stub containers
        // (e.g. `l.z-...` or `i.eoD...` with no catalog match and no metadata).
        val isUnmatchedLocalContainer = !isLibraryContainer && (id.startsWith("l.z-") || id.startsWith("i.")) &&
            resolvedCatalogId == id &&
            artwork == null &&
            (rawName.isBlank() || rawName == "Unknown Album" || rawArtist.isBlank())
        if (isUnmatchedLocalContainer) return null

        val editorialNotes = optJSONObject("editorialNotes")
            ?: catalogAttrs?.optJSONObject("editorialNotes")
        val name = rawName.ifBlank {
            editorialNotes?.optString("name").orEmpty().ifBlank { AppText.text(R.string.services_unknown_title) }
        }
        val artist = when {
            kind == CatalogKind.Artist -> AppText.text(R.string.services_kind_artist)
            rawArtist.isNotBlank() -> rawArtist
            else -> "Apple Music"
        }
        val album = optString("albumName").ifBlank {
            catalogAttrs?.optString("albumName").orEmpty().ifBlank {
                when (kind) {
                    CatalogKind.Artist -> AppText.text(R.string.services_kind_artist)
                    CatalogKind.Album -> AppText.text(R.string.services_kind_album)
                    CatalogKind.Playlist -> AppText.text(R.string.services_kind_playlist)
                    CatalogKind.Station -> AppText.text(R.string.services_kind_station)
                    CatalogKind.MusicVideo -> AppText.text(R.string.services_kind_music_video)
                    CatalogKind.Song -> AppText.text(R.string.services_kind_song)
                }
            }
        }
        val motion = findEditorialMotion() ?: catalogAttrs?.findEditorialMotion()
        val resourceIdentity = "${resourceType.orEmpty()}:$id"
        val stableIdentity = resolvedCatalogId ?: "library:$resourceIdentity"
        return Track(
            id = "apple:$stableIdentity",
            title = name,
            artist = artist,
            album = album,
            durationMs = optLong(
                "durationInMillis",
                catalogAttrs?.optLong("durationInMillis", 48_000L) ?: 48_000L,
            ).coerceAtLeast(1L),
            palette = fallbackTrackPalette(stableIdentity),
            lyrics = emptyList(),
            catalogId = resolvedCatalogId,
            catalogKind = kind,
            artworkUrl = artwork,
            artworkColors = localArtwork?.toAppleArtworkColors(catalogArtwork)
                ?: catalogArtwork?.toAppleArtworkColors(),
            motionArtworkUrl = motion?.videoUrl,
            motionPreviewUrl = motion?.previewUrl,
            previewUrl = if (kind == CatalogKind.Song) catalogAttrs?.songPreviewUrl() ?: songPreviewUrl() else null,
            discNumber = if (kind == CatalogKind.Song) {
                optPositiveInt("discNumber") ?: catalogAttrs?.optPositiveInt("discNumber")
            } else null,
            trackNumber = if (kind == CatalogKind.Song) {
                optPositiveInt("trackNumber") ?: catalogAttrs?.optPositiveInt("trackNumber")
            } else null,
            resourceId = id,
            resourceType = resourceType,
            dateAddedMs = optString("dateAdded").takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            isExplicit = optString("contentRating") == "explicit" ||
                catalogAttrs?.optString("contentRating") == "explicit",
        )
    }

    private fun JSONObject.optPositiveInt(key: String): Int? =
        optInt(key, 0).takeIf { it > 0 }

    private fun JSONObject.songPreviewUrl(): String? =
        optJSONArray("previews")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("https://") }

    private fun JSONObject.displayText(key: String): String? {
        val value = opt(key)
        return when (value) {
            is String -> value.takeIf(String::isNotBlank)
            is JSONObject -> value.optString("stringForDisplay").ifBlank {
                value.optString("name")
            }.takeIf(String::isNotBlank)
            else -> null
        }
    }

    private data class MotionAsset(val videoUrl: String, val previewUrl: String?)

    private fun JSONObject.findEditorialMotion(): MotionAsset? {
        editorialMotion()?.let { return it }
        val editorialCard = optJSONObject("editorialCard") ?: return null
        return editorialCard.editorialMotion()
    }

    private fun JSONObject.editorialMotion(): MotionAsset? {
        val videos = optJSONObject("editorialVideo") ?: return null
        val preferredFlavors = listOf(
            "motionSquareVideo1x1",
            "motionDetailSquare",
            "backgroundMotion1x1",
            "motionArtistSquare1x1",
            "motionMediumVideo16x9",
            "motionArtistWide16x9",
            "motionWideVideo21x9",
            "motionDetailTall",
            "motionTallVideo3x4",
        )
        for (flavor in preferredFlavors) {
            val item = videos.optJSONObject(flavor) ?: continue
            val videoUrl = item.optString("video").takeIf { it.startsWith("https://") }
                ?: item.optJSONArray("videoFile")?.let { files ->
                    (files.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                        files.optJSONObject(index)?.optString("assetUrl")
                            ?.takeIf { it.startsWith("https://") }
                    }
                }
                ?: continue
            val previewUrl = item.optJSONObject("previewFrame")
                ?.optString("url")
                ?.asArtworkUrl()
            return MotionAsset(videoUrl, previewUrl)
        }
        return null
    }

    private fun String.asArtworkUrl(): String? = replace("{w}", "600")
        .replace("{h}", "600")
        .replace("{f}", "jpg")
        .takeIf { it.startsWith("https://") }

    private fun catalogKind(type: String): CatalogKind? = when (type) {
        "songs", "library-songs" -> CatalogKind.Song
        "library-artists" -> CatalogKind.Artist
        "albums", "library-albums" -> CatalogKind.Album
        "playlists", "library-playlists" -> CatalogKind.Playlist
        "stations" -> CatalogKind.Station
        "music-videos", "library-music-videos" -> CatalogKind.MusicVideo
        else -> null
    }

    /** A container's library ID is for /me/library/... requests, never catalog playback. */
    private fun libraryContainerCatalogId(
        resourceId: String,
        kind: CatalogKind,
        playParams: JSONObject?,
        linkedCatalog: JSONObject?,
    ): String? {
        val expectedType = when (kind) {
            CatalogKind.Album -> "albums"
            CatalogKind.Playlist -> "playlists"
            else -> return null
        }
        fun catalogId(value: String?): String? = value?.takeIf {
            it.isNotBlank() && it != "null" && it != resourceId &&
                !it.startsWith("i.") && !it.startsWith("l.") && !it.startsWith("p.")
        }
        val linkedId = linkedCatalog?.takeIf { it.optString("type") == expectedType }
            ?.optString("id")
        return catalogId(linkedId) ?: catalogId(playParams?.optString("catalogId"))
    }

    private companion object {
        const val TAG = "AmAtvAppleMusic"
        private const val KEY_STOREFRONT_TOKEN = "user_storefront_token_sha256"
        private const val KEY_STOREFRONT = "user_storefront"
    }
}

internal data class CollectionTrackPage(val items: List<Track>, val next: String?)

/** Apple Music's own artist page: the artist (for its artwork), top songs and release shelves. */
data class CatalogArtistPage(
    val artist: Track? = null,
    val topSongs: List<Track> = emptyList(),
    val shelves: List<ContentShelf> = emptyList(),
    val complete: Boolean = true,
)

private const val ARTIST_VIEW_MAX_PAGES = 15

/** Apple Music Web's artist page sections, in its order. Music videos are left out: the SDK plays audio. */
private val ARTIST_VIEWS = listOf(
    "top-songs" to R.string.artist_top_songs,
    "latest-release" to R.string.artist_latest_release,
    "full-albums" to R.string.artist_albums,
    "singles" to R.string.artist_singles,
    "featured-playlists" to R.string.artist_playlists,
    "live-albums" to R.string.artist_live_albums,
    "compilation-albums" to R.string.artist_compilations,
    "appears-on-albums" to R.string.artist_appears_on,
    "similar-artists" to R.string.artist_similar,
)

private val appleArtworkHex = Regex("[0-9a-fA-F]{6}")

internal fun JSONObject.toAppleArtworkColors(fallback: JSONObject? = null): AppleArtworkColors? {
    fun JSONObject.hex(key: String): String? =
        (opt(key) as? String)?.takeIf(appleArtworkHex::matches)

    fun color(key: String): String? = hex(key) ?: fallback?.hex(key)

    val colors = AppleArtworkColors(
        bgColor = color("bgColor"),
        textColor1 = color("textColor1"),
        textColor2 = color("textColor2"),
        textColor3 = color("textColor3"),
        textColor4 = color("textColor4"),
    )
    return colors.takeUnless { it == AppleArtworkColors() }
}

internal fun parseUserStorefrontId(root: JSONObject): String? = root.optJSONArray("data")
    ?.optJSONObject(0)
    ?.optString("id")
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.matches(Regex("[a-z]{2}")) }

/** Reporting/global IDs are not the catalog resource's current playback identity. */
internal fun catalogPlaybackId(
    resourceId: String,
    resourceType: String?,
    playParams: JSONObject?,
    linkedCatalog: JSONObject?,
): String {
    fun JSONObject?.identifier(key: String): String? = this?.optString(key)
        ?.takeIf { it.isNotBlank() && it != "null" }
    val isLibrary = resourceType?.startsWith("library-") == true ||
        resourceId.startsWith("i.") || resourceId.startsWith("l.")
    return if (isLibrary) {
        linkedCatalog.identifier("id")
            ?: linkedCatalog?.optJSONObject("attributes")?.optJSONObject("playParams").identifier("id")
            ?: playParams.identifier("catalogId")
            ?: resourceId
    } else {
        playParams.identifier("id") ?: resourceId
    }
}

/** Only Apple's explicit per-source mapping may relink a library recording. */
internal fun equivalentCatalogSongId(root: JSONObject, originalId: String): String? {
    val mappings = root.optJSONObject("meta")?.optJSONObject("filters")
        ?.optJSONObject("equivalents")?.optJSONArray(originalId) ?: return null
    val targets = (0 until mappings.length()).mapNotNull { index ->
        mappings.optJSONObject(index)?.takeIf { it.optString("type") == "songs" }
            ?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
    }.distinct()
    val target = targets.singleOrNull() ?: return null
    val data = root.optJSONArray("data") ?: return null
    val resource = (0 until data.length()).firstNotNullOfOrNull { index ->
        data.optJSONObject(index)?.takeIf {
            it.optString("id") == target && it.optString("type") == "songs"
        }
    } ?: return null
    val playParams = resource.optJSONObject("attributes")?.optJSONObject("playParams") ?: return null
    return target.takeIf { playParams.optString("id") == target && playParams.optString("kind") == "song" }
}
