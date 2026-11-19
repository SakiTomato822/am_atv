package dev.amatv.flyme

import dev.amatv.R
import dev.amatv.model.Track
import java.text.Collator

internal enum class LibraryCategory(val shelfId: String) {
    Playlists("lib-playlists"),
    Artists("lib-artists"),
    Albums("lib-albums"),
    Songs("lib-songs"),
}

internal enum class LibrarySort(val label: Int, val descendingFirst: Boolean) {
    Default(R.string.library_sort_default, false),
    DateAdded(R.string.library_sort_date_added, true),
    Name(R.string.library_sort_name, false),
    Artist(R.string.library_sort_artist, false),
}

internal fun LibraryCategory.sorts(): List<LibrarySort> = when (this) {
    LibraryCategory.Albums, LibraryCategory.Songs ->
        listOf(LibrarySort.DateAdded, LibrarySort.Name, LibrarySort.Artist)
    else -> listOf(LibrarySort.Default, LibrarySort.Name)
}

/** The Apple Music API has no library sort parameter: any order but its own needs every page. */
internal fun sortLibrary(items: List<Track>, sort: LibrarySort, descending: Boolean, collator: Collator): List<Track> {
    val sorted = when (sort) {
        LibrarySort.Default -> items
        LibrarySort.DateAdded -> items.sortedBy { it.dateAddedMs ?: Long.MIN_VALUE }
        LibrarySort.Name -> items.sortedWith(compareBy(collator) { it.title })
        LibrarySort.Artist -> items.sortedWith(
            compareBy<Track, String>(collator) { it.artist }.thenBy(collator) { it.title },
        )
    }
    return if (descending) sorted.asReversed() else sorted
}
