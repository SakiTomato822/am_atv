package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Metadata dictionary + entry records; lyrics and native queue handles never reach disk. */
internal object PlaybackSessionCodec {
    private const val CHECKPOINT_BEFORE = 8
    private const val CHECKPOINT_AFTER = 2

    private data class Record(val id: Long, val track: Track, val autoplay: Boolean, val played: Boolean)

    fun encode(session: PlaybackSession): String = root(session).toString()

    fun encodeCheckpoint(session: PlaybackSession): String {
        val current = session.currentIndex.coerceIn(session.queue.indices)
        val start = (current - CHECKPOINT_BEFORE).coerceAtLeast(0)
        val end = (current + CHECKPOINT_AFTER + 1).coerceAtMost(session.queue.size)
        val ids = ids(session)
        val compact = session.copy(
            queue = session.queue.subList(start, end), currentIndex = current - start,
            queueEntryIds = ids.subList(start, end), contextQueue = emptyList(), contextEntryIds = emptyList(),
            autoplayEntryIndices = (start until end).filter { it in session.autoplayEntryIndices }.map { it - start }.toSet(),
        )
        return root(compact).put("windowStart", start)
            .put("futureIds", JSONArray(ids.drop(current + 1))).toString()
    }

    fun decode(raw: String): PlaybackSession? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version") == 1) return@runCatching decodeLegacy(root)
        require(root.getInt("version") == 2)
        val dictionary = root.getJSONArray("tracks")
        val tracks = (0 until dictionary.length()).map { dictionary.getJSONObject(it).toSessionTrack() }
        fun records(name: String): List<Record> {
            val data = root.optJSONArray(name) ?: return emptyList()
            return (0 until data.length()).map { index ->
                val item = data.getJSONObject(index)
                val id = item.getLong("id")
                require(id > 0 && id < Long.MAX_VALUE)
                Record(id, tracks[item.getInt("r")], item.optBoolean("a"), item.optBoolean("p"))
            }
        }
        val queue = records("queue")
        require(queue.isNotEmpty() && queue.map { it.id }.distinct().size == queue.size)
        val context = records("context")
        PlaybackSession(
            queue = queue.map { it.track }, currentIndex = root.optInt("currentIndex").coerceIn(queue.indices),
            positionMs = root.optLong("positionMs").coerceAtLeast(0), wasPlaying = root.optBoolean("wasPlaying"),
            destination = enumValueOrDefault(root.optString("destination"), Destination.Home),
            previousDestination = enumValueOrDefault(root.optString("previousDestination"), Destination.Home),
            radioActive = root.optBoolean("radioActive"), radioSeed = root.optJSONObject("radioSeed")?.toSessionTrack(),
            radioContinuation = root.optionalString("radioContinuation"),
            radioSeedVideoId = root.optionalString("radioSeedVideoId"),
            autoplaySource = autoplaySourceFromStoredValue(root.optString("autoplaySource")),
            autoplayEntryIndices = queue.indices.filter { queue[it].autoplay }.toSet(),
            queueEntryIds = queue.map { it.id }, playedEntryIds = queue.filter { it.played }.map { it.id }.toSet(),
            contextQueue = context.map { it.track }, contextEntryIds = context.map { it.id },
            sessionId = root.optLong("sessionId"), persistenceId = root.optString("persistenceId").ifBlank { "legacy-v1" },
            savedRevision = root.optLong("savedRevision"),
        ).sanitizedAutoplayEntries()
    }.getOrNull()

    /** Overlay the synchronous cursor/tail without trimming the full session history. */
    fun merge(full: PlaybackSession?, checkpointRaw: String?): PlaybackSession? {
        val checkpoint = checkpointRaw?.let(::decode) ?: return full
        if (full != null && checkpoint.savedRevision < full.savedRevision) return full
        if (full == null || full.persistenceId != checkpoint.persistenceId) return checkpoint
        val fullIds = ids(full)
        val records = full.queue.mapIndexed { index, track -> Record(fullIds[index], track,
            index in full.autoplayEntryIndices, fullIds[index] in full.playedEntryIds) }.toMutableList()
        val cpIds = ids(checkpoint)
        val cpRoot = JSONObject(checkpointRaw)
        val start = cpRoot.optInt("windowStart").coerceAtLeast(0)
        checkpoint.queue.forEachIndexed { index, track ->
            val id = cpIds[index]
            val existing = records.indexOfFirst { it.id == id }
            val item = Record(id, track, index in checkpoint.autoplayEntryIndices,
                id in checkpoint.playedEntryIds || (existing >= 0 && records[existing].played))
            if (existing >= 0) records[existing] = item
            else records.add((start + index).coerceAtMost(records.size), item)
        }
        val currentId = cpIds[checkpoint.currentIndex]
        val currentIndex = records.indexOfFirst { it.id == currentId }
        if (currentIndex < 0) return full
        val future = cpRoot.optJSONArray("futureIds")
        val ordered = if (future != null) {
            val byId = records.associateBy { it.id }
            records.take(currentIndex + 1) + (0 until future.length()).mapNotNull { byId[future.optLong(it)] }
        } else records
        return checkpoint.copy(
            queue = ordered.map { it.track }, queueEntryIds = ordered.map { it.id }, currentIndex = currentIndex,
            autoplayEntryIndices = ordered.indices.filter { ordered[it].autoplay }.toSet(),
            playedEntryIds = ordered.filter { it.played }.map { it.id }.toSet(),
            contextQueue = full.contextQueue, contextEntryIds = full.contextEntryIds,
        ).sanitizedAutoplayEntries()
    }

    private fun ids(session: PlaybackSession): List<Long> {
        val used = HashSet<Long>()
        var next = (session.queueEntryIds + session.contextEntryIds).filter { it in 1L until Long.MAX_VALUE }
            .maxOrNull()?.plus(1) ?: 1L
        return session.queue.indices.map { index ->
            session.queueEntryIds.getOrNull(index)?.takeIf { it in 1L until Long.MAX_VALUE && used.add(it) }
                ?: run { val generated = next++; used.add(generated); generated }
        }
    }

    private fun root(session: PlaybackSession): JSONObject {
        require(session.queue.isNotEmpty())
        val dictionary = LinkedHashMap<Track, Int>()
        fun resource(track: Track): Int {
            val metadata = if (track.lyrics.isEmpty() && track.playbackQueueId == null) track
                else track.copy(lyrics = emptyList(), playbackQueueId = null)
            return dictionary.getOrPut(metadata) { dictionary.size }
        }
        val queueIds = ids(session)
        val queue = JSONArray()
        session.queue.forEachIndexed { index, track ->
            queue.put(JSONObject().put("id", queueIds[index]).put("r", resource(track))
                .put("a", index in session.autoplayEntryIndices).put("p", queueIds[index] in session.playedEntryIds))
        }
        var nextId = (queueIds + session.contextEntryIds).maxOrNull()?.plus(1) ?: 1L
        val context = JSONArray()
        session.contextQueue.forEachIndexed { index, track ->
            val id = session.contextEntryIds.getOrNull(index)?.takeIf { it > 0 } ?: nextId++
            context.put(JSONObject().put("id", id).put("r", resource(track)))
        }
        return JSONObject().put("version", 2).put("tracks", JSONArray(dictionary.keys.map { it.toSessionJson() }))
            .put("queue", queue).put("context", context).put("currentIndex", session.currentIndex.coerceIn(session.queue.indices))
            .put("positionMs", session.positionMs.coerceAtLeast(0)).put("wasPlaying", session.wasPlaying)
            .put("destination", session.destination.name).put("previousDestination", session.previousDestination.name)
            .put("radioActive", session.radioActive).put("radioSeed", session.radioSeed?.toSessionJson())
            .put("radioContinuation", session.radioContinuation).put("radioSeedVideoId", session.radioSeedVideoId)
            .put("autoplaySource", session.autoplaySource.name).put("sessionId", session.sessionId)
            .put("persistenceId", session.persistenceId.ifBlank { "legacy-v1" }).put("savedRevision", session.savedRevision)
    }

    private fun decodeLegacy(root: JSONObject): PlaybackSession? {
        val data = root.getJSONArray("queue")
        val parsed = (0 until data.length()).mapNotNull { index ->
            runCatching { index to data.getJSONObject(index).toSessionTrack() }.getOrNull()
        }
        if (parsed.isEmpty()) return null
        val rawCurrent = root.optInt("currentIndex")
        val current = parsed.indexOfFirst { it.first >= rawCurrent }.takeIf { it >= 0 } ?: parsed.lastIndex
        val origins = root.optJSONArray("autoplayEntryIndices")
        val autoplay = (0 until (origins?.length() ?: 0)).map { origins!!.optInt(it, -1) }.toSet()
        return PlaybackSession(
            parsed.map { it.second }, current, if (parsed[current].first == rawCurrent) root.optLong("positionMs").coerceAtLeast(0) else 0,
            root.optBoolean("wasPlaying"), enumValueOrDefault(root.optString("destination"), Destination.Home),
            enumValueOrDefault(root.optString("previousDestination"), Destination.Home), root.optBoolean("radioActive"),
            root.optJSONObject("radioSeed")?.toSessionTrack(), root.optionalString("radioContinuation"),
            autoplaySourceFromStoredValue(root.optString("autoplaySource")),
            root.optionalString("radioSeedVideoId") ?: root.optJSONObject("radioSeed")?.optionalString("ytmVideoId"),
            parsed.indices.filter { parsed[it].first in autoplay }.toSet(),
            queueEntryIds = parsed.map { it.first + 1L },
            playedEntryIds = parsed.take(current).map { it.first + 1L }.toSet(), persistenceId = "legacy-v1",
        ).sanitizedAutoplayEntries()
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback

private fun JSONObject.optionalString(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
internal fun Track.toSessionJson() = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artist", artist)
    .put("album", album)
    .put("durationMs", durationMs)
    .put("palette", JSONArray().apply { palette.take(5).forEach { put(it.toArgb()) } })
    .put("catalogId", catalogId)
    .put("catalogKind", catalogKind.name)
    .put("resourceId", resourceId)
    .put("resourceType", resourceType)
    .put("artworkUrl", artworkUrl)
    .put("motionArtworkUrl", motionArtworkUrl)
    .put("motionPreviewUrl", motionPreviewUrl)
    .put("hasLyricsAvailable", hasLyricsAvailable)
    .put("recommendedByYtm", recommendedByYtm)
    .put("ytmVideoId", ytmVideoId)

internal fun JSONObject.toSessionTrack(): Track {
    val colors = optJSONArray("palette")
    return Track(
        id = getString("id"),
        title = getString("title"),
        artist = getString("artist"),
        album = optString("album"),
        durationMs = optLong("durationMs").coerceAtLeast(1L),
        palette = if (colors == null || colors.length() == 0) {
            listOf(Color(0xFF202428))
        } else (0 until colors.length()).map { Color(colors.getInt(it)) },
        lyrics = emptyList(),
        catalogId = optionalString("catalogId"),
        catalogKind = enumValueOrDefault(optString("catalogKind"), CatalogKind.Song),
        resourceId = optString("resourceId").takeIf { it.isNotBlank() && it != "null" },
        resourceType = optString("resourceType").takeIf { it.isNotBlank() && it != "null" },
        artworkUrl = optionalString("artworkUrl"),
        motionArtworkUrl = optionalString("motionArtworkUrl"),
        motionPreviewUrl = optionalString("motionPreviewUrl"),
        hasLyricsAvailable = optBoolean("hasLyricsAvailable"),
        // Queue indices belong to one MusicKit process and cannot survive a cold start.
        playbackQueueId = null,
        recommendedByYtm = optBoolean("recommendedByYtm"),
        ytmVideoId = optionalString("ytmVideoId"),
    )
}
