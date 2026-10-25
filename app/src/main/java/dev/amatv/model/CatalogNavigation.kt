package dev.amatv.model

enum class CatalogOpenAction { Collection, Station, Play }

val Track.openAction: CatalogOpenAction
    get() = when (catalogKind) {
        CatalogKind.Album, CatalogKind.Playlist -> CatalogOpenAction.Collection
        CatalogKind.Station -> CatalogOpenAction.Station
        CatalogKind.Song, CatalogKind.MusicVideo -> CatalogOpenAction.Play
    }
