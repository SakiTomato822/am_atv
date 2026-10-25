package dev.amatv.integration

import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricTimeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppleTtmlParserTest {
    @Test
    fun overlappingTtmlParagraphsRemainSeparateConcurrentVocalLines() {
        val lines = AppleTtmlParser.parse(
            """<tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata>
                <ttm:agent xml:id="lead" type="person"/>
                <ttm:agent xml:id="duet" type="person"/>
              </metadata></head>
              <body><div>
                <p begin="10.000" end="16.000" ttm:agent="lead">Lead</p>
                <p begin="12.000" end="17.000" ttm:agent="duet">Harmony</p>
                <p begin="18.500" end="22.000" ttm:agent="lead">Next</p>
              </div></body>
            </tt>""",
        )

        assertEquals(listOf("Lead", "Harmony", "Next"), lines.map { it.text })
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(lines, 13_000))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(lines, 16_500))
        assertEquals(0, LyricTimeline.activeLineAt(lines, 17_000, true))
        assertEquals(2, LyricTimeline.activeLineAt(lines, 18_320, true))
    }

    @Test
    fun parsesAppleWordTimingTranslationAndNestedEmphasisGroup() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word">
              <body><div>
                <p begin="10.000" end="12.500" itunes:key="L1">
                  <span begin="10.000" end="10.500">More</span>
                  <span begin="10.500" end="12.000">
                    <span begin="10.500" end="11.100">than</span>
                    <span begin="11.100" end="12.000">perfect</span>
                  </span>
                </p>
                <p begin="00:13.000" end="00:14.250">獨<span begin="13s" end="13.5s">一</span><span begin="13.5s" end="14.25s">無二</span></p>
              </div></body>
              <itunes:iTunesMetadata>
                <itunes:translations><itunes:translation>
                  <itunes:text for="L1">無比完美</itunes:text>
                </itunes:translation></itunes:translations>
              </itunes:iTunesMetadata>
            </tt>
        """.trimIndent()

        val lines = AppleTtmlParser.parse(ttml, includeTranslations = true)

        assertEquals(2, lines.size)
        assertEquals(listOf("More", "than", "perfect"), lines[0].words.map { it.text })
        assertEquals(10_000L, lines[0].words[0].startMs)
        assertEquals(12_000L, lines[0].words[2].endMs)
        assertEquals("無比完美", lines[0].translation)
        assertTrue(lines[0].joinWithSpaces)
        assertEquals(lines[0].words[1].emphasisGroupId, lines[0].words[2].emphasisGroupId)
        assertFalse(lines[1].joinWithSpaces)

        val linesWithoutTranslation = AppleTtmlParser.parse(ttml, includeTranslations = false)
        assertEquals(null, linesWithoutTranslation[0].translation)
    }

    @Test
    fun groupsAdjacentNonWhitespaceSyllablesAndParsesBackgroundVocalsAgentsAndSongwriters() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person"/>
                  <ttm:agent xml:id="v2" type="person"/>
                  <itunes:iTunesMetadata>
                    <itunes:songwriters>
                      <itunes:songwriter>Max Martin</itunes:songwriter>
                      <itunes:songwriter>Shellback</itunes:songwriter>
                    </itunes:songwriters>
                    <itunes:transliterations>
                      <itunes:transliteration xml:lang="ja-Latn">
                        <itunes:text for="L1"><span begin="1.000" end="1.500">kimi</span></itunes:text>
                      </itunes:transliteration>
                    </itunes:transliterations>
                  </itunes:iTunesMetadata>
                </metadata>
              </head>
              <body><div>
                <p begin="1.000" end="3.500" itunes:key="L1" ttm:agent="v2">
                  <span begin="1.000" end="1.500">per</span><span begin="1.500" end="2.600">fect</span> <span begin="2.600" end="3.000">world</span>
                  <span ttm:role="x-bg" begin="2.800" end="3.500">
                    <span begin="2.800" end="3.100">(yeah</span> <span begin="3.100" end="3.500">now)</span>
                  </span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val line = AppleTtmlParser.parse(ttml).single()

        assertEquals(LyricTiming.Word, line.timing)
        assertTrue(line.isMultiAgentSong)
        assertTrue(line.isOppositeAgent)
        assertEquals(listOf("Max Martin", "Shellback"), line.songwriters)
        assertEquals(listOf("per", "fect", "world"), line.words.map { it.text })
        assertNotNull(line.words[0].emphasisGroupId)
        assertEquals(line.words[0].emphasisGroupId, line.words[1].emphasisGroupId)
        assertEquals(1_600L, line.words[0].groupDurationMs)
        assertEquals(7, line.words[0].groupCharCount)
        assertEquals("kimi", line.words[0].pronunciationText)
        assertEquals(listOf("yeah", "now"), line.backgroundWords.map { it.text })
        assertTrue(line.backgroundWords.all { it.isBackgroundVocal })
    }

    @Test
    fun parsesLineTimingWithoutInventingWordIntervals() {
        val lines = AppleTtmlParser.parse(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="1.25s" end="3.75s">A whole line</p>
            </div></body></tt>""",
        )

        assertEquals(1, lines.size)
        assertEquals(1, lines.single().words.size)
        assertEquals("A whole line", lines.single().words.single().text)
        assertEquals(1_250L, lines.single().startMs)
        assertEquals(3_750L, lines.single().endMs)
    }

    @Test
    fun localeUtilAndExtractTtmlFollowApkLogic() {
        assertEquals("zh-Hant", AppleLocaleUtil.getSystemLyricsLanguage(Locale.TAIWAN))
        assertEquals("zh-Hans", AppleLocaleUtil.getSystemLyricsLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals("en-US", AppleLocaleUtil.getSystemLyricsLanguage(Locale.US))

        val client = AppleMusicLyricsClient(
            storefront = "tw",
            userToken = { "token" },
        )
        assertEquals(
            "<tt>localized</tt>",
            client.selectTtml(ttmlLocalizations = "<tt>localized</tt>", ttml = "<tt>base</tt>", preferLocalizations = true),
        )
        assertEquals(
            "<tt>base</tt>",
            client.selectTtml(ttmlLocalizations = "<tt>localized</tt>", ttml = "<tt>base</tt>", preferLocalizations = false),
        )
        assertEquals(
            "<tt>base</tt>",
            client.selectTtml(ttmlLocalizations = "", ttml = "<tt>base</tt>", preferLocalizations = false),
        )
        assertEquals(
            "<tt>localized</tt>",
            client.selectTtml(ttmlLocalizations = "<tt>localized</tt>", ttml = "", preferLocalizations = false),
        )
    }

    @Test
    fun parsesSupportedAppleTimeExpressions() {
        assertEquals(62_500L, AppleTtmlParser.parseTimeMs("01:02.500"))
        assertEquals(3_250L, AppleTtmlParser.parseTimeMs("3.25s"))
        assertEquals(725L, AppleTtmlParser.parseTimeMs("725ms"))
    }
}
