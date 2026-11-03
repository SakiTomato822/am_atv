package dev.amatv.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PlaybackDiagnosticEntry(val timeMs: Long, val text: String) {
    val clock: String get() = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timeMs))
}

/**
 * The last song changes as plain sentences (gapless or a new prepare, time to sound, why the
 * next song was not ready), shown in Settings so a car owner can screenshot them without adb.
 * Holds no account data; kept across launches.
 */
class PlaybackDiagnostics private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("am_atv_playback_diagnostics", Context.MODE_PRIVATE)
    private val mutableEntries = MutableStateFlow(load())
    val entries: StateFlow<List<PlaybackDiagnosticEntry>> = mutableEntries.asStateFlow()

    fun record(text: String, timeMs: Long = System.currentTimeMillis()) {
        synchronized(this) {
            val next = (listOf(PlaybackDiagnosticEntry(timeMs, text)) + mutableEntries.value).take(MAX_ENTRIES)
            mutableEntries.value = next
            preferences.edit().putString(KEY, encode(next)).apply()
        }
    }

    fun clear() {
        synchronized(this) {
            mutableEntries.value = emptyList()
            preferences.edit().remove(KEY).apply()
        }
    }

    private fun load(): List<PlaybackDiagnosticEntry> = runCatching {
        val array = JSONArray(preferences.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlaybackDiagnosticEntry(item.getLong("time"), item.getString("text"))
        }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<PlaybackDiagnosticEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("time", it.timeMs).put("text", it.text)) }
    }.toString()

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 30

        @Volatile private var instance: PlaybackDiagnostics? = null

        fun get(context: Context): PlaybackDiagnostics = instance ?: synchronized(this) {
            instance ?: PlaybackDiagnostics(context).also { instance = it }
        }
    }
}
