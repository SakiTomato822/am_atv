package dev.amatv.integration

import android.icu.text.Transliterator
import android.os.Build
import dev.amatv.integration.voice.NameEncoder
import dev.amatv.integration.voice.NameEvidence
import dev.amatv.integration.voice.NameForm
import dev.amatv.integration.voice.NameMatcher
import dev.amatv.integration.voice.NameRoute
import dev.amatv.integration.voice.NameTuning
import dev.amatv.integration.voice.nameKey
import dev.amatv.integration.voice.phoneticRecallTerms
import dev.amatv.integration.voice.splitPinyin
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { Transliterator.getInstance(id) }.getOrNull() else null

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

internal fun voiceCatalogKey(text: String, simplify: (String) -> String = ChineseText::simplified): String =
    nameKey(text, simplify)

internal data class VoiceCatalogMatch(
    val candidates: List<Track>,
    val confident: Boolean,
    val alternatives: List<Track> = candidates,
    val scores: Map<Pair<CatalogKind, String>, Double> = emptyMap(),
) {
    val weak: Boolean get() = candidates.firstOrNull()?.let { scores[it.voiceId()] }?.let {
        it < NameTuning.WEAK_RESULT
    } ?: true
}

internal fun mergeVoiceMatches(first: VoiceCatalogMatch, second: VoiceCatalogMatch): VoiceCatalogMatch {
    val left = first.alternatives.associateBy { it.voiceId() }
    val right = second.alternatives.associateBy { it.voiceId() }
    val scores = (left.keys + right.keys).associateWith { id ->
        maxOf(first.scores[id] ?: 0.0, second.scores[id] ?: 0.0) +
            if (id in left && id in right) NameTuning.REPEATED_EVIDENCE_BONUS else 0.0
    }
    val choices = (right + left).values.sortedByDescending { scores.getValue(it.voiceId()) }.take(NameTuning.CHOICE_LIMIT)
    return VoiceCatalogMatch(choices, false, scores = scores)
}

internal val VOICE_CORRECTIONS = setOf("不是", "不对", "不是这个", "不是这首", "不是这首歌", "错了", "播错了", "换一个", "换一首")
internal fun confirmsVoiceChoice(label: String, count: Int): Boolean =
    label in setOf("对", "这个", "就它") || (count == 1 && label in setOf("是", "嗯", "播吧"))
internal const val VOICE_CORRECTION_WINDOW_MS = 20_000L

internal fun isVoiceCorrection(label: String, now: Long, until: Long): Boolean =
    until > 0 && now <= until && label in VOICE_CORRECTIONS

private val versionSuffix = Regex(
    """(?i)(?:[\s(（\[【\-–]+(live\b|remaster\w*|cover\b|instrumental\b|karaoke\b|dj\b|remix\b)|[\s(（\[【\-–]*(现场|現場|翻唱|伴奏|女版|男版)).*?$""",
)

private fun versionKind(match: MatchResult?): String? {
    val name = match?.groupValues?.let { it[1].ifEmpty { it[2] } }?.lowercase() ?: return null
    return when {
        name == "现场" || name == "現場" -> "live"
        name == "翻唱" -> "cover"
        name == "伴奏" -> "instrumental"
        name.startsWith("remaster") -> "remaster"
        else -> name
    }
}

private fun Track.voiceId() = catalogKind to (catalogId ?: id)

internal fun VoiceCommand.Play.acceptsVoiceKind(kind: CatalogKind): Boolean {
    if (kind == CatalogKind.MusicVideo) return false
    return this.kind == VoiceCommand.Kind.Any || this.kind.name == kind.name ||
        (!kindExplicit && this.kind == VoiceCommand.Kind.Song && kind == CatalogKind.Album)
}

private fun VoiceCommand.Play.targetTitle() =
    if (kind == VoiceCommand.Kind.Album) album ?: title ?: query else title ?: artist ?: query
private fun VoiceCommand.Play.targetArtist() = artist.takeUnless { kind == VoiceCommand.Kind.Artist }

