package dev.amatv.core

import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    internal class FakeSettingsStorage(
        private val initial: SettingsState = SettingsState(),
    ) : SettingsStorage {
        var savedRepeatMode: PlaybackRepeatMode? = null
        var savedShuffleEnabled: Boolean? = null
        var savedAutoplayEnabled: Boolean? = null
        var savedLowPerformanceMode: Boolean? = null
        var savedLyricsPreferred: Boolean? = null
        var savedLyricOffsetMs: Long? = null
        var savedLyricTextSize: LyricTextSize? = null
        var savedLyricTranslationEnabled: Boolean? = null
        var savedLyricPronunciationEnabled: Boolean? = null
        var savedLyricTranslationLanguageTag: String? = null
        var savedLyricPronunciationLanguageTag: String? = null
        var savedLyricStyle: LyricBackgroundStyle? = null
        var savedAppearance: AppearanceMode? = null
        var savedLightFrom: Int? = null
        var savedLightUntil: Int? = null
        var savedFadeAlbums: Boolean? = null
        var savedFadeStations: Boolean? = null
        var savedFadePlaylists: Boolean? = null
        var savedStationsAsSongs: Boolean? = null
        var savedCacheDiskEnabled: Boolean? = null
        var savedCachePrefetchNextTwo: Boolean? = null

        override fun loadInitialState(): SettingsState = initial

        override fun saveRepeatMode(mode: PlaybackRepeatMode) { savedRepeatMode = mode }
        override fun saveShuffleEnabled(enabled: Boolean) { savedShuffleEnabled = enabled }
        override fun saveAutoplayEnabled(enabled: Boolean) { savedAutoplayEnabled = enabled }
        override fun saveLowPerformanceMode(enabled: Boolean) { savedLowPerformanceMode = enabled }
        override fun saveLyricsPreferred(preferred: Boolean) { savedLyricsPreferred = preferred }
        override fun saveLyricOffsetMs(offsetMs: Long) { savedLyricOffsetMs = offsetMs }
        override fun saveLyricTextSize(size: LyricTextSize) { savedLyricTextSize = size }
        override fun saveLyricTranslationEnabled(enabled: Boolean) { savedLyricTranslationEnabled = enabled }
        override fun saveLyricPronunciationEnabled(enabled: Boolean) { savedLyricPronunciationEnabled = enabled }
        override fun saveLyricTranslationLanguageTag(tag: String) { savedLyricTranslationLanguageTag = tag }
        override fun saveLyricPronunciationLanguageTag(tag: String) { savedLyricPronunciationLanguageTag = tag }
        override fun saveLyricStyle(style: LyricBackgroundStyle) { savedLyricStyle = style }
        override fun saveAppearance(appearance: AppearanceMode) { savedAppearance = appearance }
        override fun saveLightSchedule(fromMinute: Int, untilMinute: Int) {
            savedLightFrom = fromMinute
            savedLightUntil = untilMinute
        }
        override fun saveFadeAlbums(fade: Boolean) { savedFadeAlbums = fade }
        override fun saveFadeStations(fade: Boolean) { savedFadeStations = fade }
        override fun saveFadePlaylists(fade: Boolean) { savedFadePlaylists = fade }
        override fun saveStationsAsSongs(asSongs: Boolean) { savedStationsAsSongs = asSongs }
        override fun saveCacheDiskEnabled(enabled: Boolean) { savedCacheDiskEnabled = enabled }
        override fun saveCachePrefetchNextTwo(enabled: Boolean) { savedCachePrefetchNextTwo = enabled }
    }

    @Test
    fun initializesWithDefaultValuesWhenPreferencesEmpty() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)

        val state = store.state.value
        assertEquals(PlaybackRepeatMode.Off, state.repeatMode)
        assertFalse(state.shuffleEnabled)
        assertTrue(state.autoplayEnabled)
        assertFalse(state.lowPerformanceMode)
        assertTrue(state.lyricsPreferred)
        assertEquals(0L, state.lyricOffsetMs)
        assertFalse(state.lyricTranslationEnabled)
        assertFalse(state.lyricPronunciationEnabled)
        assertEquals(LyricBackgroundStyle.Flow, state.lyricStyle)
        assertEquals(AppearanceMode.System, state.appearance)
        assertTrue(state.cacheDiskEnabled)
        assertTrue(state.cachePrefetchNextTwo)
    }

    @Test
    fun readsExistingPreferencesOnStartup() {
        val custom = SettingsState(
            repeatMode = PlaybackRepeatMode.One,
            shuffleEnabled = true,
            lowPerformanceMode = true,
            lyricOffsetMs = -250L,
            lyricStyle = LyricBackgroundStyle.Soak,
            appearance = AppearanceMode.Dark,
        )
        val storage = FakeSettingsStorage(custom)
        val store = SettingsStore(storage)

        val state = store.state.value
        assertEquals(PlaybackRepeatMode.One, state.repeatMode)
        assertTrue(state.shuffleEnabled)
        assertTrue(state.lowPerformanceMode)
        assertEquals(-250L, state.lyricOffsetMs)
        assertEquals(LyricBackgroundStyle.Soak, state.lyricStyle)
        assertEquals(AppearanceMode.Dark, state.appearance)
    }

    @Test
    fun updatingSettingPersistsOnlyModifiedKey() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)

        store.setLyricOffsetMs(500L)
        assertEquals(500L, store.state.value.lyricOffsetMs)
        assertEquals(500L, storage.savedLyricOffsetMs)
        assertNull(storage.savedRepeatMode)
        assertNull(storage.savedLyricStyle)

        store.setLyricStyle(LyricBackgroundStyle.Soak)
        assertEquals(LyricBackgroundStyle.Soak, store.state.value.lyricStyle)
        assertEquals(LyricBackgroundStyle.Soak, storage.savedLyricStyle)
        assertNull(storage.savedAppearance)

    }

    @Test
    fun queuePreferencesInterfaceUpdatesReflectInStateAndStorage() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)

        store.repeatMode = PlaybackRepeatMode.All
        assertEquals(PlaybackRepeatMode.All, store.state.value.repeatMode)
        assertEquals(PlaybackRepeatMode.All, store.repeatMode)
        assertEquals(PlaybackRepeatMode.All, storage.savedRepeatMode)

        store.shuffleEnabled = true
        assertTrue(store.state.value.shuffleEnabled)
        assertTrue(store.shuffleEnabled)
        assertTrue(storage.savedShuffleEnabled == true)

        store.autoplayEnabled = false
        assertFalse(store.state.value.autoplayEnabled)
        assertFalse(store.autoplayEnabled)
        assertTrue(storage.savedAutoplayEnabled == false)

        store.lowPerformanceMode = true
        assertTrue(store.state.value.lowPerformanceMode)
        assertTrue(store.lowPerformanceMode)
        assertTrue(storage.savedLowPerformanceMode == true)
    }

    @Test
    fun isLightCalculatesCorrectlyForVariousModes() {
        val lightState = SettingsState(appearance = AppearanceMode.Light)
        assertTrue(lightState.isLight(systemDark = true, minuteOfDay = 0))

        val darkState = SettingsState(appearance = AppearanceMode.Dark)
        assertFalse(darkState.isLight(systemDark = false, minuteOfDay = 720))

        val systemState = SettingsState(appearance = AppearanceMode.System)
        assertTrue(systemState.isLight(systemDark = false, minuteOfDay = 720))
        assertFalse(systemState.isLight(systemDark = true, minuteOfDay = 720))

        // Time mode: 7:00 (420 min) to 19:00 (1140 min)
        val timeState = SettingsState(
            appearance = AppearanceMode.Time,
            lightFromMinute = 7 * 60,
            lightUntilMinute = 19 * 60,
        )
        assertTrue(timeState.isLight(systemDark = false, minuteOfDay = 720))
        assertFalse(timeState.isLight(systemDark = false, minuteOfDay = 300))
        assertFalse(timeState.isLight(systemDark = false, minuteOfDay = 1200))

        // Time mode crossing midnight: 22:00 (1320 min) to 06:00 (360 min)
        val crossMidnight = SettingsState(
            appearance = AppearanceMode.Time,
            lightFromMinute = 22 * 60,
            lightUntilMinute = 6 * 60,
        )
        assertTrue(crossMidnight.isLight(systemDark = false, minuteOfDay = 23 * 60))
        assertTrue(crossMidnight.isLight(systemDark = false, minuteOfDay = 2 * 60))
        assertFalse(crossMidnight.isLight(systemDark = false, minuteOfDay = 12 * 60))
    }
}
