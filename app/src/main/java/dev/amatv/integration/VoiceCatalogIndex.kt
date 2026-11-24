package dev.amatv.integration

import dev.amatv.integration.voice.NameEncoder
import dev.amatv.integration.voice.NameEvidence
import dev.amatv.integration.voice.NameMatcher
import dev.amatv.integration.voice.NameRoute
import dev.amatv.integration.voice.NameTuning
import dev.amatv.integration.voice.phoneticRecallTerms
import dev.amatv.model.Track

/** The part of the offline voice catalog index that ranks an explicit vendor intent. */
internal class VoiceCatalogIndex(
    private val request: FlymeVoiceCommand.Search,
    private val encoder: NameEncoder = flymeVoiceNameEncoder(VOICE_ALIASES),
) {
    data class Match(val track: Track, val evidence: NameEvidence, val score: Double)
    data class Ranked(val exact: Match?, val phonetic: Match?)

    private val aliases = VOICE_ALIASES.entries.associate { encoder.key(it.key) to it.value }
    private val target = encoder.prepare(request.targetTitle)
    private val performer = request.artist.takeIf {
        it.isNotEmpty() && request.kind != FlymeVoiceCommand.Search.Kind.Artist
    }?.let(encoder::prepare)
    private val matcher = NameMatcher()
    val requiresExactArtistSong = request.kind == FlymeVoiceCommand.Search.Kind.ArtistSong &&
        target.key.isNotEmpty() && performer != null

    val libraryTerm: String get() = aliases[target.key] ?: request.targetTitle.ifEmpty { request.query }

    fun recallTerms(): List<String> {
        val title = request.targetTitle
        if (target.key.isEmpty()) return emptyList()
        val artist = request.artist.takeIf { performer != null }
        val aliasTitle = aliases[target.key]
        val aliasArtist = performer?.let { aliases[it.key] }
        return buildList {
            if (aliasTitle != null || aliasArtist != null) {
                add(listOfNotNull(aliasTitle ?: title, aliasArtist ?: artist).joinToString(" "))
            }
            val simplified = ChineseText.simplified(request.query)
            if (simplified != request.query) add(simplified)
            phoneticRecallTerms(target).forEach { add(listOfNotNull(it, aliasArtist ?: artist).joinToString(" ")) }
            if (performer != null) {
                phoneticRecallTerms(performer).forEach { add("$title $it") }
            }
        }.distinct().filterNot { it.equals(request.query, ignoreCase = true) }.take(4)
    }

    fun accepts(track: Track): Boolean {
        if (!request.acceptsVoiceKind(track.catalogKind)) return false
        return performer == null || matcher.compare(performer, encoder.prepare(track.artist))?.isNamedMatch == true
    }

    fun exactArtistSong(items: List<Track>): Track? = items.firstOrNull { track ->
        request.acceptsVoiceKind(track.catalogKind) && encoder.key(track.title) == target.key &&
            artistCreditSeparator.split(track.artist).any { encoder.key(it) == performer?.key }
    }

    fun rank(library: List<Track>, catalog: List<Track> = emptyList()): Ranked {
        fun matches(items: List<Track>, inLibrary: Boolean) = items.mapIndexedNotNull { index, track ->
            if (!accepts(track)) return@mapIndexedNotNull null
            val evidence = matcher.compare(target, encoder.prepare(track.title)) ?: return@mapIndexedNotNull null
            // Keep service order for literal matches. Use the old index weights to rank phonetic alternatives.
            val score = evidence.score + if (inLibrary) NameTuning.LIBRARY_WEIGHT else NameTuning.APPLE_WEIGHT / (index + 1)
            Match(track, evidence, score)
        }
        val matches = matches(library, inLibrary = true) + matches(catalog, inLibrary = false)
        val exact = matches.firstOrNull { it.evidence.literalExact }
            ?: matches.firstOrNull { it.evidence.isNamedMatch }
        val phonetic = matches.asSequence()
            .filter { match ->
                val evidence = match.evidence
                (evidence.route == NameRoute.Mandarin || evidence.route == NameRoute.Romanized) &&
                    evidence.coverage == 1.0 && evidence.cost <= 0.45
            }
            .sortedByDescending { it.score }
            .distinctBy { encoder.key(it.track.title) }
            .take(2).toList()
        val first = phonetic.firstOrNull()
        val second = phonetic.getOrNull(1)
        val margin = if (target.key.length <= 3) NameTuning.SHORT_MARGIN else NameTuning.AUTOPLAY_MARGIN
        return Ranked(exact, first?.takeIf { second == null || it.score - second.score >= margin })
    }

    private val NameEvidence.isNamedMatch
        get() = literalExact || route == NameRoute.Alias && exact
}

internal fun flymeVoiceNameEncoder(aliases: Map<String, String> = emptyMap()) =
    NameEncoder(ChineseText::simplified, ChineseText::pinyin, ChineseText::romaji, aliases, ChineseText::alternatePinyin)

private val artistCreditSeparator = Regex("""[&、,，＆]|\s+(?:feat\.?|with)\s+""", RegexOption.IGNORE_CASE)
