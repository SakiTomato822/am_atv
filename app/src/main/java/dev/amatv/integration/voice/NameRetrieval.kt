package dev.amatv.integration.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal enum class NameRoute(val penalty: Double) {
    Literal(0.0), Alias(0.015), Mandarin(0.045), TextEdit(0.07), Latin(0.075), Romanized(0.085),
}

internal object NameTuning {
    const val MIN_COVERAGE = 0.65
    const val MIN_SIMILARITY = 0.63
    const val MIN_AUTOPLAY = 0.98
    const val AUTOPLAY_MARGIN = 0.10
    const val SHORT_MARGIN = 0.17
    const val TYPE_WEIGHT = 0.025
    const val CONTEXT_WEIGHT = 0.07
    const val LIBRARY_WEIGHT = 0.025
    const val APPLE_WEIGHT = 0.025
    const val VERSION_PENALTY = 0.22
    const val ALTERNATE_READING_PENALTY = 0.045
    const val CHOICE_LIMIT = 3
}

internal data class NameEvidence(
    val route: NameRoute,
    val similarity: Double,
    val cost: Double,
    val coverage: Double,
    val alternativeReading: Boolean = false,
) {
    val score: Double get() = similarity - route.penalty -
        (1.0 - coverage) * 0.12 - cost.coerceAtMost(3.0) * 0.018 -
        if (alternativeReading) NameTuning.ALTERNATE_READING_PENALTY else 0.0

    val literalExact get() = route == NameRoute.Literal && cost == 0.0
    val exact get() = cost == 0.0 && coverage == 1.0
}

private val marks = Regex("(?<=\\p{IsLatin})\\p{Mn}+")
private val nameWords = Regex("[a-z]+")

internal fun nameKey(text: String, simplify: (String) -> String = { it }): String =
    Normalizer.normalize(
        Normalizer.normalize(simplify(Normalizer.normalize(text, Normalizer.Form.NFKC)), Normalizer.Form.NFD)
            .replace(marks, ""),
        Normalizer.Form.NFC,
    ).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal class NameForm(
    val key: String,
    val aliases: Set<String>,
    val mandarin: List<List<String>>,
    val romanized: String?,
    val latinSound: String?,
)

/** Encodes catalog labels once. Transliteration is supplied by the platform or the JVM fixture. */
internal class NameEncoder(
    private val simplify: (String) -> String = { it },
    private val pinyin: (String) -> String? = { null },
    private val romaji: (String) -> String? = { null },
    aliases: Map<String, String> = emptyMap(),
    private val alternatePinyin: (String) -> List<String> = { emptyList() },
) {
    private val aliasesByTarget = aliases.entries.groupBy({ key(it.value) }, { key(it.key) })

    fun key(text: String) = nameKey(text, simplify)

    fun prepare(text: String): NameForm {
        val literal = key(text)
        val latin = literal.isNotEmpty() && literal.all { it in 'a'..'z' || it.isDigit() }
        val mandarin = pinyin(text)?.let { reading ->
            (listOf(reading) + alternatePinyin(text)).mapNotNull(::splitReading).distinct()
        } ?: if (latin) listOfNotNull(splitPinyin(text)) else emptyList()
        return NameForm(literal, aliasesByTarget[literal].orEmpty().toSet(), mandarin,
            romaji(text)?.let(::key)?.takeIf { it.isNotBlank() },
            if (latin) latinSound(literal) else null)
    }
}

private fun splitReading(text: String): List<String>? =
    nameWords.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList().takeIf { it.isNotEmpty() }

// Valid syllables are enumerated so an arbitrary English substring cannot enter the pinyin route.
private val syllables = """
    a ai an ang ao e ei en eng er o ou
    ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
    pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
    ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
    fa fan fang fei fen feng fo fou fu
    da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
    ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
    na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve
    la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve
    ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
    ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo
    ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
    ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
    qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
    xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
    zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
    cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo
    sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
    ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
    za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
    ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
    sa sai san sang sao se sen seng si song sou su suan sui sun suo
    ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
    wa wai wan wang wei wen weng wo wu
""".trim().split(Regex("\\s+")).toHashSet()

internal fun splitPinyin(text: String): List<String>? {
    val latin = text.lowercase(Locale.ROOT).replace('ü', 'v')
    if (latin.any { it !in 'a'..'z' && it != ' ' && it != '\'' && it != '-' }) return null
    val words = nameWords.findAll(latin).map { it.value }.toList()
    val result = mutableListOf<String>()
    for (word in words) {
        val next = IntArray(word.length + 1) { -1 }
        next[word.length] = word.length
        for (start in word.lastIndex downTo 0) {
            for (end in minOf(word.length, start + 6) downTo start + 1) {
                if (next[end] >= 0 && word.substring(start, end) in syllables) {
                    next[start] = end
                    break
                }
            }
        }
        if (next[0] < 0) return null
        var start = 0
        while (start < word.length) {
            val end = next[start]
            result += word.substring(start, end)
            start = end
        }
    }
    return result.takeIf { it.isNotEmpty() }
}

private fun latinSound(key: String): String = key.replace("ph", "f").replace("ck", "k")
    .replace("qu", "kw").replace("wh", "w").replace("gh", "g")
    .replace('c', 'k').replace('z', 's')

/** Reuses two work rows for a full scan. No substring window can become an exact name. */
internal class NameMatcher {
    private var previous = IntArray(32)
    private var current = IntArray(32)

