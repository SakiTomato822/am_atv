package dev.amatv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelineTest {
    private val lines = listOf(
        LyricLine(listOf(LyricWord("one", 1_000, 1_500))),
        LyricLine(listOf(LyricWord("two", 2_000, 2_500))),
    )

    @Test
    fun activeLineTracksLatestStartedLine() {
        assertEquals(0, LyricTimeline.activeLineAt(lines, 1_200))
        assertEquals(1, LyricTimeline.activeLineAt(lines, 2_100))
    }

    @Test
    fun dynamicKaraokeLineAdvanceClampsAndInterpolatesGap() {
        val gapLines = listOf(
            LyricLine(
                words = listOf(LyricWord("first", 1_000, 2_000), LyricWord("line", 2_000, 3_000)),
                timing = LyricTiming.Word,
            ),
            LyricLine(
                words = listOf(LyricWord("second", 3_200, 4_000), LyricWord("line", 4_000, 5_000)),
                timing = LyricTiming.Word,
            ),
            LyricLine(
                words = listOf(LyricWord("third", 5_750, 6_500), LyricWord("line", 6_500, 7_000)),
                timing = LyricTiming.Word,
            ),
        )
        assertEquals(480, LyricTimeline.dynamicKaraokeLineAdvanceMs(gapLines, 0))
        assertEquals(750, LyricTimeline.dynamicKaraokeLineAdvanceMs(gapLines, 1))
    }

    @Test
    fun adjacentLineWaitsForLastSyllableThenHandsOffWithoutAGap() {
        val adjacent = listOf(
            LyricLine(
                words = listOf(LyricWord("still", 1_000, 1_800), LyricWord("singing", 1_800, 2_000)),
                timing = LyricTiming.Word,
            ),
            LyricLine(
                words = listOf(LyricWord("next", 2_003, 2_400)),
                timing = LyricTiming.Word,
            ),
        )
        assertEquals(0, LyricTimeline.activeLineAt(adjacent, 1_999, true))
        assertEquals(listOf(0), LyricTimeline.activeLineIndicesAt(adjacent, 1_999))
        assertEquals(1, LyricTimeline.activeLineAt(adjacent, 2_000, true))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(adjacent, 2_000))
    }

    @Test
    fun briefReleaseOverlapHandsOffAtPriorEndWhileBothWordsStillAnimate() {
        val overlap = listOf(
            LyricLine(listOf(LyricWord("last", 1_000, 2_120))),
            LyricLine(listOf(LyricWord("next", 2_000, 2_500))),
        )
        assertEquals(0, LyricTimeline.activeLineAt(overlap, 2_119, true))
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(overlap, 2_119))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(overlap, 2_120))
        assertEquals(1, LyricTimeline.activeLineAt(overlap, 2_120, true))
        assertEquals(1, LyricTimeline.nextClusterStartIndex(overlap, 0))
        assertEquals(-120L, LyricTimeline.lineLeadOffsetMs(overlap, 1))

        val gap = listOf(
            LyricLine(listOf(LyricWord("last", 1_000, 2_000))),
            LyricLine(listOf(LyricWord("next", 2_500, 3_000))),
        )
        assertEquals(0, LyricTimeline.activeLineAt(gap, 2_319, true))
        assertEquals(1, LyricTimeline.activeLineAt(gap, 2_320, true))
    }

    @Test
    fun chainOfShortReleaseOverlapsAdvancesEachPhraseWithoutWaitingForTheVerse() {
        val phrases = listOf(
            LyricLine(listOf(LyricWord("one", 1_000, 2_100))),
            LyricLine(listOf(LyricWord("two", 2_000, 2_600))),
            LyricLine(listOf(LyricWord("three", 2_500, 3_100))),
            LyricLine(listOf(LyricWord("four", 3_000, 3_500))),
        )

        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(phrases, 2_050))
        assertEquals(0, LyricTimeline.activeLineAt(phrases, 2_099, true))
        assertEquals(1, LyricTimeline.activeLineAt(phrases, 2_100, true))
        assertEquals(1, LyricTimeline.activeLineAt(phrases, 2_599, true))
        assertEquals(2, LyricTimeline.activeLineAt(phrases, 2_600, true))
        assertEquals(3, LyricTimeline.activeLineAt(phrases, 3_100, true))
        assertEquals(listOf(1, 2, 3, -1), phrases.indices.map {
            LyricTimeline.nextClusterStartIndex(phrases, it)
        })
    }

    @Test
    fun genuinelyOverlappingShortSingersStayTogetherEvenInsideALongCluster() {
        val layered = listOf(
            LyricLine(listOf(LyricWord("held", 1_000, 10_000))),
            LyricLine(listOf(LyricWord("ha", 9_900, 10_100))),
            LyricLine(listOf(LyricWord("answer", 10_000, 11_000))),
            LyricLine(listOf(LyricWord("next", 11_100, 12_000))),
        )

        // The 100 ms overlap is half of the short singer's phrase, rather
        // than a release tail. The long first singer must not dilute that ratio.
        assertEquals(3, LyricTimeline.nextClusterStartIndex(layered, 0))
        assertEquals(listOf(1, 2), LyricTimeline.activeLineIndicesAt(layered, 10_050))
        assertEquals(0, LyricTimeline.activeLineAt(layered, 10_999, true))
        assertEquals(0L, LyricTimeline.lineLeadOffsetMs(layered, 2))
        assertEquals(3, LyricTimeline.activeLineAt(layered, 11_000, true))
    }

    @Test
    fun seekingAcrossReleaseOverlapsDoesNotDependOnThePreviousPlaybackPosition() {
        val phrases = listOf(
            LyricLine(listOf(LyricWord("one", 1_000, 2_100))),
            LyricLine(listOf(LyricWord("two", 2_000, 2_600))),
            LyricLine(listOf(LyricWord("three", 2_500, 3_100))),
        )

        assertEquals(2, LyricTimeline.activeLineAt(phrases, 3_000, true))
        assertEquals(0, LyricTimeline.activeLineAt(phrases, 1_500, true))
        assertEquals(listOf(0), LyricTimeline.activeLineIndicesAt(phrases, 1_500))
        assertEquals(1, LyricTimeline.activeLineAt(phrases, 2_200, true))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(phrases, 2_200))
        assertEquals(-1, LyricTimeline.activeLineAt(phrases, 0, true))
    }

    @Test
    fun staticRowsDoNotChangeOverlapBoundariesOrTheirHandoffTime() {
        val withStaticRow = listOf(
            LyricLine(listOf(LyricWord("one", 1_000, 2_100))),
            LyricLine(listOf(LyricWord("credit", 0, 0)), isStatic = true),
            LyricLine(listOf(LyricWord("two", 2_000, 2_600))),
        )

        assertEquals(2, LyricTimeline.nextClusterStartIndex(withStaticRow, 0))
        assertEquals(-100L, LyricTimeline.lineLeadOffsetMs(withStaticRow, 2))
        assertEquals(0, LyricTimeline.activeLineAt(withStaticRow, 2_099, true))
        assertEquals(2, LyricTimeline.activeLineAt(withStaticRow, 2_100, true))
    }

    @Test
    fun completedLineStaysLitAtItsAnchorAndFadesOnlyWithActualUpwardTravel() {
        val line = lines.first()

        fun presence(position: Long, top: Float) = LyricTimeline.completedLinePresence(
            line, position, top, focalAnchorY = 200f, fadeDistance = 64f,
        )

        assertEquals(0f, presence(1_499, 200f), 0f) // still governed by the live word sweep
        assertEquals(1f, presence(1_500, 200f), 0f)
        assertEquals(1f, presence(10_000, 200f), 0f) // time passing alone cannot dim it
        assertEquals(0.75f, presence(1_500, 184f), 0f)
        assertEquals(0f, presence(1_500, 136f), 0f)
        assertEquals(0f, presence(1_200, 200f), 0f) // seek backward restores the word sweep
    }

    @Test
    fun backgroundTailAndPaddedLineEndDoNotDelayMainVocalCompletion() {
        val line = LyricLine(
            words = listOf(LyricWord("lead", 1_000, 2_000)),
            backgroundWords = listOf(LyricWord("echo", 1_800, 2_800, isBackgroundVocal = true)),
            lineEndMs = 3_000,
        )

        assertEquals(1f, LyricTimeline.completedLinePresence(
            line, 2_000, rowTop = 200f, focalAnchorY = 200f, fadeDistance = 64f,
        ), 0f)
        assertEquals(0f, LyricTimeline.completedLinePresence(
            line, 2_000, rowTop = 200f, focalAnchorY = 200f, fadeDistance = 64f,
            completionMs = 2_800,
        ), 0f) // background remains driven by its own live sweep
        assertEquals(0f, LyricTimeline.completedLinePresence(
            line, 2_800, rowTop = 136f, focalAnchorY = 200f, fadeDistance = 64f,
            completionMs = 2_800,
        ), 0f)
    }

    @Test
    fun interludeDisplacementMustBeIncludedInTheRenderedPositionUsedForFading() {
        // A slot keeps the rendered row below the anchor despite 64 px of scroll.
        val renderedTop = 200f - 64f + 84f
        assertEquals(1f, LyricTimeline.completedLinePresence(
            lines.first(), 1_600, renderedTop, focalAnchorY = 200f, fadeDistance = 64f,
        ), 0f)
        // Once the slot collapses and the row actually rises, it can finish fading.
        assertEquals(0f, LyricTimeline.completedLinePresence(
            lines.first(), 1_600, renderedTop - 84f, focalAnchorY = 200f, fadeDistance = 64f,
        ), 0f)
    }

    @Test
    fun preparedScheduleSharesReleaseHandoffAndConcurrentSingersAcrossSeeks() {
        val phrases = listOf(
            LyricLine(listOf(LyricWord("one", 1_000, 2_100))),
            LyricLine(listOf(LyricWord("two", 2_000, 2_600))),
            LyricLine(listOf(LyricWord("three", 2_500, 3_100))),
        )
        val schedule = LyricTimeline.Schedule(phrases)

        assertEquals(listOf(0, 1), schedule.snapshotAt(2_050).activeLineIndices)
        assertEquals(0, schedule.snapshotAt(2_099).anchorLineIndex)
        assertEquals(1, schedule.snapshotAt(2_100).anchorLineIndex)
        assertEquals(listOf(1), schedule.snapshotAt(2_100).activeLineIndices)
        assertEquals(2, schedule.snapshotAt(2_900).anchorLineIndex)
        assertEquals(0, schedule.snapshotAt(1_200).anchorLineIndex)
        assertEquals(listOf(0), schedule.snapshotAt(1_200).activeLineIndices)
        assertEquals(2, schedule.nextClusterStartIndex(1))
    }

    @Test
    fun preparedScheduleKeepsGenuineDuetsAndPrecomputedInterludes() {
        val duet = listOf(
            LyricLine(listOf(LyricWord("lead", 10_000, 16_000))),
            LyricLine(listOf(LyricWord("harmony", 12_000, 17_000))),
            LyricLine(listOf(LyricWord("next", 25_000, 26_000))),
        )
        val schedule = LyricTimeline.Schedule(duet)

        assertEquals(listOf(0, 1), schedule.snapshotAt(15_000).activeLineIndices)
        assertEquals(0, schedule.snapshotAt(16_999).anchorLineIndex)
        assertEquals(2, schedule.nextClusterStartIndex(0))
        assertEquals(2, schedule.snapshotAt(18_000).instrumentalGap?.beforeLineIndex)
        assertEquals(2, schedule.snapshotAt(24_500).anchorLineIndex)
        assertEquals(listOf(2), schedule.snapshotAt(24_500).activeLineIndices)
        assertNull(schedule.snapshotAt(24_700).instrumentalGap)
    }

    @Test
    fun interludeTakesScrollFocusAsSoonAsThePreviousLineFinishes() {
        val schedule = LyricTimeline.Schedule(listOf(
            LyricLine(listOf(LyricWord("previous", 1_000, 2_000))),
            LyricLine(listOf(LyricWord("next", 10_000, 11_000))),
        ))
        assertEquals(0, schedule.snapshotAt(1_999).scrollAnchorLineIndex)
        assertNull(schedule.snapshotAt(1_999).instrumentalScrollAnchor)
        for (position in listOf(2_000L, 5_000L, 9_499L)) {
            val snapshot = schedule.snapshotAt(position)
            assertEquals(0, snapshot.anchorLineIndex)
            assertEquals(1, snapshot.scrollAnchorLineIndex)
            assertEquals(2_000L, snapshot.instrumentalScrollAnchor?.startMs)
            assertEquals(emptyList<Int>(), snapshot.activeLineIndices)
        }
        // The next lyric keeps the same layout anchor when its cue starts.
        val nextCue = schedule.snapshotAt(9_500)
        assertEquals(1, nextCue.scrollAnchorLineIndex)
        assertNull(nextCue.instrumentalScrollAnchor)
        assertEquals(listOf(1), nextCue.activeLineIndices)
    }

    @Test
    fun ordinarySilentGapDoesNotGainTheInterludeScrollException() {
        val schedule = LyricTimeline.Schedule(listOf(
            LyricLine(listOf(LyricWord("previous", 1_000, 2_000))),
            LyricLine(listOf(LyricWord("next", 8_500, 10_000))),
        ))
        val silentGap = schedule.snapshotAt(5_000)
        assertNull(silentGap.instrumentalScrollAnchor)
        assertEquals(0, silentGap.scrollAnchorLineIndex)
        assertEquals(emptyList<Int>(), silentGap.activeLineIndices)
    }

    @Test
    fun interludeScrollWaitsForEveryOverlappingSingerAndRewindsWithoutLatching() {
        val schedule = LyricTimeline.Schedule(listOf(
            LyricLine(listOf(LyricWord("lead", 10_000, 16_000))),
            LyricLine(listOf(LyricWord("harmony", 12_000, 17_000))),
            LyricLine(listOf(LyricWord("next", 25_000, 26_000))),
        ))
        assertEquals(0, schedule.snapshotAt(16_999).scrollAnchorLineIndex)
        assertNull(schedule.snapshotAt(16_999).instrumentalScrollAnchor)
        assertEquals(2, schedule.snapshotAt(17_000).scrollAnchorLineIndex)
        assertEquals(2, schedule.snapshotAt(22_000).scrollAnchorLineIndex)
        assertEquals(0, schedule.snapshotAt(16_999).scrollAnchorLineIndex)
        assertNull(schedule.snapshotAt(16_999).instrumentalScrollAnchor)
        assertEquals(listOf(1), schedule.snapshotAt(16_999).activeLineIndices)
    }

    @Test
    fun introSlotAndFirstLyricKeepTheSameScrollPositionAcrossTheirHandoff() {
        val schedule = LyricTimeline.Schedule(listOf(
            LyricLine(listOf(LyricWord("first", 9_000, 10_000))),
        ))
        val intro = schedule.snapshotAt(3_000)
        assertEquals(-1, intro.anchorLineIndex)
        assertEquals(0, intro.scrollAnchorLineIndex)
        assertEquals(0L, intro.instrumentalScrollAnchor?.startMs)
        val firstCue = schedule.snapshotAt(8_500)
        assertEquals(0, firstCue.scrollAnchorLineIndex)
        assertNull(firstCue.instrumentalScrollAnchor)
        assertEquals(listOf(0), firstCue.activeLineIndices)
    }

    @Test
    fun wordLiftHoldsAtVocalEndAndReturnsContinuouslyWithTheVisibleFade() {
        fun lift(position: Long, presence: Float) = LyricTimeline.wordLiftFraction(
            position, riseStartMs = 1_000, vocalEndMs = 2_000, activePresence = presence,
        )
        val endPose = lift(2_000, 1f)
        assertTrue(endPose > 0.9f)
        assertEquals(lift(1_999, 1f), endPose, 0.001f)
        assertEquals(endPose, lift(8_000, 1f), 0f) // no timer-driven reset while still visible
        assertEquals(endPose * 0.5f, lift(8_000, 0.5f), 0f)
        assertEquals(0f, lift(8_000, 0f), 0f)
        assertEquals(0f, lift(500, 1f), 0f) // seek before this word, with no stale pose
    }

    @Test
    fun backgroundWordLiftUsesItsOwnVocalEndInsteadOfTheCompletedMainLine() {
        val stillSinging = LyricTimeline.wordLiftFraction(
            2_200, riseStartMs = 1_800, vocalEndMs = 2_800, activePresence = 0f,
        )
        assertTrue(stillSinging > 0f)
        val endPose = LyricTimeline.wordLiftFraction(2_800, 1_800, 2_800, 1f)
        assertEquals(endPose, LyricTimeline.wordLiftFraction(4_000, 1_800, 2_800, 1f), 0f)
        assertEquals(0f, LyricTimeline.wordLiftFraction(4_000, 1_800, 2_800, 0f), 0f)
    }

    @Test
    fun longNoteCharacterWaveFinishesWithoutFreezingOrResettingAtVocalEnd() {
        val timing = LyricTimeline.CharacterEmphasisTiming(
            charIndex = 0,
            charCount = 1,
            animationDurationMs = 2_000,
            riseStartMs = 1_000,
            settleStartMs = 2_000,
            maximumScale = 1.14f,
        )
        fun amount(position: Long, presence: Float) = LyricTimeline.characterEmphasisAmountAt(
            timing, position, vocalEndMs = 2_500, activePresence = presence, easing = { it },
        )

        val endPose = amount(2_500, 1f)
        assertEquals(0.375f, endPose, 0f)
        assertEquals(amount(2_499, 1f), endPose, 0.001f)
        assertEquals(endPose, amount(2_501, 1f), 0.001f)
        assertEquals(0.25f, amount(3_000, 1f), 0f)
        assertEquals(0.125f, amount(3_500, 1f), 0f)
        assertEquals(0.25f * 0.25f, amount(3_000, 0.25f), 0f)
        assertEquals(0f, amount(3_000, 0f), 0f)
        assertEquals(0f, amount(4_000, 1f), 0f)
        assertEquals(0f, amount(10_000, 1f), 0f)
        assertEquals(0f, amount(500, 1f), 0f)
    }

    @Test
    fun finalStaggeredCharactersFinishWhileTheCompletedLineIsStillTheScrollAnchor() {
        val line = LyricLine(listOf(LyricWord("hold", 1_000, 3_000)))
        val schedule = LyricTimeline.Schedule(listOf(
            line,
            LyricLine(listOf(LyricWord("next", 6_000, 9_000))),
        ))
        val timings = LyricTimeline.characterEmphasisTimings(line, 0)
        val last = timings.last()
        fun amount(position: Long) = LyricTimeline.characterEmphasisAmountAt(
            last, position, line.mainEndMs, activePresence = 1f, easing = { it },
        )
        val finishMs = last.settleStartMs + last.animationDurationMs
        assertEquals(0, schedule.snapshotAt(finishMs).anchorLineIndex)
        assertTrue(amount(line.mainEndMs) > amount(finishMs - 200))
        assertTrue(amount(finishMs - 200) > 0f)
        assertEquals(0f, amount(finishMs), 0f)
        assertEquals(0f, amount(10_000), 0f)
        // A backward seek recomputes the wave instead of retaining its completed state.
        assertTrue(amount(2_800) > 0f)
        assertEquals(0f, amount(500), 0f)
    }

    @Test
    fun singleCharacterLongNoteCanFinishItsDelayedRiseAndReturnAfterVocalEnd() {
        val line = LyricLine(listOf(LyricWord("o", 1_000, 3_000)))
        val timing = LyricTimeline.characterEmphasisTimings(line, 0).single()
        fun amount(position: Long) = LyricTimeline.characterEmphasisAmountAt(
            timing, position, line.mainEndMs, activePresence = 1f, easing = { it },
        )
        assertEquals(1f, amount(3_000), 0f)
        assertEquals(1f, amount(timing.settleStartMs), 0f)
        assertEquals(0.5f, amount(timing.settleStartMs + 1_000), 0f)
        assertEquals(0f, amount(timing.settleStartMs + 2_000), 0f)
    }

    @Test
    fun duetSingersAnimateTogetherAndScrollOnlyAfterTheLastSinger() {
        val duet = listOf(
            LyricLine(listOf(LyricWord("lead", 10_000, 16_000))),
            LyricLine(listOf(LyricWord("harmony", 12_000, 17_000))),
            LyricLine(listOf(LyricWord("next", 18_500, 22_000))),
        )

        assertEquals(listOf(0), LyricTimeline.activeLineIndicesAt(duet, 11_999))
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(duet, 12_000))
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(duet, 15_999))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(duet, 16_000))
        assertEquals(0, LyricTimeline.activeLineAt(duet, 16_999, true))
        assertEquals(emptyList<Int>(), LyricTimeline.activeLineIndicesAt(duet, 17_000))
        assertEquals(0, LyricTimeline.activeLineAt(duet, 18_319, true))
        assertEquals(180L, LyricTimeline.lineLeadOffsetMs(duet, 2))
        assertEquals(2, LyricTimeline.activeLineAt(duet, 18_320, true))
        assertEquals(listOf(2), LyricTimeline.activeLineIndicesAt(duet, 18_320))
        assertEquals(750, LyricTimeline.dynamicKaraokeClusterAdvanceMs(duet, 0, 2))
    }

    @Test
    fun shortGapAfterDuetHandsOffAtClusterMaximumEnd() {
        val duet = listOf(
            LyricLine(listOf(LyricWord("lead", 10_000, 16_000))),
            LyricLine(listOf(LyricWord("harmony", 12_000, 17_000))),
            LyricLine(listOf(LyricWord("next", 17_100, 18_000))),
        )

        assertEquals(0, LyricTimeline.activeLineAt(duet, 16_999, true))
        assertEquals(2, LyricTimeline.activeLineAt(duet, 17_000, true))
        assertEquals(listOf(2), LyricTimeline.activeLineIndicesAt(duet, 17_000))
        assertEquals(100L, LyricTimeline.lineLeadOffsetMs(duet, 2))
        assertEquals(480, LyricTimeline.dynamicKaraokeClusterAdvanceMs(duet, 0, 2))
    }

    @Test
    fun transitiveOverlapsWaitForEverySingerIncludingBackgroundVocalTail() {
        val layered = listOf(
            LyricLine(
                words = listOf(LyricWord("lead", 10_000, 13_000)),
                backgroundWords = listOf(LyricWord("echo", 12_000, 14_000, isBackgroundVocal = true)),
            ),
            LyricLine(listOf(LyricWord("second", 12_000, 15_000))),
            LyricLine(listOf(LyricWord("third", 14_000, 17_000))),
            LyricLine(listOf(LyricWord("next", 17_050, 18_000))),
        )

        assertEquals(14_000L, layered[0].endMs)
        assertEquals(3, LyricTimeline.nextClusterStartIndex(layered, 0))
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(layered, 12_500))
        assertEquals(listOf(1, 2), LyricTimeline.activeLineIndicesAt(layered, 14_000))
        assertEquals(0, LyricTimeline.activeLineAt(layered, 16_999, true))
        assertEquals(3, LyricTimeline.activeLineAt(layered, 17_000, true))
        assertEquals(480, LyricTimeline.dynamicKaraokeClusterAdvanceMs(layered, 0, 3))
    }

    @Test
    fun mainVocalEndsAllowLineHandoffWhileBackgroundVocalContinues() {
        val lines = listOf(
            LyricLine(
                words = listOf(LyricWord("lead", 10_000, 13_000)),
                backgroundWords = listOf(LyricWord("echo", 12_000, 16_000, isBackgroundVocal = true)),
            ),
            LyricLine(listOf(LyricWord("next", 13_500, 18_000))),
        )

        assertEquals(13_000L, lines[0].mainEndMs)
        assertEquals(16_000L, lines[0].endMs)
        assertEquals(1, LyricTimeline.nextClusterStartIndex(lines, 0))

        // Before lead ends: line 0 focused and active
        assertEquals(0, LyricTimeline.activeLineAt(lines, 12_500, true))
        assertEquals(listOf(0), LyricTimeline.activeLineIndicesAt(lines, 12_500))

        // Lead ended at 13_000; lookahead cues line 1 at 13_500 - 180 = 13_320
        assertEquals(1, LyricTimeline.activeLineAt(lines, 13_350, true))
        // Both lines active: line 0 background still singing, line 1 cued!
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(lines, 13_350))

        // At 14_000: line 1 is singing, line 0 background still singing
        assertEquals(1, LyricTimeline.activeLineAt(lines, 14_000, true))
        assertEquals(listOf(0, 1), LyricTimeline.activeLineIndicesAt(lines, 14_000))

        // At 16_000: line 0 background finished, only line 1 remains active
        assertEquals(1, LyricTimeline.activeLineAt(lines, 16_000, true))
        assertEquals(listOf(1), LyricTimeline.activeLineIndicesAt(lines, 16_000))
    }

    @Test
    fun instrumentalGapRequiresAtLeastSevenSecondsAndTrimsEightHundredMs() {
        val withInterlude = listOf(
            LyricLine(listOf(LyricWord("intro", 9_000, 10_000))),
            LyricLine(listOf(LyricWord("verse", 18_000, 20_000))),
        )
        val firstGap = LyricTimeline.activeInstrumentalGapAt(withInterlude, 3_000)
        assertNotNull(firstGap)
        assertEquals(0, firstGap!!.beforeLineIndex)
        assertEquals(8_200L, firstGap.adjustedDurationMs)

        val secondGap = LyricTimeline.activeInstrumentalGapAt(withInterlude, 12_000)
        assertNotNull(secondGap)
        assertEquals(1, secondGap!!.beforeLineIndex)
        assertEquals(7_200L, secondGap.adjustedDurationMs)

        assertEquals(500L, LyricTimeline.lineLeadOffsetMs(withInterlude, 0))
        assertEquals(emptyList<Int>(), LyricTimeline.activeLineIndicesAt(withInterlude, 7_800))
        assertEquals(listOf(0), LyricTimeline.activeLineIndicesAt(withInterlude, 8_500))

        assertNull(LyricTimeline.activeInstrumentalGapAt(lines, 1_700))
    }

    @Test
    fun wordProgressIsClamped() {
        val word = LyricWord("test", 1_000, 2_000)
        assertEquals(0f, LyricTimeline.wordProgress(word, 500), 0f)
        assertEquals(0.5f, LyricTimeline.wordProgress(word, 1_500), 0f)
        assertEquals(1f, LyricTimeline.wordProgress(word, 2_500), 0f)
    }

    @Test
    fun lineProgressWeightsWordsByVisibleLength() {
        val line = LyricLine(
            listOf(
                LyricWord("a", 0, 1_000),
                LyricWord("three", 1_000, 2_000),
            ),
        )
        assertEquals(1f / 6f, LyricTimeline.lineProgress(line, 1_000), 0.0001f)
        assertEquals(7f / 12f, LyricTimeline.lineProgress(line, 1_500), 0.0001f)
    }

    @Test
    fun longLatinWordAndGroupUseApkEmphasisTiming() {
        val line = LyricLine(
            listOf(
                LyricWord("la", 1_000, 2_000, emphasisGroupId = 4),
                LyricWord("la", 2_000, 3_000, emphasisGroupId = 4),
            ),
        )

        val first = LyricTimeline.emphasisTiming(line, 0, 1_500)
        val second = LyricTimeline.emphasisTiming(line, 1, 1_500)
        val charTimings = LyricTimeline.characterEmphasisTimings(line, 0)

        assertEquals(true, first.eligible)
        assertEquals(1.14f, first.maximumScale, 0.0001f)
        assertEquals(1_000L, first.riseStartMs)
        assertEquals(1_400L, second.riseStartMs)
        assertEquals(3_000L, first.settleStartMs)
        assertEquals(4, charTimings.size)
        assertEquals(1_000L, charTimings[0].riseStartMs)
        assertEquals(1_200L, charTimings[1].riseStartMs)
        assertEquals(2_000L, charTimings[0].settleStartMs)
    }

    @Test
    fun excludedScriptsAndTranslationsDoNotUseLongEmphasis() {
        val cjk = LyricLine(
            listOf(LyricWord("長音", 0, 2_000, emphasisGroupId = 1)),
        )
        val translation = LyricLine(
            words = listOf(LyricWord("long", 0, 2_000, emphasisGroupId = 1)),
            isTranslation = true,
        )

        assertEquals(false, LyricTimeline.emphasisTiming(cjk, 0, 500).eligible)
        assertEquals(false, LyricTimeline.emphasisTiming(translation, 0, 500).eligible)
    }

    @Test
    fun expansionOffsetsPropagateOutwardFromGroupCentreMatchingApk() {
        val offsets = LyricTimeline.symmetricExpansionOffsets(
            halfExpansions = listOf(2f, 4f, 6f, 4f, 2f),
            isRtl = false,
        )

        assertEquals(0f, offsets[2], 0f)
        assertEquals(-10f, offsets[1], 0.0001f)
        assertEquals(10f, offsets[3], 0.0001f)
        assertEquals(-16f, offsets[0], 0.0001f)
        assertEquals(16f, offsets[4], 0.0001f)
    }
}
