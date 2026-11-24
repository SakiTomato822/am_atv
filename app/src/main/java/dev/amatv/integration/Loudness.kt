package dev.amatv.integration

import java.nio.ByteBuffer
import kotlin.math.log10

enum class LoudnessMode(val offsetDb: Float, internal val peakHeadroomDb: Float) {
    Off(0f, 0f), Standard(0f, 0f), Loud(4f, 6f), Loudest(7f, 9f);

    companion object {
        internal fun fromPreference(value: String?): LoudnessMode =
            entries.firstOrNull { it.name == value } ?: Loud
    }
}

internal const val LOUDNESS_CEILING_DB = -1f

/**
 * Sound Check tags come from the 30-second preview, usually the loudest chorus, so dense masters
 * read too loud. Cuts beyond the knee are applied at a third of their depth.
 */
internal const val LOUDNESS_CUT_KNEE_DB = -3f
internal const val LOUDNESS_CUT_RATIO = 1f / 3f
internal const val LOUDNESS_PREF = "loudness_mode"
internal const val FLYME_PLAYBACK_PREFS = "am_atv_flyme_playback"

internal data class SoundCheck(val gainDb: Float, val peakDb: Float) {
    private val peakLimitedGainDb: Float get() = LOUDNESS_CEILING_DB - peakDb

    fun gainFor(mode: LoudnessMode): Float {
        if (mode == LoudnessMode.Off) return 0f
        val gain = minOf(gainDb + mode.offsetDb, peakLimitedGainDb + mode.peakHeadroomDb)
        return if (gain < LOUDNESS_CUT_KNEE_DB) {
            LOUDNESS_CUT_KNEE_DB + (gain - LOUDNESS_CUT_KNEE_DB) * LOUDNESS_CUT_RATIO
        } else gain
    }

    fun targetHeadroomUsedDb(mode: LoudnessMode): Float =
        (gainFor(mode) - peakLimitedGainDb).coerceIn(0f, mode.peakHeadroomDb)
}

/** Album membership comes from the app queue. The native player still applies one gain per item. */
internal object AlbumLoudness {
    data class Summary(val tag: SoundCheck?, val tracksKnown: Int)

    private var tracks = emptySet<String>()
    private val tags = mutableMapOf<String, SoundCheck>()
    private var summary = Summary(null, 0)

    @Synchronized
    fun select(ids: Set<String>): Boolean {
        if (tracks == ids) return false
        tracks = ids
        tags.keys.retainAll(ids)
        summarize()
        return true
    }

    @Synchronized
    fun record(id: String, tag: SoundCheck) {
        if (id in tracks && tags.put(id, tag) != tag) summarize()
    }

    @Synchronized
    fun forTrack(id: String?): Summary? = summary.takeIf { id in tracks }

    private fun summarize() {
        val tag = if (tags.isEmpty()) null else SoundCheck(
            tags.values.map(SoundCheck::gainDb).average().toFloat(),
            tags.values.maxOf(SoundCheck::peakDb),
        )
        summary = Summary(tag, tags.size)
    }
}

internal object SoundCheckParser {
    private val fields = Regex("[0-9a-fA-F]{1,8}")

    fun parseTag(tag: String): SoundCheck? {
        val values = tag.trim().split(Regex("\\s+"))
        if (values.size != 10 || values.any { !fields.matches(it) }) return null
        val power = maxOf(values[0].toLong(16), values[1].toLong(16))
        val peak = maxOf(values[6].toLong(16), values[7].toLong(16))
        if (power == 0L || peak == 0L) return null
        return SoundCheck(
            (-10 * log10(power / 1_000.0)).toFloat(),
            (20 * log10(peak / 32_768.0)).toFloat(),
        )
    }

    /** Find the MP4 freeform item in the prefix, even when the enclosing moov extends past it. */
    fun parsePreview(prefix: ByteArray): SoundCheck? {
        val bytes = ByteBuffer.wrap(prefix)
        // ISO-8859-1 keeps string offsets identical to byte offsets.
        val text = prefix.toString(Charsets.ISO_8859_1)
        var type = text.indexOf("----", startIndex = 4)
        while (type >= 0) {
            val size = bytes.getInt(type - 4)
            if (size in 8..(prefix.size - type + 4)) {
                val end = type - 4 + size
                var child = type + 4
                var isSoundCheck = false
                var tag: String? = null
                while (end - child >= 8) {
                    val childSize = bytes.getInt(child)
                    if (childSize !in 8..(end - child)) break
                    val childEnd = child + childSize
                    when (text.substring(child + 4, child + 8)) {
                        "name" -> if (childSize >= 12) {
                            isSoundCheck = text.substring(child + 12, childEnd) == "iTunNORM"
                        }
                        "data" -> if (childSize >= 16) tag = text.substring(child + 16, childEnd)
                    }
                    child = childEnd
                }
                if (isSoundCheck) tag?.let(::parseTag)?.let { return it }
            }
            type = text.indexOf("----", startIndex = type + 4)
        }
        return null
    }
}

internal fun combinedPlaybackGain(loudnessDb: Float, fadeDb: Float): Float = loudnessDb + fadeDb
