package dev.amatv.core

import android.content.Context
import dev.amatv.BuildConfig
import dev.amatv.integration.ContentCache
import dev.amatv.integration.FlymePlaybackOptions
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.QueuePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode { Light, Dark, System, Time }
enum class LyricBackgroundStyle { Flow, Soak }

data class SettingsState(
    // 播放偏好 (am_atv_playback_preferences)
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val shuffleEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val lowPerformanceMode: LowPerformanceMode = LowPerformanceMode.Auto,
    internal val lowPerformanceHardware: Boolean = false,
    internal val playbackJankDetected: Boolean = false,

    // 歌词偏好 (am_atv_playback_preferences)
    val lyricsPreferred: Boolean = true,
    val lyricOffsetMs: Long = 0L,
    val lyricTextSize: LyricTextSize = if (BuildConfig.FLYME_AUTO) LyricTextSize.Large else LyricTextSize.Standard,
    val lyricTranslationEnabled: Boolean = false,
    val lyricPronunciationEnabled: Boolean = false,
    val lyricTranslationLanguageTag: String = "",
    val lyricPronunciationLanguageTag: String = "en-US",

    // 车机外观与播放选项 (am_atv_flyme_playback)
    val lyricStyle: LyricBackgroundStyle = LyricBackgroundStyle.Flow,
    val appearance: AppearanceMode = AppearanceMode.System,
    val lightFromMinute: Int = 7 * 60,
    val lightUntilMinute: Int = 19 * 60,
    val fadeAlbums: Boolean = false,
    val fadeStations: Boolean = true,
    val fadePlaylists: Boolean = true,
    val stationsAsSongs: Boolean = false,

    // 缓存偏好 (content-cache)
    val cacheDiskEnabled: Boolean = true,
    val cachePrefetchNextTwo: Boolean = true,
) {
    val autoLowPerformanceReason: AutoLowPerformanceReason?
        get() = when {
            lowPerformanceMode != LowPerformanceMode.Auto -> null
            lowPerformanceHardware -> AutoLowPerformanceReason.Hardware
            playbackJankDetected -> AutoLowPerformanceReason.PlaybackJank
            else -> null
        }

    val lowPerformance: Boolean
        get() = lowPerformanceMode == LowPerformanceMode.On || autoLowPerformanceReason != null

    internal val shouldMeasurePlaybackFrames: Boolean
        get() = lowPerformanceMode == LowPerformanceMode.Auto && !lowPerformance

    /** Whether the UI is light now, given the system night mode and the minute of the day. */
    fun isLight(systemDark: Boolean, minuteOfDay: Int): Boolean = when (appearance) {
        AppearanceMode.Light -> true
        AppearanceMode.Dark -> false
        AppearanceMode.System -> !systemDark
        AppearanceMode.Time -> if (lightFromMinute <= lightUntilMinute) {
            minuteOfDay in lightFromMinute until lightUntilMinute
        } else {
            minuteOfDay >= lightFromMinute || minuteOfDay < lightUntilMinute
        }
    }
}

interface SettingsStorage {
    fun loadInitialState(): SettingsState

    fun saveRepeatMode(mode: PlaybackRepeatMode)
    fun saveShuffleEnabled(enabled: Boolean)
    fun saveAutoplayEnabled(enabled: Boolean)
    fun saveLowPerformanceMode(mode: LowPerformanceMode)
    fun savePlaybackJankDetected()

    fun saveLyricsPreferred(preferred: Boolean)
    fun saveLyricOffsetMs(offsetMs: Long)
    fun saveLyricTextSize(size: LyricTextSize)
    fun saveLyricTranslationEnabled(enabled: Boolean)
    fun saveLyricPronunciationEnabled(enabled: Boolean)
    fun saveLyricTranslationLanguageTag(tag: String)
    fun saveLyricPronunciationLanguageTag(tag: String)

