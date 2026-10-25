package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.net.URI

internal data class CollectionRequest(val endpoint: String, val needsUserToken: Boolean) {
    val firstPage: String get() = "$endpoint?limit=100" + if (needsUserToken) "&include=catalog" else ""

    fun nextPage(href: String): String {
        val uri = URI("https://api.music.apple.com").resolve(href)
        check(uri.scheme == "https" && uri.host == "api.music.apple.com" &&
            uri.port == -1 && uri.userInfo == null && uri.rawPath == endpoint && uri.fragment == null) {
            AppText.text(R.string.services_collection_next_page_failed)
        }
        val query = uri.rawQuery.orEmpty()
        return endpoint + if (needsUserToken && !query.contains("include=")) {
            "?${if (query.isEmpty()) "" else "$query&"}include=catalog"
        } else if (query.isNotEmpty()) "?$query" else ""
    }
}

internal fun Track.collectionRequest(storefront: String): CollectionRequest {
    val type = when (catalogKind) {
        CatalogKind.Album -> "albums"
        CatalogKind.Playlist -> "playlists"
        else -> error(AppText.text(R.string.services_collection_required))
    }
    val id = (resourceId ?: catalogId ?: id).removePrefix("apple:")
    require(id.matches(Regex("[A-Za-z0-9._-]+"))) { AppText.text(R.string.services_collection_unrecognized) }
    // Original resource type is authoritative, even if playParams points at a catalog playlist.
    // The fallback only covers older saved items that predate resource identity persistence.
    val library = resourceType?.startsWith("library-")
        ?: (id.startsWith("p.") || id.startsWith("l."))
    val base = if (library) "/v1/me/library" else "/v1/catalog/$storefront"
    return CollectionRequest("$base/$type/$id/tracks", library)
}
