package dev.amatv.integration.voice

import dev.amatv.integration.VOICE_ALIASES
import dev.amatv.integration.VoiceCatalogIndex
import dev.amatv.integration.VoiceCommand
import dev.amatv.integration.parseVoiceCommand
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class NameRetrievalTest {
    private val sounds = mapOf(
        '安' to "an", '眠' to "mian", '於' to "yu", '追' to "zhui", '憶' to "yi", '之' to "zhi", '地' to "di",
        '因' to "yin", '為' to "wei", '所' to "suo", '以' to "yi",
        '什' to "shen", '麼' to "me", '是' to "shi", '遠' to "yuan", '方' to "fang",
        '林' to "lin", '家' to "jia", '謙' to "qian", '孫' to "sun", '燕' to "yan", '姿' to "zi",
        '西' to "xi", '凉' to "liang", '昔' to "xi", '涟' to "lian",
        '更' to "geng", '加' to "jia", '会' to "hui", '彭' to "peng", '佳' to "jia", '慧' to "hui",
    )
    private val encoder = NameEncoder(
        pinyin = { text -> text.takeIf { it.isNotEmpty() && it.all(sounds::containsKey) }
            ?.map { sounds.getValue(it) }?.joinToString(" ") },
        romaji = { if (it == "トゲナシトゲアリ") "togenashitogeari" else null },
        aliases = VOICE_ALIASES,
    )

    private fun track(name: String, kind: CatalogKind = CatalogKind.Song, artist: String = "") =
        Track(id = "test:$kind/$name/$artist", title = name, artist = artist, album = "",
            durationMs = 0, palette = emptyList(), lyrics = emptyList(), catalogKind = kind)

    private fun request(spoken: String) = parseVoiceCommand(spoken) as VoiceCommand.Play

    @Test
    fun wholePinyinAndDroppedSyllablesRecallTheOriginalEntity() {
        val songs = listOf("安眠於追憶之地", "因為所以", "什麼是遠方").map { track(it) }
        val index = VoiceCatalogIndex(songs, encoder)
        listOf("an mian yu zhui yi zhi di", "因所以", "什是遠方").forEachIndexed { i, heard ->
            val result = index.rank(request("播放 $heard"))
            assertEquals(songs[i], result.candidates.first())
            assertFalse(result.confident)
        }
        assertEquals(listOf("xi", "lian"), splitPinyin("xilian"))
        assertNull(splitPinyin("celine deon"))
    }

    @Test
    fun shortArtistOmissionAndSpellingCollisionRequireConfirmation() {
        val artist = track("林家謙", CatalogKind.Artist)
        val result = VoiceCatalogIndex(listOf(artist), encoder).rank(request("播放林謙的歌"))
        assertEquals(listOf(artist), result.candidates)
        assertFalse(result.confident)
        val alternative = track("昔涟", CatalogKind.Artist)
        val literal = track("西凉", CatalogKind.Artist)
        val index = VoiceCatalogIndex(emptyList(), encoder)
        val heard = request("播放西凉")
        assertTrue(index.needsPhoneticRecall(heard))
        assertTrue("xi lian" in index.recallTerms(heard, index.rank(heard)))
        assertTrue("xi lian" in index.recallTerms(request("播放xilian"), index.rank(request("播放xilian"))))
        val collision = VoiceCatalogIndex(listOf(alternative), encoder)
            .rank(request("播放西凉的歌"), listOf(literal))
        assertEquals(setOf(alternative, literal), collision.candidates.toSet())
        assertFalse(collision.confident)
        val latin = track("XILIAN.", CatalogKind.Artist)
        val pinyinCollision = VoiceCatalogIndex(listOf(alternative), encoder)
            .rank(request("播放 xilian 的歌"), listOf(latin))
        assertEquals(setOf(alternative, latin), pinyinCollision.candidates.toSet())
        assertFalse(pinyinCollision.confident)
        val peng = track("彭佳慧", CatalogKind.Artist)
        assertEquals(listOf(peng), VoiceCatalogIndex(listOf(peng), encoder)
            .rank(request("播放更加会的歌")).candidates)
    }

    @Test
    fun allLatinCharactersMustAlignAndAppleOrderCannotAuthorizePlayback() {
        val celine = track("Céline Dion", CatalogKind.Artist)
        val neon = track("NEON", CatalogKind.Artist)
        val result = VoiceCatalogIndex(emptyList(), encoder)
            .rank(request("播放 Celine Deon 的歌"), listOf(neon, celine))
        assertEquals(listOf(celine), result.candidates)
        assertFalse(result.confident)
        assertTrue(VoiceCatalogIndex(emptyList(), encoder)
            .appleChoices(request("播放 Celine Deon 的歌"), listOf(neon)).isEmpty())
        assertEquals("beyonce", nameKey("Ｂｅｙｏｎｃé"))
        assertEquals("トゲナシトゲアリ", nameKey("トゲナシトゲアリ"))
    }

    @Test
    fun aliasAndRomajiFindCanonicalIdsWithoutRewritingResults() {
        val bruno = track("Bruno Mars", CatalogKind.Artist)
        val billie = track("Billie Eilish", CatalogKind.Artist)
        val band = track("トゲナシトゲアリ", CatalogKind.Artist)
        val index = VoiceCatalogIndex(emptyList(), encoder)
        val spoken = request("播放布鲁诺马尔斯的歌")
        assertEquals("Bruno Mars", index.recallTerms(spoken, index.rank(spoken)).first())
        assertEquals(listOf(bruno), index.rank(spoken, listOf(bruno)).candidates)
        assertEquals(listOf(billie), index.rank(request("播放比莉艾利什的歌"), listOf(billie)).candidates)
        assertEquals(listOf(band), index.rank(request("播放无刺有刺"), listOf(band)).candidates)
        assertEquals(listOf(band), index.rank(request("播放 togenashitogeari"), listOf(band)).candidates)
        assertTrue(index.rank(spoken.copy(kind = VoiceCommand.Kind.Album, kindExplicit = true), listOf(bruno))
            .candidates.isEmpty())
    }

    @Test
    fun costsAndCoverageArePreserved() {
        assertEquals(25, mandarinSubstitution("an", "ang"))
        assertEquals(30, mandarinSubstitution("zai", "zhai"))
        assertEquals(45, mandarinSubstitution("zan", "zhang"))
        assertEquals(100, mandarinSubstitution("lin", "jia"))
        val matcher = NameMatcher()
        val full = encoder.prepare("因為所以")
        val missing = matcher.compare(encoder.prepare("因所以"), full)!!
        assertEquals(NameRoute.Mandarin, missing.route)
        assertEquals(0.60, missing.cost, 0.001)
        assertEquals(0.75, missing.coverage, 0.001)
        assertEquals(0.85, matcher.compare(full, encoder.prepare("因所以"))!!.cost, 0.001)
    }

    @Test
    fun artistAndAlbumAreIndependentFromTheTitle() {
        val index = VoiceCatalogIndex(listOf(track("林家謙", CatalogKind.Artist)), encoder)
        val spoken = request("播放林謙的某首歌")
        assertTrue(index.recallTerms(spoken, index.rank(spoken)).any { it.startsWith("林家謙 ") })

        val original = track("Title", artist = "Singer").copy(album = "Original")
        val other = original.copy(id = "other", album = "Other")
        val song = VoiceCommand.Play("Singer Title", VoiceCommand.Kind.Song, title = "Title",
            artist = "Singer", album = "Original", kindExplicit = true)
        assertEquals(listOf(original), index.rank(song, listOf(other, original)).candidates)
    }

    @Test
    fun benchmarkFullScanOfThreeThousandPreparedNames() {
        val chars = "安眠於追憶之地因為所"
        val names = List(3_000) { n ->
            "安眠" + chars[n / 1000] + chars[n / 100 % 10] + chars[n / 10 % 10] + chars[n % 10] + "地"
        }
        val index = VoiceCatalogIndex(names.map { track(it) }, encoder)
        val queries = listOf("播放 an mian yu zhui yi zhi di", "播放安眠追憶之地", "播放 an mian yin wei suo di")
            .map(::request)
        repeat(45) { index.rank(queries[it % queries.size]) }
        val times = List(90) { i -> measureNanoTime { index.rank(queries[i % queries.size]) } }.sorted()
        val p95Millis = times[85] / 1_000_000.0
        println("R3 scan benchmark (3,000 names, warmed JVM) p95=${p95Millis}ms")
        assertTrue("p95=${p95Millis}ms", p95Millis < 30.0)
    }
}