    fun saveLyricStyle(style: LyricBackgroundStyle)
    fun saveAppearance(appearance: AppearanceMode)
    fun saveLightSchedule(fromMinute: Int, untilMinute: Int)
    fun saveFadeAlbums(fade: Boolean)
    fun saveFadeStations(fade: Boolean)
    fun saveFadePlaylists(fade: Boolean)
    fun saveStationsAsSongs(asSongs: Boolean)

    fun saveCacheDiskEnabled(enabled: Boolean)
    fun saveCachePrefetchNextTwo(enabled: Boolean)
}

class SharedPreferencesSettingsStorage(context: Context) : SettingsStorage {
    private val appContext = context.applicationContext
    private val playbackPrefs = appContext.getSharedPreferences("am_atv_playback_preferences", Context.MODE_PRIVATE)
    private val flymePrefs = appContext.getSharedPreferences("am_atv_flyme_playback", Context.MODE_PRIVATE)
    private val cachePrefs = appContext.getSharedPreferences("content-cache", Context.MODE_PRIVATE)

    override fun loadInitialState(): SettingsState {
        val repeatRaw = playbackPrefs.getString(KEY_REPEAT_MODE, PlaybackRepeatMode.Off.name)
        val repeat = runCatching { PlaybackRepeatMode.valueOf(repeatRaw ?: PlaybackRepeatMode.Off.name) }
            .getOrDefault(PlaybackRepeatMode.Off)
        val textSizeRaw = playbackPrefs.getString(KEY_LYRIC_TEXT_SIZE, null).orEmpty()
        val defaultTextSize = if (BuildConfig.FLYME_AUTO) LyricTextSize.Large else LyricTextSize.Standard
        val textSize = LyricTextSize.entries.firstOrNull { it.name == textSizeRaw } ?: defaultTextSize
        val appearanceRaw = flymePrefs.getString(KEY_APPEARANCE, null).orEmpty()
        val appearance = AppearanceMode.entries.firstOrNull { it.name == appearanceRaw } ?: AppearanceMode.System
        val lyricStyle = if (flymePrefs.getBoolean(KEY_LYRIC_SOAK, false)) LyricBackgroundStyle.Soak else LyricBackgroundStyle.Flow
        val storedPerformanceMode = playbackPrefs.getString(KEY_PERFORMANCE_MODE, null)
        val performanceMode = LowPerformanceMode.entries.firstOrNull { it.name == storedPerformanceMode }
            ?: migrateLowPerformanceMode(playbackPrefs.getBoolean(KEY_LEGACY_LOW_PERFORMANCE_MODE, false))

        return SettingsState(
            repeatMode = repeat,
            shuffleEnabled = playbackPrefs.getBoolean(KEY_SHUFFLE_ENABLED, false),
            autoplayEnabled = playbackPrefs.getBoolean(KEY_AUTOPLAY_ENABLED, true),
            lowPerformanceMode = performanceMode,
            playbackJankDetected = playbackPrefs.getBoolean(KEY_PLAYBACK_JANK_DETECTED, false),
            lyricsPreferred = playbackPrefs.getBoolean(KEY_LYRICS_PREFERRED, true),
            lyricOffsetMs = playbackPrefs.getLong(KEY_LYRIC_OFFSET_MS, 0L),
            lyricTextSize = textSize,
            lyricTranslationEnabled = playbackPrefs.getBoolean(KEY_LYRIC_TRANSLATION_ENABLED, false),
            lyricPronunciationEnabled = playbackPrefs.getBoolean(KEY_LYRIC_PRONUNCIATION_ENABLED, false),
            lyricTranslationLanguageTag = playbackPrefs.getString(KEY_LYRIC_TRANSLATION_LANGUAGE, "").orEmpty(),
            lyricPronunciationLanguageTag = playbackPrefs.getString(KEY_LYRIC_PRONUNCIATION_LANGUAGE, "en-US")
                .orEmpty().ifBlank { "en-US" },
            lyricStyle = lyricStyle,
            appearance = appearance,
            lightFromMinute = flymePrefs.getInt(KEY_LIGHT_FROM, 7 * 60),
            lightUntilMinute = flymePrefs.getInt(KEY_LIGHT_UNTIL, 19 * 60),
            fadeAlbums = flymePrefs.getBoolean(KEY_FADE_ALBUMS, false),
            fadeStations = flymePrefs.getBoolean(KEY_FADE_STATIONS, true),
            fadePlaylists = flymePrefs.getBoolean(KEY_FADE_PLAYLISTS, true),
            stationsAsSongs = flymePrefs.getBoolean(KEY_STATIONS_AS_SONGS, false),
            cacheDiskEnabled = cachePrefs.getBoolean(KEY_CACHE_ENABLED, true),
            cachePrefetchNextTwo = cachePrefs.getBoolean(KEY_CACHE_PREFETCH_NEXT_TWO, true),
        )
    }

