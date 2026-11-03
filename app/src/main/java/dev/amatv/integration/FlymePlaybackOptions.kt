package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FlymePlaybackSettings(
    /** Fade out and back in at a gapless song change, per kind of queue. */
    val fadeAlbums: Boolean = false,
    val fadeStations: Boolean = true,
    val fadePlaylists: Boolean = true,
    /** Play stations song by song on the native player, never through the Widevine web player. */
    val stationsAsSongs: Boolean = false,
)

/** Car playback options from Settings; the player and the settings page share this one state. */
class FlymePlaybackOptions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("am_atv_flyme_playback", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(FlymePlaybackSettings(
        fadeAlbums = preferences.getBoolean(KEY_FADE_ALBUMS, false),
        fadeStations = preferences.getBoolean(KEY_FADE_STATIONS, true),
        fadePlaylists = preferences.getBoolean(KEY_FADE_PLAYLISTS, true),
        stationsAsSongs = preferences.getBoolean(KEY_STATIONS_AS_SONGS, false),
    ))
    val settings: StateFlow<FlymePlaybackSettings> = mutableSettings.asStateFlow()

    fun update(change: (FlymePlaybackSettings) -> FlymePlaybackSettings) {
        val next = change(mutableSettings.value)
        mutableSettings.value = next
        preferences.edit()
            .putBoolean(KEY_FADE_ALBUMS, next.fadeAlbums)
            .putBoolean(KEY_FADE_STATIONS, next.fadeStations)
            .putBoolean(KEY_FADE_PLAYLISTS, next.fadePlaylists)
            .putBoolean(KEY_STATIONS_AS_SONGS, next.stationsAsSongs)
            .apply()
    }

    private companion object {
        const val KEY_FADE_ALBUMS = "fade_albums"
        const val KEY_FADE_STATIONS = "fade_stations"
        const val KEY_FADE_PLAYLISTS = "fade_playlists"
        const val KEY_STATIONS_AS_SONGS = "stations_as_songs"
    }
}
