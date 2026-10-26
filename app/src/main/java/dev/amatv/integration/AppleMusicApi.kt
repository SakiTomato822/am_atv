package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.amatv.BuildConfig
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

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
    private val userToken: () -> String?,
) {
    private data class UserStorefrontCache(val userToken: String, val storefront: String)

    private val parseDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val userStorefrontMutex = Mutex()

    @Volatile
    private var userStorefrontCache: UserStorefrontCache? = null

    suspend fun userStorefront(): String {
        val currentUserToken = userToken()?.takeIf(String::isNotBlank)
            ?: error(AppText.text(R.string.services_sign_in_apple_music))
        userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.let { return it.storefront }
        return userStorefrontMutex.withLock {
            userStorefrontCache?.takeIf { it.userToken == currentUserToken }?.let { return@withLock it.storefront }
            val root = requestRoot(path = "/v1/me/storefront", needsUserToken = true)
            val resolved = parseUserStorefrontId(root)
                ?: error(AppText.text(R.string.services_user_storefront_missing))
            userStorefrontCache = UserStorefrontCache(currentUserToken, resolved)
            Log.i(TAG, "Apple user storefront=$resolved")
            resolved
        }
    }

    suspend fun effectiveStorefront(): String {
        val currentUserToken = userToken()?.takeIf(String::isNotBlank)
        if (currentUserToken == null) return storefront
        return runCatching { userStorefront() }.getOrNull() ?: storefront
    }

    suspend fun recommendations(): List<Track> = request(
        path = "/v1/me/recommendations?limit=10&extend=editorialArtwork,editorialVideo",
        needsUserToken = true,
    )

    suspend fun recommendationShelves(): List<ContentShelf> {
        val root = requestRoot(
            path = "/v1/me/recommendations?limit=10&extend=editorialArtwork,editorialVideo",
            needsUserToken = true,
        )
        return withContext(parseDispatcher) {
        val recommendations = root.optJSONArray("data") ?: JSONArray()
        val shelves = buildList {
            for (index in 0 until recommendations.length()) {
                val recommendation = recommendations.optJSONObject(index) ?: continue
                val attributes = recommendation.optJSONObject("attributes")
                val title = attributes?.displayText("title")
                    ?: attributes?.displayText("name")
                    ?: AppText.text(R.string.services_recommended_for_you)
                val contents = recommendation.optJSONObject("relationships")
                    ?.optJSONObject("contents")
                    ?: continue
                val tracks = parseResources(contents)
                if (tracks.isNotEmpty()) {
                    add(
                        ContentShelf(
                            id = "recommendation:${recommendation.optString("id", index.toString())}",
                            title = title,
                            items = tracks,
                            featured = isEmpty(),
                        ),
                    )
                }
            }
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

    suspend fun libraryPlaylists(maxItems: Int = 200): List<Track> = paginatedLibraryRequest(
        initialPath = "/v1/me/library/playlists?limit=100&include=catalog",
        maxItems = maxItems,
    )

    suspend fun librarySongs(maxItems: Int = 300): List<Track> = paginatedLibraryRequest(
        initialPath = "/v1/me/library/songs?limit=100&include=catalog",
        maxItems = maxItems,
    )

    private suspend fun paginatedLibraryRequest(
        initialPath: String,
        maxItems: Int,
    ): List<Track> = withContext(parseDispatcher) {
        val visited = mutableSetOf<String>()
        val results = mutableListOf<Track>()
        var path: String? = initialPath
        while (path != null && results.size < maxItems) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(path)) break
            val root = requestRoot(path, needsUserToken = true)
            val tracks = parseAllTracks(root)
            results += tracks
            val next = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
            path = if (results.size < maxItems) next else null
        }
        results.take(maxItems)
    }

    suspend fun resolveCatalogSongId(id: String): String {
        if (!id.startsWith("i.")) return id
        val root = requestRoot(
            path = "/v1/me/library/songs/${URLEncoder.encode(id, Charsets.UTF_8.name())}?include=catalog",
            needsUserToken = true,
        )
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: error(AppText.text(R.string.services_library_song_catalog_missing, id))
        val playParams = item.optJSONObject("attributes")?.optJSONObject("playParams")
        val fromPlayParams = playParams?.optString("catalogId")?.takeIf(String::isNotBlank)
            ?: playParams?.optString("reportingId")?.takeIf(String::isNotBlank)
        if (fromPlayParams != null) return fromPlayParams
        val fromRelationship = item.optJSONObject("relationships")
            ?.optJSONObject("catalog")
            ?.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("id")
            ?.takeIf(String::isNotBlank)
        if (fromRelationship != null) return fromRelationship
        throw IllegalStateException(AppText.text(R.string.services_uploaded_song_lyrics_unavailable))
    }

    suspend fun charts(types: String = "albums,songs,playlists"): List<Track> {
        val sf = effectiveStorefront()
        return request(
            path = "/v1/catalog/$sf/charts?types=$types&limit=14&" +
                "extend=editorialArtwork,editorialVideo",
            needsUserToken = false,
        )
    }

    suspend fun liveRadio(): List<Track> {
        val sf = effectiveStorefront()
        return request(
            path = "/v1/catalog/$sf/stations?filter%5Bfeatured%5D=apple-music-live-radio",
            needsUserToken = false,
        )
    }

    suspend fun search(term: String): List<Track> {
        if (term.isBlank()) return emptyList()
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        return request(
            path = "/v1/catalog/$sf/search?term=$encoded&types=songs,albums,playlists,music-videos,stations&limit=15",
            needsUserToken = false,
        )
    }

    /** Search only playable songs for cross-catalog resolution. */
    suspend fun searchSongs(term: String): List<Track> {
        if (term.isBlank()) return emptyList()
        val sf = effectiveStorefront()
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        return request(
            path = "/v1/catalog/$sf/search?term=$encoded&types=songs&limit=25",
            needsUserToken = false,
        ).filter { it.catalogKind == CatalogKind.Song && it.catalogId != null }
    }

    suspend fun createContinuousStation(songId: String): Pair<String?, List<Track>> {
        val cleanSongId = songId.removePrefix("apple:")
        val type = if (cleanSongId.startsWith("i.")) "library-songs" else "songs"
        val bodyJson = JSONObject().put("data", JSONArray().put(JSONObject().put("id", cleanSongId).put("type", type)))
        val root = requestRoot(
            path = "/v1/me/stations/continuous?limit[results:tracks]=10&with=tracks",
            needsUserToken = true,
            method = "POST",
            postBody = bodyJson.toString(),
        )
        return continuousStationFromResponse(root, ::stationNextTracks)
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

    suspend fun collectionTracks(collection: Track): List<Track> =
        collectionTracks(collection) { path, needsUserToken -> requestRoot(path, needsUserToken) }

    internal suspend fun collectionTracks(
        collection: Track,
        fetchPage: suspend (String, Boolean) -> JSONObject,
    ): List<Track> = withContext(parseDispatcher) {
        val request = collection.collectionRequest(effectiveStorefront())
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        var path: String? = request.firstPage
        while (path != null) {
            currentCoroutineContext().ensureActive()
            check(visited.add(path)) { AppText.text(R.string.services_playlist_page_repeated) }
            val root = fetchPage(path, request.needsUserToken)
            val data = root.optJSONArray("data") ?: error(AppText.text(R.string.services_playlist_tracks_unavailable))
            // Read only this relationship, in order. Nested catalog resources are metadata,
            // and repeated songs are separate playlist entries, not duplicates to remove.
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (catalogKind(type) != CatalogKind.Song) continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val linked = item.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0)
                attributes.toTrack(item.getString("id"), CatalogKind.Song, linked, type)?.let(tracks::add)
            }
            path = root.optString("next").takeIf { it.isNotBlank() && it != "null" }
                ?.let(request::nextPage)
        }
        tracks
    }

    private suspend fun request(path: String, needsUserToken: Boolean): List<Track> {
        val root = requestRoot(path, needsUserToken)
        return withContext(parseDispatcher) { parseResources(root) }
    }

    private suspend fun requestRoot(
        path: String,
        needsUserToken: Boolean,
        method: String = "GET",
        postBody: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val musicUserToken = userToken()
        if (needsUserToken) {
            check(!musicUserToken.isNullOrBlank()) {
                AppText.text(R.string.services_sign_in_apple_music)
            }
        }

        suspend fun execute(forceTokenRefresh: Boolean): Pair<Int, String> {
            val developerToken = developerTokens.token(forceRefresh = forceTokenRefresh)
            val connection = URI("https://api.music.apple.com$path").toURL()
                .openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer $developerToken")
                connection.setRequestProperty("Accept", "application/json")
                if (!musicUserToken.isNullOrBlank()) {
                    connection.setRequestProperty("Music-User-Token", musicUserToken)
                }
                if (postBody != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(postBody) }
                }
                val status = connection.responseCode
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
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val retried = execute(forceTokenRefresh = true)
            status = retried.first
            body = retried.second
        }
        check(status in 200..299) {
            when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> AppText.text(R.string.services_apple_auth_http_error, status)
                else -> AppText.text(R.string.services_apple_api_http_error, status)
            }
        }
        JSONObject(body)
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
        val catalogPlayParams = catalogAttrs?.optJSONObject("playParams")
        val resolvedCatalogId = playParams?.optString("catalogId")?.takeIf(String::isNotBlank)
            ?: playParams?.optString("reportingId")?.takeIf(String::isNotBlank)
            ?: playParams?.optString("globalId")?.takeIf(String::isNotBlank)
            ?: linkedCatalog?.optString("id")?.takeIf(String::isNotBlank)
            ?: catalogPlayParams?.optString("id")?.takeIf(String::isNotBlank)
            ?: id

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
        val artwork = (optJSONObject("artwork")?.optString("url")
            ?: catalogAttrs?.optJSONObject("artwork")?.optString("url"))
            ?.asArtworkUrl()

        // Skip unplayable unmatched iCloud Music Library local stub containers
        // (e.g. `l.z-...` or `i.eoD...` with no catalog match and no metadata).
        val isUnmatchedLocalContainer = (id.startsWith("l.z-") || id.startsWith("i.")) &&
            resolvedCatalogId == id &&
            artwork == null &&
            (rawName.isBlank() || rawName == "Unknown Album" || rawArtist.isBlank())
        if (isUnmatchedLocalContainer) return null

        val editorialNotes = optJSONObject("editorialNotes")
            ?: catalogAttrs?.optJSONObject("editorialNotes")
        val name = rawName.ifBlank {
            editorialNotes?.optString("name").orEmpty().ifBlank { AppText.text(R.string.services_unknown_title) }
        }
        val artist = rawArtist.ifBlank { "Apple Music" }
        val album = optString("albumName").ifBlank {
            catalogAttrs?.optString("albumName").orEmpty().ifBlank {
                when (kind) {
                    CatalogKind.Album -> AppText.text(R.string.services_kind_album)
                    CatalogKind.Playlist -> AppText.text(R.string.services_kind_playlist)
                    CatalogKind.Station -> AppText.text(R.string.services_kind_station)
                    CatalogKind.MusicVideo -> AppText.text(R.string.services_kind_music_video)
                    CatalogKind.Song -> AppText.text(R.string.services_kind_song)
                }
            }
        }
        val motion = findEditorialMotion() ?: catalogAttrs?.findEditorialMotion()
        val palettes = paletteFor(resolvedCatalogId)
        val hasLyrics = optBoolean("hasLyrics", false) ||
            (catalogAttrs?.optBoolean("hasLyrics", false) == true)
        return Track(
            id = "apple:$resolvedCatalogId",
            title = name,
            artist = artist,
            album = album,
            durationMs = optLong(
                "durationInMillis",
                catalogAttrs?.optLong("durationInMillis", 48_000L) ?: 48_000L,
            ).coerceAtLeast(1L),
            palette = palettes,
            lyrics = emptyList(),
            catalogId = resolvedCatalogId,
            catalogKind = kind,
            artworkUrl = artwork,
            motionArtworkUrl = motion?.videoUrl,
            motionPreviewUrl = motion?.previewUrl,
            hasLyricsAvailable = hasLyrics,
            resourceId = id,
            resourceType = resourceType,
        )
    }

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
        "albums", "library-albums" -> CatalogKind.Album
        "playlists", "library-playlists" -> CatalogKind.Playlist
        "stations" -> CatalogKind.Station
        "music-videos", "library-music-videos" -> CatalogKind.MusicVideo
        else -> null
    }

    private fun paletteFor(id: String): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF38104E), Color(0xFFA41E62), Color(0xFFE17665)),
            listOf(Color(0xFF102E49), Color(0xFF147185), Color(0xFFD3A25E)),
            listOf(Color(0xFF242044), Color(0xFF5B55A7), Color(0xFFEA786D)),
            listOf(Color(0xFF143A32), Color(0xFF378C69), Color(0xFFD6C94D)),
        )
        return palettes[(id.hashCode() and Int.MAX_VALUE) % palettes.size]
    }

    private companion object {
        const val TAG = "AmAtvAppleMusic"
    }
}

internal fun parseUserStorefrontId(root: JSONObject): String? = root.optJSONArray("data")
    ?.optJSONObject(0)
    ?.optString("id")
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.matches(Regex("[a-z]{2}")) }
