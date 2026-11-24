package dev.amatv.integration

import android.util.Log
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val LOUDNESS_LOG = "AMATV-Loudness"

/** The playback thread only reads the in-memory map. Loading and atomic writes run on IO. */
internal class LoudnessCache(private val file: File, private val maxEntries: Int = 5_000) {
    private val entries = LinkedHashMap<String, SoundCheck>()

    fun load() {
        if (!file.exists()) return
        val array = JSONArray(file.readText())
        synchronized(entries) {
            for (index in (array.length() - maxEntries).coerceAtLeast(0) until array.length()) {
                val row = array.optJSONArray(index) ?: continue
                val gain = row.optDouble(1).toFloat()
                val peak = row.optDouble(2).toFloat()
                if (gain.isFinite() && peak.isFinite()) entries[row.getString(0)] = SoundCheck(gain, peak)
            }
        }
    }

    operator fun get(catalogId: String): SoundCheck? = synchronized(entries) { entries[catalogId] }

    @Synchronized
    fun put(catalogId: String, soundCheck: SoundCheck) {
        val snapshot = synchronized(entries) {
            entries.remove(catalogId)
            entries[catalogId] = soundCheck
            if (entries.size > maxEntries) entries.remove(entries.keys.first())
            entries.toMap()
        }
        val array = JSONArray()
        snapshot.forEach { (id, value) -> array.put(JSONArray().put(id).put(value.gainDb).put(value.peakDb)) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(array.toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}

internal data class LoudnessLookup(val tag: SoundCheck?, val source: String, val reason: String? = null)

internal class LoudnessRepository(
    private val cache: LoudnessCache,
    private val catalogPreviewUrl: suspend (String) -> String?,
) {
    // A bounded IO view keeps rapid queue edits from starting unbounded preview transfers.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val loaded = scope.async {
        runCatching { cache.load() }.onFailure { Log.w(LOUDNESS_LOG, "Cache read failed: ${it.javaClass.simpleName}") }
    }
    private val inFlight = mutableMapOf<String, Deferred<LoudnessLookup>>()

    fun cached(catalogId: String?): SoundCheck? = catalogId?.let { id ->
        cache[id]?.also { AlbumLoudness.record(id, it) }
    }

    fun request(track: Track): Deferred<LoudnessLookup>? {
        val id = track.catalogId?.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
            ?: return null
        return synchronized(inFlight) {
            inFlight[id] ?: scope.async(start = CoroutineStart.LAZY) {
                loaded.await()
                cached(id)?.let { LoudnessLookup(it, "cache") } ?: fetch(id, track.previewUrl)
            }.also { request ->
                inFlight[id] = request
                request.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(id, request) } }
                request.start()
            }
        }
    }

    private suspend fun fetch(id: String, previewUrl: String?): LoudnessLookup {
        return try {
            val url = previewUrl ?: catalogPreviewUrl(id) ?: return LoudnessLookup(null, "missing", "no-preview")
            val tag = readSoundCheck(url) ?: return LoudnessLookup(null, "missing", "no-valid-iTunNORM")
            AlbumLoudness.record(id, tag)
            runCatching { cache.put(id, tag) }
                .onFailure { Log.w(LOUDNESS_LOG, "Cache write failed: ${it.javaClass.simpleName}") }
            LoudnessLookup(tag, "network")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoudnessLookup(null, "missing", "network-${e.javaClass.simpleName}")
        }
    }

    private fun readSoundCheck(url: String): SoundCheck? {
        val uri = URI(url)
        require(uri.scheme == "https")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Range", "bytes=0-65535")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL && status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            return connection.inputStream.use { input ->
                val prefix = ByteArray(65_536)
                var size = 0
                while (size < prefix.size) {
                    val count = input.read(prefix, size, prefix.size - size)
                    if (count < 0) break
                    size += count
                }
                SoundCheckParser.parsePreview(if (size == prefix.size) prefix else prefix.copyOf(size))
            }
        } finally {
            connection.disconnect()
        }
    }
}
