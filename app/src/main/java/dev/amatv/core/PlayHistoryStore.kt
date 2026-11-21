package dev.amatv.core

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import dev.amatv.playback.ContextKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val PLAY_HISTORY_THRESHOLD_MS: Long = 30_000L
const val PLAY_HISTORY_MAX_ENTRIES: Int = 1_000

enum class PlayHistorySource {
    Album,
    Playlist,
    Radio,
    Search,
    Other,
}

enum class PlayHistoryDateGroup {
    Today,
    Yesterday,
    ThisWeek,
    Earlier,
}

@Immutable
data class PlayHistoryEntry(
    val songKey: String,
    val catalogId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val playedAtMs: Long,
    val source: PlayHistorySource,
) {
    fun toTrack(): Track = Track(
        id = catalogId?.takeIf(String::isNotBlank) ?: songKey,
        title = title,
        artist = artist,
        album = album,
        durationMs = 0L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogId = catalogId,
        catalogKind = CatalogKind.Song,
        artworkUrl = artworkUrl,
    )
}

@Immutable
data class PlayHistoryDateSection(
    val group: PlayHistoryDateGroup,
    val entries: List<PlayHistoryEntry>,
)

interface PlayHistoryStorage {
    fun load(): List<PlayHistoryEntry>
    fun save(entries: List<PlayHistoryEntry>)
    fun clear()
}

fun playHistorySongKey(track: Track): String =
    track.catalogId?.takeIf(String::isNotBlank) ?: track.id

fun qualifiesForPlayHistory(
    listenedMs: Long,
    ended: Boolean,
    thresholdMs: Long = PLAY_HISTORY_THRESHOLD_MS,
): Boolean = ended || listenedMs >= thresholdMs

fun resolvePlayHistorySource(
    contextKind: ContextKind?,
    isStation: Boolean,
    currentDestination: Destination,
    previousDestination: Destination,
): PlayHistorySource = when {
    isStation -> PlayHistorySource.Radio
    contextKind == ContextKind.Album -> PlayHistorySource.Album
    contextKind == ContextKind.Playlist || contextKind == ContextKind.LibraryPlaylist -> PlayHistorySource.Playlist
    currentDestination == Destination.Search ||
        (currentDestination == Destination.NowPlaying && previousDestination == Destination.Search) -> PlayHistorySource.Search
    else -> PlayHistorySource.Other
}

/**
 * Prepends [entry] to [existing] newest-first, updating only the timestamp/metadata when the
 * immediately preceding entry is the exact same song, and trimming above [maxEntries].
 */
fun appendPlayHistoryEntry(
    existing: List<PlayHistoryEntry>,
    entry: PlayHistoryEntry,
    maxEntries: Int = PLAY_HISTORY_MAX_ENTRIES,
): List<PlayHistoryEntry> {
    if (maxEntries <= 0) return emptyList()
    val head = existing.firstOrNull()
    val updated = if (head != null && head.songKey == entry.songKey) {
        listOf(
            head.copy(
                catalogId = entry.catalogId ?: head.catalogId,
                title = entry.title.ifBlank { head.title },
                artist = entry.artist.ifBlank { head.artist },
                album = entry.album.ifBlank { head.album },
                artworkUrl = entry.artworkUrl ?: head.artworkUrl,
                playedAtMs = entry.playedAtMs,
                source = entry.source,
            ),
        ) + existing.drop(1)
    } else {
        listOf(entry) + existing
    }
    return updated.take(maxEntries)
}

