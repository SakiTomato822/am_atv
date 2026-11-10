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
    /** Light or dark car UI; [lightFromMinute]..[lightUntilMinute] (minutes after midnight) for [Appearance.Time]. */
    val appearance: Appearance = Appearance.System,
    val lightFromMinute: Int = 7 * 60,
    val lightUntilMinute: Int = 19 * 60,
    val lyricStyle: LyricStyle = LyricStyle.Flow,
) {
    enum class Appearance { Light, Dark, System, Time }
    enum class LyricStyle { Flow, Soak }

    /** Whether the UI is light now, given the system night mode and the minute of the day. */
    fun isLight(systemDark: Boolean, minuteOfDay: Int): Boolean = when (appearance) {
        Appearance.Light -> true
        Appearance.Dark -> false
        Appearance.System -> !systemDark
        Appearance.Time -> if (lightFromMinute <= lightUntilMinute) {
            minuteOfDay in lightFromMinute until lightUntilMinute
        } else {
            minuteOfDay >= lightFromMinute || minuteOfDay < lightUntilMinute
        }
    }
}

/** Car playback and appearance options from Settings; one shared state for the whole app. */
class FlymePlaybackOptions private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("am_atv_flyme_playback", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(FlymePlaybackSettings(
        fadeAlbums = preferences.getBoolean(KEY_FADE_ALBUMS, false),
        fadeStations = preferences.getBoolean(KEY_FADE_STATIONS, true),
        fadePlaylists = preferences.getBoolean(KEY_FADE_PLAYLISTS, true),
        stationsAsSongs = preferences.getBoolean(KEY_STATIONS_AS_SONGS, false),
        appearance = runCatching {
            FlymePlaybackSettings.Appearance.valueOf(preferences.getString(KEY_APPEARANCE, null) ?: "System")
        }.getOrDefault(FlymePlaybackSettings.Appearance.System),
        lightFromMinute = preferences.getInt(KEY_LIGHT_FROM, 7 * 60),
        lightUntilMinute = preferences.getInt(KEY_LIGHT_UNTIL, 19 * 60),
        lyricStyle = if (preferences.getBoolean(KEY_LYRIC_SOAK, false)) FlymePlaybackSettings.LyricStyle.Soak
            else FlymePlaybackSettings.LyricStyle.Flow,
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
            .putString(KEY_APPEARANCE, next.appearance.name)
            .putInt(KEY_LIGHT_FROM, next.lightFromMinute)
            .putInt(KEY_LIGHT_UNTIL, next.lightUntilMinute)
            .putBoolean(KEY_LYRIC_SOAK, next.lyricStyle == FlymePlaybackSettings.LyricStyle.Soak)
            .apply()
    }

    companion object {
        @Volatile private var instance: FlymePlaybackOptions? = null

        fun get(context: Context): FlymePlaybackOptions = instance ?: synchronized(this) {
            instance ?: FlymePlaybackOptions(context).also { instance = it }
        }

        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_LIGHT_FROM = "light_from_minute"
        private const val KEY_LIGHT_UNTIL = "light_until_minute"
        private const val KEY_FADE_ALBUMS = "fade_albums"
        private const val KEY_FADE_STATIONS = "fade_stations"
        private const val KEY_FADE_PLAYLISTS = "fade_playlists"
        private const val KEY_STATIONS_AS_SONGS = "stations_as_songs"
        private const val KEY_LYRIC_SOAK = "lyric_style_soak"
    }
}
