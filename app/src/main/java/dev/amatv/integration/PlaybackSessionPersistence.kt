package dev.amatv.integration

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One process-wide writer coalesces timeline changes; cursor checkpoints stay small. */
internal class PlaybackSessionPersistence private constructor(
    context: Context,
    private val legacyPreferences: SharedPreferences,
) {
    private val cursorPreferences = context.getSharedPreferences("am_atv_playback_cursor", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.filesDir, "playback-session-v2.json"))
    private val fileLock = Any()
    private val pendingLock = Any()
    private var pending: PlaybackSession? = null
    private var writing = false
    private var lastStructure: Structure? = null
    private val revision = AtomicLong(System.currentTimeMillis())

    private data class Structure(
        val persistenceId: String,
        val ids: List<Long>,
        val autoplay: Set<Int>,
        val played: Set<Long>,
        val contextIds: List<Long>,
    )

    fun save(session: PlaybackSession, synchronous: Boolean) {
        if (session.queue.isEmpty()) return
        // ON_STOP commits only a bounded current-cover/cursor/recovery window in
        // a separate preferences file, never the potentially long timeline JSON.
        val snapshot = session.copy(savedRevision = revision.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) })
        val checkpoint = PlaybackSessionCodec.encodeCheckpoint(snapshot)
        val edit = cursorPreferences.edit().putString(CHECKPOINT, checkpoint)
        if (synchronous) edit.commit() else edit.apply()
        synchronized(pendingLock) {
            pending = snapshot
            if (writing) return
            writing = true
        }
        writer.execute {
            while (true) {
                val next = synchronized(pendingLock) {
                    pending.also {
                        pending = null
                        if (it == null) writing = false
                    }
                } ?: break
                try {
                    val structure = Structure(next.persistenceId, next.queueEntryIds, next.autoplayEntryIndices,
                        next.playedEntryIds, next.contextEntryIds)
                    // Five-second playhead updates need no re-encoding of old metadata.
                    if (structure == lastStructure && next.queueEntryIds.isNotEmpty()) continue
                    val bytes = PlaybackSessionCodec.encode(next).toByteArray(Charsets.UTF_8)
                    synchronized(fileLock) {
                        val output = file.startWrite()
                        try {
                            output.write(bytes)
                            file.finishWrite(output)
                        } catch (error: Throwable) {
                            file.failWrite(output)
                            throw error
                        }
                    }
                    lastStructure = structure
                    legacyPreferences.edit().remove(LEGACY_SESSION).apply()
                } catch (error: Exception) {
                    // Keep the previous atomic snapshot and the fresh cursor checkpoint.
                    Log.w("AMATV-session", "Could not persist playback timeline", error)
                }
            }
        }
    }

    fun checkpoint(): PlaybackSession? = cursorPreferences.getString(CHECKPOINT, null)
        ?.let(PlaybackSessionCodec::decode)

    /** Caller must run this full-history read/parse on Dispatchers.IO. */
    fun restore(): PlaybackSession? {
        val full = synchronized(fileLock) {
            runCatching { file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrNull()?.let(PlaybackSessionCodec::decode)
        } ?: legacyPreferences.getString(LEGACY_SESSION, null)?.let(PlaybackSessionCodec::decode)
        full?.let { value -> revision.updateAndGet { maxOf(it, value.savedRevision) } }
        return PlaybackSessionCodec.merge(full, cursorPreferences.getString(CHECKPOINT, null))
    }

    companion object {
        private const val CHECKPOINT = "session_cursor_v2"
        private const val LEGACY_SESSION = "pref_playback_session_v1"
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "AMATV-session-store")
                .apply { isDaemon = true }
        }
        @Volatile private var instance: PlaybackSessionPersistence? = null
        fun get(context: Context, legacy: SharedPreferences): PlaybackSessionPersistence = instance ?: synchronized(this) {
            instance ?: PlaybackSessionPersistence(context.applicationContext, legacy).also { instance = it }
        }
    }
}