    fun compare(query: NameForm, item: NameForm): NameEvidence? {
        if (query.key.isEmpty() || item.key.isEmpty()) return null
        if (query.key == item.key) return NameEvidence(NameRoute.Literal, 1.0, 0.0, 1.0)
        if (query.key in item.aliases) return NameEvidence(NameRoute.Alias, 1.0, 0.0, 1.0)
        var best: NameEvidence? = null
        fun consider(route: NameRoute, from: Int, to: Int, cost: Int, alternate: Boolean = false) {
            val length = maxOf(from, to)
            val similarity = 1.0 - cost / (100.0 * length)
            if (similarity < NameTuning.MIN_SIMILARITY) return
            val match = NameEvidence(route, similarity, cost / 100.0, minOf(from, to).toDouble() / length, alternate)
            if (match.score > (best?.score ?: 0.0)) best = match
        }
        for ((aIndex, a) in query.mandarin.withIndex()) for ((bIndex, b) in item.mandarin.withIndex()) {
            if (!comparable(a.size, b.size)) continue
            val cost = distance(a.size, b.size, 60, 85) { i, j -> mandarinSubstitution(a[i], b[j]) }
            consider(NameRoute.Mandarin, a.size, b.size, cost, aIndex != 0 || bIndex != 0)
        }
        if (query.latinSound != null && item.latinSound != null &&
            query.key.length >= 4 && item.key.length >= 4 && comparable(query.key.length, item.key.length)) {
            consider(NameRoute.Latin, query.key.length, item.key.length, letters(query.key, item.key))
            if (comparable(query.latinSound.length, item.latinSound.length)) {
                consider(NameRoute.Romanized, query.latinSound.length, item.latinSound.length,
                    letters(query.latinSound, item.latinSound))
            }
        } else if (query.latinSound == null && item.latinSound == null &&
            minOf(query.key.length, item.key.length) >= 3 && comparable(query.key.length, item.key.length)) {
            consider(NameRoute.TextEdit, query.key.length, item.key.length, letters(query.key, item.key))
        }
        if (query.romanized != null || item.romanized != null) {
            val a = query.romanized ?: query.key.takeIf { query.latinSound != null }
            val b = item.romanized ?: item.key.takeIf { item.latinSound != null }
            if (a != null && b != null && comparable(a.length, b.length)) {
                consider(NameRoute.Romanized, a.length, b.length, letters(a, b))
            }
        }
        return best
    }

    private fun comparable(a: Int, b: Int) =
        minOf(a, b) >= 2 && minOf(a, b) >= maxOf(a, b) * NameTuning.MIN_COVERAGE

    private fun letters(a: String, b: String): Int =
        distance(a.length, b.length, 85, 85) { i, j -> if (a[i] == b[j]) 0 else 100 }

    // A catalog sound absent from ASR is a deletion (60); an extra ASR sound costs 85.
    private inline fun distance(a: Int, b: Int, missing: Int, extra: Int, substitution: (Int, Int) -> Int): Int {
        if (previous.size <= b) {
            previous = IntArray(b + 1)
            current = IntArray(b + 1)
        }
        for (j in 0..b) previous[j] = j * missing
        val limit = (maxOf(a, b) * 100 * (1 - NameTuning.MIN_SIMILARITY)).toInt()
        if (abs(a - b) * minOf(missing, extra) > limit) return limit + 100
        for (i in 1..a) {
            current[0] = i * extra
            var rowBest = current[0]
            for (j in 1..b) {
                val value = minOf(previous[j] + extra, current[j - 1] + missing,
                    previous[j - 1] + substitution(i - 1, j - 1))
                current[j] = value
                rowBest = minOf(rowBest, value)
            }
            if (rowBest > limit) return limit + 100
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b]
    }
}

private data class SyllableParts(val initial: String, val final: String, val foldedInitial: String, val foldedFinal: String)

private val syllableParts = syllables.associateWith { syllable ->
    val initialLength = when {
        syllable.startsWith("zh") || syllable.startsWith("ch") || syllable.startsWith("sh") -> 2
        syllable[0] in "bpmfdtnlgkhjqxzcsryw" -> 1
        else -> 0
    }
    val initial = syllable.substring(0, initialLength)
    val final = syllable.substring(initialLength)
    val foldedInitial = if (initial == "n") "l" else if (initialLength == 2) initial.take(1) else initial
    val foldedFinal = if (final.endsWith("ang") || final.endsWith("eng") || final.endsWith("ing")) final.dropLast(1) else final
    SyllableParts(initial, final, foldedInitial, foldedFinal)
}

internal fun mandarinSubstitution(a: String, b: String): Int {
    if (a == b) return 0
    val left = syllableParts[a] ?: return 100
    val right = syllableParts[b] ?: return 100
    if (left.foldedInitial != right.foldedInitial || left.foldedFinal != right.foldedFinal) return 100
    return when {
        left.initial == right.initial -> 25
        left.final == right.final -> 30
        else -> 45
    }
}

private val nearbySyllables = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

private fun nearby(sound: String): List<String> = nearbySyllables.computeIfAbsent(sound) {
    syllables.mapNotNull { other ->
        val cost = mandarinSubstitution(sound, other)
        if (cost in 1..45) other to cost else null
    }.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
}

/** Spell the complete phonetic query and try one low-cost sound change at a time. */
internal fun phoneticRecallTerms(form: NameForm): List<String> {
    val sounds = form.mandarin.firstOrNull()?.takeIf { it.size >= 2 } ?: return emptyList()
    return buildList {
        add(sounds.joinToString(" "))
        sounds.forEachIndexed { index, sound ->
            for (other in nearby(sound)) {
                if (size == 4) return@buildList
                add(sounds.toMutableList().apply { set(index, other) }.joinToString(" "))
            }
        }
    }
}
