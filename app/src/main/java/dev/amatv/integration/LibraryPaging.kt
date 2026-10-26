package dev.amatv.integration

import dev.amatv.model.Track

/** One server page; callers retain the continuation until the user asks for more. */
data class LibraryPage(
    val items: List<Track>,
    val nextPage: String?,
    val requestedPage: String,
)

enum class LibraryCollection(val resourceType: String) {
    Playlists("library-playlists"),
    Songs("library-songs");

    internal val request: CollectionRequest
        get() = CollectionRequest("/v1/me/library/${if (this == Playlists) "playlists" else "songs"}", true)
}

/** Library entries have durable resource identities, even when catalog releases are relinked. */
internal fun libraryResourceKey(track: Track): String =
    track.resourceId?.let { "${track.resourceType.orEmpty()}:$it" }
        ?: "${track.catalogKind}:${track.id}"

/** Retain distinct i.* entries that happen to point at the same catalog recording. */
fun mergeLibraryPage(existing: List<Track>, incoming: List<Track>): List<Track> {
    if (incoming.isEmpty()) return existing
    val known = existing.mapTo(HashSet(existing.size + incoming.size), ::libraryResourceKey)
    return existing + incoming.filter { known.add(libraryResourceKey(it)) }
}
