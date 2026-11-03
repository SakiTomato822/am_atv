package dev.amatv.integration

import android.content.Context
import android.util.Log
import dev.amatv.model.ContentShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The last Home and Browse shelves, shown at once on the next launch while fresh ones load.
 * Each snapshot belongs to one account (stored as a token digest) and one content language;
 * anything else reads as missing. Kept in private app storage and removed on sign-out.
 */
class ShelfSnapshots(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "shelf-snapshots-v1")

    suspend fun read(name: String, userToken: String, languageTag: String): List<ContentShelf>? =
        withContext(Dispatchers.IO) {
            val file = file(name)
            if (!file.isFile || file.length() !in 1..MAX_BYTES) return@withContext null
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optString("scope") != scope(userToken, languageTag)) return@runCatching null
                decodeShelves(root.getJSONArray("shelves"))
            }.onFailure { Log.w(TAG, "Ignoring unreadable $name snapshot", it) }.getOrNull()
        }

    suspend fun write(name: String, userToken: String, languageTag: String, shelves: List<ContentShelf>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("scope", scope(userToken, languageTag))
                    .put("shelves", encodeShelves(shelves))
                    .toString()
                if (body.length > MAX_BYTES) return@runCatching
                directory.mkdirs()
                val target = file(name)
                val temporary = File(directory, "$name.tmp")
                temporary.writeText(body, Charsets.UTF_8)
                if (!temporary.renameTo(target)) {
                    target.delete()
                    temporary.renameTo(target)
                }
            }.onFailure { Log.w(TAG, "Could not save $name snapshot", it) }
        }
    }

    fun clear() {
        directory.deleteRecursively()
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z]+"))) { "Snapshot name is invalid" }
        return File(directory, "$name.json")
    }

    private fun scope(userToken: String, languageTag: String): String =
        MessageDigest.getInstance("SHA-256").digest("$userToken\n$languageTag".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AMATV-Shelves"
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}

internal fun encodeShelves(shelves: List<ContentShelf>): JSONArray = JSONArray().apply {
    shelves.forEach { shelf ->
        put(JSONObject()
            .put("id", shelf.id)
            .put("title", shelf.title)
            .put("featured", shelf.featured)
            .put("items", JSONArray().apply {
                shelf.items.forEach { track ->
                    put(track.toSessionJson()
                        .put("dateAddedMs", track.dateAddedMs)
                        .put("pickReason", track.pickReason))
                }
            }))
    }
}

internal fun decodeShelves(array: JSONArray): List<ContentShelf> = (0 until array.length()).map { index ->
    val shelf = array.getJSONObject(index)
    val items = shelf.getJSONArray("items")
    ContentShelf(
        id = shelf.getString("id"),
        title = shelf.getString("title"),
        featured = shelf.optBoolean("featured"),
        items = (0 until items.length()).map { itemIndex ->
            val item = items.getJSONObject(itemIndex)
            item.toSessionTrack().copy(
                dateAddedMs = item.optLong("dateAddedMs", 0L).takeIf { item.has("dateAddedMs") && it > 0L },
                pickReason = item.optString("pickReason").takeIf { it.isNotBlank() && it != "null" },
            )
        },
    )
}
