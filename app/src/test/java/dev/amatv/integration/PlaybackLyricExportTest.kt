package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class PlaybackLyricExportTest {
    @Test fun parsedTtmlExportsSyllablesAsWordsAndChineseWithoutInsertedSpaces() {
        val lines = AppleTtmlParser.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="0s" end="3s"><span begin="0s" end="0.5s">Lis</span><span begin="0.5s" end="1s">ten</span><span begin="1s" end="1.5s">ing</span> <span begin="1.5s" end="3s">now</span></p>
              <p begin="3s" end="6s"><span begin="3s" end="4s">你</span><span begin="4s" end="5s">好</span><span begin="5s" end="6s">吗</span> </p>
            </div></body></tt>
        """.trimIndent())
        val export = PlaybackLyricExport.create("track:parsed", lines, includeBackgroundVocals = false)
        assertEquals("Listening now\n你好吗", export.plainText)
        assertEquals("[00:00.000]Listening now\n[00:03.000]你好吗", export.timedLrc)
    }

    private fun line(
        main: String? = null,
        mainMs: Long = 0L,
        background: String? = null,
        backgroundMs: Long = mainMs,
        timing: LyricTiming = LyricTiming.Word,
        isStatic: Boolean = false,
        isTranslation: Boolean = false,
        translation: String? = null,
        pronunciation: String? = null,
    ) = LyricLine(
        words = main?.let { listOf(LyricWord(it, mainMs, mainMs + 2_000L)) }.orEmpty(),
        backgroundWords = background?.let {
            listOf(LyricWord(it, backgroundMs, backgroundMs + 1_500L, isBackgroundVocal = true))
        }.orEmpty(),
        translation = translation,
        isTranslation = isTranslation,
        pronunciationText = pronunciation,
        isStatic = isStatic,
        timing = timing,
    )

    @Test fun mainAndHarmonyKeepTheirOwnStartTimes() {
        val export = PlaybackLyricExport.create(
            "track:duet",
            listOf(line(main = "主唱句", mainMs = 1_200L, background = "和声句", backgroundMs = 450L)),
        )

        assertEquals(PlaybackLyricExportStatus.Ready, export.status)
        assertEquals("主唱句\n和声句", export.plainText)
        assertEquals("[00:00.450]和声句\n[00:01.200]主唱句", export.timedLrc)
        assertEquals(450L, export.currentLineAt(450L)?.startMs)
        assertEquals(1_200L, export.nextLineAfter(450L)?.startMs)
    }

    @Test fun mainOnlyPolicyExcludesHarmonyFromTextCuesAndStructuredVersion() {
        val original = line(
            main = "主唱原文",
            mainMs = 1_200L,
            background = "和声一",
            backgroundMs = 450L,
        ).copy(
            backgroundTranslation = "和声翻译甲",
            backgroundPronunciationText = "harmony reading A",
        )
        val changedHarmony = original.copy(
            words = original.words + LyricWord(
                "嵌入和声", 100L, 800L, isBackgroundVocal = true,
            ),
            backgroundWords = listOf(LyricWord("和声二", 50L, 800L, isBackgroundVocal = true)),
            backgroundTranslation = "和声翻译乙",
            backgroundPronunciationText = "harmony reading B",
            timing = LyricTiming.Line,
        )
        val originalExport = PlaybackLyricExport.create(
            "track:main-only", listOf(original), includeBackgroundVocals = false,
        )
        val changedExport = PlaybackLyricExport.create(
            "track:main-only", listOf(changedHarmony), includeBackgroundVocals = false,
        )

        assertEquals(PlaybackLyricExportStatus.Ready, originalExport.status)
        assertFalse(originalExport.includeBackgroundVocals)
        assertEquals("主唱原文", originalExport.plainText)
        assertEquals("[00:01.200]主唱原文", originalExport.timedLrc)
        assertEquals(originalExport.structuredLyricsVersion, changedExport.structuredLyricsVersion)
        assertNotEquals(
            originalExport.structuredLyricsVersion,
            PlaybackLyricExport.create("track:main-only", listOf(original)).structuredLyricsVersion,
        )
        assertEquals(originalExport.currentLineAt(1_200L), changedExport.currentLineAt(1_200L))
        assertEquals("主唱原文", changedExport.currentLineAt(1_200L)?.text)
        assertEquals("主唱原文", changedExport.currentLineAt(1_200L)?.mainText)
        assertNull(changedExport.currentLineAt(1_200L)?.backgroundText)
        assertEquals(originalExport.currentLineAt(1_200L), changedExport.currentLineAt(1_200L))
        assertNull(originalExport.currentLineAt(500L))
        assertNull(changedExport.currentLineAt(500L))
        assertEquals(1_200L, originalExport.nextLineAfter(500L)?.startMs)
        assertEquals(originalExport.nextLineAfter(500L), changedExport.nextLineAfter(500L))

        val withHarmonyOnlyLine = PlaybackLyricExport.create(
            "track:main-only",
            listOf(line(
                background = "独立和声行", backgroundMs = 2_000L,
                timing = LyricTiming.None, isStatic = true,
            ), original),
            includeBackgroundVocals = false,
        )
        assertEquals(PlaybackLyricExportStatus.Ready, withHarmonyOnlyLine.status)
        assertEquals(originalExport.structuredLyricsVersion, withHarmonyOnlyLine.structuredLyricsVersion)
        assertEquals(originalExport.timedLrc, withHarmonyOnlyLine.timedLrc)
        assertEquals(originalExport.currentLineAt(1_200L), withHarmonyOnlyLine.currentLineAt(1_200L))
        assertEquals(originalExport.nextLineAfter(500L), withHarmonyOnlyLine.nextLineAfter(500L))
    }

    @Test fun renderedWordTextKeepsSyllablesChineseAndActualEnglishWordBoundaries() {
        val listening = LyricLine(
            words = listOf(
                LyricWord("lis", 0L, 300L, emphasisGroupId = 1, hasTrailingWhitespace = false),
                LyricWord("ten", 300L, 600L, emphasisGroupId = 1, hasTrailingWhitespace = false),
                LyricWord("ing", 600L, 900L, emphasisGroupId = 1, hasTrailingWhitespace = true),
            ),
            joinWithSpaces = true,
            timing = LyricTiming.Word,
        )
        val accept = LyricLine(
            words = listOf(
                LyricWord("acc", 1_000L, 1_300L, emphasisGroupId = 2, hasTrailingWhitespace = false),
                LyricWord("ept", 1_300L, 1_600L, emphasisGroupId = 2, hasTrailingWhitespace = true),
            ),
            joinWithSpaces = true,
            timing = LyricTiming.Word,
        )
        val chinese = LyricLine(
            words = listOf(
                LyricWord("主", 2_000L, 2_200L, hasTrailingWhitespace = true),
                LyricWord("歌", 2_200L, 2_400L, hasTrailingWhitespace = true),
                LyricWord("词", 2_400L, 2_600L, hasTrailingWhitespace = true),
            ),
            joinWithSpaces = false,
            timing = LyricTiming.Word,
        )
        val englishWords = LyricLine(
            words = listOf(
                LyricWord("Good", 3_000L, 3_400L, hasTrailingWhitespace = true),
                LyricWord("morning", 3_500L, 4_000L, hasTrailingWhitespace = true),
            ),
            joinWithSpaces = true,
            timing = LyricTiming.Word,
        )
        val displayTextWords = LyricLine(
            words = listOf(
                LyricWord(
                    text = "Hello,", startMs = 4_500L, endMs = 4_800L,
                    displayText = "“Hello, ", hasTrailingWhitespace = true,
                ),
                LyricWord(
                    text = "world!", startMs = 4_800L, endMs = 5_100L,
                    displayText = "world!”", hasTrailingWhitespace = true,
                ),
            ),
            joinWithSpaces = true,
            timing = LyricTiming.Word,
        )

        val export = PlaybackLyricExport.create(
            "track:rendered-words",
            listOf(listening, accept, chinese, englishWords, displayTextWords),
            includeBackgroundVocals = false,
        )

        assertEquals(PlaybackLyricExportStatus.Ready, export.status)
        assertEquals("listening\naccept\n主歌词\nGood morning\n“Hello, world!”", export.plainText)
        assertEquals(
            "[00:00.000]listening\n" +
                "[00:01.000]accept\n" +
                "[00:02.000]主歌词\n" +
                "[00:03.000]Good morning\n" +
                "[00:04.500]“Hello, world!”",
            export.timedLrc,
        )
        assertEquals("listening", export.currentLineAt(0L)?.text)
        assertEquals("accept", export.currentLineAt(1_000L)?.text)
        assertEquals("主歌词", export.currentLineAt(2_000L)?.text)
        assertEquals("Good morning", export.currentLineAt(3_000L)?.text)
        assertEquals("“Hello, world!”", export.currentLineAt(4_500L)?.text)
    }

    @Test fun staticLineUsesItsOriginalDisplayTextWithoutSyntheticSpacing() {
        val static = LyricLine(
            words = listOf(
                LyricWord(
                    text = "Static English lyric",
                    startMs = 0L,
                    endMs = 0L,
                    displayText = "Static English lyric",
                    hasTrailingWhitespace = true,
                ),
            ),
            joinWithSpaces = true,
            isStatic = true,
            timing = LyricTiming.None,
        )

        val export = PlaybackLyricExport.create("track:static-line", listOf(static), includeBackgroundVocals = false)

        assertEquals(PlaybackLyricExportStatus.MissingTimestamps, export.status)
        assertEquals("Static English lyric", export.plainText)
        assertNull(export.timedLrc)
        assertNull(export.currentLineAt(0L))
    }

    @Test fun fullExportUsesRecordedBoundariesForBackgroundVocalsToo() {
        val line = LyricLine(
            words = listOf(
                LyricWord("sing", 1_000L, 1_200L, hasTrailingWhitespace = false),
                LyricWord("ing", 1_200L, 1_400L, hasTrailingWhitespace = true),
            ),
            backgroundWords = listOf(
                LyricWord("har", 800L, 1_000L, hasTrailingWhitespace = false, isBackgroundVocal = true),
                LyricWord("mony", 1_000L, 1_200L, hasTrailingWhitespace = true, isBackgroundVocal = true),
            ),
            joinWithSpaces = true,
            timing = LyricTiming.Word,
        )

        val export = PlaybackLyricExport.create("track:harmony-rendering", listOf(line))

        assertEquals("singing\nharmony", export.plainText)
        assertEquals("[00:00.800]harmony\n[00:01.000]singing", export.timedLrc)
    }

    @Test fun simultaneousVoicesAndRepeatedLinesKeepStableSourceOrder() {
        val export = PlaybackLyricExport.create(
            "track:repeat",
            listOf(
                line(main = "Main one", mainMs = 2_000L, background = "Harmony one", backgroundMs = 2_000L),
                line(main = "Main again", mainMs = 2_000L),
                line(main = "Earlier harmony", mainMs = 3_000L, background = "Earlier", backgroundMs = 1_000L),
            ),
        )

        assertEquals(
            "[00:01.000]Earlier\n" +
                "[00:02.000]Main one\n" +
                "[00:02.000]Harmony one\n" +
                "[00:02.000]Main again\n" +
                "[00:03.000]Earlier harmony",
            export.timedLrc,
        )
        val simultaneous = export.currentLineAt(2_000L)!!
        assertEquals(3, simultaneous.segments.size)
        assertEquals(listOf("Main one", "Harmony one", "Main again"), simultaneous.segments.map { it.text })
        assertEquals("Main one\nHarmony one\nMain again", simultaneous.text)
        assertEquals("Main one\nMain again", simultaneous.mainText)
        assertEquals("Harmony one", simultaneous.backgroundText)
        assertEquals(3_000L, export.nextLineAfter(2_000L)?.startMs)
    }

    @Test fun lrcTimestampsAllowMinutesBeyondOneHour() {
        val export = PlaybackLyricExport.create("track:long", listOf(line(main = "Long track", mainMs = 3_662_003L)))
        assertEquals("[61:02.003]Long track", export.timedLrc)
    }

    @Test fun staticAndMixedUntimedLyricsStayInPlainTextWithoutInventedTimes() {
        val static = PlaybackLyricExport.create(
            "track:static",
            listOf(line(main = "无时间歌词", background = "静态和声", timing = LyricTiming.None, isStatic = true)),
        )
        assertEquals(PlaybackLyricExportStatus.MissingTimestamps, static.status)
        assertEquals("无时间歌词\n静态和声", static.plainText)
        assertNull(static.timedLrc)
        assertNull(static.currentLineAt(Long.MAX_VALUE))

        val mixed = PlaybackLyricExport.create(
            "track:mixed",
            listOf(line(main = "Timed", mainMs = 1_000L),
                line(main = "Untimed", timing = LyricTiming.None, isStatic = true)),
        )
        assertEquals(PlaybackLyricExportStatus.MissingTimestamps, mixed.status)
        assertEquals("Timed\nUntimed", mixed.plainText)
        assertNull(mixed.timedLrc)
        assertNull(mixed.currentLineAt(1_000L))
        assertNull(mixed.nextLineAfter(0L))
    }

    @Test fun emptyOrWhitespaceOnlyLyricsHaveNoFakeCue() {
        val export = PlaybackLyricExport.create(
            "track:empty",
            listOf(line(main = "  \n\t "), line(background = " \r\n ")),
        )
        assertEquals(PlaybackLyricExportStatus.NoLyrics, export.status)
        assertEquals("", export.plainText)
        assertNull(export.timedLrc)
        assertNull(export.currentLineAt(Long.MAX_VALUE))
        assertNull(export.nextLineAfter(Long.MIN_VALUE))
    }

    @Test fun emptyTextIsRemovedAndNewlinesAreCleanedWithoutAddingTranslationsOrPronunciation() {
        val export = PlaybackLyricExport.create(
            "track:clean",
            listOf(
                line(main = "   \r\n \t", background = "\n\t"),
                line(
                    main = "hello\r\nworld",
                    mainMs = 500L,
                    background = "bg\nverse",
                    backgroundMs = 700L,
                    translation = "不要输出这句",
                    pronunciation = "do not output pronunciation",
                ),
                line(main = "单独翻译行", mainMs = 800L, isTranslation = true),
            ),
        )
        assertEquals("hello world\nbg verse", export.plainText)
        assertEquals("[00:00.500]hello world\n[00:00.700]bg verse", export.timedLrc)
        assertFalse(export.plainText!!.contains('\r'))
        assertFalse(export.plainText!!.contains("不要输出这句"))
        assertFalse(export.plainText!!.contains("pronunciation"))
        assertFalse(export.plainText!!.contains("单独翻译行"))
    }

    @Test fun utf8PayloadLimitHasAnExactBoundaryAndNeverReturnsPartialLines() {
        val complete = PlaybackLyricExport.create("track:utf8", listOf(line(main = "你好")))
        assertEquals("[00:00.000]你好", complete.timedLrc)
        val atLimit = PlaybackLyricExport.create(
            "track:utf8", listOf(line(main = "你好")), maxPayloadUtf8Bytes = complete.payloadUtf8Bytes,
        )
        val overLimit = PlaybackLyricExport.create(
            "track:utf8", listOf(line(main = "你好")), maxPayloadUtf8Bytes = complete.payloadUtf8Bytes - 1,
        )

        assertEquals(PlaybackLyricExportStatus.Ready, atLimit.status)
        assertEquals(complete.timedLrc, atLimit.timedLrc)
        assertEquals(PlaybackLyricExportStatus.PayloadTooLarge, overLimit.status)
        assertNull(overLimit.plainText)
        assertNull(overLimit.timedLrc)
        assertNull(overLimit.currentLineAt(0L))

        val wholeLine = PlaybackLyricExport.create(
            "track:whole",
            listOf(line(main = "一整句不能截断")),
            maxPayloadUtf8Bytes = 32,
        )
        assertEquals(PlaybackLyricExportStatus.PayloadTooLarge, wholeLine.status)
        assertNull(wholeLine.timedLrc)
    }

    @Test fun structuredVersionTracksOriginalWordsButIgnoresLocalization() {
        val first = PlaybackLyricExport.create("track:version", listOf(line(
            main = "original", mainMs = 1_000L, translation = "first translation", pronunciation = "p1",
        )))
        val relocalized = PlaybackLyricExport.create("track:version", listOf(line(
            main = "original", mainMs = 1_000L, translation = "second translation", pronunciation = "p2",
        )))
        val withSeparateTranslation = PlaybackLyricExport.create("track:version", listOf(
            line(main = "original", mainMs = 1_000L),
            line(main = "separate translated words", mainMs = 1_100L, isTranslation = true),
        ))
        val changed = PlaybackLyricExport.create("track:version", listOf(line(main = "changed", mainMs = 1_000L)))

        assertEquals(first.structuredLyricsVersion, relocalized.structuredLyricsVersion)
        assertEquals(first.structuredLyricsVersion, withSeparateTranslation.structuredLyricsVersion)
        assertNotEquals(first.structuredLyricsVersion, changed.structuredLyricsVersion)
    }
}
