package dev.amatv.integration

import android.content.Context
import android.content.SharedPreferences
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track

enum class AutoplaySource {
    AppleMusic,
    YouTubeMusic,
}

/** Only a recognized stored selection opts in to a different recommendation provider. */
internal fun autoplaySourceFromStoredValue(raw: String?): AutoplaySource =
    AutoplaySource.entries.firstOrNull { it.name == raw } ?: AutoplaySource.AppleMusic

data class PlaybackSession(
    val queue: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val destination: Destination,
    val previousDestination: Destination,
    val radioActive: Boolean,
    val radioSeed: Track?,
    val radioContinuation: String?,
    val autoplaySource: AutoplaySource = AutoplaySource.AppleMusic,
    val radioSeedVideoId: String? = null,
    val autoplayEntryIndices: Set<Int> = emptySet(),
    val queueEntryIds: List<Long> = emptyList(),
    val playedEntryIds: Set<Long> = emptySet(),
    val contextQueue: List<Track> = emptyList(),
    val contextEntryIds: List<Long> = emptyList(),
    val sessionId: Long = 0L,
    val persistenceId: String = "",
    /** Store-owned write revision; callers do not need to assign it. */
    val savedRevision: Long = 0L,
)

/** Remove only malformed recommendations whose provenance was actually saved. */
internal fun PlaybackSession.sanitizedAutoplayEntries(): PlaybackSession? {
    if (queue.isEmpty()) return null
    val oldCurrent = currentIndex.coerceIn(queue.indices)
    val retained = queue.indices.filter { index ->
        index !in autoplayEntryIndices || queue[index].catalogKind == CatalogKind.Song
    }
    if (retained.isEmpty()) return null
    val selected = if (oldCurrent in retained) oldCurrent else {
        retained.firstOrNull { it > oldCurrent && queue[it].catalogKind == CatalogKind.Song }
            ?: retained.lastOrNull { it < oldCurrent && queue[it].catalogKind == CatalogKind.Song }
            ?: retained.firstOrNull { it > oldCurrent }
            ?: retained.last()
    }
    val changedCurrent = selected != oldCurrent
    return copy(
        queue = retained.map(queue::get),
        currentIndex = retained.indexOf(selected),
        queueEntryIds = if (queueEntryIds.isEmpty()) emptyList() else retained.map { queueEntryIds.getOrElse(it) { 0L } },
        positionMs = if (changedCurrent) 0L else positionMs,
        autoplayEntryIndices = retained.withIndex().filter { it.value in autoplayEntryIndices }
            .map { it.index }.toSet(),
        radioSeed = if (changedCurrent) queue[selected] else radioSeed,
        radioContinuation = if (changedCurrent) null else radioContinuation,
        radioSeedVideoId = if (changedCurrent) null else radioSeedVideoId,
    )
}

interface QueuePreferences {
    var repeatMode: PlaybackRepeatMode
    var shuffleEnabled: Boolean
    var autoplayEnabled: Boolean
    var autoplaySource: AutoplaySource
    var lowPerformanceMode: Boolean
}

/**
 * Persists the logical queue, playhead, active screen, and repeat/shuffle/YTM radio modes.
 */
class PlaybackPreferences(context: Context) : QueuePreferences {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("am_atv_playback_preferences", Context.MODE_PRIVATE)

    private val sessions = PlaybackSessionPersistence.get(context.applicationContext, preferences)

    override var repeatMode: PlaybackRepeatMode
        get() {
            val raw = preferences.getString(KEY_REPEAT_MODE, PlaybackRepeatMode.Off.name)
            return runCatching { PlaybackRepeatMode.valueOf(raw ?: PlaybackRepeatMode.Off.name) }
                .getOrDefault(PlaybackRepeatMode.Off)
        }
        set(value) {
            preferences.edit().putString(KEY_REPEAT_MODE, value.name).apply()
        }

    override var shuffleEnabled: Boolean
        get() = preferences.getBoolean(KEY_SHUFFLE_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()
        }