    override fun saveRepeatMode(mode: PlaybackRepeatMode) {
        playbackPrefs.edit().putString(KEY_REPEAT_MODE, mode.name).apply()
    }

    override fun saveShuffleEnabled(enabled: Boolean) {
        playbackPrefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, enabled).apply()
    }

    override fun saveAutoplayEnabled(enabled: Boolean) {
        playbackPrefs.edit().putBoolean(KEY_AUTOPLAY_ENABLED, enabled).apply()
    }

    override fun saveLowPerformanceMode(mode: LowPerformanceMode) {
        val editor = playbackPrefs.edit().putString(KEY_PERFORMANCE_MODE, mode.name)
        if (mode == LowPerformanceMode.Auto) editor.remove(KEY_PLAYBACK_JANK_DETECTED)
        editor.apply()
    }

    override fun savePlaybackJankDetected() {
        playbackPrefs.edit().putBoolean(KEY_PLAYBACK_JANK_DETECTED, true).apply()
    }

    override fun saveLyricsPreferred(preferred: Boolean) {
        playbackPrefs.edit().putBoolean(KEY_LYRICS_PREFERRED, preferred).apply()
    }

    override fun saveLyricOffsetMs(offsetMs: Long) {
        playbackPrefs.edit().putLong(KEY_LYRIC_OFFSET_MS, offsetMs).apply()
    }

    override fun saveLyricTextSize(size: LyricTextSize) {
        playbackPrefs.edit().putString(KEY_LYRIC_TEXT_SIZE, size.name).apply()
    }

    override fun saveLyricTranslationEnabled(enabled: Boolean) {
        playbackPrefs.edit().putBoolean(KEY_LYRIC_TRANSLATION_ENABLED, enabled).apply()
    }

    override fun saveLyricPronunciationEnabled(enabled: Boolean) {
        playbackPrefs.edit().putBoolean(KEY_LYRIC_PRONUNCIATION_ENABLED, enabled).apply()
    }

    override fun saveLyricTranslationLanguageTag(tag: String) {
        playbackPrefs.edit().putString(KEY_LYRIC_TRANSLATION_LANGUAGE, tag).apply()
    }

    override fun saveLyricPronunciationLanguageTag(tag: String) {
        playbackPrefs.edit().putString(KEY_LYRIC_PRONUNCIATION_LANGUAGE, tag).apply()
    }

    override fun saveLyricStyle(style: LyricBackgroundStyle) {
        flymePrefs.edit().putBoolean(KEY_LYRIC_SOAK, style == LyricBackgroundStyle.Soak).apply()
    }

    override fun saveAppearance(appearance: AppearanceMode) {
        flymePrefs.edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    override fun saveLightSchedule(fromMinute: Int, untilMinute: Int) {
        flymePrefs.edit().putInt(KEY_LIGHT_FROM, fromMinute).putInt(KEY_LIGHT_UNTIL, untilMinute).apply()
    }

    override fun saveFadeAlbums(fade: Boolean) {
        flymePrefs.edit().putBoolean(KEY_FADE_ALBUMS, fade).apply()
    }

    override fun saveFadeStations(fade: Boolean) {
        flymePrefs.edit().putBoolean(KEY_FADE_STATIONS, fade).apply()
    }

    override fun saveFadePlaylists(fade: Boolean) {
        flymePrefs.edit().putBoolean(KEY_FADE_PLAYLISTS, fade).apply()
    }

    override fun saveStationsAsSongs(asSongs: Boolean) {
        flymePrefs.edit().putBoolean(KEY_STATIONS_AS_SONGS, asSongs).apply()
    }

    override fun saveCacheDiskEnabled(enabled: Boolean) {
        cachePrefs.edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply()
    }

    override fun saveCachePrefetchNextTwo(enabled: Boolean) {
        cachePrefs.edit().putBoolean(KEY_CACHE_PREFETCH_NEXT_TWO, enabled).apply()
    }

    private companion object {
        const val KEY_REPEAT_MODE = "pref_playback_repeat_mode"
        const val KEY_SHUFFLE_ENABLED = "pref_playback_shuffle_enabled"
        const val KEY_AUTOPLAY_ENABLED = "pref_playback_autoplay_enabled"
        const val KEY_LEGACY_LOW_PERFORMANCE_MODE = "pref_playback_low_performance_mode"
        const val KEY_PERFORMANCE_MODE = "pref_playback_performance_mode"
        const val KEY_PLAYBACK_JANK_DETECTED = "pref_playback_jank_detected"
        const val KEY_LYRICS_PREFERRED = "pref_playback_lyrics_preferred"
        const val KEY_LYRIC_OFFSET_MS = "pref_playback_lyric_offset_ms"
        const val KEY_LYRIC_TEXT_SIZE = "pref_lyric_text_size"
        const val KEY_LYRIC_TRANSLATION_ENABLED = "pref_lyric_translation_enabled"
        const val KEY_LYRIC_PRONUNCIATION_ENABLED = "pref_lyric_pronunciation_enabled"
        const val KEY_LYRIC_TRANSLATION_LANGUAGE = "pref_lyric_translation_language"
        const val KEY_LYRIC_PRONUNCIATION_LANGUAGE = "pref_lyric_pronunciation_language"

        const val KEY_APPEARANCE = "appearance"
        const val KEY_LIGHT_FROM = "light_from_minute"
        const val KEY_LIGHT_UNTIL = "light_until_minute"
        const val KEY_FADE_ALBUMS = "fade_albums"
        const val KEY_FADE_STATIONS = "fade_stations"
        const val KEY_FADE_PLAYLISTS = "fade_playlists"
        const val KEY_STATIONS_AS_SONGS = "stations_as_songs"
        const val KEY_LYRIC_SOAK = "lyric_style_soak"

        const val KEY_CACHE_ENABLED = "enabled"
        const val KEY_CACHE_PREFETCH_NEXT_TWO = "prefetch-next-two"
    }
}

