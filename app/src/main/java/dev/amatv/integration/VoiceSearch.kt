package dev.amatv.integration

import android.icu.text.Transliterator
import android.os.Build
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import java.text.Normalizer
import java.util.Locale

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

internal data class VoiceCatalogMatch(
    val candidates: List<Track>,
    val confident: Boolean,
    val alternatives: List<Track> = candidates,
)

private val versionSuffix = Regex(
    """(?i)[\s(（\[【\-–]*(?:live\b|remaster\w*|cover\b|instrumental\b|karaoke\b|dj\b|remix\b|现场|現場|翻唱|伴奏|女版|男版).*?$""",
)

// Accents dropped (Céline -> celine, Beyoncé -> beyonce): nobody says them, recognizers never
// write them. Decomposed first so the mark is separate from its letter, then removed, but only
// after Latin letters: kana voicing marks (ゲ = ケ + ゙) are part of the word.
private val combiningMarks = Regex("(?<=\\p{IsLatin})\\p{Mn}+")

internal fun voiceCatalogKey(text: String, simplify: (String) -> String = ChineseText::simplified): String =
    simplify(Normalizer.normalize(text, Normalizer.Form.NFKC))
        .let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(combiningMarks, "") }
        .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Ranks concrete catalog entries. A partial or phonetic match always needs confirmation. */
internal fun rankCatalog(
    request: VoiceCommand.Play,
    candidates: List<Track>,
    library: List<Track> = emptyList(),
    simplify: (String) -> String = ChineseText::simplified,
    pinyin: (String) -> String? = ChineseText::pinyin,
): VoiceCatalogMatch {
    fun key(text: String) = voiceCatalogKey(text, simplify)
    // Fuzzy pinyin (an/ang, z/zh, n/l...): 「西凉」 and 「昔涟」 sound the same to a recognizer.
    fun sound(text: String) = pinyin(text)?.split(' ')?.filter { it.isNotEmpty() }
        ?.joinToString("") { fuzzySyllable(it) }?.let(::key)?.takeIf { it.isNotEmpty() }
    fun sameSound(a: String, b: String) = sound(a)?.let { it == sound(b) } == true
    fun Track.identity() = catalogKind to (catalogId ?: id)
    val wantedKind = CatalogKind.entries.firstOrNull { it.name == request.kind.name }
    val libraryIds = library.mapTo(hashSetOf()) { it.identity() }
    val pool = (library + candidates).filter { item ->
        item.catalogKind != CatalogKind.MusicVideo &&
            (wantedKind == null || item.catalogKind == wantedKind ||
                (!request.kindExplicit && request.kind == VoiceCommand.Kind.Song && item.catalogKind == CatalogKind.Album))
    }.distinctBy { it.identity() }
    data class Ranked(val track: Track, val score: Int, val exact: Boolean)

    val title = request.album ?: request.title ?: request.artist ?: request.query
    val artist = request.artist.takeUnless { request.kind == VoiceCommand.Kind.Artist }
    val query = key(request.query)

    fun rank(title: String, artist: String?): List<Ranked> {
        val titleKey = key(title)
        if (titleKey.isEmpty()) return emptyList()
        val artistKey = artist?.let(::key)
        return pool.mapNotNull { item ->
            val artistExact = artistKey == null || key(item.artist) == artistKey
            if (!artistExact && !sameSound(requireNotNull(artist), item.artist)) return@mapNotNull null
            val itemTitle = key(item.title)
            // 「Adele Easy On Me」: the artist said together with the title, either way round.
            fun saidWithArtist(name: String) = artist == null && request.kind == VoiceCommand.Kind.Any &&
                (query == key(item.artist) + name || query == name + key(item.artist))
            val exact = itemTitle == titleKey || saidWithArtist(itemTitle)
            val versionBase = item.title.replace(versionSuffix, "")
            val alternateVersion = versionBase != item.title && !exact
            val score = when {
                exact -> 100
                alternateVersion && (key(versionBase) == titleKey || saidWithArtist(key(versionBase))) -> 72
                sameSound(title, item.title) -> 70
                titleKey.length >= 2 && itemTitle.startsWith(titleKey) -> 56
                titleKey.length >= 2 && itemTitle.contains(titleKey) -> 44
                // "hoyomex" for HOYO-MiX: a Latin name a letter or two off is only ever a choice.
                latinClose(titleKey, itemTitle) -> 50
                else -> return@mapNotNull null
            }
            Ranked(item, score + (if (artistExact) 5 else 0) +
                (if (item.identity() in libraryIds) 4 else 0) -
                (if (alternateVersion) 8 else 0), exact && artistExact)
        }.sortedByDescending { it.score }
    }

    var ranked = rank(title, artist)
    // 「最好的我」 can be a whole title even if the rules initially interpreted it as artist/title.
    if (artist != null && request.kind == VoiceCommand.Kind.Song && !request.kindExplicit &&
        ranked.none { it.exact }) {
        val fallback = rank("$artist 的 $title", null)
        ranked = (ranked + fallback).sortedByDescending { it.score }.distinctBy { it.track.identity() }
    }
    val first = ranked.firstOrNull() ?: return VoiceCatalogMatch(emptyList(), false)
    val second = ranked.getOrNull(1)
    val confident = first.exact && (second == null || (!second.exact && first.score - second.score >= 15))
    // If an exact title exists, omit lower quality prefix/cover results from the choices.
    val choices = ranked.filter { !first.exact || it.exact }.map { it.track }
    return VoiceCatalogMatch(choices, confident, ranked.map { it.track })
}
