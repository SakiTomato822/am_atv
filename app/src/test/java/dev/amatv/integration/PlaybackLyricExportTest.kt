package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class PlaybackLyricExportTest {
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
