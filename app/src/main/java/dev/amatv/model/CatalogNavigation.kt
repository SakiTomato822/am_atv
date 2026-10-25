package dev.amatv.model

enum class CatalogOpenAction { Collection, Station, Play }

val Track.openAction: CatalogOpenAction
    get() = when (catalogKind) {
        CatalogKind.Album, CatalogKind.Playlist -> CatalogOpenAction.Collection
        CatalogKind.Station -> CatalogOpenAction.Station
        CatalogKind.Song, CatalogKind.MusicVideo -> CatalogOpenAction.Play
    }

val Track.kindLabel: String
    get() = when (catalogKind) {
        CatalogKind.Album -> "專輯"
        CatalogKind.Playlist -> "歌單"
        CatalogKind.Station -> "電台"
        CatalogKind.Song -> "歌曲"
        CatalogKind.MusicVideo -> "音樂影片"
    }
