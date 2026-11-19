package dev.amatv.core

import dev.amatv.integration.LibraryCollection
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/** A saved album with a catalog release can also show the complete track list. */
internal val Track.canShowFullAlbum: Boolean
    get() = catalogKind == CatalogKind.Album &&
        resourceType == LibraryCollection.Albums.resourceType && catalogId != null

/** Collection requests for the complete release read the catalog instead of the library. */
internal fun Track.catalogAlbum(): Track = copy(resourceId = null, resourceType = "albums")
