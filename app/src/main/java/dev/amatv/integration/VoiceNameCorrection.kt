package dev.amatv.integration

/**
 * Snaps names the recognizer misheard back to names the user actually has (library, recent
 * plays, the screen). Works after any engine, local or cloud, and on words a hotword list can't
 * carry (Latin names in a cjkchar model):
 *
 *  - Chinese: same fuzzy pinyin as a known name, so 「西凉」 (xi liang) becomes 「昔涟」 (xi lian).
 *    Fuzzy means the confusions speech recognition and accents make: an/ang, en/eng, in/ing,
 *    z/zh, c/ch, s/sh, n/l.
 *  - Latin: one or two letters away from a known name, so "hoyomex" becomes "HOYO-MiX".
 *
 * Longer names win, replaced spans never overlap, and an exact name is left alone.
 * [syllables] gives one pinyin syllable per Han character (null for anything else).
 */
internal fun correctNames(
    text: String,
    names: Collection<String>,
    syllables: (Char) -> String? = ::hanSyllable,
): String {
    if (text.isBlank() || names.isEmpty()) return text
    data class Fix(val start: Int, val end: Int, val name: String)
    val fixes = mutableListOf<Fix>()
    fun free(start: Int, end: Int) = fixes.none { start < it.end && it.start < end }

    // Chinese names, longest first.
    val textSyllables = text.map { syllables(it)?.let(::fuzzySyllable) }
    names.asSequence()
        .map { it.trim() }
        .filter { name -> name.length in 2..10 && name.all { syllables(it) != null } }
        .distinct()
        .sortedByDescending { it.length }
        .forEach { name ->
            val wanted = name.map { fuzzySyllable(syllables(it)!!) }
            var start = 0
            while (start + name.length <= text.length) {
                val end = start + name.length
                val window = text.substring(start, end)
                if (window != name && free(start, end) && textSyllables.subList(start, end) == wanted) {
                    fixes += Fix(start, end, name)
                    start = end
                } else start++
            }
        }

    // Latin names: compare letters only ("HOYO-MiX" -> "hoyomix") against runs of 1..4 words.
    val words = Regex("[A-Za-z][A-Za-z0-9']*").findAll(text).toList()
    names.asSequence()
        .map { it.trim() }
        .filter { name -> name.any { it in 'A'..'Z' || it in 'a'..'z' } && name.none { syllables(it) != null } }
        .map { it to latinKey(it) }
        .filter { (_, key) -> key.length >= 4 }
        .distinctBy { it.second }
        .sortedByDescending { it.second.length }
        .forEach { (name, key) ->
            val allowed = if (key.length >= 9) 2 else 1
            var best: Fix? = null
            var bestDistance = Int.MAX_VALUE
            for (first in words.indices) {
                for (last in first until minOf(words.size, first + 4)) {
                    // Only words joined by spaces or hyphens belong to one name.
                    if (last > first && text.substring(words[last - 1].range.last + 1, words[last].range.first).any { it != ' ' && it != '-' }) break
                    val start = words[first].range.first
                    val end = words[last].range.last + 1
                    val spokenKey = latinKey(text.substring(start, end))
                    if (kotlin.math.abs(spokenKey.length - key.length) > allowed) continue
                    val distance = editDistance(spokenKey, key, allowed)
                    if (distance in 1..allowed && distance < bestDistance && free(start, end)) {
                        best = Fix(start, end, name)
                        bestDistance = distance
                    }
                }
            }
            best?.let { fixes += it }
        }

    if (fixes.isEmpty()) return text
    val out = StringBuilder(text)
    fixes.sortedByDescending { it.start }.forEach { out.replace(it.start, it.end, it.name) }
    return out.toString()
}

private fun latinKey(text: String) = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

/** Two Latin keys (letters and digits only) one letter apart, or two for long names. */
internal fun latinClose(a: String, b: String): Boolean {
    if (a.length < 4 || b.length < 4 || a == b) return false
    if (!a.all { it in 'a'..'z' || it in '0'..'9' } || !b.all { it in 'a'..'z' || it in '0'..'9' }) return false
    val allowed = if (maxOf(a.length, b.length) >= 9) 2 else 1
    if (kotlin.math.abs(a.length - b.length) > allowed) return false
    return editDistance(a, b, allowed) <= allowed
}

/** Levenshtein distance, giving up (returning limit + 1) once it must exceed [limit]. */
private fun editDistance(a: String, b: String, limit: Int): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            rowMin = minOf(rowMin, current[j])
        }
        if (rowMin > limit) return limit + 1
        previous = current
    }
    return previous[b.length]
}

/** Folds the sounds recognizers and accents mix up into one spelling. */
internal fun fuzzySyllable(syllable: String): String {
    var s = syllable
    s = when {
        s.startsWith("zh") -> "z" + s.drop(2)
        s.startsWith("ch") -> "c" + s.drop(2)
        s.startsWith("sh") -> "s" + s.drop(2)
        s.startsWith("n") && !s.startsWith("ng") -> "l" + s.drop(1)
        else -> s
    }
    return when {
        s.endsWith("ang") -> s.dropLast(1)
        s.endsWith("eng") -> s.dropLast(1)
        s.endsWith("ing") -> s.dropLast(1)
        else -> s
    }
}

private val syllableCache = java.util.concurrent.ConcurrentHashMap<Char, String>()

/** One Han character's pinyin syllable ("涟" -> "lian"), or null for anything else. */
private fun hanSyllable(char: Char): String? {
    if (Character.UnicodeScript.of(char.code) != Character.UnicodeScript.HAN) return null
    syllableCache[char]?.let { return it.ifEmpty { null } }
    val value = ChineseText.pinyin(char.toString())?.replace(" ", "").orEmpty()
    syllableCache[char] = value
    return value.ifEmpty { null }
}