class SettingsStore(
    private val storage: SettingsStorage,
    private val onCacheDiskChanged: ((Boolean) -> Unit)? = null,
    private val onCachePrefetchChanged: ((Boolean) -> Unit)? = null,
    lowPerformanceHardware: Boolean = false,
) : QueuePreferences {
    private val mutableState = MutableStateFlow(storage.loadInitialState().copy(lowPerformanceHardware = lowPerformanceHardware))
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    override var repeatMode: PlaybackRepeatMode
        get() = mutableState.value.repeatMode
        set(value) {
            mutableState.value = mutableState.value.copy(repeatMode = value)
            storage.saveRepeatMode(value)
        }

    override var shuffleEnabled: Boolean
        get() = mutableState.value.shuffleEnabled
        set(value) {
            mutableState.value = mutableState.value.copy(shuffleEnabled = value)
            storage.saveShuffleEnabled(value)
        }

    override var autoplayEnabled: Boolean
        get() = mutableState.value.autoplayEnabled
        set(value) {
            mutableState.value = mutableState.value.copy(autoplayEnabled = value)
            storage.saveAutoplayEnabled(value)
        }

    fun setLowPerformanceMode(mode: LowPerformanceMode) {
        val current = mutableState.value
        storage.saveLowPerformanceMode(mode)
        mutableState.value = current.copy(
            lowPerformanceMode = mode,
            playbackJankDetected = mode != LowPerformanceMode.Auto && current.playbackJankDetected,
        )
    }

    internal fun enableLowPerformanceForPlaybackJank(): Boolean {
        val current = mutableState.value
        if (!current.shouldMeasurePlaybackFrames) return false
        storage.savePlaybackJankDetected()
        mutableState.value = current.copy(playbackJankDetected = true)
        return true
    }

    fun setLyricsPreferred(preferred: Boolean) {
        mutableState.value = mutableState.value.copy(lyricsPreferred = preferred)
        storage.saveLyricsPreferred(preferred)
    }

    fun setLyricOffsetMs(offsetMs: Long) {
        mutableState.value = mutableState.value.copy(lyricOffsetMs = offsetMs)
        storage.saveLyricOffsetMs(offsetMs)
    }

    fun setLyricTextSize(size: LyricTextSize) {
        mutableState.value = mutableState.value.copy(lyricTextSize = size)
        storage.saveLyricTextSize(size)
    }

    fun setLyricTranslationEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(lyricTranslationEnabled = enabled)
        storage.saveLyricTranslationEnabled(enabled)
    }

    fun setLyricPronunciationEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(lyricPronunciationEnabled = enabled)
        storage.saveLyricPronunciationEnabled(enabled)
    }

    fun setLyricTranslationLanguageTag(tag: String) {
        mutableState.value = mutableState.value.copy(lyricTranslationLanguageTag = tag)
        storage.saveLyricTranslationLanguageTag(tag)
    }

    fun setLyricPronunciationLanguageTag(tag: String) {
        mutableState.value = mutableState.value.copy(lyricPronunciationLanguageTag = tag)
        storage.saveLyricPronunciationLanguageTag(tag)
    }

    fun setLyricStyle(style: LyricBackgroundStyle) {
        mutableState.value = mutableState.value.copy(lyricStyle = style)
        storage.saveLyricStyle(style)
    }

    fun setAppearance(appearance: AppearanceMode) {
        mutableState.value = mutableState.value.copy(appearance = appearance)
        storage.saveAppearance(appearance)
    }

    fun setLightSchedule(fromMinute: Int, untilMinute: Int) {
        mutableState.value = mutableState.value.copy(lightFromMinute = fromMinute, lightUntilMinute = untilMinute)
        storage.saveLightSchedule(fromMinute, untilMinute)
    }

    fun setFadeAlbums(fade: Boolean) {
        mutableState.value = mutableState.value.copy(fadeAlbums = fade)
        storage.saveFadeAlbums(fade)
    }

    fun setFadeStations(fade: Boolean) {
        mutableState.value = mutableState.value.copy(fadeStations = fade)
        storage.saveFadeStations(fade)
    }

    fun setFadePlaylists(fade: Boolean) {
        mutableState.value = mutableState.value.copy(fadePlaylists = fade)
        storage.saveFadePlaylists(fade)
    }

    fun setStationsAsSongs(asSongs: Boolean) {
        mutableState.value = mutableState.value.copy(stationsAsSongs = asSongs)
        storage.saveStationsAsSongs(asSongs)
    }

    fun setCacheDiskEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(cacheDiskEnabled = enabled)
        storage.saveCacheDiskEnabled(enabled)
        onCacheDiskChanged?.invoke(enabled)
    }

    fun setCachePrefetchNextTwo(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(cachePrefetchNextTwo = enabled)
        storage.saveCachePrefetchNextTwo(enabled)
        onCachePrefetchChanged?.invoke(enabled)
    }
}
