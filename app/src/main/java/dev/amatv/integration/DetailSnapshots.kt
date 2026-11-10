package dev.amatv.integration

import dev.amatv.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, bounded last-known detail data. Call read and write on a worker. */
internal class DetailSnapshots(private val directory: File) {
    private val lock = Any()
    private var generation = 0L

    fun revision(): Long = synchronized(lock) { generation }

    fun <T> read(scope: String, key: String, decode: (JSONObject) -> T): T? {
        val revision = revision()
        val file = file(scope, key)
        return runCatching {
            if (file.length() !in 1..MAX_FILE_BYTES) return null
            val value = decode(JSONObject(file.readText(Charsets.UTF_8)))
            synchronized(lock) {
                if (revision != generation) null
                else value.also { file.setLastModified(System.currentTimeMillis()) }
            }
        }.getOrNull()
    }

    fun write(revision: Long, scope: String, key: String, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return
        synchronized(lock) {
            if (revision != generation) return
            runCatching {
                directory.mkdirs()
                val target = file(scope, key)
                if (target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
                    target.setLastModified(System.currentTimeMillis())
                    return@runCatching
                }
                val staging = File(directory, "pending.tmp")
                try {
                    staging.writeBytes(bytes)
                    Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    staging.delete()
                }
                var retainedBytes = 0L
                directory.listFiles().orEmpty().filter { it.extension == "json" }
                    .sortedByDescending(File::lastModified).forEachIndexed { index, file ->
                        retainedBytes += file.length()
                        if (index >= MAX_FILES || retainedBytes > MAX_TOTAL_BYTES) file.delete()
                    }
            }
        }
    }

    fun clear() = synchronized(lock) {
        generation++
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    fun invalidateWrites() = synchronized(lock) { generation++ }

    private fun file(scope: String, key: String) = File(directory, "${digest("$scope\n$key")}.json")

    companion object {
        fun scope(userToken: String, storefront: String): String = digest("$userToken\n$storefront")

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private const val MAX_FILES = 64
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    }
}

internal fun encodeDetailTracks(items: List<Track>) = JSONObject().put("tracks", trackArray(items))

internal fun decodeDetailTracks(root: JSONObject): List<Track> = root.getJSONArray("tracks").detailTracks()

internal fun encodeDetailArtist(page: CatalogArtistPage): JSONObject = JSONObject()
    .put("artist", page.artist?.toContentSnapshot())
    .put("songs", trackArray(page.topSongs))
    .put("shelves", encodeShelves(page.shelves))

internal fun decodeDetailArtist(root: JSONObject) = CatalogArtistPage(
    artist = root.optJSONObject("artist")?.toContentTrack(),
    topSongs = root.getJSONArray("songs").detailTracks(),
    shelves = decodeShelves(root.getJSONArray("shelves")),
)

private fun trackArray(items: List<Track>) = JSONArray().apply { items.forEach { put(it.toContentSnapshot()) } }

private fun JSONArray.detailTracks(): List<Track> = List(length()) { getJSONObject(it).toContentTrack() }