    override var autoplayEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTOPLAY_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTOPLAY_ENABLED, value).apply()
        }

    override var autoplaySource: AutoplaySource
        get() {
            val raw = runCatching { preferences.getString(KEY_AUTOPLAY_SOURCE, null) }.getOrNull()
            return autoplaySourceFromStoredValue(raw)
        }
        set(value) {
            preferences.edit().putString(KEY_AUTOPLAY_SOURCE, value.name).apply()
        }

    override var lowPerformanceMode: Boolean
        get() = preferences.getBoolean(KEY_LOW_PERFORMANCE_MODE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_LOW_PERFORMANCE_MODE, value).apply()
        }

    var lyricOffsetMs: Long
        get() = preferences.getLong(KEY_LYRIC_OFFSET_MS, 0L)
        set(value) {
            preferences.edit().putLong(KEY_LYRIC_OFFSET_MS, value).apply()
        }

    var lyricTranslationEnabled: Boolean
        get() = preferences.getBoolean(KEY_LYRIC_TRANSLATION_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_LYRIC_TRANSLATION_ENABLED, value).apply()
        }

    var lyricTextSize: LyricTextSize
        get() = enumValueOrDefault(
            preferences.getString(KEY_LYRIC_TEXT_SIZE, null).orEmpty(),
            if (dev.amatv.BuildConfig.FLYME_AUTO) LyricTextSize.Large else LyricTextSize.Standard,
        )
        set(value) { preferences.edit().putString(KEY_LYRIC_TEXT_SIZE, value.name).apply() }

    var lyricPronunciationEnabled: Boolean
        get() = preferences.getBoolean(KEY_LYRIC_PRONUNCIATION_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_LYRIC_PRONUNCIATION_ENABLED, value).apply()
        }

    /** Empty means follow the current app language. */
    var lyricTranslationLanguageTag: String
        get() = preferences.getString(KEY_LYRIC_TRANSLATION_LANGUAGE, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_LYRIC_TRANSLATION_LANGUAGE, value).apply()
        }

    /** English/Latin is the useful default for romanized pronunciation. */
    var lyricPronunciationLanguageTag: String
        get() = preferences.getString(KEY_LYRIC_PRONUNCIATION_LANGUAGE, "en-US")
            .orEmpty()
            .ifBlank { "en-US" }
        set(value) {
            preferences.edit().putString(KEY_LYRIC_PRONUNCIATION_LANGUAGE, value).apply()
        }

    val activeTab: Destination
        get() = enumValueOrDefault(
            preferences.getString(KEY_ACTIVE_TAB, null).orEmpty(), Destination.Home,
        )

    fun saveActiveTab(tab: Destination, synchronous: Boolean = false) {
        val edit = preferences.edit().putString(KEY_ACTIVE_TAB, tab.name)
        if (synchronous) edit.commit() else edit.apply()
    }

    /** Timeline serialization/disk I/O is coalesced off the UI thread. */
    fun saveSession(session: PlaybackSession, synchronous: Boolean = false) = sessions.save(session, synchronous)

    fun clearSession() = sessions.clear()

    /** Blocking file read/parse: call on Dispatchers.IO. */
    fun restoreSession(): PlaybackSession? = sessions.restore()

    /** Bounded cursor/cover metadata only, safe for initial composition. */
    fun restoreCheckpointSession(): PlaybackSession? = sessions.checkpoint()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private companion object {
        const val KEY_ACTIVE_TAB = "pref_active_tab"
        const val KEY_REPEAT_MODE = "pref_playback_repeat_mode"
        const val KEY_SHUFFLE_ENABLED = "pref_playback_shuffle_enabled"
        const val KEY_AUTOPLAY_ENABLED = "pref_playback_autoplay_enabled"
        const val KEY_AUTOPLAY_SOURCE = "pref_playback_autoplay_source"
        const val KEY_LYRIC_OFFSET_MS = "pref_playback_lyric_offset_ms"
        const val KEY_LYRIC_TRANSLATION_ENABLED = "pref_lyric_translation_enabled"
        const val KEY_LYRIC_PRONUNCIATION_ENABLED = "pref_lyric_pronunciation_enabled"
        const val KEY_LYRIC_TRANSLATION_LANGUAGE = "pref_lyric_translation_language"
        const val KEY_LYRIC_PRONUNCIATION_LANGUAGE = "pref_lyric_pronunciation_language"
        const val KEY_LOW_PERFORMANCE_MODE = "pref_playback_low_performance_mode"
        const val KEY_LYRIC_TEXT_SIZE = "pref_lyric_text_size"
    }
}
