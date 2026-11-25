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
        var savedLowPerformanceMode: LowPerformanceMode? = null
        var savedPlaybackJankDetected = initial.playbackJankDetected
            private set
        var savedLyricsPreferred: Boolean? = null
        var savedLyricOffsetMs: Long? = null
        var savedLyricTextSize: LyricTextSize? = null
        var savedLyricTranslationEnabled: Boolean? = null
        var savedLyricPronunciationEnabled: Boolean? = null
        var savedLyricTranslationLanguageTag: String? = null
        var savedLyricPronunciationLanguageTag: String? = null
        var savedHomeBackgroundStyle: HomeBackgroundStyle? = null
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

        override fun loadInitialState(): SettingsState = initial.copy(
            homeBackgroundStyle = savedHomeBackgroundStyle ?: initial.homeBackgroundStyle,
            lowPerformanceMode = savedLowPerformanceMode ?: initial.lowPerformanceMode,
            playbackJankDetected = savedPlaybackJankDetected,
        )

        override fun saveRepeatMode(mode: PlaybackRepeatMode) { savedRepeatMode = mode }
        override fun saveShuffleEnabled(enabled: Boolean) { savedShuffleEnabled = enabled }
        override fun saveAutoplayEnabled(enabled: Boolean) { savedAutoplayEnabled = enabled }
        override fun saveLowPerformanceMode(mode: LowPerformanceMode) {
            savedLowPerformanceMode = mode
            if (mode == LowPerformanceMode.Auto) savedPlaybackJankDetected = false
        }
        override fun savePlaybackJankDetected() { savedPlaybackJankDetected = true }
        override fun saveLyricsPreferred(preferred: Boolean) { savedLyricsPreferred = preferred }
        override fun saveLyricOffsetMs(offsetMs: Long) { savedLyricOffsetMs = offsetMs }
        override fun saveLyricTextSize(size: LyricTextSize) { savedLyricTextSize = size }
        override fun saveLyricTranslationEnabled(enabled: Boolean) { savedLyricTranslationEnabled = enabled }
        override fun saveLyricPronunciationEnabled(enabled: Boolean) { savedLyricPronunciationEnabled = enabled }
        override fun saveLyricTranslationLanguageTag(tag: String) { savedLyricTranslationLanguageTag = tag }
        override fun saveLyricPronunciationLanguageTag(tag: String) { savedLyricPronunciationLanguageTag = tag }
        override fun saveHomeBackgroundStyle(style: HomeBackgroundStyle) { savedHomeBackgroundStyle = style }
        override fun saveLyricStyle(style: LyricBackgroundStyle) { savedLyricStyle = style }
        override fun saveAppearance(appearance: AppearanceMode) { savedAppearance = appearance }
        override fun saveLightSchedule(fromMinute: Int, untilMinute: Int) {
            savedLightFrom = fromMinute
            savedLightUntil = untilMinute
        }
        override fun saveFadeAlbums(fade: Boolean) { savedFadeAlbums = fade }
        override fun saveFadeStations(fade: Boolean) { savedFadeStations = fade }
        override fun saveFadePlaylists(fade: Boolean) { savedFadePlaylists = fade }
        override fun saveLoudnessMode(mode: dev.amatv.integration.LoudnessMode) = Unit
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
        assertEquals(LowPerformanceMode.Auto, state.lowPerformanceMode)
        assertFalse(state.lowPerformance)
        assertTrue(state.lyricsPreferred)
        assertEquals(0L, state.lyricOffsetMs)
        assertFalse(state.lyricTranslationEnabled)
        assertFalse(state.lyricPronunciationEnabled)
        assertEquals(HomeBackgroundStyle.Lava, state.homeBackgroundStyle)
        assertEquals(LyricBackgroundStyle.Flow, state.lyricStyle)
        assertEquals(AppearanceMode.System, state.appearance)
        assertTrue(state.cacheDiskEnabled)
        assertTrue(state.cachePrefetchNextTwo)
    }

    @Test
    fun persistsHomeBackgroundStyleAndPublishesItImmediately() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)

        store.setHomeBackgroundStyle(HomeBackgroundStyle.Ribbon)
        assertEquals(HomeBackgroundStyle.Ribbon, store.state.value.homeBackgroundStyle)
        assertEquals(HomeBackgroundStyle.Ribbon, SettingsStore(storage).state.value.homeBackgroundStyle)

        store.setHomeBackgroundStyle(HomeBackgroundStyle.Lava)
        assertEquals(HomeBackgroundStyle.Lava, SettingsStore(storage).state.value.homeBackgroundStyle)
    }

    @Test
    fun readsExistingPreferencesOnStartup() {
        val custom = SettingsState(
            repeatMode = PlaybackRepeatMode.One,
            shuffleEnabled = true,
            lowPerformanceMode = LowPerformanceMode.On,
            lyricOffsetMs = -250L,
            lyricStyle = LyricBackgroundStyle.Soak,
            appearance = AppearanceMode.Dark,
        )
        val storage = FakeSettingsStorage(custom)
        val store = SettingsStore(storage)

        val state = store.state.value
        assertEquals(PlaybackRepeatMode.One, state.repeatMode)
        assertTrue(state.shuffleEnabled)
        assertTrue(state.lowPerformance)
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

    }

    @Test
    fun manualModesOverrideHardwareAndDisableSampling() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage, lowPerformanceHardware = true)

        assertTrue(store.state.value.lowPerformance)
        assertEquals(AutoLowPerformanceReason.Hardware, store.state.value.autoLowPerformanceReason)
        assertFalse(store.enableLowPerformanceForPlaybackJank())

        store.setLowPerformanceMode(LowPerformanceMode.Off)
        assertFalse(store.state.value.lowPerformance)
        assertNull(store.state.value.autoLowPerformanceReason)
        assertFalse(store.enableLowPerformanceForPlaybackJank())
        assertEquals(LowPerformanceMode.Off, storage.savedLowPerformanceMode)

        store.setLowPerformanceMode(LowPerformanceMode.On)
        assertTrue(store.state.value.lowPerformance)
        assertNull(store.state.value.autoLowPerformanceReason)
        assertFalse(store.enableLowPerformanceForPlaybackJank())
        assertEquals(LowPerformanceMode.On, storage.savedLowPerformanceMode)
    }

    @Test
    fun playbackJankPersistsAndSelectingAutoAgainClearsIt() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)
        assertTrue(store.state.value.shouldMeasurePlaybackFrames)
        assertTrue(store.enableLowPerformanceForPlaybackJank())
        assertTrue(store.state.value.lowPerformance)
        assertFalse(store.state.value.shouldMeasurePlaybackFrames)
        assertFalse(store.enableLowPerformanceForPlaybackJank())

        val restarted = SettingsStore(storage)
        assertTrue(restarted.state.value.lowPerformance)
        assertEquals(AutoLowPerformanceReason.PlaybackJank, restarted.state.value.autoLowPerformanceReason)
        restarted.setLowPerformanceMode(LowPerformanceMode.Auto)
        assertFalse(restarted.state.value.lowPerformance)
        assertTrue(restarted.state.value.shouldMeasurePlaybackFrames)
        assertFalse(storage.savedPlaybackJankDetected)
    }

    @Test
    fun manualOffSuppressesPersistedJankAcrossRestarts() {
        val storage = FakeSettingsStorage(SettingsState(playbackJankDetected = true))
        SettingsStore(storage).setLowPerformanceMode(LowPerformanceMode.Off)

        val restarted = SettingsStore(storage)
        assertFalse(restarted.state.value.lowPerformance)
        assertNull(restarted.state.value.autoLowPerformanceReason)
        assertFalse(restarted.state.value.shouldMeasurePlaybackFrames)
    }

    @Test
    fun returningToAutoStillAppliesTheStartupHardwareDecision() {
        val store = SettingsStore(FakeSettingsStorage(), lowPerformanceHardware = true)
        store.setLowPerformanceMode(LowPerformanceMode.Off)
        store.setLowPerformanceMode(LowPerformanceMode.Auto)

        assertTrue(store.state.value.lowPerformance)
        assertEquals(AutoLowPerformanceReason.Hardware, store.state.value.autoLowPerformanceReason)
        assertFalse(store.state.value.shouldMeasurePlaybackFrames)
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
