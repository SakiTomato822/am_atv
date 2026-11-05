package dev.amatv.integration

import android.icu.text.Transliterator
import android.os.Build
import dev.amatv.model.Track

/**
 * Speech recognition often gets a name's sound right but its characters wrong ("张会没" for
 * 张惠妹). Apple Music's search understands pinyin, so a query whose results do not match is
 * searched again by its pinyin. Uses the platform's ICU (Android 10+); without it, no fallback.
 */
internal object ChineseText {
    private val toLatin: Transliterator? by lazy { transliterator("Han-Latin; Latin-ASCII; Lower") }
    private val toSimplified: Transliterator? by lazy { transliterator("Traditional-Simplified") }

    private fun transliterator(id: String): Transliterator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { Transliterator.getInstance(id) }.getOrNull() else null

    /** "张会没" -> "zhang hui mei"; null when there is nothing Chinese to convert. */
    fun pinyin(text: String): String? {
        if (text.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
        return toLatin?.transliterate(text)?.replace(Regex("[^a-z0-9 ]"), " ")?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun simplified(text: String): String = toSimplified?.transliterate(text) ?: text
}

/**
 * Whether [songs] answer [query]: some song's title, artist and album together contain every
 * word of the query. [normalize] maps both sides to one script (traditional vs simplified).
 */
internal fun songsMatch(query: String, songs: List<Track>, normalize: (String) -> String = { it }): Boolean {
    val words = normalize(query.lowercase()).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return songs.isNotEmpty()
    return songs.take(10).any { song ->
        val text = normalize("${song.title} ${song.artist} ${song.album}".lowercase()).replace(" ", "")
        words.all { text.contains(it.replace(" ", "")) }
    }
}
