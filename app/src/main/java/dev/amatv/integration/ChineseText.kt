package dev.amatv.integration

import android.icu.text.Transliterator
import android.os.Build

internal object ChineseText {
    private val toLatin by lazy { transliterator("Han-Latin; Latin-ASCII; Lower") }
    private val toSimplified by lazy { transliterator("Traditional-Simplified") }
    private val toRomaji by lazy { transliterator("Katakana-Hiragana; Hiragana-Latin; Latin-ASCII; Lower") }
    private val spaces = Regex("""\s+""")
    private val nonLatin = Regex("[^a-z0-9 ]")
    private val alternatives = mapOf('樂' to "le yue", '乐' to "le yue", '行' to "xing hang",
        '長' to "chang zhang", '长' to "chang zhang", '重' to "zhong chong", '單' to "dan shan",
        '单' to "dan shan", '曾' to "zeng ceng", '藏' to "cang zang")

    private fun transliterator(id: String): Transliterator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Transliterator.getInstance(id) else null

    private fun script(char: Char) = Character.UnicodeScript.of(char.code)

    fun pinyin(text: String): String? {
        if (text.none { script(it) == Character.UnicodeScript.HAN }) return null
        return toLatin?.let { synchronized(it) { it.transliterate(text) } }?.replace(nonLatin, " ")?.replace(spaces, " ")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun romaji(text: String): String? {
        if (text.none { script(it) == Character.UnicodeScript.HIRAGANA || script(it) == Character.UnicodeScript.KATAKANA }) return null
        return toRomaji?.let { synchronized(it) { it.transliterate(text) } }
    }

    fun alternatePinyin(text: String): List<String> {
        if (text.none(alternatives::containsKey)) return emptyList()
        val han = text.filter { script(it) == Character.UnicodeScript.HAN }
        val reading = pinyin(text)?.split(' ') ?: return emptyList()
        if (han.length != reading.size) return emptyList()
        return han.indices.asSequence().flatMap { index ->
            alternatives[han[index]].orEmpty().split(' ').asSequence()
                .filter { it.isNotEmpty() && it != reading[index] }
                .map { alternative -> reading.toMutableList().apply { set(index, alternative) }.joinToString(" ") }
        }.take(3).toList()
    }

    fun simplified(text: String): String = toSimplified?.let { synchronized(it) { it.transliterate(text) } } ?: text
}