/** Reused while the library snapshot stays the same. Results from Apple are indexed for this query. */
internal class VoiceCatalogIndex(
    library: List<Track>,
    private val encoder: NameEncoder = NameEncoder(
        ChineseText::simplified, ChineseText::pinyin, ChineseText::romaji, VOICE_ALIASES, ChineseText::alternatePinyin,
    ),
) {
    private data class Entry(
        val track: Track,
        val title: NameForm,
        val artist: NameForm,
        val album: NameForm,
        val versionBase: NameForm?,
        val versionKind: String?,
        val inLibrary: Boolean,
    )
    private data class Ranked(val entry: Entry, val match: NameEvidence, val score: Double, val version: Boolean)

    private val libraryEntries = library.distinctBy { it.voiceId() }.map { entry(it, inLibrary = true) }
    private val libraryIds = libraryEntries.mapTo(hashSetOf()) { it.track.voiceId() }
    private val libraryArtists = libraryEntries.asSequence().map {
        if (it.track.catalogKind == CatalogKind.Artist) it.track.title to it.title else it.track.artist to it.artist
    }.filter { it.second.key.isNotEmpty() }.distinctBy { it.second.key }.toList()
    private val aliases = VOICE_ALIASES.entries.associate { encoder.key(it.key) to it.value }

    private fun entry(item: Track, inLibrary: Boolean): Entry {
        val version = versionSuffix.find(item.title)
        val base = version?.range?.first?.let { item.title.substring(0, it) }?.takeIf { it.isNotBlank() }
        return Entry(item, encoder.prepare(item.title), encoder.prepare(item.artist), encoder.prepare(item.album),
            base?.let(encoder::prepare), versionKind(version), inLibrary)
    }

    /** Canonical names are extra search terms only. The original request remains the scoring query. */
    fun needsPhoneticRecall(request: VoiceCommand.Play): Boolean {
        val title = request.targetTitle()
        return splitPinyin(title)?.let { it.size >= 2 } == true ||
            (encoder.key(title).length in 2..3 && encoder.prepare(title).mandarin.isNotEmpty()) ||
            latinSearchStem(title) != null
    }

    private fun latinSearchStem(name: String): String? {
        val words = name.trim().split(' ')
        val last = words.last()
        if (words.size < 2 || last.length < 7 || words.any { it.isEmpty() || !it.isAsciiName() }) return null
        return (words.dropLast(1) + last.dropLast(3)).joinToString(" ")
    }

    fun recallTerms(request: VoiceCommand.Play, local: VoiceCatalogMatch): List<String> {
        val name = request.targetTitle()
        val artist = request.targetArtist()
        val aliasTitle = aliases[encoder.key(name)]
        val aliasArtist = artist?.let { aliases[encoder.key(it)] }
        val relatedArtist = if (artist != null && aliasArtist == null) {
            val query = encoder.prepare(artist)
            val matcher = NameMatcher()
            libraryArtists.asSequence().mapNotNull { (name, indexed) ->
                matcher.compare(query, indexed)?.let { name to it.score }
            }.maxByOrNull { it.second }?.takeIf { it.second >= 0.62 }?.first
        } else null
        return buildList {
            if (aliasTitle != null || aliasArtist != null) add(listOfNotNull(aliasArtist ?: artist, aliasTitle ?: name).joinToString(" "))
            if (relatedArtist != null && encoder.key(relatedArtist) != encoder.key(artist.orEmpty())) add("$relatedArtist $name")
            if (artist != null && request.kind == VoiceCommand.Kind.Song && !request.kindExplicit) add("${artist}的$name")
            for (item in local.candidates.take(2)) {
                add(listOfNotNull(item.artist.takeIf { request.targetArtist() != null && it.isNotBlank() }, item.title).joinToString(" "))
            }
            latinSearchStem(name)?.let { add(listOfNotNull(artist, it).joinToString(" ")) }
            addAll(phoneticRecallTerms(encoder.prepare(request.query)))
        }.distinct().filterNot { it.equals(request.query, ignoreCase = true) }.take(4)
    }

    fun appleChoices(request: VoiceCommand.Play, apple: List<Track>): List<Track> {
        val title = encoder.key(request.targetTitle())
        val artist = request.targetArtist()?.let(encoder::key)
        if (title.length < 2 && artist.isNullOrEmpty()) return emptyList()
        val version = versionKind(versionSuffix.find(request.targetTitle()))
        return apple.asSequence().filter { request.acceptsVoiceKind(it.catalogKind) }
            .filter { version == null || versionKind(versionSuffix.find(it.title)) == version }
            .map { item -> Triple(item, encoder.key(item.title), encoder.key(item.artist)) }
            .filter { (_, _, performer) -> artist == null || performer == artist }
            .filter { (_, name, _) ->
                // Apple's associations can be useful across scripts. For two Latin names, a
                // short fragment such as NEON cannot stand in for the complete spoken name.
                !title.isAsciiName() || !name.isAsciiName() ||
                    minOf(title.length, name.length) >= maxOf(title.length, name.length) * NameTuning.MIN_COVERAGE
            }
            .distinctBy { (item, name, artist) -> Triple(item.catalogKind, name, artist) }
            .take(NameTuning.CHOICE_LIMIT).map { it.first }.toList()
    }

    fun fallbackChoices(request: VoiceCommand.Play, apple: List<Track>): VoiceCatalogMatch {
        if (request.kind == VoiceCommand.Kind.Station) {
            val station = apple.firstOrNull { it.catalogKind == CatalogKind.Station }
            if (station != null) return VoiceCatalogMatch(listOf(station), confident = true)
        }
        val choices = appleChoices(request, apple)
        val scores = if (request.targetArtist() != null) choices.associate { it.voiceId() to NameTuning.WEAK_RESULT }
            else emptyMap()
        return VoiceCatalogMatch(choices, false, scores = scores)
    }

    fun sameNameSongsForMissingAlbum(request: VoiceCommand.Play, apple: List<Track>): List<Track> {
        if (request.kind != VoiceCommand.Kind.Album || !request.kindExplicit) return emptyList()
        val title = encoder.key(request.targetTitle())
        val artist = request.artist?.let(encoder::key)
        return (apple + libraryEntries.map { it.track }).asSequence()
            .filter { it.catalogKind == CatalogKind.Song && encoder.key(it.title) == title &&
                (artist == null || encoder.key(it.artist) == artist) }
            .distinctBy { it.voiceId() }.take(NameTuning.CHOICE_LIMIT).toList()
    }

    fun rank(request: VoiceCommand.Play, apple: List<Track> = emptyList()): VoiceCatalogMatch {
        val appleRanks = apple.withIndex().associate { it.value.voiceId() to it.index }
        val pool = libraryEntries + apple.distinctBy { it.voiceId() }
            .filterNot { it.voiceId() in libraryIds }.map { entry(it, inLibrary = false) }
        val matcher = NameMatcher()
        val target = encoder.prepare(request.targetTitle())
        val requestedVersion = versionKind(versionSuffix.find(request.targetTitle()))
        val artist = request.targetArtist()?.let(encoder::prepare)
        val album = request.album?.takeUnless { request.kind == VoiceCommand.Kind.Album }?.let(encoder::prepare)

        fun rankField(title: NameForm, artist: NameForm?): List<Ranked> = pool.mapNotNull { item ->
            if (!request.acceptsVoiceKind(item.track.catalogKind)) return@mapNotNull null
            if (requestedVersion != null && requestedVersion != item.versionKind) return@mapNotNull null
            val artistMatch = artist?.let { matcher.compare(it, item.artist) ?: return@mapNotNull null }
            if (artistMatch != null && !artistMatch.exact && artistMatch.score < 0.65) return@mapNotNull null
            val albumMatch = album?.let { matcher.compare(it, item.album) ?: return@mapNotNull null }
            if (albumMatch != null && !albumMatch.exact && albumMatch.score < 0.65) return@mapNotNull null

            fun compare(name: NameForm): NameEvidence? {
                if (artist == null && request.kind == VoiceCommand.Kind.Any && title.key in item.artist.aliases) {
                    return NameEvidence(NameRoute.Alias, 1.0, 0.0, 1.0)
                }
                if (artist == null && request.kind == VoiceCommand.Kind.Any && item.artist.key.isNotEmpty() &&
                    (title.key == item.artist.key + name.key || title.key == name.key + item.artist.key)) {
                    return NameEvidence(NameRoute.Literal, 1.0, 0.0, 1.0)
                }
                return matcher.compare(title, name)
            }

            val full = compare(item.title)
            val base = item.versionBase?.let(::compare)
            val version = requestedVersion == null && full?.literalExact != true && item.versionBase != null
            val match = if (base != null && base.score > (full?.score ?: 0.0)) base else full
            if (match == null) return@mapNotNull null
            val appleRank = appleRanks[item.track.voiceId()]
            val shortArtistPrefix = request.kind == VoiceCommand.Kind.Artist && item.track.catalogKind == CatalogKind.Artist &&
                title.key.length == 2 && item.title.key.length == 3 && item.title.key.startsWith(title.key) &&
                (item.inLibrary || (appleRank != null && appleRank < 5))
            val score = match.score + (if (request.kind.name == item.track.catalogKind.name) NameTuning.TYPE_WEIGHT else 0.0) +
                (artistMatch?.let { it.score * NameTuning.CONTEXT_WEIGHT } ?: 0.0) +
                (albumMatch?.let { it.score * NameTuning.CONTEXT_WEIGHT } ?: 0.0) +
                (if (item.inLibrary) NameTuning.LIBRARY_WEIGHT else 0.0) +
                (appleRank?.let { NameTuning.APPLE_WEIGHT / (it + 1) } ?: 0.0) +
                (if (shortArtistPrefix) NameTuning.SHORT_ARTIST_PREFIX_BONUS else 0.0) -
                (if (version) NameTuning.VERSION_PENALTY else 0.0)
            // Keep context similarity useful for ranking without promoting it to literal evidence.
            val weakContext = artistMatch?.takeUnless { it.literalExact } ?: albumMatch?.takeUnless { it.literalExact }
            Ranked(item, if (weakContext != null && match.literalExact) weakContext else match, score, version)
        }.sortedByDescending { it.score }

        var ranked = rankField(target, artist)
        if (artist != null && request.kind == VoiceCommand.Kind.Song && !request.kindExplicit &&
            ranked.none { it.match.literalExact }) {
            ranked = (ranked + rankField(encoder.prepare("${request.artist}的${request.title}"), null))
                .sortedByDescending { it.score }.distinctBy { it.entry.track.voiceId() }
        }
        val distinct = ranked.distinctBy { item ->
            listOf(item.entry.track.catalogKind.name, item.entry.title.key, item.entry.artist.key, item.entry.album.key)
        }
        val first = distinct.firstOrNull() ?: return VoiceCatalogMatch(emptyList(), false)
        val second = distinct.getOrNull(1)
        val minimumGap = if (target.key.length <= 3) NameTuning.SHORT_MARGIN else NameTuning.AUTOPLAY_MARGIN
        val typeCollision = !request.kindExplicit && distinct.drop(1).any {
            it.entry.track.catalogKind != first.entry.track.catalogKind &&
                it.entry.title.key == first.entry.title.key
        }
        val shortSoundCollision = target.key.length <= 3 && distinct.drop(1).any {
            it.match.route == NameRoute.Mandarin && it.match.coverage == 1.0 && it.match.cost <= 0.45
        }
        val nearLatin = distinct.drop(1).any {
            it.match.route == NameRoute.Latin && it.match.cost in 0.01..1.0 && it.match.coverage >= 0.85
        }
        val reading = target.mandarin.firstOrNull()
        val libraryHomophone = first.entry.inLibrary && first.entry.track.catalogKind == CatalogKind.Song &&
            first.match.route == NameRoute.Mandarin && first.match.exact && !first.match.alternativeReading &&
            reading != null && reading.size >= 3 && reading == first.entry.title.mandarin.firstOrNull() &&
            target.key.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } &&
            (artist == null || artist.key == first.entry.artist.key) && (album == null || album.key == first.entry.album.key)
        val strongEvidence = (first.match.literalExact || libraryHomophone) && !first.version
        val confident = first.score >= NameTuning.MIN_AUTOPLAY &&
            (second == null || first.score - second.score >= minimumGap) &&
            strongEvidence && !typeCollision && !shortSoundCollision && !nearLatin
        val display = distinct.filter { !first.match.literalExact || !it.version }
            .take(NameTuning.CHOICE_LIMIT).map { it.entry.track }
        return VoiceCatalogMatch(display, confident, distinct.take(NameTuning.CHOICE_LIMIT).map { it.entry.track },
            distinct.take(NameTuning.CHOICE_LIMIT).associate { it.entry.track.voiceId() to it.score })
    }
}

private fun String.isAsciiName() = isNotEmpty() && all { it in 'a'..'z' || it in '0'..'9' }

internal fun rankCatalog(
    request: VoiceCommand.Play,
    candidates: List<Track>,
    library: List<Track> = emptyList(),
    simplify: (String) -> String = ChineseText::simplified,
    pinyin: (String) -> String? = ChineseText::pinyin,
): VoiceCatalogMatch = VoiceCatalogIndex(library, NameEncoder(simplify, pinyin, aliases = VOICE_ALIASES))
    .rank(request, candidates)