fun groupPlayHistoryByDate(
    entries: List<PlayHistoryEntry>,
    nowMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<PlayHistoryDateSection> {
    if (entries.isEmpty()) return emptyList()
    val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val buckets = PlayHistoryDateGroup.entries.associateWith { mutableListOf<PlayHistoryEntry>() }

    for (entry in entries) {
        val entryDate = Instant.ofEpochMilli(entry.playedAtMs).atZone(zoneId).toLocalDate()
        val group = when {
            !entryDate.isBefore(today) -> PlayHistoryDateGroup.Today
            entryDate == yesterday -> PlayHistoryDateGroup.Yesterday
            !entryDate.isBefore(weekStart) -> PlayHistoryDateGroup.ThisWeek
            else -> PlayHistoryDateGroup.Earlier
        }
        buckets.getValue(group).add(entry)
    }

    return PlayHistoryDateGroup.entries.mapNotNull { group ->
        val list = buckets.getValue(group)
        if (list.isEmpty()) null else PlayHistoryDateSection(group = group, entries = list)
    }
}

class PlayHistoryStore(
    private val storage: PlayHistoryStorage,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val thresholdMs: Long = PLAY_HISTORY_THRESHOLD_MS,
    private val maxEntries: Int = PLAY_HISTORY_MAX_ENTRIES,
) {
    private val lock = Any()
    private val _entries = MutableStateFlow(storage.load().take(maxEntries))
    val entries: StateFlow<List<PlayHistoryEntry>> = _entries.asStateFlow()

    private var activeSongKey: String? = null
    private var accumulatedPlayMs: Long = 0L
    private var playingSinceElapsedMs: Long? = null
    private var recordedCurrentPlay: Boolean = false

    fun onPlaybackProgress(
        track: Track,
        isPlaying: Boolean,
        elapsedRealtimeMs: Long,
        source: PlayHistorySource,
        ended: Boolean = false,
    ) {
        val recordToSave: List<PlayHistoryEntry>?
        synchronized(lock) {
            if (!isTrackRecordable(track)) {
                playingSinceElapsedMs = null
                return
            }
            val songKey = playHistorySongKey(track)
            if (activeSongKey != songKey) {
                activeSongKey = songKey
                accumulatedPlayMs = 0L
                playingSinceElapsedMs = if (isPlaying) elapsedRealtimeMs else null
                recordedCurrentPlay = false
            } else {
                val startedMs = playingSinceElapsedMs
                if (startedMs != null && elapsedRealtimeMs >= startedMs) {
                    accumulatedPlayMs += elapsedRealtimeMs - startedMs
                }
                playingSinceElapsedMs = if (isPlaying) elapsedRealtimeMs else null
            }

            if (!recordedCurrentPlay && qualifiesForPlayHistory(accumulatedPlayMs, ended, thresholdMs)) {
                val entry = PlayHistoryEntry(
                    songKey = songKey,
                    catalogId = track.catalogId?.takeIf(String::isNotBlank),
                    title = track.title.trim(),
                    artist = track.artist.trim(),
                    album = track.album.trim(),
                    artworkUrl = track.artworkUrl?.takeIf(String::isNotBlank),
                    playedAtMs = wallClock(),
                    source = source,
                )
                val updated = appendPlayHistoryEntry(_entries.value, entry, maxEntries)
                _entries.value = updated
                recordedCurrentPlay = true
                recordToSave = updated
            } else {
                recordToSave = null
            }

            if (ended) {
                activeSongKey = null
                accumulatedPlayMs = 0L
                playingSinceElapsedMs = null
                recordedCurrentPlay = false
            }
        }
        if (recordToSave != null) persist(recordToSave)
    }

    fun onTrackFinished(
        track: Track,
        elapsedRealtimeMs: Long,
        source: PlayHistorySource,
    ) {
        onPlaybackProgress(
            track = track,
            isPlaying = false,
            elapsedRealtimeMs = elapsedRealtimeMs,
            source = source,
            ended = true,
        )
    }

    fun remainingThresholdMs(): Long = synchronized(lock) {
        if (recordedCurrentPlay || activeSongKey == null) 0L
        else (thresholdMs - accumulatedPlayMs).coerceAtLeast(0L)
    }

    fun clear() {
        synchronized(lock) {
            activeSongKey = null
            accumulatedPlayMs = 0L
            playingSinceElapsedMs = null
            recordedCurrentPlay = false
            _entries.value = emptyList()
        }
        if (coroutineScope != null) {
            coroutineScope.launch(ioDispatcher) { storage.clear() }
        } else {
            storage.clear()
        }
    }

    private fun isTrackRecordable(track: Track): Boolean =
        track.catalogKind == CatalogKind.Song && track.title.isNotBlank()

    private fun persist(snapshot: List<PlayHistoryEntry>) {
        if (coroutineScope != null) {
            coroutineScope.launch(ioDispatcher) { storage.save(snapshot) }
        } else {
            storage.save(snapshot)
        }
    }
}

class SharedPreferencesPlayHistoryStorage(context: Context) : PlayHistoryStorage {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): List<PlayHistoryEntry> = runCatching {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val songKey = obj.optString("songKey").trim()
                val title = obj.optString("title").trim()
                if (songKey.isEmpty() || title.isEmpty()) continue
                val source = runCatching {
                    PlayHistorySource.valueOf(obj.optString("source"))
                }.getOrDefault(PlayHistorySource.Other)
                add(
                    PlayHistoryEntry(
                        songKey = songKey,
                        catalogId = obj.optString("catalogId").takeIf { it.isNotBlank() },
                        title = title,
                        artist = obj.optString("artist"),
                        album = obj.optString("album"),
                        artworkUrl = obj.optString("artworkUrl").takeIf { it.isNotBlank() },
                        playedAtMs = obj.optLong("playedAtMs", 0L),
                        source = source,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<PlayHistoryEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("songKey", entry.songKey)
            entry.catalogId?.let { obj.put("catalogId", it) }
            obj.put("title", entry.title)
            obj.put("artist", entry.artist)
            obj.put("album", entry.album)
            entry.artworkUrl?.let { obj.put("artworkUrl", it) }
            obj.put("playedAtMs", entry.playedAtMs)
            obj.put("source", entry.source.name)
            array.put(obj)
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private companion object {
        const val PREFS_NAME = "am_atv_play_history"
        const val KEY_ENTRIES = "entries"
    }
}
